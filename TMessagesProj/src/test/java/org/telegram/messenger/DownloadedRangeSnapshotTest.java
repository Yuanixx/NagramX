package org.telegram.messenger;

/** Plain-JVM regression tests for immutable downloaded-range snapshots. */
public final class DownloadedRangeSnapshotTest {

    private static int passed;

    public static void main(String[] args) {
        testContiguousAvailabilityAroundOneHole();
        testMultipleHoles();
        testCompleteAndUnknownSnapshots();
        testConstructorCopiesInputArrays();
        testRejectsOverlappingRanges();
        System.out.println("DOWNLOADED RANGE SNAPSHOT: " + passed + " checks passed");
    }

    private static void testContiguousAvailabilityAroundOneHole() {
        DownloadedRangeSnapshot snapshot = new DownloadedRangeSnapshot(
                new long[]{10}, new long[]{20}, 100, false);
        check(snapshot.availableLength(0, 100) == 10, "read stops before first missing range");
        check(snapshot.availableLength(10, 10) == 0, "read inside a missing range returns zero");
        check(snapshot.availableLength(20, 50) == 50, "read resumes after the missing range");
        check(snapshot.availableLength(90, 50) == 10, "read is capped at the declared file size");
    }

    private static void testMultipleHoles() {
        DownloadedRangeSnapshot snapshot = new DownloadedRangeSnapshot(
                new long[]{10, 40}, new long[]{20, 50}, 100, false);
        check(snapshot.availableLength(20, 30) == 20, "read stops at the next missing range");
        check(snapshot.availableLength(50, 10) == 10, "read after the last hole reaches file end");
        check(snapshot.availableLength(45, 10) == 0, "second missing range is recognized");
    }

    private static void testCompleteAndUnknownSnapshots() {
        DownloadedRangeSnapshot complete = new DownloadedRangeSnapshot(
                new long[0], new long[0], 100, true);
        check(complete.availableLength(25, 50) == 50, "complete file exposes all remaining bytes");
        check(complete.availableLength(100, 10) == 0, "complete file does not read beyond EOF");
        DownloadedRangeSnapshot unknown = new DownloadedRangeSnapshot(
                new long[0], new long[0], 100, false);
        check(unknown.availableLength(0, 100) == -1, "empty incomplete snapshot remains unknown");
    }

    private static void testConstructorCopiesInputArrays() {
        long[] starts = {10};
        long[] ends = {20};
        DownloadedRangeSnapshot snapshot = new DownloadedRangeSnapshot(starts, ends, 100, false);
        starts[0] = 30;
        ends[0] = 40;
        check(snapshot.availableLength(0, 100) == 10, "published bounds cannot be mutated by caller");
        check(snapshot.availableLength(10, 1) == 0, "snapshot retains original missing interval");
    }

    private static void testRejectsOverlappingRanges() {
        boolean rejected = false;
        try {
            new DownloadedRangeSnapshot(new long[]{10, 15}, new long[]{20, 25}, 100, false);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check(rejected, "overlapping missing intervals are rejected");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("FAIL: " + message);
        }
        passed++;
    }
}
