/*
 * Copyright (C) 2024 NagramX Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.telegram.ui.Components;

import android.media.MediaDataSource;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Adapts Telegram's progressively downloaded media file to a decoder.
 *
 * Telegram streams video into a local file that keeps growing as bytes arrive.
 * Pointing a decoder straight at that truncated file is what crashed: the
 * container parser and frame decoder seek and read past the end of what has
 * actually arrived, which is undefined behaviour at the native level.
 *
 * This source makes the boundary explicit: only bytes already on disk are
 * served, and anything beyond that reads as EOF, so a decoder that reaches an
 * unbuffered range fails to produce a frame instead of running off the end of a
 * half-written file.
 *
 * Reads go through a memory-mapped window over the downloaded prefix. While
 * hunting for the moov atom and the nearest keyframe the decoder issues a large
 * number of small reads; serving those with seek()+read() system calls made an
 * online preview take 0.7-1.8 s against 0.15 s for the same media on local
 * storage. A mapped window turns them into plain memory reads.
 */
public class TelegramMediaDataSource extends MediaDataSource {

    /** Reports how many bytes are really on disk for a span of the media. */
    public interface DownloadedRangeProvider {
        /**
         * @return bytes actually present starting at {@code offset}, capped at
         *         {@code length}; 0 when that span is not available yet
         */
        long getDownloadedLength(long offset, long length);
    }

    /**
     * Mapping is only attempted for files up to this size; beyond it the
     * address-space cost is not worth it and positioned reads are used.
     */
    private static final long MAX_MAP_BYTES = 512L * 1024 * 1024;

    private final File file;
    private final long declaredSize;
    private final DownloadedRangeProvider rangeProvider;
    // Optional in-memory window holding bytes fetched on demand. It is consulted
    // first, because those bytes are exactly the ones the local file lacks.
    private final byte[] overlay;
    private final long overlayStart;
    private final Object mapLock = new Object();

    private MappedByteBuffer mapped;
    private long mappedLength = -1;
    private RandomAccessFile accessFile;
    private volatile boolean released;

    /**
     * @param file         the local file holding the bytes downloaded so far
     *                     (Telegram's {@code *.temp} while streaming, the final
     *                     file once complete)
     * @param declaredSize the media's full size in bytes, or &lt;= 0 if unknown
     */
    public TelegramMediaDataSource(File file, long declaredSize) {
        this(file, declaredSize, null);
    }

    /**
     * @param rangeProvider tells which bytes really exist. A streaming download
     *                      writes sparsely, so the file can report a length that
     *                      covers holes; without this the decoder would be fed
     *                      zeros where the media has not arrived.
     */
    public TelegramMediaDataSource(File file, long declaredSize, DownloadedRangeProvider rangeProvider) {
        this(file, declaredSize, rangeProvider, null, 0);
    }

    /**
     * @param overlay      bytes fetched for this lookup (a keyframe the file has
     *                     not buffered yet), served ahead of everything else
     * @param overlayStart file offset those bytes begin at
     */
    public TelegramMediaDataSource(File file, long declaredSize, DownloadedRangeProvider rangeProvider,
                                   byte[] overlay, long overlayStart) {
        this.file = file;
        this.declaredSize = declaredSize;
        this.rangeProvider = rangeProvider;
        this.overlay = overlay;
        this.overlayStart = overlayStart;
    }

    /**
     * Maps (or re-maps) the downloaded prefix. The file grows underneath us, so
     * the window is refreshed whenever its length changed — otherwise the
     * decoder would keep seeing the size that was mapped first.
     */
    private MappedByteBuffer ensureMapped() {
        synchronized (mapLock) {
            if (released) {
                return null;
            }
            final long length = file.length();
            if (length <= 0 || length > MAX_MAP_BYTES) {
                return null;
            }
            if (mapped != null && mappedLength == length) {
                return mapped;
            }
            try (RandomAccessFile raf = new RandomAccessFile(file, "r");
                 FileChannel channel = raf.getChannel()) {
                mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, length);
                mappedLength = length;
                return mapped;
            } catch (Throwable e) {
                mapped = null;
                mappedLength = -1;
                return null;
            }
        }
    }

    @Override
    public int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
        if (released || buffer == null || size <= 0) {
            return -1;
        }
        if (position < 0 || offset < 0 || offset + size > buffer.length) {
            return -1;
        }

        if (overlay != null && position >= overlayStart
                && position < overlayStart + overlay.length) {
            final int from = (int) (position - overlayStart);
            final int take = Math.min(size, overlay.length - from);
            System.arraycopy(overlay, from, buffer, offset, take);
            return take;
        }

        // Real availability first: a sparse download leaves holes that read back
        // as zeros, so the provider decides what may be served at all. Treating
        // a hole as data is what fed the decoder garbage.
        long downloadable = size;
        if (rangeProvider != null) {
            downloadable = rangeProvider.getDownloadedLength(position, size);
            if (downloadable <= 0) {
                return -1;
            }
        }
        final int allowed = (int) Math.min(size, downloadable);

        final MappedByteBuffer window = ensureMapped();
        if (window != null) {
            final int limit = window.limit();
            if (position >= limit) {
                // Past what has arrived: an explicit EOF, never a zero fill.
                return -1;
            }
            final int toRead = Math.min(allowed, limit - (int) position);
            if (toRead <= 0) {
                return -1;
            }
            // duplicate() so concurrent readers each own their position.
            final MappedByteBuffer view = (MappedByteBuffer) window.duplicate();
            view.position((int) position);
            view.get(buffer, offset, toRead);
            return toRead;
        }

        // Fallback: very large media, or the platform refused to map.
        final long available = Math.min(file.length(), position + allowed);
        if (position >= available) {
            return -1;
        }
        final int toRead = (int) Math.min(allowed, available - position);
        if (toRead <= 0) {
            return -1;
        }
        RandomAccessFile raf = accessFile;
        if (raf == null) {
            raf = new RandomAccessFile(file, "r");
            accessFile = raf;
        }
        raf.seek(position);
        int total = 0;
        while (total < toRead) {
            final int read = raf.read(buffer, offset + total, toRead - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total > 0 ? total : -1;
    }

    @Override
    public long getSize() {
        // Advertise the full size so the container parser can seek within the
        // downloaded prefix; readAt() enforces what is really readable.
        return declaredSize > 0 ? declaredSize : file.length();
    }

    @Override
    public void close() {
        released = true;
        synchronized (mapLock) {
            mapped = null;
            mappedLength = -1;
        }
        final RandomAccessFile raf = accessFile;
        accessFile = null;
        if (raf != null) {
            try {
                raf.close();
            } catch (IOException ignore) {
            }
        }
    }
}
