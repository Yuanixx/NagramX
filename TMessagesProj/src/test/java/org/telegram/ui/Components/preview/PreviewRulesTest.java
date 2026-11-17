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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plain-JVM regression tests for the preview rules. No JUnit, no Android:
 * compile with javac and run in a few seconds.
 */
public final class PreviewRulesTest {

    private static int passed;

    public static void main(String[] args) throws Exception {
        testShortModeGate();
        testDisplayLimit();
        testHoverGrid();
        testRangeAssembly();
        testDecodeGateRace();
        testDecodeGateStress();
        testFloodDoesNotDisableSession();
        testFailureCooldownAndExpiry();
        testCooldownBackoff();
        testTailMoovAtWindowEdge();
        testTailMoovNeedsExpansion();
        testTailMoovNeedsPrefix();
        testTailMoovTooLargeAndInvalid();
        System.out.println("PREVIEW RULES: " + passed + " checks passed");
    }

    // ---------------------------------------------------------------- rules

    private static void testShortModeGate() {
        check(!PreviewRules.shouldUseShortMode(false, 0, 5_000L),
                "unknown index must not enter short mode");
        check(PreviewRules.shouldUseShortMode(true, 3, 5_000L),
                "known sparse index on a short video uses short mode");
        check(!PreviewRules.shouldUseShortMode(true, 12, 5_000L),
                "a dozen keyframes already paint the timeline");
        check(!PreviewRules.shouldUseShortMode(true, 3, 0L),
                "unknown duration cannot be short mode");
        check(!PreviewRules.shouldUseShortMode(true, 3, 60_001L),
                "long videos stay on the index path");
        check(PreviewRules.shouldUseShortMode(true, 3, 60_000L),
                "the one-minute boundary is included");
    }

    private static void testDisplayLimit() {
        check(PreviewRules.displayLimitMs(0L) == 1500L, "unknown duration uses 1.5s");
        check(PreviewRules.displayLimitMs(5_000L) == 2500L, "5s video uses half its length");
        check(PreviewRules.displayLimitMs(9_000L) == 4500L, "9s video uses half its length");
        check(PreviewRules.displayLimitMs(10_000L) == 1500L, "10s is no longer very short");
        check(PreviewRules.displayLimitMs(60_000L) == 3000L, "60s uses five percent");
        check(PreviewRules.displayLimitMs(100_000L) == 5000L, "100s uses five percent");
        check(PreviewRules.displayLimitMs(20_000L) == 1500L, "the floor is 1.5s");
    }

    private static void testHoverGrid() {
        check(PreviewRules.hoverTime(1_120L, false, 750L) == 1120L,
                "an index keeps the exact finger position");
        check(PreviewRules.hoverTime(1_120L, true, 750L) == 750L,
                "no index snaps down to the grid");
        check(PreviewRules.hoverTime(1_500L, true, 750L) == 1500L,
                "the midpoint rounds up");
        check(PreviewRules.hoverTime(-5L, true, 750L) == 0L,
                "negative positions clamp to zero");
    }

    private static void testRangeAssembly() {
        check(!PreviewRules.rangeComplete(10, 20), "half a range is not complete");
        check(!PreviewRules.rangeComplete(0, 1), "no bytes is not complete");
        check(PreviewRules.rangeComplete(20, 20), "exactly full is complete");
        check(PreviewRules.rangeComplete(21, 20), "over-fill still counts");
        check(!PreviewRules.rangeComplete(20, 0), "an empty want is not success");
    }

    // ---------------------------------------------------------------- gate

    private static void testDecodeGateRace() {
        final boolean[] cancelled = {false};
        final DecodeGate gate = new DecodeGate();
        check(gate.begin(() -> cancelled[0] = false), "first begin opens the gate");
        gate.stop(() -> cancelled[0] = true);
        check(cancelled[0], "stop delivers the cancel");
        check(!gate.begin(() -> cancelled[0] = false),
                "begin after stop must fail");
        check(cancelled[0], "a refused begin must not erase the cancel");
        check(gate.isStopped(), "the gate stays closed");
    }

