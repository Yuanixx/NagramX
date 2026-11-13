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

package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.text.TextPaint;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.preview.PreviewLog;
import org.telegram.ui.Components.preview.PreviewSession;
import org.telegram.ui.Components.preview.Thumbnail;

import java.io.File;
import java.util.Objects;

/**
 * Seek-bar preview bubble.
 *
 * The drag path is deliberately trivial: it looks up the nearest already
 * decoded thumbnail in an immutable store and paints it. All decoding,
 * scheduling and eviction happen on a worker owned by {@link PreviewSession}.
 * A missing picture never clears the bubble; the timestamp stays and the
 * nearest available frame remains on screen.
 */
public class VideoSeekFramePreview extends View {

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint bgPaint = new Paint();
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dstRect = new RectF();

    private Bitmap frame;
    /** True only for an external bitmap, which this view owns and may recycle. */
    private boolean frameOwned;
    private String frameTime;
    private int timeWidth;
    private int previewWidth;
    private int previewHeight;

    private Uri mediaUri;
    private File mediaFile;
    private long mediaDeclaredSize;
    private TLRPC.Document mediaDocument;
    private int mediaAccount = -1;
    private long mediaId;
    private long mediaDialogId;
    private int mediaMessageId;

    private PreviewSession session;
    private volatile PressedProvider pressedProvider;
    private volatile BufferingProvider bufferingProvider;
    private volatile long lastFrameAt;
    private boolean dragging;
    private boolean retainUriForCurrentDrag;
    private boolean loggedUriRetention;
    private long requestedPosition = -1;
    private int requestLogCount;

    /**
     * Last line of defence against a stuck bubble: if it is visible and either
     * the seek bar is no longer pressed or nothing has updated it for 1.5s,
     * remove it. This does not depend on any callback arriving.
     */
    private final Runnable staleRunnable = new Runnable() {
        @Override
        public void run() {
            if (getVisibility() != VISIBLE) {
                return;
            }
            if (isPressedNow()) {
                postDelayed(this, 500);
                return;
            }
            if (System.currentTimeMillis() - lastFrameAt >= 1500) {
                hide("stale");
            } else {
                postDelayed(this, 500);
            }
        }
    };

    /**
     * Buffering is queried on the UI thread; the scheduler never touches the
     * player. Checked a few times per second while a session is alive.
     */
    private final Runnable bufferingRunnable = new Runnable() {
        @Override
        public void run() {
            final PreviewSession current = session;
            if (current == null) {
                return;
            }
            current.setBuffering(isBufferingNow());
            postDelayed(this, 250);
        }
    };

    public VideoSeekFramePreview(Context context) {
        super(context);
        setVisibility(INVISIBLE);
        bgPaint.setColor(0x99000000);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(AndroidUtilities.dp(13));
    }

    public void setMediaFile(File file, long totalStreamSize, TLRPC.Document document, int account) {
        setMediaFile(file, totalStreamSize, document, account,
                document != null ? document.id : 0);
    }

    /**
     * @param mediaId stable identity of the media (the message id). For a
     *                multi-quality video the document id changes as different
     *                qualities become cached, so it cannot identify a session.
     */
    public void setMediaFile(File file, long totalStreamSize, TLRPC.Document document,
                             int account, long mediaId) {
        setMediaFile(file, totalStreamSize, document, account, mediaId, 0, 0);
    }

    /**
     * @param dialogId  the chat the message lives in; message ids restart in
     *                  every channel, so the dialog is part of the identity
     * @param messageId the message id inside that chat
     */
    public void setMediaFile(File file, long totalStreamSize, TLRPC.Document document,
                             int account, long mediaId, long dialogId, int messageId) {
        if (dragging && retainUriForCurrentDrag && session != null && mediaUri != null
                && isSameStreamingMedia(document, account, mediaId, dialogId, messageId)) {
            // Opening the content:// provider can create the active .temp file.
            // Do not replace the in-flight URI decoder with that file halfway
            // through the same gesture; the next drag may prefer the local file.
            if (!loggedUriRetention) {
                loggedUriRetention = true;
                PreviewLog.d("SeekPreview: retain uri for drag file="
                        + (file != null ? file.getName() : "null"));
            }
            updateBuffering();
            return;
        }
        final boolean same = session != null && mediaUri == null
                && mediaFile != null && file != null
                && mediaFile.getAbsolutePath().equals(file.getAbsolutePath())
                && mediaDeclaredSize == totalStreamSize
                && this.mediaId == mediaId
                && this.mediaDialogId == dialogId
                && this.mediaMessageId == messageId;
        if (same) {
            // The document can change as a different quality becomes cached;
            // the session itself stays identified by the message.
            mediaDocument = document;
            mediaAccount = account;
            updateBuffering();
            return;
        }

        closeSession();
        dropFrame();
        requestedPosition = -1;
        retainUriForCurrentDrag = false;
        loggedUriRetention = false;
        mediaUri = null;
        mediaFile = file;
        mediaDeclaredSize = totalStreamSize;
        mediaDocument = document;
        mediaAccount = account;
        this.mediaId = mediaId;
        this.mediaDialogId = dialogId;
        this.mediaMessageId = messageId;
        if (file != null) {
            openSession();
        }
    }

