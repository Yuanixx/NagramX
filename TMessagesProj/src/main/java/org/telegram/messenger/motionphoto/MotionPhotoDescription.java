/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.telegram.messenger.motionphoto;

import androidx.annotation.Nullable;

import androidx.media3.common.C;

import java.util.List;

/**
 * Describes the layout and metadata of a motion photo file.
 *
 * <p>Copied from {@code androidx.media3.extractor.jpeg.MotionPhotoDescription}, which is
 * package-private in Media3. Only the fields used by the app are kept.
 */
public final class MotionPhotoDescription {

    /** Describes a media item in the motion photo. */
    public static final class ContainerItem {
        /** The MIME type of the media item. */
        public final String mime;

        /** The application-specific meaning of the media item. */
        public final String semantic;

        /**
         * The positive integer length in bytes of the media item, or 0 for primary media items and
         * secondary media items that share their resource with the preceding media item.
         */
        public final long length;

        /**
         * The number of bytes of additional padding between the end of the primary media item and
         * the start of the next media item. 0 for secondary media items.
         */
        public final long padding;

        public ContainerItem(String mime, String semantic, long length, long padding) {
            this.mime = mime;
            this.semantic = semantic;
            this.length = length;
            this.padding = padding;
        }
    }

    /**
     * The presentation timestamp of the primary media item, in microseconds, or
     * {@link C#TIME_UNSET} if unknown.
     */
    public final long photoPresentationTimestampUs;

    /**
     * The media items represented by the motion photo file, in order. The primary media item is
     * listed first, followed by any secondary media items.
     */
    public final List<ContainerItem> items;

    public MotionPhotoDescription(long photoPresentationTimestampUs, List<ContainerItem> items) {
        this.photoPresentationTimestampUs = photoPresentationTimestampUs;
        this.items = items;
    }
}
