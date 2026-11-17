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

package org.telegram.messenger;

/**
 * Pure decision rules for fragmented-MP4 index building. No Android dependencies so the
 * plain-JVM regression test can compile and exercise the exact same code the app runs.
 *
 * <p>Keeping these rules here (in {@code messenger}, next to {@link FragmentIndexBuilder})
 * instead of in the UI preview package lets the index builder use them without a layering
 * violation, and lets CI cover them with javac alone.</p>
 */
public final class FragmentIndexRules {

    /** Maximum trailing bytes a genuinely complete file may carry. */
    public static final long MAX_TAIL_BYTES = 64;

    private FragmentIndexRules() {
    }

    /**
     * True when a file that claims the full document length actually holds only a prefix of
     * real boxes: the box chain stopped far before the declared length, leaving a large zero
     * tail (a pre-allocated streaming temp). Such a file must be indexed with the partial
     * rules so the player gets a seekable map from the fragments that did arrive instead of
     * restarting every seek at byte 0.
     *
     * @param len        number of bytes actually readable (here the file length)
     * @param docSize    declared document size. Kept for call-site/signature compatibility;
     *                   not used by the rule itself. At the only production call site
     *                   (FragmentIndexBuilder.scan), this is reached only under {@code complete},
     *                   which already implies {@code len == docSize}, so a {@code len < docSize}
     *                   branch here would be unreachable. The growing-prefix case is decided by
     *                   {@code complete == false} before this rule is consulted.
     * @param validEnd   byte offset where the last complete top-level box ends
     * @param maxTail    maximum tolerated trailing bytes for a genuinely complete file
     */
    public static boolean shouldTreatAsPartial(long len, long docSize, long validEnd, long maxTail) {
        if (validEnd < 0 || validEnd > len) {
            return true;
        }
        return len - validEnd > maxTail;
    }

    /**
     * PARTIAL cache key. A pre-allocated temp keeps {@code len == docSize} while its real
     * content grows, so length alone cannot detect growth. The allocated-byte count (from
     * {@code Os.stat().st_blocks * 512}) changes as blocks are written and is folded in here,
     * so a grown file misses the old prefix index instead of reusing a stale one.
     */
    public static String partialCacheKey(long docId, long docSize, long len, long allocatedBytes) {
        return docId + "_" + docSize + "_" + len + "_" + allocatedBytes;
    }
}