    /**
     * Sets a {@code content://} source. This is the fallback for media with no
     * local file at all; it uses MediaMetadataRetriever through the provider.
     */
    public void setMediaUri(Uri uri, TLRPC.Document document, int account,
                            long mediaId, long dialogId, int messageId) {
        if (uri == null && mediaUri == null && session == null) {
            retainUriForCurrentDrag = false;
            return;
        }
        if (uri != null && session != null && isSameMediaUri(uri, mediaUri)
                && isSameStreamingMedia(document, account, mediaId, dialogId, messageId)) {
            retainUriForCurrentDrag = dragging;
            updateBuffering();
            return;
        }
        closeSession();
        dropFrame();
        requestedPosition = -1;
        retainUriForCurrentDrag = dragging && uri != null;
        loggedUriRetention = false;
        mediaUri = uri;
        mediaFile = null;
        mediaDeclaredSize = document != null ? document.size : 0;
        mediaDocument = document;
        mediaAccount = account;
        this.mediaId = mediaId;
        this.mediaDialogId = dialogId;
        this.mediaMessageId = messageId;
        if (uri != null) {
            openSession();
        }
    }

    private boolean isSameStreamingMedia(TLRPC.Document document, int account,
                                         long mediaId, long dialogId, int messageId) {
        return mediaDocument != null && document != null
                && mediaDocument.id == document.id
                && mediaAccount == account
                && this.mediaId == mediaId
                && mediaDialogId == dialogId
                && mediaMessageId == messageId;
    }

    private static boolean isSameMediaUri(Uri first, Uri second) {
        if (first == null || second == null
                || !Objects.equals(first.getScheme(), second.getScheme())
                || !Objects.equals(first.getAuthority(), second.getAuthority())
                || !Objects.equals(first.getPath(), second.getPath())) {
            return false;
        }
        // getStreamingContentUri() allocates a new parent-reference id on every
        // seek tick. That volatile rid must not tear down an active decoder;
        // every document-identifying parameter still has to match.
        final String[] keys = {"account", "id", "hash", "dc", "size", "mime", "name", "reference"};
        for (String key : keys) {
            if (!Objects.equals(first.getQueryParameter(key), second.getQueryParameter(key))) {
                return false;
            }
        }
        return true;
    }

    private void openSession() {
        closeSession();
        try {
            if (mediaFile != null) {
                session = new PreviewSession(getContext(), mediaFile, mediaDeclaredSize,
                        mediaDocument, mediaAccount, this::onSessionThumbnail);
            } else if (mediaUri != null) {
                session = new PreviewSession(getContext(), mediaUri,
                        this::onSessionThumbnail);
            }
        } catch (Throwable e) {
            PreviewLog.e("SeekPreview: open session failed", e);
            session = null;
        }
        final PreviewSession current = session;
        if (current != null) {
            current.setDragging(dragging);
            current.setDesiredPosition(requestedPosition);
            current.setBuffering(isBufferingNow());
            postDelayed(bufferingRunnable, 250);
        }
    }

    private void closeSession() {
        removeCallbacks(bufferingRunnable);
        final PreviewSession current = session;
        session = null;
        if (current != null) {
            current.stop();
        }
    }

    /** Main thread: a worker added a picture, so the bubble may need to update. */
    private void onSessionThumbnail() {
        if (session == null || !isPressedNow() || requestedPosition < 0) {
            return;
        }
        final Thumbnail thumbnail = session.nearest(requestedPosition);
        if (thumbnail != null && thumbnail.bitmap != frame) {
            setFrame(thumbnail.bitmap, requestedPosition, false);
        }
    }

