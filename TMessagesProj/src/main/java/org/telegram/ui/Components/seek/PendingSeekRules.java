package org.telegram.ui.Components.seek;

/** Pure rules for one-shot, owner-bound replay of a deferred seek. */
public final class PendingSeekRules {

    private PendingSeekRules() {
    }

    /** A seek is deferred only for a streaming target that no current map can resolve. */
    public static boolean shouldDefer(boolean streaming, long targetMs, boolean covered) {
        return streaming && targetMs > 0L && !covered;
    }

    /**
     * Only the player instance and generation that the rebuild actually produced may consume
     * the pending target. A boolean flag alone is not an owner token.
     */
    public static boolean canConsume(boolean rebuilt, Object owner, Object current,
                                     int ownerGeneration, int currentGeneration,
                                     long pendingDocId, long currentDocId) {
        return rebuilt
                && owner != null && owner == current
                && ownerGeneration >= 0 && ownerGeneration == currentGeneration
                && pendingDocId == currentDocId;
    }
}
