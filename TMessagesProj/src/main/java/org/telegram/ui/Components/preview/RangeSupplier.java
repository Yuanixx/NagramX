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

package org.telegram.ui.Components.preview;

/**
 * Supplies the bytes of one keyframe that is not on disk yet.
 *
 * The decoder calls this only after the downloader said the range is still
 * missing, and it runs on the preview scheduler thread, never on the drag
 * path. Implementations may block while the bytes are fetched; returning null
 * keeps the old behaviour, which is to skip the frame.
 *
 * The returned array must contain the whole requested sample. A truncated or
 * zero-filled keyframe would fail inside the codec and hide the real reason,
 * so callers refuse anything shorter than the requested size.
 */
public interface RangeSupplier {

    /**
     * @param offset absolute byte offset of the sample in the document
     * @param size   sample size in bytes
     * @param timeMs presentation time of this keyframe, used to prioritise
     *               downloads around the position the finger is on
     * @return the sample bytes, or null when they cannot be obtained
     */
    byte[] fetch(long offset, long size, long timeMs);
}
