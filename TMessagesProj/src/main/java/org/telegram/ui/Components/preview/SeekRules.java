/*
 * Copyright (C) 2026 NagramX Team
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
 * Pure decision rules for video seek positions. No Android or media3 dependency,
 * so the JVM tests exercise the exact code the app runs.
 *
 * A fragmented stream that trips a player rebuild briefly reports a near-zero
 * currentPosition (tens of milliseconds) together with a full duration. Saving
 * that as a 0..1 progress and replaying it later issues a seek to byte 0, which
 * the user sees as "fast-forward jumped back to the start". These rules define
 * one shared lower bound for both the save side and the seek funnel.
 */
public final class SeekRules {

    /** Absolute floor in milliseconds: positions below this are never a real watch position. */
    public static final long SAVE_FLOOR_MS = 1000L;
    /** Relative floor: 0.1% of the duration. */
    public static final double SAVE_FLOOR_RATIO = 0.001d;
    /** media3 TIME_UNSET sentinel. Kept local so this class stays Android-free. */
    public static final long TIME_UNSET = Long.MIN_VALUE;

    private SeekRules() {
    }

    /**
     * The single source of truth for the minimum legitimate seek/persist position.
     *
     * min(1000ms, duration*0.001). An invalid duration fails safe with MAX_VALUE
     * so every caller rejects (never a tiny threshold that could let a near-zero
     * position through).
     */
    public static long tMinMs(long durationMs) {
        if (durationMs <= 0L || durationMs == TIME_UNSET) {
            return Long.MAX_VALUE;
        }
        return Math.min(SAVE_FLOOR_MS, (long) Math.floor(durationMs * SAVE_FLOOR_RATIO));
    }

    /** The same bound expressed as a 0..1 progress, using floating-point division. */
    public static double tMinProgress(long durationMs) {
        if (durationMs <= 0L || durationMs == TIME_UNSET) {
            return 1.0d;
        }
        return (double) tMinMs(durationMs) / (double) durationMs;
    }

    /**
     * True when a seek must be rejected as a near-zero dirty value.
     *
     * @param progress        the raw normalized progress (may be NaN/Inf/negative/>1)
     * @param targetMs        round(progress * duration), ±1ms tolerated via the caller
     * @param durationMs      decoded duration in milliseconds (player or WebView)
     * @param intentionalZero true only for callers that deliberately seek to 0
     */
    public static boolean isNearZeroDirty(float progress, long targetMs,
                                           long durationMs, boolean intentionalZero) {
        if (Float.isNaN(progress) || Float.isInfinite(progress)) {
            return true;
        }
        if (progress < 0f || progress > 1f) {
            return true;
        }
        long tMin = tMinMs(durationMs);
        // An explicit, deliberate seek(0) is allowed; an accidental 0 is not.
        if (targetMs <= 0L) {
            return !intentionalZero;
        }
        // ±1ms tolerance absorbs float32 rounding (e.g. 1000/1660840 -> 999).
        return targetMs + 1L < tMin;
    }

    /** True when a persisted position is high enough to be a genuine watch position. */
    public static boolean isValidSavePosition(long positionMs, long durationMs) {
        if (positionMs <= 0L || durationMs <= 0L || durationMs == TIME_UNSET) {
            return false;
        }
        return positionMs >= tMinMs(durationMs);
    }
}
