/*
 * Copyright (C) 2024 NagramX Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.telegram.messenger;

/** Pure decision rule for moving an active, sparse-file Telegram stream. */
final class StreamRepositionRules {

    private StreamRepositionRules() {
    }

    static boolean shouldCancelInFlightRequests(boolean alreadyStarted, boolean hasStream,
                                                boolean streamPriority, boolean hasSparseRanges,
                                                long previousAlignedOffset, long nextAlignedOffset) {
        return alreadyStarted && hasStream && !streamPriority && hasSparseRanges
                && previousAlignedOffset != nextAlignedOffset;
    }
}
