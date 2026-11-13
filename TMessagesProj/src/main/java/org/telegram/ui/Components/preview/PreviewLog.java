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

import android.util.Log;

import org.telegram.messenger.FileLog;

/**
 * Preview diagnostics that survive a release build. Messages use the always-on
 * ABPV tag and go through {@link FileLog}, so both logcat and the current app
 * session file retain the decoder and scheduler milestones.
 */
public final class PreviewLog {

    private static final String TAG = "ABPV";

    private PreviewLog() {
    }

    public static void d(String message) {
        FileLog.d("ABPV: " + message);
    }

    public static void w(String message) {
        Log.w(TAG, message);
        FileLog.w("ABPV: " + message);
    }

    public static void w(String message, Throwable throwable) {
        Log.w(TAG, message, throwable);
        FileLog.e("ABPV: " + message, throwable);
    }

    public static void e(String message, Throwable throwable) {
        Log.e(TAG, message, throwable);
        FileLog.e("ABPV: " + message, throwable);
    }
}
