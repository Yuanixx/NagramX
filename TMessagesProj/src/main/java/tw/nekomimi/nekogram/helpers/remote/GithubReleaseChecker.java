package tw.nekomimi.nekogram.helpers.remote;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.AlertDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

import xyz.nextalone.nagram.NaConfig;

/**
 * Checks the public release mirror for a newer staging build.
 *
 * The source repository is private, so the app never talks to it. The mirror
 * holds only installable files and a small version file; no token is needed
 * and GitHub's API rate limit is not involved because the stable release
 * download URL is used.
 */
public final class GithubReleaseChecker {

    private static final String UPDATE_INFO_URL =
            "https://github.com/Yuanixx/NagramX-releases/releases/download/staging/update.json";
    private static final String UPDATE_ASSET_BASE =
            "https://github.com/Yuanixx/NagramX-releases/releases/download/staging/";
    private static final String PREFS_NAME = "nax_update";
    private static final long CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private static final long MIN_APK_BYTES = 1024L * 1024L;

    private static volatile boolean checking;
    private static volatile boolean downloading;

    private GithubReleaseChecker() {
    }

    /**
     * Runs a check in the background. {@code manual} is true for the version
     * menu action: it bypasses the 24 hour throttle and reports failures and
     * an up-to-date result. Automatic checks stay silent in those cases.
     */
    public static void check(Activity activity, boolean manual, Browser.Progress progress) {
        if (activity == null) {
            return;
        }
        if (!manual && !isAutoCheckEnabled()) {
            finishProgress(progress);
            return;
        }
        if (!manual && !shouldCheckNow()) {
            finishProgress(progress);
            return;
        }
        if (checking) {
            finishProgress(progress);
            return;
        }
        checking = true;
        if (progress != null) {
            progress.init();
        }
        markAttempt();
        final Activity target = activity;
        new Thread(() -> {
            JSONObject info = null;
            Throwable error = null;
            try {
                info = fetchUpdateInfo();
            } catch (Throwable e) {
                error = e;
                FileLog.e("NaxUpdate: check failed", e);
            }
            final JSONObject result = info;
            final Throwable failure = error;
            AndroidUtilities.runOnUIThread(() -> {
                checking = false;
                finishProgress(progress);
                if (target.isFinishing() || target.isDestroyed()) {
                    return;
                }
                if (result == null) {
                    if (manual) {
                        showMessage(target, LocaleController.getString(R.string.ErrorOccurred)
                                + (failure != null && failure.getMessage() != null
                                ? "\n" + failure.getMessage() : ""));
                    }
                    return;
                }
                final long remoteBuiltAt = result.optLong("builtAt", 0L);
                if (remoteBuiltAt <= BuildConfig.BUILD_TIMESTAMP) {
                    if (manual) {
                        showMessage(target, LocaleController.getString(R.string.YourVersionIsLatestNax));
                    }
                    return;
                }
                final SharedPreferences prefs = prefs(target);
                if (!manual && remoteBuiltAt == prefs.getLong("dismissedBuiltAt", 0L)) {
                    return;
                }
                showUpdateDialog(target, result);
            });
        }, "nax-update-check").start();
    }

    private static boolean isAutoCheckEnabled() {
        try {
            return NaConfig.INSTANCE.getAutoUpdateChannel().Int() != UpdateHelper.UPDATE_OFF;
        } catch (Throwable e) {
            return true;
        }
    }

    private static boolean shouldCheckNow() {
        final long last = prefs(ApplicationLoader.applicationContext)
                .getLong("lastAttempt", 0L);
        return System.currentTimeMillis() - last >= CHECK_INTERVAL_MS;
    }

