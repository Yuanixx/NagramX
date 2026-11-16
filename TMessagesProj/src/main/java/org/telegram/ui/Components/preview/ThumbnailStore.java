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

import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Immutable, sorted snapshot of decoded thumbnails.
 *
 * The UI thread only ever reads {@link #snapshot()} and binary-searches it:
 * no lock, no copy, no waiting for the decoder. Writers (the scheduler thread)
 * publish a fresh array after every change.
 *
 * Eviction removes references from the snapshot but never calls
 * {@link Bitmap#recycle()}: the view may still be drawing the bitmap it got a
 * moment ago, and a recycled bitmap on a Canvas is a crash or a black frame.
 * Dropped bitmaps are reclaimed by the GC in due course.
 */
public final class ThumbnailStore {

    /** Hard ceiling for decoded preview pictures. */
    public static final long MEMORY_BUDGET_BYTES = 16L * 1024 * 1024;

    public static final class Snapshot {
        static final Snapshot EMPTY = new Snapshot(new Thumbnail[0]);

        final Thumbnail[] items;
        final long bytes;

        Snapshot(Thumbnail[] items) {
            this.items = items;
            long total = 0;
            for (Thumbnail item : items) {
                total += item.byteCount();
            }
            this.bytes = total;
        }

        public int size() {
            return items.length;
        }

        public long byteCount() {
            return bytes;
        }

        public Thumbnail nearest(long timeMs) {
            return nearest(timeMs, Long.MAX_VALUE);
        }

        /**
         * @param maxDistanceMs the caller refuses a frame that is farther from
         *                      the requested time than this; showing one would
         *                      paint the wrong moment under a correct timestamp
         */
        public Thumbnail nearest(long timeMs, long maxDistanceMs) {
            final int n = items.length;
            if (n == 0) {
                return null;
            }
            int lo = 0;
            int hi = n - 1;
            while (lo <= hi) {
                final int mid = (lo + hi) >>> 1;
                final long t = items[mid].timeMs;
                if (t < timeMs) {
                    lo = mid + 1;
                } else if (t > timeMs) {
                    hi = mid - 1;
                } else {
                    return items[mid];
                }
            }
            final Thumbnail before = lo > 0 ? items[lo - 1] : null;
            final Thumbnail after = lo < n ? items[lo] : null;
            final long distanceBefore = before != null ? timeMs - before.timeMs : Long.MAX_VALUE;
            final long distanceAfter = after != null ? after.timeMs - timeMs : Long.MAX_VALUE;
            final Thumbnail closest = distanceBefore <= distanceAfter ? before : after;
            if (closest == null) {
                return null;
            }
            final long distance = closest == before ? distanceBefore : distanceAfter;
            return distance <= maxDistanceMs ? closest : null;
        }

        public Thumbnail exact(long timeMs) {
            int lo = 0;
            int hi = items.length - 1;
            while (lo <= hi) {
                final int mid = (lo + hi) >>> 1;
                final long t = items[mid].timeMs;
                if (t < timeMs) {
                    lo = mid + 1;
                } else if (t > timeMs) {
                    hi = mid - 1;
                } else {
                    return items[mid];
                }
            }
            return null;
        }

        /** 1-based position of this exact time in the sorted store, or 0. */
        public int ordinalOf(long timeMs) {
            int lo = 0;
            int hi = items.length - 1;
            while (lo <= hi) {
                final int mid = (lo + hi) >>> 1;
                final long t = items[mid].timeMs;
                if (t < timeMs) {
                    lo = mid + 1;
                } else if (t > timeMs) {
                    hi = mid - 1;
                } else {
                    return mid + 1;
                }
            }
            return 0;
        }
    }

    private static final Comparator<Thumbnail> BY_TIME =
            (a, b) -> Long.compare(a.timeMs, b.timeMs);

    private final Object lock = new Object();
    private final ArrayList<Thumbnail> items = new ArrayList<>();
    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private boolean sealed;

    public Snapshot snapshot() {
        return snapshot;
    }

    public Thumbnail nearest(long timeMs) {
        return snapshot.nearest(timeMs);
    }

    public Thumbnail nearest(long timeMs, long maxDistanceMs) {
        return snapshot.nearest(timeMs, maxDistanceMs);
    }

    /** 1-based sorted position of an exact thumbnail time, or 0. */
    public int ordinalOf(long timeMs) {
        return snapshot.ordinalOf(timeMs);
    }

    public boolean containsTime(long timeMs) {
        return snapshot.exact(timeMs) != null;
    }

    public int size() {
        return snapshot.size();
    }

    /**
     * Adds or replaces one picture and republishes the snapshot.
     *
     * @param desiredMs current drag position, used to decide which fine
     *                  thumbnail is farthest and therefore evicted first
     */
    public void put(Thumbnail thumbnail, long desiredMs) {
        if (thumbnail == null || thumbnail.bitmap == null
                || thumbnail.bitmap.isRecycled()) {
            return;
        }
        synchronized (lock) {
            if (sealed) {
                return;
            }
            final int existing = indexOfLocked(thumbnail);
            if (existing >= 0) {
                final Thumbnail old = items.get(existing);
                if (old.bitmap == thumbnail.bitmap) {
                    return;
                }
                items.remove(existing);
            }
            int insert = 0;
            while (insert < items.size()
                    && BY_TIME.compare(items.get(insert), thumbnail) <= 0) {
                insert++;
            }
            items.add(insert, thumbnail);
            trimLocked(desiredMs);
            publishLocked();
        }
    }

    /**
     * Drops every reference. The store is sealed afterwards so a decode that
     * lands after the session was torn down cannot resurrect it.
     */
    public void clear() {
        synchronized (lock) {
            sealed = true;
            items.clear();
            snapshot = Snapshot.EMPTY;
        }
    }

    private int indexOfLocked(Thumbnail thumbnail) {
        for (int i = 0; i < items.size(); i++) {
            final Thumbnail item = items.get(i);
            if (thumbnail.keyframeIndex >= 0 && item.keyframeIndex >= 0) {
                if (item.keyframeIndex == thumbnail.keyframeIndex) {
                    return i;
                }
            } else if (item.timeMs == thumbnail.timeMs) {
                return i;
            }
        }
        return -1;
    }

    private void trimLocked(long desiredMs) {
        long total = 0;
        for (Thumbnail item : items) {
            total += item.byteCount();
        }
        while (total > MEMORY_BUDGET_BYTES) {
            int victim = farthestLocked(Thumbnail.TIER_FINE, desiredMs);
            if (victim < 0) {
                victim = farthestLocked(Thumbnail.TIER_MID, desiredMs);
            }
            if (victim < 0) {
                // Coarse thumbnails are the floor: never evict them.
                break;
            }
            final Thumbnail removed = items.remove(victim);
            total -= removed.byteCount();
        }
    }

    private int farthestLocked(int tier, long desiredMs) {
        int victim = -1;
        long farthest = -1;
        for (int i = 0; i < items.size(); i++) {
            final Thumbnail item = items.get(i);
            if (item.tier != tier) {
                continue;
            }
            final long distance = Math.abs(item.timeMs - desiredMs);
            if (distance > farthest) {
                farthest = distance;
                victim = i;
            }
        }
        return victim;
    }

    private void publishLocked() {
        final Thumbnail[] array = items.toArray(new Thumbnail[0]);
        Arrays.sort(array, BY_TIME);
        snapshot = new Snapshot(array);
    }
}
