/*
 * Copyright (C) 2024 NagramX Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.telegram.ui.Components.seek;

/**
 * Pure rules for deciding which native Media3 SeekMaps can be passed through.
 *
 * A real SIDX is exposed as ChunkIndex. MFRA/tfra is exposed as TrackAwareSeekMap.
 * Both carry actual random-access byte positions. For MFRA, a target before the
 * first tfra entry returns SeekPoint(0, firstMediaDataPosition): zero is the
 * timestamp, not the byte offset. A target after the last tfra entry resolves
 * to the last preceding random-access point, which is a valid decoder start point.
 *
 * Unknown seekable implementations remain rejected. Unseekable maps are never
 * passed through as trusted indexes.
 *
 * No Android or Media3 dependencies: this class is plain-JVM testable.
 */
public final class NativeSeekMapRules {

    private NativeSeekMapRules() {
    }

    /** Whether a recognized native map may be passed through. */
    public static boolean shouldPassThrough(
            boolean isChunkIndex, boolean isTrackAware, boolean isSeekable) {
        return isSeekable && (isChunkIndex || isTrackAware);
    }

    /** Whether any usable local, remote, or native mapping has been initialized. */
    public static boolean hasManagedIndex(
            boolean attempted, boolean hasLocal, boolean hasRemote, boolean hasNative) {
        return attempted && (hasLocal || hasRemote || hasNative);
    }

    /**
     * Whether a TrackAwareSeekMap produced valid byte positions for this target.
     *
     * <p>ChunkIndex is deliberately excluded from this shortcut: SIDX coverage is
     * finite and remains guarded by the local ChunkIndex coverage check.
     */
    public static boolean hasUsableNativeSeekPoints(
            boolean isTrackAware,
            boolean hasNativeMap,
            boolean isSeekable,
            long firstPosition,
            long secondPosition) {
        return isTrackAware
                && hasNativeMap
                && isSeekable
                && firstPosition >= 0
                && secondPosition >= 0;
    }

    /** A passed-through SIDX ChunkIndex must also be reflected in resolvedIndex. */
    public static boolean syncResolvedFromNative(boolean passThrough, boolean isChunkIndex) {
        return passThrough && isChunkIndex;
    }
}
