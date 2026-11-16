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

import android.graphics.Bitmap;

/**
 * One immutable preview picture.
 *
 * Thumbnails are never modified after they are published to
 * {@link ThumbnailStore}. The UI may keep a reference for as long as it is on
 * screen, so nothing here is ever recycled; dropping the reference is enough.
 */
public final class Thumbnail {

    /** Sparse timeline layer: 24 points, always kept. */
    public static final int TIER_COARSE = 0;
    /** Medium layer: 60 points, evicted only when coarse is not enough. */
    public static final int TIER_MID = 1;
    /** Densification layer, evicted farthest from the current position first. */
    public static final int TIER_FINE = 2;

    public final Bitmap bitmap;
    /** Presentation time of the picture actually decoded. */
    public final long timeMs;
    /**
     * Index in the track's keyframe list, or -1 for a frame that came from
     * MediaMetadataRetriever and therefore has no keyframe identity.
     */
    public final int keyframeIndex;
    public final int tier;
    /** 8x8 colour fingerprint, used by diagnostics to spot duplicate frames. */
    public final long fingerprint;

    public Thumbnail(Bitmap bitmap, long timeMs, int keyframeIndex, int tier) {
        this.bitmap = bitmap;
        this.timeMs = timeMs;
        this.keyframeIndex = keyframeIndex;
        this.tier = tier;
        this.fingerprint = fingerprintOf(bitmap);
    }

    public int byteCount() {
        return bitmap != null ? bitmap.getByteCount() : 0;
    }

    private static long fingerprintOf(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return 0L;
        }
        final Bitmap small = Bitmap.createScaledBitmap(bitmap, 8, 8, false);
        if (small == null) {
            return 0L;
        }
        try {
            final int[] pixels = new int[64];
            small.getPixels(pixels, 0, 8, 0, 0, 8, 8);
            long hash = 0xcbf29ce484222325L;
            for (int pixel : pixels) {
                final int r = (pixel >> 16) & 0xff;
                final int g = (pixel >> 8) & 0xff;
                final int b = pixel & 0xff;
                hash = (hash ^ (r >> 3)) * 0x100000001b3L;
                hash = (hash ^ (g >> 3)) * 0x100000001b3L;
                hash = (hash ^ (b >> 3)) * 0x100000001b3L;
            }
            return hash;
        } catch (Throwable ignore) {
            return 0L;
        } finally {
            if (small != bitmap && !small.isRecycled()) {
                small.recycle();
            }
        }
    }
}
