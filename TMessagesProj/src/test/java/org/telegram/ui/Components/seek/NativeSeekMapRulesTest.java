package org.telegram.ui.Components.seek;

/** Plain-JVM regression tests for native SeekMap trust decisions. */
public final class NativeSeekMapRulesTest {

    private static int passed;

    public static void main(String[] args) {
        testSidxChunkIndexPassesThrough();
        testMfraTrackAwareMapPassesThrough();
        testUnknownSeekableMapIsRejected();
        testUnseekableMapNeverPassesThrough();
        testHasManagedIndex();
        testNativeSeekPointsRequireValidBytePositions();
        testSidxPassThroughSyncsResolvedState();
        System.out.println("NATIVE SEEK MAP RULES: " + passed + " checks passed");
    }

    private static void testSidxChunkIndexPassesThrough() {
        check(NativeSeekMapRules.shouldPassThrough(true, false, true),
                "a real sidx ChunkIndex is trusted and passed through");
    }

    private static void testMfraTrackAwareMapPassesThrough() {
        check(NativeSeekMapRules.shouldPassThrough(false, true, true),
                "a parsed MFRA TrackAwareSeekMap is trusted and passed through");
    }

    private static void testUnknownSeekableMapIsRejected() {
        check(!NativeSeekMapRules.shouldPassThrough(false, false, true),
                "an unknown seekable map is not trusted by type alone");
    }

    private static void testUnseekableMapNeverPassesThrough() {
        check(!NativeSeekMapRules.shouldPassThrough(true, false, false),
                "an unseekable map never passes through even if marked as ChunkIndex");
        check(!NativeSeekMapRules.shouldPassThrough(false, true, false),
                "an unseekable map never passes through even if track-aware");
    }

    private static void testHasManagedIndex() {
        check(!NativeSeekMapRules.hasManagedIndex(false, true, true, true),
                "not attempted means no index yet");
        check(!NativeSeekMapRules.hasManagedIndex(true, false, false, false),
                "attempted with neither local, remote, nor native mapping is not ready");
        check(NativeSeekMapRules.hasManagedIndex(true, true, false, false),
                "local coverage alone is a managed index");
        check(NativeSeekMapRules.hasManagedIndex(true, false, true, false),
                "remote probe alone is a managed index");
        check(NativeSeekMapRules.hasManagedIndex(true, false, false, true),
                "a passed-through native map is also a usable index");
    }

    private static void testNativeSeekPointsRequireValidBytePositions() {
        // Before the first TFRA entry, timeUs=0 is valid when the byte offset points
        // at the first media fragment. The timestamp must not be confused with position.
        check(NativeSeekMapRules.hasUsableNativeSeekPoints(true, true, 0L, 0L),
                "zero is a valid non-negative byte position for an extractor input");
        check(NativeSeekMapRules.hasUsableNativeSeekPoints(true, true, 128L, 128L),
                "MFRA first-media-data and TFRA moof offsets are valid candidates");
        // Seeking beyond the final TFRA entry clamps to its last preceding RAP, still valid.
        check(NativeSeekMapRules.hasUsableNativeSeekPoints(true, true, 4096L, 4096L),
                "a final preceding random-access point remains usable");
        check(!NativeSeekMapRules.hasUsableNativeSeekPoints(true, true, -1L, 10L),
                "negative byte offsets must never be accepted");
        check(!NativeSeekMapRules.hasUsableNativeSeekPoints(false, true, 10L, 10L),
                "points without an accepted native map cannot authorize seeking");
        check(!NativeSeekMapRules.hasUsableNativeSeekPoints(true, false, 10L, 10L),
                "an unseekable native map cannot authorize seeking");
    }

    private static void testSidxPassThroughSyncsResolvedState() {
        check(NativeSeekMapRules.syncResolvedFromNative(true, true),
                "passed-through sidx ChunkIndex must sync into resolvedIndex");
        check(!NativeSeekMapRules.syncResolvedFromNative(true, false),
                "a passed-through non-ChunkIndex must never sync into resolvedIndex");
        check(!NativeSeekMapRules.syncResolvedFromNative(false, true),
                "an intercepted map must not sync");
        boolean resolvedReady = NativeSeekMapRules.syncResolvedFromNative(true, true);
        check(NativeSeekMapRules.hasManagedIndex(true, resolvedReady, false, false),
                "after sidx sync, hasIndex sees the managed ChunkIndex");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("FAIL: " + message);
        }
        passed++;
    }
}
