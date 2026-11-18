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
 * Plain-JVM regression tests for the seek lower-bound rules. No JUnit, no Android.
 * These are the negative tests that guard against the "fast-forward jumps to
 * the first frame" regression and against fixing it with a fake-green KPI.
 */
public final class SeekRulesTest {

    private static int passed;

    public static void main(String[] args) {
        testFloorFormula();
        testInvalidDurationFailsSafe();
        testRealReproTargets();
        testBoundaryTolerance();
        testNaNAndInfinity();
        testNegativeAndOverOne();
        testSeekZeroPolarity();
        testDeepLinkAllowed();
        testValidSavePosition();
        testAbsoluteVsRelative();
        test2063Leak();
        System.out.println("SEEK RULES: " + passed + " checks passed");
    }

    private static void testFloorFormula() {
        // 27-min video: min(2000, 1660840*0.001=1660) = 1660ms (relative branch)
        check(SeekRules.tMinMs(1_660_840L) == 1660L, "27min floor is 1660ms");
        // 10s video: min(2000, 10000*0.001=10) = 10ms
        check(SeekRules.tMinMs(10_000L) == 10L, "10s floor is 10ms");
        // 44-min: min(2000, 2662480*0.001=2662) = 2000ms (absolute cap now catches 1437ms)
        check(SeekRules.tMinMs(2_662_480L) == 2000L, "44min floor is 2000ms");
    }

    private static void testInvalidDurationFailsSafe() {
        check(SeekRules.tMinMs(0L) == Long.MAX_VALUE, "zero duration fails safe");
        check(SeekRules.tMinMs(-1L) == Long.MAX_VALUE, "negative duration fails safe");
        check(SeekRules.tMinMs(SeekRules.TIME_UNSET) == Long.MAX_VALUE, "TIME_UNSET fails safe");
    }

    private static void testRealReproTargets() {
        // The exact production bug: target 17ms / 88ms on a 27-min video must be rejected.
        float p17 = 17f / 1_660_840f;   // 1.0235784E-5
        float p88 = 88f / 1_660_840f;   // 5.2985237E-5
        check(SeekRules.isNearZeroDirty(p17, 17L, 1_660_840L, false), "target=17ms is dirty");
        check(SeekRules.isNearZeroDirty(p88, 88L, 1_660_840L, false), "target=88ms is dirty");
        // The 2026-11-18 regression: a 44-min video auto-seeked to 1437ms (≈first frame)
        // after a switch. The old 1000ms floor let it through; the 2000ms floor must catch it.
        float p1437 = 5.4009794E-4f;   // 1438/2662480
        check(SeekRules.isNearZeroDirty(p1437, 1437L, 2_662_480L, false),
                "target=1437ms on 44min is dirty (regression guard)");
        // But a legitimate 3s deep link in a 1-hour video must still pass.
        check(!SeekRules.isNearZeroDirty(3f / 3600f, 3000L, 3_600_000L, false),
                "3s deep link still allowed after floor raise");
    }

    private static void testBoundaryTolerance() {
        long dur = 1_660_840L;      // floor 1660ms
        // Just below floor with tolerance: 1659ms must be tolerated (not dirty).
        check(!SeekRules.isNearZeroDirty(1659f / dur, 1659L, dur, false), "1659ms tolerated");
        // Well below floor: 500ms dirty.
        check(SeekRules.isNearZeroDirty(500f / dur, 500L, dur, false), "500ms dirty");
        // At floor: 1660ms allowed.
        check(!SeekRules.isNearZeroDirty(1660f / dur, 1660L, dur, false), "1660ms allowed");
    }

    private static void testNaNAndInfinity() {
        check(SeekRules.isNearZeroDirty(Float.NaN, 0L, 1_660_840L, false), "NaN rejected");
        check(SeekRules.isNearZeroDirty(Float.POSITIVE_INFINITY, 0L, 1_660_840L, false), "Inf rejected");
    }

    private static void testNegativeAndOverOne() {
        check(SeekRules.isNearZeroDirty(-0.5f, -500L, 1_660_840L, false), "negative rejected");
        check(SeekRules.isNearZeroDirty(1.5f, 2_000_000L, 1_660_840L, false), ">1 rejected");
    }

    private static void testSeekZeroPolarity() {
        // Accidental zero (rebuild residue) rejected; intentional zero (end-of-video) allowed.
        check(SeekRules.isNearZeroDirty(0f, 0L, 1_660_840L, false), "accidental 0 rejected");
        check(!SeekRules.isNearZeroDirty(0f, 0L, 1_660_840L, true), "intentional 0 allowed");
    }

    private static void testDeepLinkAllowed() {
        // Deep link to 3s in a 1-hour video: p=3/3600=8.33e-4 (below 0.001 p-floor)
        // but target=3000ms is legitimate by the absolute predicate.
        long dur = 3_600_000L;      // floor = min(2000, 3600) = 2000ms; 3000ms deep link still allowed
        float p = 3f / 3600f;
        check(!SeekRules.isNearZeroDirty(p, 3000L, dur, false), "3s deep link allowed");
    }

    private static void testValidSavePosition() {
        check(!SeekRules.isValidSavePosition(88L, 1_660_840L), "88ms not saveable");
        check(SeekRules.isValidSavePosition(5000L, 1_660_840L), "5s saveable");
        check(!SeekRules.isValidSavePosition(5L, 10_000L), "5ms not saveable on 10s");
        check(SeekRules.isValidSavePosition(50L, 10_000L), "50ms saveable on 10s");
        check(!SeekRules.isValidSavePosition(100L, 0L), "invalid duration not saveable");
    }

    private static void testAbsoluteVsRelative() {
        // A pure p<0.001 floor would kill the 3s deep link (p=8.33e-4).
        // The absolute-target predicate keeps it. This locks in the design choice.
        long dur = 3_600_000L;
        float p = 3f / 3600f;
        boolean naivePFloorRejects = p < 0.001f;
        boolean ourPredicateRejects = SeekRules.isNearZeroDirty(p, 3000L, dur, false);
        check(naivePFloorRejects && !ourPredicateRejects,
                "absolute target predicate avoids naive p-floor false kill");
    }

    /**
     * Locks in the KNOWN leak observed on device 2026-11-18: on the 44-min video
     * (dur=2662480ms, floor=2000ms) a dirty near-zero progress producing
     * target=2063ms passes isNearZeroDirty (2063 > 2000). This is a diagnostic
     * baseline, NOT an endorse: when the root cause is fixed, this assertion must
     * be inverted. Its purpose is to prove the 63ms leak is real and reproducible
     * in pure JVM, so the eventual fix has a falsifiable before/after.
     */
    private static void test2063Leak() {
        long dur = 2_662_480L;              // 44-min, floor = min(2000, 2662) = 2000ms
        float p2063 = 7.752171E-4f;         // observed raw progress: 2063 / 2662480
        long targetMs = (long) ((double) p2063 * (double) dur);
        check(SeekRules.tMinMs(dur) == 2000L, "44min floor is 2000ms");
        check(!SeekRules.isNearZeroDirty(p2063, 2063L, dur, false),
                "target=2063ms is NOT rejected today (the known 63ms leak)");
        check(targetMs == 2063L, "p*duration reproduces target=2063ms");
    }

    private static void check(boolean ok, String msg) {
        if (!ok) {
            throw new AssertionError("FAIL: " + msg);
        }
        passed++;
    }
}
