package online.fatmaxxer.publicRelease1;

import android.content.Context;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages log file creation, writing, and lifecycle for a FatMaxxer session.
 *
 * Handles RR interval logs, feature logs, debug logs, and ECG logs.
 * Writes to external storage first, falling back to internal storage.
 */
public class SessionLogger {

    private static final String TAG = "SessionLogger";

    private final Map<String, File> logFiles = new HashMap<>();
    private final Map<String, FileWriter> logWriters = new HashMap<>();
    private File logsDir;
    private final Context context;

    public SessionLogger(Context context) {
        this.context = context;
    }

    /**
     * Get all current log files (keyed by tag: "rr", "features", "debug", "ecg").
     */
    public Map<String, File> getLogFiles() {
        return logFiles;
    }

    /**
     * Get the directory where logs are stored.
     */
    public File getLogsDir() {
        return logsDir;
    }

    /**
     * Create a log file with the given tag. Tries external storage first,
     * falls back to internal.
     */
    public void createLogFile(String tag) {
        android.util.Log.d(TAG, "createLogFile: " + tag);
        if (!createLogFileInDir(getExtLogsDir(), tag)) {
            createLogFileInDir(getIntLogsDir(), tag);
        }
    }

    /**
     * Write a line to the log file identified by tag.
     */
    public void writeLogFile(String msg, String tag) {
        FileWriter writer = logWriters.get(tag);
        try {
            if (writer != null) {
                writer.append(msg).append("\n");
                writer.flush();
            } else {
                android.util.Log.e(TAG, "ERROR: " + tag + " logStream is null");
            }
        } catch (IOException e) {
            android.util.Log.d(TAG, "IOException writing to " + tag + " log: " + e.getMessage());
        }
    }

    /**
     * Check if a log writer exists for the given tag.
     */
    public boolean hasWriter(String tag) {
        return logWriters.get(tag) != null;
    }

    /**
     * Close all open log files.
     */
    public void closeLogs() {
        for (FileWriter fw : logWriters.values()) {
            try {
                fw.close();
            } catch (IOException e) {
                android.util.Log.e(TAG, "IOException closing log: " + e.getMessage());
            }
        }
    }

    /**
     * Rename current log files with the given tag prefix.
     */
    public void renameLogs(String value) {
        if (logsDir == null) return;
        renameLogFile("rr", value);
        renameLogFile("features", value);
        renameLogFile("debug", value);
        if (logFiles.containsKey("ecg")) {
            renameLogFile("ecg", value);
        }
    }

    /**
     * Generate a timestamped log filename.
     */
    public String makeLogfileName(String stem, String type) {
        String dateString = getDate(System.currentTimeMillis(), "yyyyMMdd_HHmmss");
        String extension = type.equals("debug") ? "log" : "csv";
        return "/ftmxr_" + dateString + "_" + stem + "." + type + "." + extension;
    }

    // ========================================================================
    // INTERNAL
    // ========================================================================

    private boolean createLogFileInDir(File dir, String tag) {
        try {
            File logFile = new File(dir, makeLogfileName("", tag));
            FileWriter writer = new FileWriter(logFile);
            android.util.Log.d(TAG, "Logging " + tag + " to " + logFile.getAbsolutePath());
            logFiles.put(tag, logFile);
            logWriters.put(tag, writer);
            logsDir = dir;
            return true;
        } catch (FileNotFoundException e) {
            android.util.Log.e(TAG, "File not found: " + e.getMessage());
        } catch (IOException e) {
            android.util.Log.e(TAG, "createLogFile: " + e.getMessage());
        }
        return false;
    }

    private void renameLogFile(String tag, String value) {
        File current = logFiles.get(tag);
        if (current != null) {
            File renamed = new File(logsDir, makeLogfileName(value, tag));
            if (current.renameTo(renamed)) {
                logFiles.put(tag, renamed);
            }
        }
    }

    private File getExtLogsDir() {
        File rootDir = context.getExternalFilesDir(null);
        if (rootDir != null) rootDir.mkdir();
        File dir = new File(rootDir, "logs");
        dir.mkdir();
        return dir;
    }

    private File getIntLogsDir() {
        File rootDir = context.getFilesDir();
        rootDir.mkdir();
        File dir = new File(rootDir, "logs");
        dir.mkdir();
        return dir;
    }

    private static String getDate(long milliSeconds, String dateFormat) {
        SimpleDateFormat formatter = new SimpleDateFormat(dateFormat);
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(milliSeconds);
        return formatter.format(calendar.getTime());
    }
}
