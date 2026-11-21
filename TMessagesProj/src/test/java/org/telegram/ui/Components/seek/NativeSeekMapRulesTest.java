package org.telegram.ui.Components.seek;

/** Plain-JVM regression tests for native SeekMap trust decision. */
public final class NativeSeekMapRulesTest {

    private static int passed;

    public static void main(String[] args) {
        testOnlyChunkIndexPassesThrough();
        testMfraSeekableIsRejected();
        testUnseekableNeverPassesThrough();
        testHasManagedIndex();
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

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("FAIL: " + message);
        }
        passed++;
    }
}
