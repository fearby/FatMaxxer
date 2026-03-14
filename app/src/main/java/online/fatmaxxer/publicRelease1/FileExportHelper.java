package online.fatmaxxer.fatmaxxer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * LogExportHelper — FatMaxxer (fearby fork)
 *
 * Replaces the original direct external-storage write approach, which stopped
 * working reliably on Android 10+ (API 29) due to Scoped Storage.
 *
 * HOW IT WORKS:
 *   1. Log files are written to getExternalFilesDir("logs") — the app's own
 *      private external storage. No WRITE_EXTERNAL_STORAGE permission needed.
 *   2. When the user taps "Export", this helper copies the files to the app's
 *      cache dir, wraps them in a FileProvider URI, and launches the system
 *      ShareSheet. The user can then save to Downloads, email, etc.
 *
 * SETUP REQUIRED (already provided in this patch):
 *   - Add <provider> to AndroidManifest.xml (see manifest_additions.xml)
 *   - Add res/xml/file_paths.xml (provided)
 *
 * USAGE IN MainActivity:
 *   // Instead of old direct file write:
 *   File logDir = LogExportHelper.getLogDirectory(this);
 *   File rrLog = new File(logDir, "rr.log");
 *   // ... write to rrLog normally ...
 *
 *   // To export:
 *   LogExportHelper.shareAllLogs(this);
 */
public class LogExportHelper {

    private static final String TAG = "LogExportHelper";
    private static final String LOG_DIR_NAME = "logs";
    private static final String CACHE_EXPORT_DIR = "export";
    private static final String AUTHORITY = "online.fatmaxxer.fatmaxxer.fileprovider";

    /**
     * Returns the directory where FatMaxxer should write log files.
     * This replaces any hardcoded Environment.getExternalStorageDirectory() paths.
     *
     * Path: /Android/data/online.fatmaxxer.fatmaxxer/files/logs/
     */
    public static File getLogDirectory(Context context) {
        File logDir = new File(context.getExternalFilesDir(null), LOG_DIR_NAME);
        if (!logDir.exists()) {
            if (!logDir.mkdirs()) {
                Log.e(TAG, "Failed to create log directory: " + logDir.getAbsolutePath());
            }
        }
        return logDir;
    }

    /**
     * Returns a specific log file handle, creating parent dirs as needed.
     *
     * @param context  App context
     * @param filename e.g. "rr.log", "artifacts.log", "debug.log", "features.csv"
     */
    public static File getLogFile(Context context, String filename) {
        return new File(getLogDirectory(context), filename);
    }

    /**
     * Returns a timestamped log file — useful for sessions.
     *
     * @param context  App context
     * @param prefix   e.g. "rr", "artifacts"
     * @param extension e.g. "log", "csv"
     */
    public static File getTimestampedLogFile(Context context, String prefix, String extension) {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String filename = prefix + "_" + timestamp + "." + extension;
        return new File(getLogDirectory(context), filename);
    }

    /**
     * Share all log files in the log directory via the Android ShareSheet.
     * Works on all Android versions including 10+.
     *
     * Call this from a button click or menu item in MainActivity.
     */
    public static void shareAllLogs(Activity activity) {
        File logDir = getLogDirectory(activity);
        File[] logFiles = logDir.listFiles();

        if (logFiles == null || logFiles.length == 0) {
            Log.w(TAG, "No log files to share");
            // Optionally: show a Toast/Snackbar here
            return;
        }

        ArrayList<Uri> uris = new ArrayList<>();
        File cacheExportDir = new File(activity.getCacheDir(), CACHE_EXPORT_DIR);
        if (!cacheExportDir.exists()) cacheExportDir.mkdirs();

        for (File logFile : logFiles) {
            if (!logFile.isFile()) continue;
            try {
                // Copy to cache dir for FileProvider access
                File cacheCopy = new File(cacheExportDir, logFile.getName());
                copyFile(logFile, cacheCopy);

                Uri uri = FileProvider.getUriForFile(
                        activity,
                        AUTHORITY,
                        cacheCopy
                );
                uris.add(uri);
            } catch (IOException e) {
                Log.e(TAG, "Failed to copy log file for sharing: " + logFile.getName(), e);
            }
        }

        if (uris.isEmpty()) {
            Log.w(TAG, "No URIs to share after copying");
            return;
        }

        Intent shareIntent;
        if (uris.size() == 1) {
            shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            shareIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
            shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }

        shareIntent.setType("text/plain");
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        String subject = "FatMaxxer logs " +
                new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, subject);

        activity.startActivity(Intent.createChooser(shareIntent, "Export FatMaxxer Logs"));
    }

    /**
     * Share a single specific log file.
     */
    public static void shareLogFile(Activity activity, File logFile) {
        if (!logFile.exists()) {
            Log.w(TAG, "Log file does not exist: " + logFile.getAbsolutePath());
            return;
        }

        try {
            File cacheExportDir = new File(activity.getCacheDir(), CACHE_EXPORT_DIR);
            if (!cacheExportDir.exists()) cacheExportDir.mkdirs();

            File cacheCopy = new File(cacheExportDir, logFile.getName());
            copyFile(logFile, cacheCopy);

            Uri uri = FileProvider.getUriForFile(activity, AUTHORITY, cacheCopy);

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "FatMaxxer: " + logFile.getName());

            activity.startActivity(Intent.createChooser(shareIntent, "Share " + logFile.getName()));

        } catch (IOException e) {
            Log.e(TAG, "Failed to share log file: " + logFile.getName(), e);
        }
    }

    /**
     * Delete all files in the log directory.
     * Useful for the "Clear logs" settings option.
     */
    public static void clearLogs(Context context) {
        File logDir = getLogDirectory(context);
        File[] files = logDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.delete()) {
                    Log.w(TAG, "Could not delete: " + f.getName());
                }
            }
        }
        // Also clear the export cache
        File cacheDir = new File(context.getCacheDir(), CACHE_EXPORT_DIR);
        File[] cacheFiles = cacheDir.listFiles();
        if (cacheFiles != null) {
            for (File f : cacheFiles) f.delete();
        }
    }

    /**
     * Returns the total size of all log files in bytes.
     */
    public static long getLogDirectorySize(Context context) {
        File logDir = getLogDirectory(context);
        File[] files = logDir.listFiles();
        long total = 0;
        if (files != null) {
            for (File f : files) total += f.length();
        }
        return total;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static void copyFile(File src, File dst) throws IOException {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst);
             FileChannel inCh = in.getChannel();
             FileChannel outCh = out.getChannel()) {
            inCh.transferTo(0, inCh.size(), outCh);
        }
    }
}
