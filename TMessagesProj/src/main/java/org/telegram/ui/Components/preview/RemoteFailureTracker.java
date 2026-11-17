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

/**
 * Classifies range-fetch outcomes for one preview session.
 *
 * <p>A server flood wait (420) is not a session failure: the account-wide
 * cooldown in the network layer already parks every request, and mistaking it
 * for a plain failure used to switch the session off for good. Cancelled and
 * locally refused requests are not failures either. Only a real failure moves
 * the streak, and three in a row park this session for 30s, doubling up to
 * five minutes. A success is the recovery probe and clears everything.
 */
final class RemoteFailureTracker {

    enum Outcome {
        SUCCESS,
        FLOOD,
        LOCAL_COOLDOWN,
        CANCELLED,
        REAL_FAILURE
    }

    static final int MAX_FAILURES = 3;
    static final long FIRST_COOLDOWN_MS = 30_000L;
    static final long MAX_COOLDOWN_MS = 5L * 60L * 1000L;

    private int failures;
    private int streak;
    private int floodWaits;
    private long disabledUntilMs;
    private boolean probePending;
    private boolean expired;

    /**
     * The scheduler thread, the prefetch thread and the decoder fetch thread
     * all read this state, so every method is synchronized; the critical
     * sections are a few field writes and never block on I/O.
     *
     * @return true when this outcome started a session cooldown, so the
     *         caller can cancel requests that are still on the wire
     */
    synchronized boolean onOutcome(Outcome outcome, long nowMs) {
        if (outcome == null) {
            outcome = Outcome.REAL_FAILURE;
        }
        switch (outcome) {
            case SUCCESS:
                failures = 0;
                streak = 0;
                floodWaits = 0;
                probePending = false;
                return false;
            case FLOOD:
                floodWaits++;
                return false;
            case LOCAL_COOLDOWN:
            case CANCELLED:
                return false;
            case REAL_FAILURE:
            default:
                failures++;
                if (failures >= MAX_FAILURES || probePending) {
                    startCooldown(nowMs);
                    return true;
                }
                return false;
        }
    }

    private void startCooldown(long nowMs) {
        final long cooldown = cooldownMsForStreak(streak);
        streak = Math.min(streak + 1, 8);
        disabledUntilMs = nowMs + cooldown;
        failures = 0;
        probePending = true;
    }

    /** 30s, 60s, 120s, 240s, then the 300s cap. */
    static long cooldownMsForStreak(int streak) {
        long cooldown = FIRST_COOLDOWN_MS;
        for (int i = 0; i < streak; i++) {
            if (cooldown >= MAX_COOLDOWN_MS) {
                return MAX_COOLDOWN_MS;
            }
            cooldown *= 2L;
        }
        return Math.min(cooldown, MAX_COOLDOWN_MS);
    }

    /**
     * True while this session is parked. The expiry is latched so the caller
     * can log it once and let the next request act as the recovery probe.
     */
    synchronized boolean isCoolingDown(long nowMs) {
        if (disabledUntilMs == 0L) {
            return false;
        }
        if (nowMs < disabledUntilMs) {
            return true;
        }
        disabledUntilMs = 0L;
        expired = true;
        return false;
    }

    synchronized boolean consumeExpired() {
        final boolean value = expired;
        expired = false;
        return value;
    }

    synchronized int floodWaits() {
        return floodWaits;
    }

    synchronized int failures() {
        return failures;
    }

    synchronized int streak() {
        return streak;
    }

    synchronized long disabledUntilMs() {
        return disabledUntilMs;
    }

    synchronized boolean probePending() {
        return probePending;
    }
}