    private static void markAttempt() {
        prefs(ApplicationLoader.applicationContext).edit()
                .putLong("lastAttempt", System.currentTimeMillis()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static JSONObject fetchUpdateInfo() throws Exception {
        HttpURLConnection connection = null;
        InputStream input = null;
        try {
            connection = openConnection(UPDATE_INFO_URL);
            final int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("HTTP " + code);
            }
            input = connection.getInputStream();
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            final byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > 64 * 1024) {
                    throw new IllegalStateException("version file too large");
                }
            }
            return new JSONObject(out.toString("UTF-8"));
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (Throwable ignore) {
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static HttpURLConnection openConnection(String address) throws Exception {
        final HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "NagramX-Update");
        connection.setRequestProperty("Accept", "application/json");
        return connection;
    }

    private static void showUpdateDialog(Activity activity, JSONObject info) {
        final String version = info.optString("version", "");
        final String notes = info.optString("notes", "");
        final long builtAt = info.optLong("builtAt", 0L);
        final StringBuilder message = new StringBuilder();
        if (!version.isEmpty()) {
            message.append(LocaleController.formatString(R.string.NaxUpdateVersion, version));
        }
        if (!notes.isEmpty()) {
            if (message.length() > 0) {
                message.append("\n\n");
            }
            message.append(notes.length() > 1500 ? notes.substring(0, 1500) : notes);
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString(R.string.NaxUpdateTitle));
        builder.setMessage(message.toString());
        builder.setPositiveButton(LocaleController.getString(R.string.AppUpdateNow),
                (dialog, which) -> startDownload(activity, info));
        builder.setNegativeButton(LocaleController.getString(R.string.AppUpdateRemindMeLater),
                (dialog, which) -> {
                    prefs(activity).edit().putLong("dismissedBuiltAt", builtAt).apply();
                    dialog.dismiss();
                });
        builder.show();
    }

    private static String chooseAsset(JSONObject info) {
        final String arm64 = info.optString("arm64", "");
        final String universal = info.optString("universal", "");
        if (isArm64Device() && !arm64.isEmpty()) {
            return arm64;
        }
        return !universal.isEmpty() ? universal : arm64;
    }

    private static boolean isArm64Device() {
        if (Build.SUPPORTED_ABIS == null) {
            return false;
        }
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) {
                return true;
            }
        }
        return false;
    }

    private static void startDownload(Activity activity, JSONObject info) {
        if (downloading) {
            return;
        }
        final String asset = chooseAsset(info);
        if (asset.isEmpty()) {
            showMessage(activity, LocaleController.getString(R.string.NaxUpdateFailed));
            return;
        }
        downloading = true;
        final File dir = new File(activity.getCacheDir(), "updates");
        if (!dir.exists() && !dir.mkdirs()) {
            downloading = false;
            showMessage(activity, LocaleController.getString(R.string.NaxUpdateFailed));
            return;
        }
        final File[] old = dir.listFiles((d, name) -> name.endsWith(".apk"));
        if (old != null) {
            for (File file : old) {
                file.delete();
            }
        }
        final File target = new File(dir, asset);
        final File part = new File(dir, asset + ".part");
        final AtomicBoolean cancelled = new AtomicBoolean();

        final LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        final int padding = AndroidUtilities.dp(24);
        layout.setPadding(padding, AndroidUtilities.dp(8), padding, 0);
        final TextView text = new TextView(activity);
        text.setTextSize(15);
        text.setText(LocaleController.formatString(R.string.NaxUpdateDownloading, 0));
        final ProgressBar bar = new ProgressBar(activity, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        layout.addView(text, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        layout.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString(R.string.NaxUpdateTitle));
        builder.setView(layout);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel),
                (dialog, which) -> {
                    cancelled.set(true);
                    dialog.dismiss();
                });
        final AlertDialog dialog = builder.create();
        dialog.setOnCancelListener(d -> cancelled.set(true));
        dialog.show();

        new Thread(() -> {
            Throwable failure = null;
            try {
                downloadFile(UPDATE_ASSET_BASE + Uri.encode(asset), part, bar, text, cancelled);
                if (cancelled.get()) {
                    return;
                }
                if (target.exists()) {
                    target.delete();
                }
                if (!part.renameTo(target)) {
                    throw new IllegalStateException("cannot move the downloaded file");
                }
                if (target.length() < MIN_APK_BYTES) {
                    throw new IllegalStateException("downloaded file is too small");
                }
            } catch (Throwable e) {
                failure = e;
                FileLog.e("NaxUpdate: download failed", e);
            } finally {
                downloading = false;
                if (cancelled.get()) {
                    //noinspection ResultOfMethodCallIgnored
                    part.delete();
                }
            }
            final Throwable error = failure;
            AndroidUtilities.runOnUIThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                dialog.dismiss();
                if (cancelled.get()) {
                    return;
                }
                if (error != null) {
                    showMessage(activity, LocaleController.getString(R.string.NaxUpdateFailed));
                } else {
                    installApk(activity, target);
                }
            });
        }, "nax-update-download").start();
    }

    private static void downloadFile(String address, File destination, ProgressBar bar,
                                     TextView text, AtomicBoolean cancelled) throws Exception {
        HttpURLConnection connection = null;
        InputStream input = null;
        FileOutputStream output = null;
        try {
            connection = openConnection(address);
            connection.setRequestProperty("Accept", "application/octet-stream");
            final int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("HTTP " + code);
            }
            final int total = connection.getContentLength();
            if (total <= 0) {
                AndroidUtilities.runOnUIThread(() -> bar.setIndeterminate(true));
            }
            input = connection.getInputStream();
            output = new FileOutputStream(destination);
            final byte[] buffer = new byte[64 * 1024];
            long written = 0L;
            long lastUpdate = 0L;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (cancelled.get()) {
                    return;
                }
                output.write(buffer, 0, read);
                written += read;
                final long now = SystemClock.elapsedRealtime();
                if (now - lastUpdate >= 100L) {
                    lastUpdate = now;
                    final long copied = written;
                    final int percent = total > 0
                            ? (int) Math.min(100L, copied * 100L / total) : 0;
                    AndroidUtilities.runOnUIThread(() -> {
                        if (total > 0) {
                            bar.setIndeterminate(false);
                            bar.setProgress(percent);
                        }
                        text.setText(LocaleController.formatString(
                                R.string.NaxUpdateDownloading, percent));
                    });
                }
            }
            output.flush();
            if (total > 0 && written != total) {
                throw new IllegalStateException("incomplete download");
            }
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (Throwable ignore) {
                }
            }
            if (output != null) {
                try {
                    output.close();
                } catch (Throwable ignore) {
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static void installApk(Activity activity, File apk) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            try {
                final Intent settings = new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(settings);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            showMessage(activity, LocaleController.getString(R.string.NaxUpdatePermission));
            return;
        }
        try {
            final Uri uri = FileProvider.getUriForFile(activity,
                    ApplicationLoader.getApplicationId() + ".provider", apk);
            final Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.setClipData(ClipData.newRawUri("", uri));
            activity.startActivity(intent);
        } catch (Throwable e) {
            FileLog.e(e);
            showMessage(activity, LocaleController.getString(R.string.NaxUpdateFailed));
        }
    }

    private static void showMessage(Activity activity, String text) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setMessage(text);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        builder.show();
    }

    private static void finishProgress(Browser.Progress progress) {
        if (progress != null) {
            AndroidUtilities.runOnUIThread(progress::end);
        }
    }
}
