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
 * One-frame-at-a-time decoder contract. Implementations are used from a single
 * scheduler thread; {@link #cancel()} is the only method called from another
 * thread and must be cheap.
 */
public interface FrameDecoder {

    final class DecodedFrame {
        public final Bitmap bitmap;
        public final long timeMs;
        public final int keyframeIndex;

        public DecodedFrame(Bitmap bitmap, long timeMs, int keyframeIndex) {
            this.bitmap = bitmap;
            this.timeMs = timeMs;
            this.keyframeIndex = keyframeIndex;
        }
    }

    /**
     * Decodes the best frame for {@code timeMs}. For a keyframe decoder this is
     * the nearest keyframe and the returned time is that keyframe's time.
     *
     * @param maxWidth  target box width in pixels (before rotation)
     * @param maxHeight target box height in pixels (before rotation)
     */
    DecodedFrame decodeAt(long timeMs, int maxWidth, int maxHeight) throws Exception;

    /** Media duration in milliseconds, or 0 while still unknown. */
    long getDurationMs();

    String name();

    /** Aborts an in-flight decode if the implementation can do so safely. */
    void cancel();

    /** True when the last decode ended because {@link #cancel()} was called. */
    boolean wasCancelled();

    void close();
}
