package org.telegram.ui.Components.seek;

/** Plain-JVM regression tests for native SeekMap trust decision. */
public final class NativeSeekMapRulesTest {

    private static int passed;

    public static void main(String[] args) {
        testOnlyChunkIndexPassesThrough();
        testMfraSeekableIsRejected();
        testUnseekableNeverPassesThrough();
        testHasManagedIndex();
        testSidxPassThroughSyncsResolvedState();
        System.out.println("NATIVE SEEK MAP RULES: " + passed + " checks passed");
    }

    private static void testOnlyChunkIndexPassesThrough() {
        check(NativeSeekMapRules.shouldPassThrough(true, true),
                "a real sidx ChunkIndex is trusted and passed through");
    }

    private static void testMfraSeekableIsRejected() {
        check(!NativeSeekMapRules.shouldPassThrough(false, true),
                "MfraSeekMap (seekable but not ChunkIndex) must be replaced by managed mapping");
    }

    private static void testUnseekableNeverPassesThrough() {
        check(!NativeSeekMapRules.shouldPassThrough(true, false),
                "unseekable map never passes through even if chunk index");
        check(!NativeSeekMapRules.shouldPassThrough(false, false),
                "unseekable non-index map never passes through");
    }

    private static void testHasManagedIndex() {
        check(!NativeSeekMapRules.hasManagedIndex(false, true, true),
                "not attempted means no index yet");
        check(!NativeSeekMapRules.hasManagedIndex(true, false, false),
                "attempted with neither local nor remote is not managed");
        check(NativeSeekMapRules.hasManagedIndex(true, true, false),
                "local coverage alone is a managed index");
        check(NativeSeekMapRules.hasManagedIndex(true, false, true),
                "remote probe alone is a managed index");
    }

    private static void testSidxPassThroughSyncsResolvedState() {
        // 原生 SIDX 放行 → 必须同步进 resolvedIndex，否则 canSeekTo/hasIndex 看不到它。
        check(NativeSeekMapRules.syncResolvedFromNative(true, true),
                "passed-through sidx ChunkIndex must sync into resolvedIndex");
        check(!NativeSeekMapRules.syncResolvedFromNative(true, false),
                "a passed-through non-ChunkIndex must never sync");
        check(!NativeSeekMapRules.syncResolvedFromNative(false, true),
                "an intercepted map must not sync");
        // 联动：同步后 resolvedIndex 存在 → hasManagedIndex 与 coversLocal 才能识别。
        boolean resolvedReady = NativeSeekMapRules.syncResolvedFromNative(true, true);
        check(NativeSeekMapRules.hasManagedIndex(true, resolvedReady, false),
                "after sidx sync, hasIndex sees managed index (no needless rebuild)");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("FAIL: " + message);
        }
        passed++;
    }
}