    public void setPreviewSize(int width, int height) {
        if (previewWidth == width && previewHeight == height) {
            return;
        }
        previewWidth = width;
        previewHeight = height;
        requestLayout();
    }

    /** Target width of the preview bubble in pixels. */
    public int getPreviewWidth() {
        return previewWidth > 0 ? previewWidth : AndroidUtilities.dp(140);
    }

    /**
     * Requests the frame for a position (milliseconds). This only reads the
     * immutable store; it never decodes, downloads or waits for a lock.
     */
    public void requestFrame(long positionMs) {
        if (positionMs < 0) {
            return;
        }
        final boolean logRequest = (++requestLogCount & 31) == 1;
        if (logRequest) {
            FileLog.d("ABPV: requestFrame pos=" + positionMs
                    + " pressed=" + isPressedNow()
                    + " vis=" + (getVisibility() == VISIBLE)
                    + " session=" + (session != null));
        }
        if (!isPressedNow()) {
            // The finger is up. A late frame must not bring a bubble back on
            // screen, but a bubble that is already showing still has to keep
            // painting: the store may have received a picture after the finger
            // left, and leaving the old (or null) frame in place would show an
            // empty box. The staleRunnable below is what actually hides it,
            // 1.5s after the last update, so there is no stuck bubble here.
            if (getVisibility() != VISIBLE) {
                return;
            }
        }
        lastFrameAt = System.currentTimeMillis();
        final PreviewSession current = session;
        if (current != null) {
            current.setBuffering(isBufferingNow());
            current.setDesiredPosition(positionMs);
        }
        requestedPosition = positionMs;
        updateTimeLabel(positionMs);
        if (current == null) {
            FileLog.d("ABPV: requestFrame no session pos=" + positionMs);
            return;
        }
        final Thumbnail thumbnail = current.nearest(positionMs);
        if (thumbnail != null) {
            if (thumbnail.bitmap != frame) {
                FileLog.d("ABPV: nearest t=" + thumbnail.timeMs
                        + " tier=" + thumbnail.tier
                        + " storeSize=" + current.thumbnailCount()
                        + " pos=" + positionMs);
                setFrame(thumbnail.bitmap, positionMs, false);
            }
        } else if (frame != null) {
            // Nothing decoded close enough to this position: keeping the
            // previous picture under the new timestamp would show the wrong
            // moment, so drop to a time-only bubble.
            FileLog.d("ABPV: nearest too far, dropping frame pos=" + positionMs
                    + " storeSize=" + current.thumbnailCount());
            setFrame(null, positionMs, false);
        } else if (logRequest) {
            FileLog.d("ABPV: nearest=null storeSize=" + current.thumbnailCount()
                    + " pos=" + positionMs);
        }
    }

    /**
     * Displays a frame rendered externally (e.g. grabbed from an off-screen
     * player). Takes ownership of the bitmap.
     */
    public void setExternalFrame(Bitmap externalBitmap, long positionMs) {
        if (externalBitmap == null || externalBitmap.isRecycled() || positionMs < 0) {
            return;
        }
        if (!isPressedNow()) {
            if (getVisibility() == VISIBLE) {
                hide("setExternalFrame not pressed");
            }
            return;
        }
        lastFrameAt = System.currentTimeMillis();
        requestedPosition = positionMs;
        updateTimeLabel(positionMs);
        setFrame(externalBitmap, positionMs, true);
        if (getVisibility() != VISIBLE) {
            setAlpha(1f);
            setVisibility(VISIBLE);
        }
    }

    /**
     * Releases the whole session, not just the picture on screen. The viewer
     * calls this when it closes or hands the player back.
     */
    public void releaseSession() {
        closeSession();
        requestedPosition = -1;
        retainUriForCurrentDrag = false;
        loggedUriRetention = false;
        dropFrame();
        hide("releaseSession");
    }

    public void clear() {
        dragging = false;
        retainUriForCurrentDrag = false;
        loggedUriRetention = false;
        requestedPosition = -1;
        removeCallbacks(staleRunnable);
        final PreviewSession current = session;
        if (current != null) {
            current.setDragging(false);
        }
        dropFrame();
        hide("clear");
    }

    /** Asked before showing or keeping a frame on screen. */
    public interface PressedProvider {
        boolean isPressed();
    }