    private static void testDecodeGateStress() throws Exception {
        for (int i = 0; i < 500; i++) {
            final DecodeGate gate = new DecodeGate();
            final AtomicBoolean cancelled = new AtomicBoolean(false);
            final CyclicBarrier barrier = new CyclicBarrier(2);
            final CountDownLatch done = new CountDownLatch(2);
            final Thread decoder = new Thread(() -> {
                try {
                    barrier.await();
                    gate.begin(() -> cancelled.set(false));
                } catch (Throwable ignore) {
                } finally {
                    done.countDown();
                }
            });
            final Thread stopper = new Thread(() -> {
                try {
                    barrier.await();
                    gate.stop(() -> cancelled.set(true));
                } catch (Throwable ignore) {
                } finally {
                    done.countDown();
                }
            });
            decoder.start();
            stopper.start();
            done.await();
            check(cancelled.get(), "stop wins every interleaving (iteration " + i + ")");
        }
    }

    // ---------------------------------------------------------- failure rules

    private static void testFloodDoesNotDisableSession() {
        final RemoteFailureTracker tracker = new RemoteFailureTracker();
        for (int i = 0; i < 10; i++) {
            check(!tracker.onOutcome(RemoteFailureTracker.Outcome.FLOOD, 1_000L + i),
                    "a server flood never starts the session cooldown");
        }
        check(!tracker.isCoolingDown(2_000L), "flood leaves the session usable");
        check(tracker.disabledUntilMs() == 0L, "flood does not set remoteDisabled");
        check(tracker.failures() == 0, "flood is not a plain failure");
        check(tracker.floodWaits() == 10, "floods are counted for the summary");

        check(!tracker.onOutcome(RemoteFailureTracker.Outcome.LOCAL_COOLDOWN, 3_000L),
                "a local cooldown is not a failure");
        check(!tracker.onOutcome(RemoteFailureTracker.Outcome.CANCELLED, 4_000L),
                "a cancelled request is not a failure");
        check(tracker.disabledUntilMs() == 0L, "neither disables the session");
        check(!tracker.probePending(), "neither asks for a recovery probe");
    }

    private static void testFailureCooldownAndExpiry() {
        final RemoteFailureTracker tracker = new RemoteFailureTracker();
        check(!tracker.onOutcome(RemoteFailureTracker.Outcome.REAL_FAILURE, 1_000L),
                "one failure is not enough");
        check(!tracker.onOutcome(RemoteFailureTracker.Outcome.REAL_FAILURE, 2_000L),
                "two failures are not enough");
        check(tracker.onOutcome(RemoteFailureTracker.Outcome.REAL_FAILURE, 3_000L),
                "the third failure starts a cooldown");
        check(tracker.disabledUntilMs() == 33_000L, "the first cooldown is 30s");
        check(tracker.isCoolingDown(32_999L), "the cooldown is still running");
        check(!tracker.isCoolingDown(33_000L), "the cooldown expires on time");
        check(tracker.consumeExpired(), "the expiry is reported once");
        check(!tracker.consumeExpired(), "the expiry is not repeated");
        check(tracker.probePending(), "the next request is a recovery probe");

        // A probe failure parks again immediately.
        check(tracker.onOutcome(RemoteFailureTracker.Outcome.REAL_FAILURE, 40_000L),
                "a failed probe starts the next cooldown");
        check(tracker.disabledUntilMs() == 100_000L, "the second cooldown is 60s");

        check(!tracker.onOutcome(RemoteFailureTracker.Outcome.SUCCESS, 110_000L),
                "success never starts a cooldown");
        check(!tracker.probePending(), "success confirms the probe");
        check(tracker.streak() == 0, "success clears the backoff streak");
        check(tracker.failures() == 0, "success clears the failure count");
    }

