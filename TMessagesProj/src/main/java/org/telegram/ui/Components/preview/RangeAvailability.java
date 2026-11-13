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
 * Asks the downloader whether one exact byte range of a streaming file is
 * already on disk. A partially downloaded .temp is a sparse file: a range that
 * the downloader has not filled yet reads as zeros, and feeding zeros to a
 * codec produces the failure storms seen on real devices. The decoder consults
 * this before every sample read.
 */
public interface RangeAvailability {

    /**
     * @param offset file offset of the range
     * @param size    length of the range in bytes
     * @return true when every byte of [offset, offset + size) is local
     */
    boolean isAvailable(long offset, long size);
}
