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
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.Image;

import java.nio.ByteBuffer;

/**
 * YUV_420_888 to a small RGB_565 bitmap, with BT.601/BT.709 handling and
 * rotation. RGB_565 halves the memory of the thumbnail cache and is more than
 * enough for a preview bubble.
 *
 * The scaling is a cheap box/bilinear hybrid: one bilinear sample per output
 * pixel with the chroma sampled from the nearest pair. It was validated
 * against MediaMetadataRetriever output on H.264 and HEVC during stage 0.
 */
final class YuvToBitmap {

    private YuvToBitmap() {
    }

    static Bitmap convert(Image image, int targetW, int targetH,
                          int rotationDegrees, int colorMatrix, boolean fullRange) {
        if (targetW <= 0 || targetH <= 0) {
            return null;
        }
        final Image.Plane[] planes = image.getPlanes();
        final ByteBuffer yBuf = planes[0].getBuffer();
        final ByteBuffer uBuf = planes[1].getBuffer();
        final ByteBuffer vBuf = planes[2].getBuffer();
        final int yRow = planes[0].getRowStride();
        final int yPix = planes[0].getPixelStride();
        final int uRow = planes[1].getRowStride();
        final int uPix = planes[1].getPixelStride();
        final int vRow = planes[2].getRowStride();
        final int vPix = planes[2].getPixelStride();

        Rect crop = image.getCropRect();
        if (crop == null) {
            crop = new Rect(0, 0, image.getWidth(), image.getHeight());
        }
        final int cropW = crop.width();
        final int cropH = crop.height();
        if (cropW <= 0 || cropH <= 0) {
            return null;
        }
        final int chromaW = Math.max(1, (cropW + 1) / 2);
        final int chromaH = Math.max(1, (cropH + 1) / 2);

        final boolean bt709 = colorMatrix == 1 || colorMatrix == 9
                || (colorMatrix == 0 && cropH >= 720);

        // Coefficients scaled by 1024.
        final int yMul;
        final int yOff;
        final int rv;
        final int gu;
        final int gv;
        final int bu;
        if (bt709) {
            if (fullRange) {
                yMul = 1024;
                yOff = 0;
                rv = 1613;
                gu = 192;
                gv = 479;
                bu = 1900;
            } else {
                yMul = 1192;
                yOff = 16;
                rv = 1836;
                gu = 218;
                gv = 546;
                bu = 2163;
            }
        } else {
            if (fullRange) {
                yMul = 1024;
                yOff = 0;
                rv = 1436;
                gu = 352;
                gv = 731;
                bu = 1815;
            } else {
                yMul = 1192;
                yOff = 16;
                rv = 1634;
                gu = 401;
                gv = 833;
                bu = 2066;
            }
        }

        final int[] pixels = new int[targetW * targetH];
        final float scaleX = (float) cropW / targetW;
        final float scaleY = (float) cropH / targetH;

        // Sampling coordinates depend on only one output axis. Precomputing
        // them keeps floating-point floor and stride multiplication out of the
        // per-pixel loop without changing the bilinear result.
        final int[] x0Offsets = new int[targetW];
        final int[] x1Offsets = new int[targetW];
        final int[] xWeights = new int[targetW];
        final int[] chromaXU = new int[targetW];
        final int[] chromaXV = new int[targetW];
        for (int x = 0; x < targetW; x++) {
            final float sx = crop.left + (x + 0.5f) * scaleX - 0.5f;
            int x0 = (int) Math.floor(sx);
            int x1;
            int wx;
            if (x0 < crop.left) {
                x0 = crop.left;
                x1 = x0;
                wx = 0;
            } else if (x0 >= crop.right - 1) {
                x0 = crop.right - 1;
                x1 = x0;
                wx = 0;
            } else {
                x1 = x0 + 1;
                wx = (int) ((sx - x0) * 256);
            }
            x0Offsets[x] = x0 * yPix;
            x1Offsets[x] = x1 * yPix;
            xWeights[x] = wx;
            int cx = ((int) (crop.left + (x + 0.5f) * scaleX)) >> 1;
            if (cx >= chromaW) cx = chromaW - 1;
            chromaXU[x] = cx * uPix;
            chromaXV[x] = cx * vPix;
        }

        final int[] y0Rows = new int[targetH];
        final int[] y1Rows = new int[targetH];
        final int[] yWeights = new int[targetH];
        final int[] chromaY = new int[targetH];
        for (int y = 0; y < targetH; y++) {
            final float sy = crop.top + (y + 0.5f) * scaleY - 0.5f;
            int y0 = (int) Math.floor(sy);
            int y1;
            int wy;
            if (y0 < crop.top) {
                y0 = crop.top;
                y1 = y0;
                wy = 0;
            } else if (y0 >= crop.bottom - 1) {
                y0 = crop.bottom - 1;
                y1 = y0;
                wy = 0;
            } else {
                y1 = y0 + 1;
                wy = (int) ((sy - y0) * 256);
            }
            y0Rows[y] = y0 * yRow;
            y1Rows[y] = y1 * yRow;
            yWeights[y] = wy;
            int cy = ((int) (crop.top + (y + 0.5f) * scaleY)) >> 1;
            if (cy >= chromaH) cy = chromaH - 1;
            chromaY[y] = cy;
        }

        for (int y = 0; y < targetH; y++) {
            final int y0Row = y0Rows[y];
            final int y1Row = y1Rows[y];
            final int wy = yWeights[y];
            final int uChromaRow = chromaY[y] * uRow;
            final int vChromaRow = chromaY[y] * vRow;
            final int pixelRow = y * targetW;
            for (int x = 0; x < targetW; x++) {
                final int x0Offset = x0Offsets[x];
                final int x1Offset = x1Offsets[x];
                final int wx = xWeights[x];
                final int y00 = yBuf.get(y0Row + x0Offset) & 0xff;
                final int y10 = yBuf.get(y0Row + x1Offset) & 0xff;
                final int y01 = yBuf.get(y1Row + x0Offset) & 0xff;
                final int y11 = yBuf.get(y1Row + x1Offset) & 0xff;
                final int top = (y00 * (256 - wx) + y10 * wx) >> 8;
                final int bottom = (y01 * (256 - wx) + y11 * wx) >> 8;
                final int yv = (top * (256 - wy) + bottom * wy) >> 8;

                final int uv = uBuf.get(uChromaRow + chromaXU[x]) & 0xff;
                final int vv = vBuf.get(vChromaRow + chromaXV[x]) & 0xff;
                final int yy = ((yv - yOff) * yMul) >> 10;
                final int du = uv - 128;
                final int dv = vv - 128;
                int r = yy + ((rv * dv) >> 10);
                int g = yy - ((gu * du) >> 10) - ((gv * dv) >> 10);
                int b = yy + ((bu * du) >> 10);
                if (r < 0) r = 0; else if (r > 255) r = 255;
                if (g < 0) g = 0; else if (g > 255) g = 255;
                if (b < 0) b = 0; else if (b > 255) b = 255;
                pixels[pixelRow + x] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
        }

        final Bitmap raw = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.RGB_565);
        raw.setPixels(pixels, 0, targetW, 0, 0, targetW, targetH);
        if (rotationDegrees == 0) {
            return raw;
        }
        final Matrix matrix = new Matrix();
        matrix.postRotate(rotationDegrees);
        final Bitmap rotated = Bitmap.createBitmap(raw, 0, 0, targetW, targetH, matrix, true);
        if (rotated != raw) {
            raw.recycle();
        }
        return rotated;
    }
}