    private static void testCooldownBackoff() {
        check(RemoteFailureTracker.cooldownMsForStreak(0) == 30_000L, "30s");
        check(RemoteFailureTracker.cooldownMsForStreak(1) == 60_000L, "60s");
        check(RemoteFailureTracker.cooldownMsForStreak(2) == 120_000L, "120s");
        check(RemoteFailureTracker.cooldownMsForStreak(3) == 240_000L, "240s");
        check(RemoteFailureTracker.cooldownMsForStreak(4) == 300_000L, "capped at 300s");
        check(RemoteFailureTracker.cooldownMsForStreak(99) == 300_000L, "always capped");
    }

    // ------------------------------------------------------------- tail moov

    private static final int BLOCK = 1024;
    private static final int FILE_SIZE = 100_000;

    private static void testTailMoovAtWindowEdge() {
        // The atom starts exactly at the first byte of the two-block window.
        final int moovOffset = FILE_SIZE - 2 * BLOCK;
        final int moovSize = 2 * BLOCK;
        final byte[] file = buildFile(FILE_SIZE, moovOffset, moovSize);
        final int dataStart = (int) TailMoovLocator.windowStart(FILE_SIZE, 2, BLOCK);
        final byte[] tail = slice(file, dataStart, FILE_SIZE - dataStart);
        final TailMoovLocator.Analysis result = TailMoovLocator.analyze(
                tail, dataStart, FILE_SIZE, BLOCK);
        check(result.kind == TailMoovLocator.Kind.COMPLETE,
                "an atom exactly at the window edge is complete");
        check(result.moovOffset == moovOffset, "the atom offset is reported");
        check(result.moovSize == moovSize, "the atom size is reported");
        check(result.moov != null && result.moov.length == moovSize,
                "the atom bytes are returned");
    }

    private static void testTailMoovNeedsExpansion() {
        // The atom starts one byte before the two-block window: the first read
        // cannot see the "moov" tag, so the probe must move one window back.
        final int moovOffset = FILE_SIZE - 2 * BLOCK - 1;
        final int moovSize = 2 * BLOCK + 1;
        final byte[] file = buildFile(FILE_SIZE, moovOffset, moovSize);
        final int firstStart = (int) TailMoovLocator.windowStart(FILE_SIZE, 2, BLOCK);
        final byte[] first = slice(file, firstStart, FILE_SIZE - firstStart);
        final TailMoovLocator.Analysis missing = TailMoovLocator.analyze(
                first, firstStart, FILE_SIZE, BLOCK);
        check(missing.kind == TailMoovLocator.Kind.NOT_FOUND,
                "the first window misses an atom that starts before it");

        final int secondStart = (int) TailMoovLocator.windowStart(FILE_SIZE, 4, BLOCK);
        final byte[] second = slice(file, secondStart, firstStart - secondStart);
        final byte[] combined = TailMoovLocator.combine(second, first);
        final TailMoovLocator.Analysis result = TailMoovLocator.analyze(
                combined, secondStart, FILE_SIZE, BLOCK);
        check(result.kind == TailMoovLocator.Kind.COMPLETE,
                "expanding one window back finds the atom");
        check(result.moovOffset == moovOffset, "the expanded window keeps the offset");

        check(TailMoovLocator.windowStart(FILE_SIZE, 2, BLOCK)
                        + 2L * BLOCK == FILE_SIZE,
                "a window always ends at the declared size");
    }

