/*
 * Copyright (C) 2024 NagramX Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.telegram.messenger;

/**
 * Immutable snapshot of the byte ranges that have not yet reached disk.
 *
 * FileLoadOperation mutates its stage-queue Range objects while responses arrive.
 * Publishing a shallow copy of the list still exposes those mutable objects to
 * decoder threads, which can observe a new range boundary before its bytes have
 * been written. This value copies the boundaries into primitive arrays and is
 * published only after the corresponding write has completed.
 */
final class DownloadedRangeSnapshot {

    private final long[] missingStarts;
    private final long[] missingEnds;
    private final long totalBytes;
    private final boolean complete;

    DownloadedRangeSnapshot(long[] starts, long[] ends, long totalBytes, boolean complete) {
        if (starts == null || ends == null || starts.length != ends.length || totalBytes < 0) {
            throw new IllegalArgumentException("Invalid downloaded-range snapshot");
        }
        this.missingStarts = starts.clone();
        this.missingEnds = ends.clone();
        this.totalBytes = totalBytes;
        this.complete = complete;
        for (int i = 0; i < missingStarts.length; i++) {
            long start = missingStarts[i];
            long end = missingEnds[i];
            if (start < 0 || end < start || end > totalBytes) {
                throw new IllegalArgumentException("Invalid missing range at " + i);
            }
        }
    }

    /**
     * Returns the contiguous known-good byte count at offset.
     * Returns -1 when an empty range list cannot prove that the whole file is present.
     */
    long availableLength(long offset, long requestedLength) {
        if (offset < 0 || requestedLength < 0) {
            return 0;
        }
        if (requestedLength == 0 || offset >= totalBytes) {
            return 0;
        }
        final long boundedLength = Math.min(requestedLength, totalBytes - offset);
        if (complete) {
            return boundedLength;
        }
        if (missingStarts.length == 0) {
            return -1;
        }

        // The legacy range mutators do not promise list ordering, so derive the
        // nearest missing boundary without relying on array order.
        long nearestMissingStart = Long.MAX_VALUE;
        for (int i = 0; i < missingStarts.length; i++) {
            final long missingStart = missingStarts[i];
            final long missingEnd = missingEnds[i];
            if (missingStart <= offset && offset < missingEnd) {
                return 0;
            }
            if (missingStart > offset && missingStart < nearestMissingStart) {
                nearestMissingStart = missingStart;
            }
        }
        return nearestMissingStart == Long.MAX_VALUE
                ? boundedLength
                : Math.min(boundedLength, nearestMissingStart - offset);
    }
}
