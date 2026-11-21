package org.telegram.messenger;

/** Plain-JVM regression tests for download-completion cross-checks. */
public final class DownloadCompletionRulesTest {

    private static int passed;

    public static void main(String[] args) {
        testCompleteWhenAllEvidenceAgrees();
        testDiskShorterThanDeclaredIsNotComplete();
        testMissingRangesMeansNotComplete();
        testUnderDownloadedBytesMeansNotComplete();
        testUnknownSizeIsNotComplete();
        testUnknownDiskLengthIsNotComplete();
        testExactlyFullDiskIsComplete();
        System.out.println("DOWNLOAD COMPLETION RULES: " + passed + " checks passed");
    }

    private static void testCompleteWhenAllEvidenceAgrees() {
        check(DownloadCompletionRules.isComplete(true, 1000, 1000, 1000),
                "matching counters and full disk means complete");
    }

    private static void testDiskShorterThanDeclaredIsNotComplete() {
        check(!DownloadCompletionRules.isComplete(true, 1000, 1000, 900),
                "declared complete but disk short is the zero-hole regression: not complete");
        check(!DownloadCompletionRules.isComplete(true, 1000, 1000, 1),
                "severely truncated disk file must not be trusted");
    }

    private static void testMissingRangesMeansNotComplete() {
        check(!DownloadCompletionRules.isComplete(false, 1000, 1000, 1000),
                "outstanding missing ranges means not complete even if counters match");
    }

    private static void testUnderDownloadedBytesMeansNotComplete() {
        check(!DownloadCompletionRules.isComplete(true, 800, 1000, 1000),
                "downloaded counter below declared size means not complete");
    }

    private static void testUnknownSizeIsNotComplete() {
        check(!DownloadCompletionRules.isComplete(true, 0, 0, 0),
                "unknown declared size cannot prove completion");
        check(!DownloadCompletionRules.isComplete(true, 0, -1, 100),
                "negative declared size cannot prove completion");
    }

    private static void testUnknownDiskLengthIsNotComplete() {
        check(!DownloadCompletionRules.isComplete(true, 1000, 1000, -1),
                "unreadable disk length must fail closed to conservative reads");
    }

    private static void testExactlyFullDiskIsComplete() {
        check(DownloadCompletionRules.isComplete(true, 1000, 1000, 1000),
                "disk length equal to declared size is complete");
        check(DownloadCompletionRules.isComplete(true, 1000, 1000, 1200),
                "disk longer than declared size is still complete");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("FAIL: " + message);
        }
        passed++;
    }
}