    private static void testTailMoovNeedsPrefix() {
        // A truncated read: the header is here but the atom continues past the
        // bytes. The caller must fetch exactly the missing prefix.
        final int declared = 4_000;
        final int moovSize = 2_000;
        final byte[] file = buildFile(declared, 0, moovSize);
        final byte[] head = slice(file, 0, 1_000);
        final TailMoovLocator.Analysis result = TailMoovLocator.analyze(
                head, 0L, declared, BLOCK);
        check(result.kind == TailMoovLocator.Kind.NEED_PREFIX,
                "a cut atom asks for its missing prefix");
        check(result.missingOffset == 1_000L, "the prefix starts after the read");
        check(result.missingLength == 1_000, "the prefix is exactly the rest");
        check(result.moovOffset == 0L, "the atom offset is still known");
        check(result.moovSize == moovSize, "the atom size is still known");

        final byte[] prefix = slice(file, 1_000, 1_000);
        final byte[] combined = TailMoovLocator.combine(head, prefix);
        final TailMoovLocator.Analysis done = TailMoovLocator.analyze(
                combined, 0L, declared, BLOCK);
        check(done.kind == TailMoovLocator.Kind.COMPLETE,
                "the stitched atom is complete");
        check(done.moov.length == moovSize, "the stitched atom has the right length");
    }

    private static void testTailMoovTooLargeAndInvalid() {
        // Nine blocks worth of atom: one more than the probe budget.
        final int tooLarge = 9 * BLOCK;
        final byte[] big = new byte[16];
        writeU32(big, 0, tooLarge);
        writeTag(big, 4);
        final TailMoovLocator.Analysis large = TailMoovLocator.analyze(
                big, 0L, tooLarge, BLOCK);
        check(large.kind == TailMoovLocator.Kind.TOO_LARGE,
                "an atom over eight blocks is refused");

        final byte[] noise = new byte[64];
        for (int i = 0; i < noise.length; i++) {
            noise[i] = (byte) (i * 7);
        }
        check(TailMoovLocator.analyze(noise, 0L, 64L, BLOCK).kind
                        == TailMoovLocator.Kind.NOT_FOUND,
                "an atom that is not there is not an error");
        check(TailMoovLocator.analyze(new byte[8], 0L, 8L, BLOCK).kind
                        == TailMoovLocator.Kind.NOT_FOUND,
                "a too-short read is not an error");

        final byte[] broken = new byte[16];
        writeU32(broken, 0, 8); // below the minimum atom size
        writeTag(broken, 4);
        check(TailMoovLocator.analyze(broken, 0L, 16L, BLOCK).kind
                        == TailMoovLocator.Kind.INVALID,
                "a non-credible atom size is rejected");

        check(TailMoovLocator.blocksForRange(1L, 10L, 8) == 2,
                "the block calculation counts the alignment");
        check(TailMoovLocator.blocksForRange(8L, 8L, 8) == 1,
                "an aligned exact block is one block");
        check(TailMoovLocator.blocksForRange(0L, 9L, 8) == 2,
                "a range past the block edge touches two blocks");
    }

    // ------------------------------------------------------------- fixtures

    private static byte[] buildFile(int size, int moovOffset, int moovSize) {
        final byte[] file = new byte[size];
        writeU32(file, moovOffset, moovSize);
        writeTag(file, moovOffset + 4);
        // A few filler bytes so a misread size is visible.
        for (int i = moovOffset + 8; i < Math.min(size, moovOffset + 64); i++) {
            file[i] = (byte) 0x5a;
        }
        return file;
    }

    private static void writeU32(byte[] data, int offset, long value) {
        data[offset] = (byte) (value >>> 24);
        data[offset + 1] = (byte) (value >>> 16);
        data[offset + 2] = (byte) (value >>> 8);
        data[offset + 3] = (byte) value;
    }

    private static void writeTag(byte[] data, int offset) {
        data[offset] = 'm';
        data[offset + 1] = 'o';
        data[offset + 2] = 'o';
        data[offset + 3] = 'v';
    }

    private static byte[] slice(byte[] source, int offset, int length) {
        final byte[] out = new byte[length];
        System.arraycopy(source, offset, out, 0, length);
        return out;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("FAILED: " + message);
        }
        passed++;
    }
}