    public void setPressedProvider(PressedProvider provider) {
        pressedProvider = provider;
    }

    private boolean isPressedNow() {
        final PressedProvider provider = pressedProvider;
        if (provider == null) {
            return dragging;
        }
        try {
            return provider.isPressed();
        } catch (Throwable e) {
            return dragging;
        }
    }

    /** Public so the viewer can report gesture state. */
    public void setDragging(boolean value) {
        dragging = value;
        if (value) {
            lastFrameAt = System.currentTimeMillis();
        } else {
            retainUriForCurrentDrag = false;
            loggedUriRetention = false;
        }
        final PreviewSession current = session;
        if (current != null) {
            current.setDragging(value);
        }
    }

    /** Public so the viewer can report playback stalls. */
    public void setBufferingProvider(BufferingProvider provider) {
        bufferingProvider = provider;
        updateBuffering();
    }

    public interface BufferingProvider {
        boolean isBuffering();
    }

    private void updateBuffering() {
        final PreviewSession current = session;
        if (current != null) {
            current.setBuffering(isBufferingNow());
        }
    }

    private boolean isBufferingNow() {
        final BufferingProvider provider = bufferingProvider;
        if (provider == null) {
            return false;
        }
        try {
            return provider.isBuffering();
        } catch (Throwable e) {
            return false;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        closeSession();
        removeCallbacks(staleRunnable);
        dropFrame();
        if (getVisibility() != INVISIBLE) {
            hide("detachedFromWindow");
        }
        super.onDetachedFromWindow();
    }

    private void updateTimeLabel(long positionMs) {
        final String time = AndroidUtilities.formatShortDuration((int) (positionMs / 1000));
        if (!time.equals(frameTime)) {
            frameTime = time;
            timeWidth = (int) Math.ceil(textPaint.measureText(time));
        }
        invalidate();
    }

    private void hide(String reason) {
        PreviewLog.d("SeekPreview: hide reason=" + reason
                + " dragging=" + dragging
                + " visible=" + (getVisibility() == VISIBLE));
        animate().cancel();
        setVisibility(INVISIBLE);
    }

    private void setFrame(Bitmap bitmap, long positionMs, boolean owned) {
        final boolean changed = frame != bitmap;
        FileLog.d("ABPV: setFrame bmp=" + (bitmap != null)
                + " changed=" + changed
                + " pos=" + positionMs
                + " owned=" + owned);
        if (changed) {
            releaseFrame();
            frame = bitmap;
            frameOwned = owned;
        }
        if (bitmap == null) {
            invalidate();
            return;
        }
        lastFrameAt = System.currentTimeMillis();
        invalidate();
        removeCallbacks(staleRunnable);
        postDelayed(staleRunnable, 1500);
    }

    private void releaseFrame() {
        if (frameOwned && frame != null && !frame.isRecycled()) {
            frame.recycle();
        }
        frame = null;
        frameOwned = false;
    }

    private void dropFrame() {
        releaseFrame();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int width = previewWidth > 0 ? previewWidth : AndroidUtilities.dp(140);
        final int height = previewHeight > 0 ? previewHeight : AndroidUtilities.dp(96);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int width = getWidth();
        final int height = getHeight();
        final int videoHeight = frameTime != null ? height - AndroidUtilities.dp(22) : height;

        dstRect.set(0, 0, width, height);
        canvas.drawRoundRect(dstRect, AndroidUtilities.dp(6), AndroidUtilities.dp(6), bgPaint);

        final Bitmap picture = frame;
        if (picture != null && !picture.isRecycled()) {
            final int frameWidth = picture.getWidth();
            final int frameHeight = picture.getHeight();
            if (frameWidth > 0 && frameHeight > 0) {
                final float scale = Math.min((float) width / frameWidth,
                        (float) videoHeight / frameHeight);
                final int drawWidth = (int) (frameWidth * scale);
                final int drawHeight = (int) (frameHeight * scale);
                dstRect.set(
                        (width - drawWidth) / 2f,
                        (videoHeight - drawHeight) / 2f,
                        (width + drawWidth) / 2f,
                        (videoHeight + drawHeight) / 2f);
                canvas.drawBitmap(picture, null, dstRect, bitmapPaint);
            }
        }

        if (frameTime != null) {
            canvas.drawText(frameTime, (width - timeWidth) / 2f,
                    height - AndroidUtilities.dp(6), textPaint);
        }
    }
}
