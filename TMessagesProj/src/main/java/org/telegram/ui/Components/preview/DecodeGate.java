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
 * Orders "clear the cancel flag and start decoding" against "stop and cancel".
 *
 * <p>The old code reset the decoder's cancel flag at the top of every
 * {@code decodeAt}, while {@code stop()} set it from another thread. If the
 * stop landed in between, the reset erased a cancellation that had already
 * been delivered and the decode ran on after the session was torn down.
 * Both operations now share one lock: a begin either happens entirely before
 * the stop (so the stop's cancel survives) or is refused because the gate is
 * already closed.
 */
final class DecodeGate {

    private final Object lock = new Object();
    private boolean stopped;

    /**
     * Opens one decode. The reset runs under the same lock as the stop check,
     * so a concurrent {@link #stop(Runnable)} cannot be undone by it.
     *
     * @return false when the gate is closed; the caller must not decode
     */
    boolean begin(Runnable resetCancel) {
        synchronized (lock) {
            if (stopped) {
                return false;
            }
            if (resetCancel != null) {
                resetCancel.run();
            }
            return true;
        }
    }

    /** Closes the gate; the cancel runs after the lock so it always lands. */
    void stop(Runnable cancel) {
        synchronized (lock) {
            if (stopped) {
                return;
            }
            stopped = true;
        }
        if (cancel != null) {
            cancel.run();
        }
    }

    boolean isStopped() {
        synchronized (lock) {
            return stopped;
        }
    }
}
