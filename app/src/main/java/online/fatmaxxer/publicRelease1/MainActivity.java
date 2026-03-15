package online.fatmaxxer.publicRelease1;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.OpenableColumns;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import org.jetbrains.annotations.NotNull;


import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import online.fatmaxxer.fatmaxxer.databinding.ActivityMainBinding;
import online.fatmaxxer.fatmaxxer.R;
import online.fatmaxxer.fatmaxxer.BuildConfig;
import org.reactivestreams.Publisher;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.functions.Function;
import com.polar.sdk.api.PolarBleApi;
import com.polar.sdk.api.PolarBleApiCallback;
import com.polar.sdk.api.PolarBleApiDefaultImpl;
import com.polar.sdk.api.errors.PolarInvalidArgument;
import com.polar.sdk.api.model.PolarDeviceInfo;
import com.polar.sdk.api.model.PolarEcgData;
import com.polar.sdk.api.model.PolarHrData;
import com.polar.sdk.api.model.PolarSensorSetting;

import static java.lang.Math.abs;
import static java.lang.Math.max;
import static java.lang.Math.round;

import static online.fatmaxxer.publicRelease1.MainActivity.FMMenuItem.*;

public class MainActivity extends AppCompatActivity {
    public static final boolean requestLegacyExternalStorage = true;
    private static final String TAG = MainActivity.class.getSimpleName();
    private static final String API_LOGGER_TAG = "Polar API";
    public static final String AUDIO_OUTPUT_ENABLED = "audioOutputEnabled";
    private static final int NOTIFICATION_ID = 1;
    private static final String NOTIFICATION_TAG = "alpha1update";

    public static final String ALPHA_1_CALC_PERIOD_PREFERENCE_STRING = "alpha1CalcPeriod";
    public static final String LAMBDA_PREFERENCE_STRING = "lambdaPref";
    public static final String ARTIFACT_REJECTION_THRESHOLD_PREFERENCE_STRING = "artifactThreshold";
    public static final String NOTIFICATIONS_ENABLED_PREFERENCE_STRING = "notificationsEnabled";
    public static final String POLAR_DEVICE_ID_PREFERENCE_STRING = "polarDeviceID";
    public static final String KEEP_LOGS_PREFERENCE_STRING = "keepLogs";
    public static final String KEEP_SCREEN_ON_PREFERENCE_STRING = "keepScreenOn";
    public static final String NOTIFICATION_DETAIL_PREFERENCE_STRING = "notificationDetail";
    public static final String RR_LOGFILE_HEADER = "timestamp, rr, since_start ";
    public static final String ENABLE_SENSOR_EMULATION = "enableSensorEmulation";
    public static final String ENABLE_REPLAY = "enableReplay";
    public static final String ENABLE_ECG = "enableECG";

    final double alpha1HRVvt1 = 0.75;
    final double alpha1HRVvt2 = 0.5;
    private DFACalculator dfaCalculator;
    private SessionLogger sessionLogger;
    private int lastObservedHRNotificationWithArtifacts = 0;
    // system time of first observed ecg sample
    private long ecgStartTSmillis;
    // timestamp of first observed ecg sample
    private long ecgStartInternalTSnanos;

    public MainActivity() {
        //super(R.layout.activity_fragment_container);
        super();
        Log = new Log();
    }

    public void deleteFile(File f) {
        Log.d(TAG, "deleteFile " + f.getPath());
        File fdelete = f;
        if (fdelete.exists()) {
            Log.d(TAG, "deleteFile: File deleted: " + fdelete.getPath());
            if (fdelete.delete()) {
                Log.d(TAG, "deleteFile: file deleted: " + fdelete.getPath());
            } else {
                Log.d(TAG, "File not deleted(?) Will try after exit: " + fdelete.getPath());
                fdelete.deleteOnExit();
            }
        } else {
            Log.d(TAG, "file does not exist??" + fdelete.getPath());
        }
    }

    public void startAnalysis() {
        searchForPolarDevices();
        // TODO: CHECK: is this safe or do we have to wait for some other setup tasks to finish...?
        tryPolarConnectToPreferredDevice();
    }

    public void confirmQuit() {
        new AlertDialog.Builder(this)
                .setMessage("FatMaxxer: Confirm Quit")
                .setCancelable(false)
                .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        finish();
                    }
                })
                .setNegativeButton("No", null)
                .show();
    }

    @Override
    public void finish() {
        closeLogs();
        uiNotificationManager.cancel(NOTIFICATION_TAG, NOTIFICATION_ID);
        try {
            api.disconnectFromDevice(SENSOR_ID);
        } catch (PolarInvalidArgument polarInvalidArgument) {
            logException("Quit: disconnectFromDevice: polarInvalidArgument ", polarInvalidArgument);
        }
        super.finish();
    }

    public void onBackPressed() {
        Toast.makeText(getBaseContext(), R.string.UseMenuQuitToExit, Toast.LENGTH_LONG).show();
    }

    PolarBleApi api;
    Disposable broadcastDisposable;
    Disposable ecgDisposable = null;
    String SENSOR_ID = "";
    SharedPreferences sharedPreferences;

    Context thisContext = this;
    private int batteryLevel = 0;
    private String exerciseMode = "Light";
    private EditText input_field;
    private static final String SERVICE_CHANNEL_ID = "FatMaxxerServiceChannel";
    private static final String UI_CHANNEL_ID = "FatMaxxerUIChannel";
    private static final String SERVICE_CHANNEL_NAME = "FatMaxxer Service Notification";
    private static final String UI_CHANNEL_NAME = "FatMaxxer Notifications";

    public static class LocalService extends Service {
        private NotificationManager mNM;

        @Override
        public void onDestroy() {
            android.util.Log.d(TAG, "LocalService: onDestroy");
            super.onDestroy();
        }

        public LocalService() {
        }

        /**
         * Class for clients to access.  Because we know this service always
         * runs in the same process as its clients, we don't need to deal with
         * IPC.
         */
        public class LocalBinder extends Binder {
            LocalService getService() {
                return LocalService.this;
            }
        }


        //https://stackoverflow.com/questions/47531742/startforeground-fail-after-upgrade-to-android-8-1
        @RequiresApi(api = Build.VERSION_CODES.O)
        @Override
        public void onCreate() {
            //Log.d(TAG, "FatMaxxer service onCreate");
            super.onCreate();
            createNotificationChannel();
            try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                startMyOwnForeground();
            else
                startForeground(1, new Notification());
        } catch (Exception e) {
            android.util.Log.w("LocalService", "startForeground failed: " + e.getMessage());
        }
        }

        private void startMyOwnForeground() {
            // https://stackoverflow.com/questions/5502427/resume-application-and-stack-from-notification
            final Intent notificationIntent = new Intent(this, MainActivity.class)
                    .setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // https://stackoverflow.com/a/42002705/16251655
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
            //notificationBuilder.setContentIntent(pendingIntent)

            NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, SERVICE_CHANNEL_ID);
            Notification notification =
                    notificationBuilder.setOngoing(true)
                            .setSmallIcon(R.mipmap.ic_launcher)
                            .setContentTitle("FatMaxxer started")
                            .setPriority(NotificationManager.IMPORTANCE_HIGH)
                            .setCategory(Notification.CATEGORY_SERVICE)
                            .setContentIntent(pendingIntent)
                            .build();
            startForeground(2, notification);
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            android.util.Log.d("FatMaxxerLocalService", "Received start id " + startId + ": " + intent);
            return START_NOT_STICKY;
        }

        @Override
        public IBinder onBind(Intent intent) {
            return mBinder;
        }

        // This is the object that receives interactions from clients.  See
        // RemoteService for a more complete example.
        private final IBinder mBinder = new LocalBinder();

        //@RequiresApi(Build.VERSION_CODES.O)
        @RequiresApi(api = Build.VERSION_CODES.O)
        private String createNotificationChannel() {
            NotificationChannel chan = new NotificationChannel(SERVICE_CHANNEL_ID,
                    SERVICE_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
            NotificationManager service = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            service.createNotificationChannel(chan);
            return SERVICE_CHANNEL_ID;
        }
    }

    /**
     * Example of binding and unbinding to the local service.
     * bind to, receiving an object through which it can communicate with the service.
     * <p>
     * Note that this is implemented as an inner class only keep the sample
     * all together; typically this code would appear in some separate class.
     */

    //
    // SERVICE BINDING
    //

    // Don't attempt to unbind from the service unless the client has received some
    // information about the service's state.
    private boolean mShouldUnbind;

    LocalService service;

    // To invoke the bound service, first make sure that this value
    // is not null.
    private LocalService mBoundService;

    private ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            // This is called when the connection with the service has been
            // established, giving us the service object we can use to
            // interact with the service.  Because we have bound to a explicit
            // service that we know is running in our own process, we can
            // cast its IBinder to a concrete class and directly access it.
            mBoundService = ((LocalService.LocalBinder) service).getService();

            // Tell the user about this for our demo.
            Toast.makeText(MainActivity.this, R.string.FatMaxxerBoundToService,
                    Toast.LENGTH_SHORT).show();
        }

        public void onServiceDisconnected(ComponentName className) {
            // This is called when the connection with the service has been
            // unexpectedly disconnected -- that is, its process crashed.
            // Because it is running in our same process, we should never
            // see this happen.
            mBoundService = null;
            Toast.makeText(MainActivity.this, "FatMaxxer disconnected from service",
                    Toast.LENGTH_SHORT).show();
        }
    };

    void doBindService() {
        Log.d(TAG, "FatMaxxer: binding to service");
        // Attempts to establish a connection with the service.  We use an
        // explicit class name because we want a specific service
        // implementation that we know will be running in our own process
        // (and thus won't be supporting component replacement by other
        // applications).
        if (bindService(new Intent(MainActivity.this, LocalService.class),
                mConnection, Context.BIND_AUTO_CREATE)) {
            mShouldUnbind = true;
        } else {
            Log.e(TAG, "Error: The requested service doesn't " +
                    "exist, or this client isn't allowed access to it.");
        }
    }

    void doUnbindService() {
        if (mShouldUnbind) {
            // Release information about the service's state.
            unbindService(mConnection);
            mShouldUnbind = false;
        }
    }

    //
    // END BINDING
    //

    public static class MySettingsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preferences, rootKey);
        }
    }

    public void testDFA_alpha1() {
        Log.d(TAG, "testDFA_alpha1");
        dfaCalculator.selfTest();
        Log.d(TAG, "Self-test DFA alpha1 passed");
    }

    TextView text_view;
    TextView text_time;
    TextView text_batt;
    TextView text_mode;
    TextView text_hr;
    TextView text_secondary;
    TextView text_secondary_label;
    TextView text_a1;
    TextView text_a1_label;
    TextView text_artifacts;

    //public boolean experimental = false;
    // 120s ring buffer for dfa alpha1
    public final int featureWindowSizeSec = 120;
    // buffer to allow at least 45 beats forward/backward per Kubios
    public final int sampleBufferMarginSec = 45;
    //public final int rrWindowSizeSec = featureWindowSizeSec + sampleBufferMarginSec;
    public final int rrWindowSizeSec = featureWindowSizeSec;
    // time between alpha1 calculations
    public int alpha1EvalPeriodSec;
    // max hr approx 300bpm(!?) across 120s window
    // FIXME: this is way larger than needed
    public final int maxrrs = 300 * rrWindowSizeSec;
    // circular buffer of recently recorded RRs
    // FIXME: premature optimization is the root of all evil
    // Not that much storage required, does not avoid the fundamental problem that
    // our app gets paused anyway
    public double[] rrInterval = new double[maxrrs];
    // timestamp of recently recorded RR (in ms since epoch)
    public long[] rrIntervalTimestamp = new long[maxrrs];
    // timestamps of artifact
    private long[] artifactTimestamp = new long[maxrrs];
    // oldest and newest recorded RRs in the recent window
    public int oldestSample = 0;
    public int newestSample = 0;
    // oldest and newest recorded artifact( timestamp)s in the recent window
    public int oldestArtifactSample = 0;
    public int newestArtifactSample = 0;
    // time first sample received in MS since epoch
    public long firstSampleTimestampMS;
    // have we started sampling?
    public boolean started = false;
    double rmssdWindowed = 0;
    // last known alpha1 (default resting nominally 1.0)
    //double alpha1V1Windowed = 1.0;
    //double alpha1V1RoundedWindowed = 1.0;
    double alpha1V2Windowed = 1.0;
    double alpha1V2RoundedWindowed = 1.0;
    int zone = 1;
    int zonePrev = 1;
    public boolean zoneChange = false;
    int artifactsPercentWindowed;
    int lambdaSetting = 500;
    int currentHR;
    double hrMeanWindowed = 0;
    double rrMeanWindowed = 0;
    // maximum tolerable variance of adjacent RR intervals
    double artifactCorrectionThreshold = 0.05;
    boolean disableArtifactCorrection = false;
    // elapsed time in terms of cumulative sum of all seen RRs (as for HRVLogger)
    long logRRelapsedMS = 0;
    // the last time (since epoch) a1 was evaluated
    public long prevA1TimestampMS = 0;
    public long prevHRPlotTimestampMS = 0;
    public long prevRRPlotTimestampMS = 0;
    public long prevFeatPlotTimestampMS = 0;
    public double prevrr = 0;
    public boolean starting = false;
    public int totalRejected = 0;
    public boolean thisIsFirstSample = false;
    long currentTimeMS;
    long elapsedMS;
    long prevTimeMS;
    long elapsedSecondsTrunc;
    double elapsedMin;

    static NotificationManagerCompat uiNotificationManager;
    static NotificationCompat.Builder uiNotificationBuilder;

    private AudioFeedbackManager audioFeedback;


    private class Log {
        public int d(String tag, String msg) {
            if (sessionLogger != null && sessionLogger.hasWriter("debug")) sessionLogger.writeLogFile(msg, "debug");
            return android.util.Log.d(tag, msg);
        }

        public int w(String tag, String msg) {
            if (sessionLogger != null && sessionLogger.hasWriter("debug")) sessionLogger.writeLogFile(msg, "debug");
            return android.util.Log.e(tag, msg);
        }

        public int e(String tag, String msg) {
            if (sessionLogger != null && sessionLogger.hasWriter("debug")) sessionLogger.writeLogFile(msg, "debug");
            return android.util.Log.e(tag, msg);
        }

        public int i(String tag, String msg) {
            if (sessionLogger != null && sessionLogger.hasWriter("debug")) sessionLogger.writeLogFile(msg, "debug");
            return android.util.Log.i(tag, msg);
        }

        public int v(String tag, String s) {
            if (sessionLogger != null && sessionLogger.hasWriter("debug")) sessionLogger.writeLogFile(s, "debug");
            return android.util.Log.v(tag,s);
        }
    }

    private static Log Log;

    private void closeLogs() {
        sessionLogger.closeLogs();
    }

    PowerManager powerManager;
    PowerManager.WakeLock wakeLock;

    //    PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
//    PowerManager.WakeLock wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp::MyWakelockTag");

    private ActivityMainBinding binding;
    private ChartHelper chartHelper;


    static enum FMMenuItem {
        MENU_QUIT,
        MENU_SEARCH,
        MENU_SET_DEFAULT,
        MENU_CONNECT_DEFAULT,
        MENU_EXPORT,
        MENU_DELETE_ALL,
        MENU_DELETE_DEBUG,
        MENU_OLD_LOG_FILES,
        MENU_EXPORT_SELECTED_LOG_FILES,
        MENU_DELETE_SELECTED_LOG_FILES,
        MENU_REPLAY,
        MENU_START,
        MENU_IMPORT,
        MENU_IMPORT_REPLAY,
        MENU_RENAME_LOGS,
        MENU_BLE_AD_START,
        MENU_BLE_AD_END,
        MENU_CONNECT_DISCOVERED // MUST BE LAST in enum as extra connection options are based off it
    }

    static int menuItem(FMMenuItem item) { return item.ordinal(); }

    // collect devices by deviceId so we don't spam the menu
    Map<String, String> discoveredDevices = new HashMap<String, String>();
    Map<Integer, String> discoveredDevicesMenu = new HashMap<Integer, String>();

    /**
     * Gets called every time the user presses the menu button.
     * Use if your menu is dynamic.
     */
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.clear();
        String startedStatus = "";
        String startedOpen = "[";
        String startedClose = "]";
        if (!logOrReplayStarted()) {
            startedOpen = "";
            startedClose = "";
        }
        if (logOrReplayStarted()) startedStatus=" ("+getString(R.string.BeforeNewConnectOrReplay)+")";
        menu.add(0, FMMenuItem.MENU_QUIT.ordinal(), Menu.NONE, getString(R.string.Quit)+" "+startedStatus);
        if (sharedPreferences.getBoolean(ENABLE_REPLAY, false)) {
//            menu.add(0, menuItem(MENU_IMPORT), Menu.NONE, "Import RR Log");
            menu.add(0, menuItem(MENU_REPLAY), Menu.NONE, startedOpen + getString(R.string.ReplayRRIntervalsLog) + startedClose);
            menu.add(0, menuItem(MENU_IMPORT_REPLAY), Menu.NONE, startedOpen + getString(R.string.ImportAndReplayRRIntervalsLog) + startedClose);
        }
        menu.add(0, menuItem(MENU_EXPORT_SELECTED_LOG_FILES), Menu.NONE, R.string.ExportSelectedLogs);
        menu.add(0, menuItem(MENU_RENAME_LOGS), Menu.NONE, R.string.RenameCurrentLogFiles);
        if (sharedPreferences.getBoolean(ENABLE_SENSOR_EMULATION, false)) {
            menu.add(0, menuItem(MENU_BLE_AD_START), Menu.NONE, "BLE Ad start");
            menu.add(0, menuItem(MENU_BLE_AD_END), Menu.NONE, "BLE Ad end");
        }
//        menu.add(0, menuItem(MENU_DELETE_SELECTED_LOG_FILES), Menu.NONE, R.string.DeleteSelectedLogs);
//        menu.add(0, menuItem(MENU_OLD_LOG_FILES), Menu.NONE, R.string.DeleteAllOldLogs);
//        menu.add(0, menuItem(MENU_DELETE_DEBUG), Menu.NONE, R.string.DeleteAllDebugLogs);
//        menu.add(0, menuItem(MENU_DELETE_ALL), Menu.NONE, R.string.DeleteAllLogs);
        String tmpDeviceId = sharedPreferences.getString(POLAR_DEVICE_ID_PREFERENCE_STRING, "");
        if (logOrReplayStarted()) {
            menu.add(0, menuItem(MENU_SET_DEFAULT), Menu.NONE, "Set connected device as preferred");
        }
        if (tmpDeviceId.length() > 0) {
            menu.add(0, menuItem(MENU_CONNECT_DEFAULT), Menu.NONE, startedOpen+getString(R.string.ConnectedPreferredDevice)+" " + tmpDeviceId+startedClose);
        }
        int i = 0;
        // Offer connect for discovered devices if not already connected/replaying
        for (String tmpDeviceID : discoveredDevices.keySet()) {
            menu.add(0, menuItem(MENU_CONNECT_DISCOVERED) + i, Menu.NONE, startedOpen+getString(R.string.Connect)+" " + discoveredDevices.get(tmpDeviceID)+startedClose);
            discoveredDevicesMenu.put(menuItem(MENU_CONNECT_DISCOVERED) + i, tmpDeviceID);
            i++;
        }
//        }
        menu.add(0, menuItem(MENU_SEARCH), Menu.NONE, R.string.SearchForPolarDevices);
        return super.onPrepareOptionsMenu(menu);
    }

    private boolean logOrReplayStarted() {
        return started;
    }

    public Uri getUri(File f) {
        try {
            return FileProvider.getUriForFile(
                    MainActivity.this,
//                    "online.fatmaxxer.publicRelease1.fileprovider",
                    BuildConfig.APPLICATION_ID+".fileprovider",
                    f);
        } catch (IllegalArgumentException e) {
            logException("getUri "+f.toString(), e);
        }
        return null;
    }

    final int REQUEST_IMPORT_CSV = 1;
    final int REQUEST_IMPORT_REPLAY_CSV = 2;

    public void importLogFile() {
        Intent receiveIntent = new Intent(Intent.ACTION_GET_CONTENT);
        receiveIntent.setType("text/*");
        receiveIntent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(receiveIntent, getString(R.string.ImportCSVFile)), REQUEST_IMPORT_CSV); //REQUEST_IMPORT_CSV is just an int representing a request code for the activity result callback later
    }

    private void importReplayLogFile() {
        Intent receiveIntent = new Intent(Intent.ACTION_GET_CONTENT);
        receiveIntent.setType("text/*");
        receiveIntent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(receiveIntent, getString(R.string.ImportCSVFile)), REQUEST_IMPORT_REPLAY_CSV); //REQUEST_IMPORT_CSV is just an int representing a request code for the activity result callback later
    }

    public void exportLogFiles() {
        ArrayList<Uri> logUris = new ArrayList<Uri>();
        logUris.add(getUri(sessionLogger.getLogFiles().get("rr")));
        logUris.add(getUri(sessionLogger.getLogFiles().get("features")));

        Intent shareIntent = new Intent();
        shareIntent.setAction(Intent.ACTION_SEND_MULTIPLE);
        shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, logUris);
        shareIntent.setType("text/plain");
        final int exportLogFilesCode = 254;
        startActivityForResult(Intent.createChooser(shareIntent, getString(R.string.ExportLogFilesTo)+"..."), exportLogFilesCode);
    }

    public List<File> rrLogFiles() {
        Log.d(TAG, "rrLogFiles...");
        File dir = getLogsDir();
        File[] allFiles = dir.listFiles();
        List<File> rrLogFiles = new ArrayList<File>();
        for (File f : allFiles) {
            String name = f.getName();
            if (isRRfileName(name)) {
                Log.d(TAG, "Found RR log file: " + f.getName());
                rrLogFiles.add(f);
            } else {
                Log.d(TAG, "Not RR log file: " + f.getName());
            }
        }
        return rrLogFiles;
    }

    private boolean isRRfileName(String name) {
        return name.endsWith(".rr.csv") || name.endsWith("RRIntervals.csv");
    }

    public List<Uri> logFiles() {
        Log.d(TAG, "logFiles...");
        File dir = getLogsDir();
        File[] allFiles = dir.listFiles();
        ArrayList<Uri> allUris = new ArrayList<Uri>();
        Arrays.sort(allFiles, new Comparator<File>() {
            public int compare(File f1, File f2) {
                return -Long.compare(f1.lastModified(),f2.lastModified());
            }
        });
        for (File f : allFiles) {
            Log.d(TAG, "Found log file: " + f.getName());
            allUris.add(getUri(f));
        }
        return allUris;
    }

    public void renameLogs() {
        AlertDialog.Builder alert = new AlertDialog.Builder(this);
        alert.setTitle(R.string.RenameLogFiles);
        alert.setMessage(R.string.TagToUseForCurrentLogFileNames);
        // Set an EditText view to get user input
        final EditText input = new EditText(this);
        alert.setView(input);
        alert.setPositiveButton(R.string.OK, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int whichButton) {
                String value = input.getText().toString();
                String msg = getString(R.string.RenameTo) +" "+ value;
                Toast.makeText(getBaseContext(), msg, Toast.LENGTH_SHORT).show();
                Log.d(TAG, msg);
                sessionLogger.renameLogs(value);
            }
        });
        alert.setNegativeButton(R.string.Cancel, null);
        alert.show();
    }


    public void exportFiles(ArrayList<Uri> allUris) {
        Intent shareIntent = new Intent();
        shareIntent.setAction(Intent.ACTION_SEND_MULTIPLE);
        shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, allUris);
        shareIntent.setType("text/plain");
        Log.d(TAG,"Exporting log file/s via ShareSheet "+allUris.toString());
        startActivity(Intent.createChooser(shareIntent, getString(R.string.ExportLogFilesTo)+"..."));
    }

    public void deleteCurrentLogFiles() {
        ArrayList<Uri> allUris = new ArrayList<Uri>();
        StringBuilder filenames = new StringBuilder();
        deleteFile(sessionLogger.getLogFiles().get("rr"));
        deleteFile(sessionLogger.getLogFiles().get("features"));
        deleteFile(sessionLogger.getLogFiles().get("debug"));
        deleteFile(sessionLogger.getLogFiles().get("ecg"));
    }

    public long durationMStoWholeDays(long durationMS) {
        return durationMS / (1000 * 3600 * 24);
    }

    public long durationMStoWholeHours(long durationMS) {
        return durationMS / (1000 * 3600);
    }

    public void expireLogFiles() {
        Log.d(TAG, "expireLogFiles...");
        ArrayList<Uri> allUris = new ArrayList<Uri>();
        File logsDir = getLogsDir();
        File[] allFiles = logsDir.listFiles();
        StringBuilder filenames = new StringBuilder();
        long curTimeMS = System.currentTimeMillis();
        for (File f : allFiles) {
            long ageMS = curTimeMS - f.lastModified();
            Log.d(TAG, "expire? "+f.getName()+" age in sec "+(ageMS / 1000));
            if (durationMStoWholeDays(ageMS)>=1) {
                Log.d(TAG, "- deleting log file " + f);
                f.delete();
                filenames.append(f.getName() + " ");
            }
        }
        String deletedFiles = filenames.toString();
        if (deletedFiles.length() > 0) {
            Log.i(TAG, getString(R.string.Deleted)+ " " + filenames.toString());
        }
    }

    public void deleteOldLogFiles() {
        ArrayList<Uri> allUris = new ArrayList<Uri>();
        File logsDir = getLogsDir();
        File[] allFiles = logsDir.listFiles();
        StringBuilder filenames = new StringBuilder();
        for (File f : allFiles) {
            if (!sessionLogger.getLogFiles().containsValue(f)) {
                Log.d(TAG, "deleting log file " + f);
                f.delete();
                filenames.append(f.getName() + " ");
            }
        }
        String deletedFiles = filenames.toString();
        if (deletedFiles.length() > 0) {
            Toast.makeText(getBaseContext(), getString(R.string.Deleted)+ " " + filenames.toString(), Toast.LENGTH_LONG).show();
        }
    }

    public File[] logFilesDeletable() {
        File logsDir = getLogsDir();
        File[] allFiles = logsDir.listFiles();
        StringBuilder filenames = new StringBuilder();
        return allFiles;
    }

    public void deleteAllLogFiles() {
        File logsDir = getLogsDir();
        File[] allFiles = logsDir.listFiles();
        StringBuilder filenames = new StringBuilder();
        for (File f : allFiles) {
            Log.d(TAG, "deletion option on " + f);
            if (!sessionLogger.getLogFiles().containsValue(f)) {
                Log.d(TAG, "deleting log file: " + f);
                f.delete();
                filenames.append(f.getName() + " ");
            } else {
                Log.d(TAG, "deleting log file on exit: " + f);
                f.deleteOnExit();
            }
        }
        Toast.makeText(getBaseContext(), R.string.Deleted+" "+filenames.toString(), Toast.LENGTH_LONG).show();
    }

    public void exportDebug() {
        Log.d(TAG, "exportAllDebugFiles...");
        ArrayList<Uri> allUris = new ArrayList<Uri>();
        File privateRootDir = getFilesDir();
        privateRootDir.mkdir();
        File logsDir = new File(privateRootDir, "logs");
        logsDir.mkdir();
        allUris.add(getUri(sessionLogger.getLogFiles().get("debug")));
        Intent shareIntent = new Intent();
        shareIntent.setAction(Intent.ACTION_SEND_MULTIPLE);
        shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, allUris);
        shareIntent.setType("text/plain");
        startActivity(Intent.createChooser(shareIntent, getString(R.string.ExportLogFilesTo)+"..."));
    }

    public void deleteAllDebugFiles() {
        ArrayList<Uri> allUris = new ArrayList<Uri>();
        File logsDir = getLogsDir();
        File[] allFiles = logsDir.listFiles();
        StringBuilder filenames = new StringBuilder();
        for (File f : allFiles) {
            if (f.getName().endsWith(".debug.log") && !sessionLogger.getLogFiles().containsValue(f)) {
                Log.d(TAG, "deleting log file (on exit) " + f);
                f.deleteOnExit();
                filenames.append(f.getName() + " ");
            }
        }
        Toast.makeText(getBaseContext(), getString(R.string.DeletingOnExit)+" "+filenames.toString(), Toast.LENGTH_LONG).show();
    }

    public String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    int columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (columnIndex>-1) {
                        result = cursor.getString(columnIndex);
                    }
                }
            } finally {
                cursor.close();
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }
        return result;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable @org.jetbrains.annotations.Nullable Intent data) {
        Log.d(TAG, "onActivityResult "+requestCode+" "+resultCode);
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode==REQUEST_IMPORT_CSV || requestCode==REQUEST_IMPORT_REPLAY_CSV) {
            if (data == null) {
                Log.w(TAG, getString(R.string.ImportCSVFailedDataIsNull));
                Toast.makeText(getBaseContext(), getString(R.string.ImportCSVFailedDataIsNull), Toast.LENGTH_LONG).show();
            } else {
                Uri uri = data.getData();
                if (uri == null) {
                    Log.w(TAG, getString(R.string.ImportCSVFailedCouldNotGetURIFromData));
                    Toast.makeText(getBaseContext(), getString(R.string.ImportCSVFailedCouldNotGetURIFromData), Toast.LENGTH_LONG).show();
                } else {
                    File importedRR = importRRFile(uri, getLogsDir());
                    if (requestCode ==REQUEST_IMPORT_REPLAY_CSV) {
                        if (importedRR!=null) {
                            replayRRfile(importedRR);
                        } else {
                            Toast.makeText(getBaseContext(), getString(R.string.ProblemWithImportedFile)+" " + uri, Toast.LENGTH_LONG).show();
                        }
                    }
                }
            }
        }
    }

    // https://stackoverflow.com/questions/10854211/android-store-inputstream-in-file/39956218
    private File importRRFile(Uri uri, File dir) {
        String filename = getFileName(uri);
        if (!isRRfileName(filename)) {
            String msg = getString(R.string.NotRRLogNotImporting)+": "+filename;
            Toast.makeText(getBaseContext(), msg, Toast.LENGTH_LONG).show();
            Log.w(TAG, msg);
            return null;
        }
        Log.d(TAG,"Importing RR file "+filename+" into logs");
        try {
            InputStream input = getContentResolver().openInputStream(uri);
            try {
                File file = new File(dir, filename);
                try (OutputStream output = new FileOutputStream(file)) {
                    Log.d(TAG,"Opened "+filename);
                    byte[] buffer = new byte[4 * 1024]; // or other buffer size
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }
                    output.flush();
                    Toast.makeText(getBaseContext(), getString(R.string.ImportedRRFile)+": " + filename, Toast.LENGTH_LONG).show();
                    return file;
                }
            } finally {
                    input.close();
            }
        } catch (Throwable e) {
            Toast.makeText(getBaseContext(), getString(R.string.ProblemImportingRRLog)+": " + filename, Toast.LENGTH_LONG).show();
            logException("Exception importing RR log",e);
        }
        return null;
    }

    void exportSelectedLogFiles() {
        Log.d(TAG, "exportSelectedLogFiles");
        AlertDialog.Builder adb = new AlertDialog.Builder(this);
        List<Uri> logFiles = logFiles();
        int nrLogFiles = 0;
        for (Uri uri : logFiles) nrLogFiles++;
        CharSequence[] items = new CharSequence[nrLogFiles];
        Uri[] uris = new Uri[nrLogFiles];
        boolean[] checkItems = new boolean[nrLogFiles];
        int i = 0;
        for (Uri uri : logFiles) {
            Log.d(TAG,"exportSelectedLogFiles "+uri);
            uris[i] = uri;
            items[i] = uri.getLastPathSegment();
            i++;
        }
        adb.setMultiChoiceItems(items, checkItems, new DialogInterface.OnMultiChoiceClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i, boolean b) {
                checkItems[i] = b;
            }
        });
        adb.setNegativeButton(R.string.Cancel, null);
        adb.setPositiveButton(R.string.OK, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int iOption) {
                        ArrayList<Uri> exports = new ArrayList();
                        for (int i = 0; i < items.length; i++) {
                            if (checkItems[i]) {
                                exports.add(uris[i]);
                            }
                        }
                        exportFiles(exports);
                    }
                }
        );
        adb.setTitle(R.string.SelectFilesForExport);
        adb.show();
    }

    void selectReplayRRfile() {
        Log.d(TAG, "selectReplayRRfile");
        AlertDialog.Builder adb = new AlertDialog.Builder(this);
        List<File> logFiles = rrLogFiles();
        int nrLogFiles = 0;
        for (File f : logFiles) nrLogFiles++;
        CharSequence[] items = new CharSequence[nrLogFiles];
        File[] files = new File[nrLogFiles];
        boolean[] checkItems = new boolean[nrLogFiles];
        int i = 0;
        for (File f : logFiles) {
            files[i] = f;
            items[i] = f.getName();
            i++;
        }
        adb.setSingleChoiceItems(items, 0, null);
        adb.setNegativeButton(R.string.Cancel, null);
        adb.setPositiveButton(R.string.OK, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        dialogInterface.dismiss();
                        int selectedPosition = ((AlertDialog) dialogInterface).getListView().getCheckedItemPosition();
                        File f = files[selectedPosition];
                        replayRRfile(f);
                    }
                }
        );
        adb.setTitle(R.string.SelectRRFileForTestInput);
        adb.show();
    }
    //                            logException("Exception opening RR log for replay", e);

    //runs without a timer by reposting handler at the end of the runnabl
    Handler timerHandler = new Handler();
    Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!testDataQueue.isEmpty()) {
                TestDataPacket data = testDataQueue.remove();
                //Log.d(TAG, "Timer .run method invoked wtih " + data.toString());
                updateTrackedFeatures(data.polarData, data.timestamp, false);
                timerHandler.postDelayed(this, 1);
            } else {
                Toast.makeText(getBaseContext(), R.string.FinishedReplay, Toast.LENGTH_LONG).show();
                //takeScreenshot();
            }
        }
    };

    static class TestDataPacket {
        PolarHrData polarData;
        long timestamp;

        @Override
        public String toString() {
            return "TestDataPacket{" +
                    "polarData=" + polarData.getSamples().get(0).getRrsMs().toString() +
                    ", timestamp=" + timestamp +
                    '}';
        }
    }

    Queue<TestDataPacket> testDataQueue;

    private void replayRRfile(File f) {
        FileReader fr = null;
        try {
            fr = new FileReader(f);
        } catch (FileNotFoundException e) {
            logException("Exception trying to open RR file "+f.getName()+" for replay", e);
            return;
        }
        testDataQueue = new ConcurrentLinkedQueue<TestDataPacket>();
        Log.d(TAG, "Starting RR reader test with " + f.getName());
        BufferedReader reader = new BufferedReader(fr);
        int prevRR = 1000;
        // header
        try {
            String header = reader.readLine();
            String headerExpected = RR_LOGFILE_HEADER;
            if (!header.equals(headerExpected)) {
                String msg = f.getName() + ": "+getString(R.string.WarningExpectedHeader)+ ": " + headerExpected +" "+getString(R.string.Got)+" "+header;
                Log.w(TAG, msg);
                Toast.makeText(getBaseContext(), msg, Toast.LENGTH_LONG).show();
//                return;
            }
            // gap
            String gap = reader.readLine();
            if (!header.equals(headerExpected)) {
                String msg = f.getName() + ": "+getString(R.string.WarningExpectedEmptyLineGot)+" " + gap;
                Log.w(TAG, msg);
                Toast.makeText(getBaseContext(), msg, Toast.LENGTH_LONG).show();
//                return;
            }
            // body
            String line = reader.readLine();
            double polarHR = 0;
            int lineCount = 0;
            // RR file is complex: In the RR files produced by FatMaxxer
            // there is the possibility of local inconsistency
            // between elapsed and timestamp columns. Timestamp is constructed
            // from a polar HR update's initial timestamp and then offset incrementally by the
            // RRs provided. But it seems this leads to the loss of monotonicity on the
            // timestamp column: the last timestamp constructed may be larger than the
            // timestamp provided in the next HR update.
            // Here we replay using the initial timestamp + the (monotonic) current value of the elapsed
            // field for the provided time in MS; this may lead to a discrepancy of a few seconds
            // in the overall activity(?!)
            long baseTimeStamp = 0;
            long elapsedMS = 0;
            long nextUpdateMS = 1000;
            List<Integer> rrs = new ArrayList<Integer>();
            int rr;
            int rrMean;
            int hr;
            while (line != null) {
                //Log.d(TAG, "RR file line: " + line);
                String[] fields = line.split(",");
                if (fields.length >= 3) {
                    // Initialize?
                    if (baseTimeStamp==0)
                        baseTimeStamp = Long.valueOf(fields[0]);
                    // Next RR interval - make rough HR calculation
                    rr = Integer.valueOf(fields[1]);
                    rrMean = (rr + prevRR) / 2;
                    prevRR = rr;
                    hr = 60000 / rrMean;
                    elapsedMS += rr; // don't trust the RR file for elapsed time; just sum the RRs individually
                    // Send updates, advancing time, until most-recent RR fits in current window
                    while (elapsedMS > nextUpdateMS) {
                        // Send update
                        List<PolarHrData.PolarHrSample> samples = new java.util.ArrayList<>(); samples.add(new PolarHrData.PolarHrSample(hr, rrs, true, true, true)); PolarHrData data = new PolarHrData(samples);
                        TestDataPacket testData = new TestDataPacket();
                        testData.polarData = data;
                        testData.timestamp = baseTimeStamp + nextUpdateMS;
                        testDataQueue.add(testData);
                        Log.d(TAG,"RR playback: next update "+testData.toString());
                        // Reset for next update
                        rrs = new ArrayList<Integer>();
                        nextUpdateMS += 1000;
                    }
                    // Post: most-recent RR fits in current window; add to update
                    rrs.add(rr);
                    // finished with this RR entry
                    lineCount++;
                    if (lineCount == 1) {
                        Log.d(TAG, "Started replay timer");
                        timerHandler.postDelayed(timerRunnable, 1000);
                    }
                }
                line = reader.readLine();
            }
            Log.d(TAG,"Finished queueing RR data");
            Toast.makeText(getBaseContext(), getString(R.string.StartedRRFileReplay)+": " + f.getName(), Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            logException("Reading replay data from RR file", e);
        }
    }

    void deleteSelectedLogFiles() {
        Log.d(TAG, "deleteSelectedLogFiles");
        AlertDialog.Builder adb = new AlertDialog.Builder(this);
        File[] logFiles = logFilesDeletable();
        int nrLogFiles = logFiles.length;
        CharSequence[] items = new CharSequence[nrLogFiles];
        boolean[] checkItems = new boolean[nrLogFiles];
        for (int i = 0; i < logFiles.length; i++) {
            items[i] = logFiles[i].getName();
        }
        adb.setMultiChoiceItems(items, checkItems, new DialogInterface.OnMultiChoiceClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i, boolean b) {
                checkItems[i] = b;
            }
        });
        adb.setNegativeButton(R.string.Cancel, null);
        adb.setPositiveButton(R.string.OK, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int iOption) {
                        for (int i = 0; i < items.length; i++) {
                            if (checkItems[i]) {
                                Log.d(TAG, "Delete selected file " + logFiles[i].getName());
                                deleteFile(logFiles[i]);
                            }
                        }
                    }
                }
        );
        adb.setTitle(R.string.SelectFilesForDeletion);
        adb.show();
    }

    public boolean quitRequired() {
        boolean result = logOrReplayStarted();
        if (result) {
            Toast.makeText(getBaseContext(), R.string.QuitOrRestartRequiredAfterConnectOrReplay, Toast.LENGTH_LONG).show();
        }
        return result;
    }

    public boolean onOptionsItemSelected(MenuItem item) {
        //respond to menu item selection
        Log.d(TAG, "onOptionsItemSelected... " + item.getItemId());
        int itemID = item.getItemId();
        if (itemID == menuItem(MENU_QUIT)) confirmQuit();
        if (itemID == menuItem(MENU_RENAME_LOGS)) renameLogs();
        if (itemID == menuItem(MENU_REPLAY)) if (!quitRequired()) selectReplayRRfile();
        if (itemID == menuItem(MENU_BLE_AD_START)) bleService.startAdvertising();
        if (itemID == menuItem(MENU_BLE_AD_END)) bleService.stopAdvertising();
        if (itemID == menuItem(MENU_EXPORT_SELECTED_LOG_FILES)) exportSelectedLogFiles();
        if (itemID == menuItem(MENU_DELETE_SELECTED_LOG_FILES)) deleteSelectedLogFiles();
        if (itemID == menuItem(MENU_EXPORT)) exportLogFiles();
        if (itemID == menuItem(MENU_IMPORT)) importLogFile();
        if (itemID == menuItem(MENU_IMPORT_REPLAY)) if (!quitRequired()) importReplayLogFile();
        if (itemID == menuItem(MENU_DELETE_ALL)) deleteAllLogFiles();
        if (itemID == menuItem(MENU_DELETE_DEBUG)) deleteAllDebugFiles();
        if (itemID == menuItem(MENU_SET_DEFAULT))  setConnectedDeviceAsPreferred();
        if (itemID == menuItem(MENU_CONNECT_DEFAULT)) if (!quitRequired()) tryPolarConnectToPreferredDevice();
        if (itemID == menuItem(MENU_OLD_LOG_FILES)) deleteOldLogFiles();
        if (itemID == menuItem(MENU_SEARCH)) searchForPolarDevices();
        if (discoveredDevicesMenu.containsKey(item.getItemId())) {
            if (!quitRequired())
                tryPolarConnect(discoveredDevicesMenu.get(item.getItemId()));
        }
        return super.onOptionsItemSelected(item);
    }

    public void quitSearchForPolarDevices() {
        if (broadcastDisposable != null) {
            broadcastDisposable.dispose();
            broadcastDisposable = null;
        }
    }

    public void searchForPolarDevices() {
        Toast.makeText(getBaseContext(), R.string.SearchingForPolarHRDevices, Toast.LENGTH_SHORT).show();
        if (broadcastDisposable == null) {
            broadcastDisposable = api.startListenForPolarHrBroadcasts(null)
                    .subscribe(polarBroadcastData -> {
                                if (!discoveredDevices.containsKey(polarBroadcastData.getPolarDeviceInfo().getDeviceId())) {
                                    String desc = polarBroadcastData.getPolarDeviceInfo().getName();
                                    String msg = getString(R.string.Discovered)+" " + desc + " "+getString(R.string.HeartRateAbbrev)+" " + polarBroadcastData.getHr();
                                    discoveredDevices.put(polarBroadcastData.getPolarDeviceInfo().getDeviceId(), desc);
                                    Toast.makeText(getBaseContext(), msg, Toast.LENGTH_SHORT).show();
                                    Log.d(TAG, msg);
                                }
                            },
                            error -> {
                                Log.e(TAG, "Broadcast listener failed. Reason: " + error);
                            },
                            () -> {
                                Log.d(TAG, "complete");
                            });
        } else {
            broadcastDisposable.dispose();
            broadcastDisposable = null;
        }
    }

    //@RequiresApi(Build.VERSION_CODES.O)
    @RequiresApi(api = Build.VERSION_CODES.O)
    private String createUINotificationChannel() {
        NotificationChannel chan = new NotificationChannel(UI_CHANNEL_ID,
                UI_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
        NotificationManager serviceNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        serviceNotificationManager.createNotificationChannel(chan);
        return UI_CHANNEL_ID;
    }

    String getStackTraceString(Throwable t) {
        StackTraceElement[] trace = t.getStackTrace();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < trace.length; i++) {
            b.append(trace[i]);
        }
        return b.toString();
    }

    public void logException(String comment, Throwable e) {
        android.util.Log.d(TAG, comment + " exception " + e.toString() + " " + getStackTraceString(e));
    }

    public void handleUncaughtException(Thread thread, Throwable e) {
        android.util.Log.d(TAG, "Uncaught ", e);
        //exportDebug();
        finish();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Set up handler for uncaught exceptions.
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable e) {
                handleUncaughtException(thread, e);
                System.exit(2);
            }
        });
        Log.d(TAG,"onCreate: set default uncaught exception handler");
        // Get intent, action and MIME type
        Intent intent = getIntent();
        String action = intent.getAction();
        String type = intent.getType();
        Log.d(TAG, "FatMaxxer version_name "+BuildConfig.VERSION_NAME);
        Log.d(TAG, "FatMaxxer version_code "+BuildConfig.VERSION_CODE);
        Log.d(TAG,"MainActivity checking invocation context: intent, action, type: "+intent+" "+action+" "+type);

        uiNotificationManager = NotificationManagerCompat.from(this);
        Intent i = new Intent(MainActivity.this, LocalService.class);
        i.setAction("START");
        Log.d(TAG, "intent to start local service " + i);
        ComponentName serviceComponentName = null; try { serviceComponentName = MainActivity.this.startService(i); } catch (Exception e) { android.util.Log.w("MainActivity", "startService failed: " + e.getMessage()); }
        Log.d(TAG, "start result " + serviceComponentName);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar == null) { android.util.Log.w("MainActivity", "No ActionBar - skipping"); }
        if (actionBar != null) actionBar.setDisplayShowHomeEnabled(true);
        if (actionBar != null) actionBar.setIcon(R.mipmap.ic_launcher);

        //setContentView(R.layout.activity_fragment_container);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setSupportActionBar(binding.toolbar);
        setContentView(binding.getRoot());
        chartHelper = new ChartHelper(this, binding.lineChart);
        if (binding.fabConnect != null) {
            binding.fabConnect.setOnClickListener(v -> {
                if (SENSOR_ID != null && SENSOR_ID.length() > 0) {
                    try { api.disconnectFromDevice(SENSOR_ID); } catch (Exception e) { /* ignore */ }
                    SENSOR_ID = "";
                    binding.chipConnectionStatus.setText("Disconnected");
                    updateFabState(false);
                } else {
                    searchForPolarDevices();
                    tryPolarConnectToPreferredDevice();
                }
            });
        }

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        dfaCalculator = new DFACalculator(Integer.parseInt(
                sharedPreferences.getString(LAMBDA_PREFERENCE_STRING, "500")));

        doBindService();
        createUINotificationChannel();

        // Notice PolarBleApi.ALL_FEATURES are enabled
        //api = PolarBleApiDefaultImpl.defaultImplementation(this, PolarBleApi.ALL_FEATURES);
        api = PolarBleApiDefaultImpl.defaultImplementation(this, new java.util.HashSet<>(java.util.Arrays.asList(PolarBleApi.PolarBleSdkFeature.values())));
        //api.setPolarFilter(false);

        text_time = binding.chipElapsedTime;
        // text_batt not in new layout
        // text_mode not in new layout
        text_hr = binding.textHeartRate;
        text_secondary = binding.textRMSSD;
        // text_secondary_label not in new layout
        text_a1 = binding.textAlpha1Value;
        // text_a1_label not in new layout
        text_artifacts = binding.textArtifacts;
        // text_view not in new layout

        //text.setTextSize(100);
        //text.setMovementMethod(new ScrollingMovementMethod());

        // scrollView not in new layout
        // FIXME: Why does the scrollable not start with top visible?

        testDFA_alpha1();

        api.setApiLogger(
                s -> {
                    int maxch = 63;
                    if (s.length()>64) {
                        Log.d(API_LOGGER_TAG, s.substring(0, 63)+"...");
                    } else {
                        Log.d(API_LOGGER_TAG, s);
                    }
                }
        );

        Log.d(TAG, "version: " + PolarBleApiDefaultImpl.versionInfo());

        audioFeedback = new AudioFeedbackManager(getApplicationContext(), sharedPreferences);
        audioFeedback.initialize(() -> audioFeedback.speakImmediate("Voice output ready"));

        sessionLogger = new SessionLogger(this);
        sessionLogger.createLogFile("rr");
        sessionLogger.writeLogFile(RR_LOGFILE_HEADER, "rr");
        sessionLogger.writeLogFile("", "rr");
        sessionLogger.createLogFile("features");
        sessionLogger.writeLogFile("date,timestamp,elapsedSec,heartrate,rmssd,sdnn,alpha1v1,filtered,samples,droppedPercent,artifactThreshold,alpha1v2", "features");
        sessionLogger.createLogFile("debug");





        //hrvSeries.setColor(Color.BLUE);

        //setContentView(R.layout.activity_settings);
        Log.d(TAG, "Settings...");
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings_container, MySettingsFragment.class, null)
                .commit();

        powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp::MyWakelockTag");

        api.setApiCallback(new PolarBleApiCallback() {

            @Override
            public void blePowerStateChanged(boolean powered) {
                Log.d(TAG, "BLE power: " + powered);
                if (text_view != null) text_view.setText("BLE power " + powered);
            }

            @Override
            public void deviceConnected(@NonNull PolarDeviceInfo polarDeviceInfo) {
                quitSearchForPolarDevices();
                SENSOR_ID = polarDeviceInfo.getDeviceId();
                Log.d(TAG, "Polar device CONNECTED: " + polarDeviceInfo.getDeviceId());
                if (binding != null) { binding.chipConnectionStatus.setText("Connected: " + SENSOR_ID); binding.chipConnectionStatus.setChipBackgroundColorResource(R.color.colorFatMaxIntensity); }
                Toast.makeText(getBaseContext(), getString(R.string.ConnectedToDevice)+" " + SENSOR_ID, Toast.LENGTH_SHORT).show();
                ensurePreferenceSet(POLAR_DEVICE_ID_PREFERENCE_STRING,polarDeviceInfo.getDeviceId());
                updateFabState(true);
            }

            @Override
            public void deviceConnecting(@NonNull PolarDeviceInfo polarDeviceInfo) {
                Log.d(TAG, "Polar device CONNECTING"+": " + polarDeviceInfo.getDeviceId());
                if (text_view != null) text_view.setText(getString(R.string.ConnectingToHeartRateSensor)+" " + polarDeviceInfo.getDeviceId());
            }

            @Override
            public void deviceDisconnected(@NonNull PolarDeviceInfo polarDeviceInfo) {
                Log.d(TAG, "DISCONNECTED: " + polarDeviceInfo.getDeviceId());
                if (binding != null) { binding.chipConnectionStatus.setText("Disconnected"); binding.chipConnectionStatus.setChipBackgroundColorResource(android.R.color.transparent); }
                updateFabState(false);
                if (text_view != null) text_view.setText(getString(R.string.DisconnectedFromHeartRateSensor)+" " + polarDeviceInfo.getDeviceId());
                ecgDisposable = null;
                searchForPolarDevices();
            }

            @Override
            public void bleSdkFeatureReady(@NonNull final String identifier,
                                               @NonNull final PolarBleApi.PolarBleSdkFeature feature) {
                Log.d(TAG, "Streaming feature " + feature.toString() + " is ready");
                if (feature == PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING || feature == PolarBleApi.PolarBleSdkFeature.FEATURE_HR) {
                    api.startHrStreaming(identifier)
                        .observeOn(io.reactivex.rxjava3.android.schedulers.AndroidSchedulers.mainThread())
                        .subscribe(
                            data -> updateTrackedFeatures(data, System.currentTimeMillis(), true),
                            error -> Log.e(TAG, "HR streaming error: " + error)
                        );
                }
            }

            @Override
            public void hrFeatureReady(@NonNull String identifier) {
                Log.d(TAG, "HR READY: " + identifier);
                if (text_view != null) text_view.setText("HR feature " + identifier + " is ready");
                // hr notifications are about to start
            }

            @Override
            public void disInformationReceived(@NonNull String identifier, @NonNull UUID uuid, @NonNull String value) {
                Log.d(TAG, "uuid: " + uuid + " value: " + value);
            }

            @Override
            public void batteryLevelReceived(@NonNull String identifier, int level) {
                batteryLevel = level;
                Log.d(TAG, "BATTERY LEVEL: " + level);
            }

            //NotificationManagerCompat notificationManager = NotificationManagerCompat.from(thisContext);

            // FIXME: this is a makeshift main event & timer loop
            // hrNotificationReceived removed in SDK 5.x - HR now via startHrStreaming
            // HR streaming is started in bleSdkFeatureReady when FEATURE_HR is ready

            // polarFtpFeatureReady removed in SDK 5.x
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && savedInstanceState == null) {
            //this.requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, "android.permission.BLUETOOTH_ADVERTISE"}, 1);
            this.requestPermissions(new String[]{Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, 1);
            //requestPermissions(new Object[](Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), PERMISSION_REQUEST_CODE);
        }

        expireLogFiles();

        // scrollView removed - using CoordinatorLayout

        // auto-start
        if (!sharedPreferences.getBoolean(ENABLE_REPLAY,false)) {
            startAnalysis();
        }
        // start BLE sensor emulator service
        if (sharedPreferences.getBoolean(ENABLE_SENSOR_EMULATION, false)) {
            startBLESensorEmulatorService();
        }
    }

    boolean bleServiceStarted = false;
    boolean mBound = false;
    BLEEmulator bleService = null;

    private ServiceConnection bleServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className,  IBinder service) {
            Log.d(TAG,"bleServiceConnection.onServiceConnected");
            BLEEmulator.LocalBinder binder = (BLEEmulator.LocalBinder) service;
            bleService = binder.getService();
            mBound = true;
        }
        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            Log.d(TAG,"bleServiceConnection.onServiceDisconnected");
            mBound = false;
        }
    };

    private void startBLESensorEmulatorService() {
        Log.d(TAG,"startBLESensorEmulatorService");
        Intent mServiceIntent = new Intent(getApplicationContext(), BLEEmulator.class);
        if (!bleServiceStarted) {
            Log.d(TAG, "Starting BLESensorEmulatorService Service");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                MainActivity.this.startForegroundService(mServiceIntent);
            else
                MainActivity.this.startService(mServiceIntent);
            // Bind to the service so we can interact with it
            if (!bindService(mServiceIntent, bleServiceConnection, Context.BIND_AUTO_CREATE)) {
                Log.d(TAG, "Failed to bind to service");
            } else {
                mBound = true;
            }
        }
    }

    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    // currently logging ECG data after observing an artifact
    boolean ecgLogging = false;
    final int ecgPacketSize = 73;
    final int ecgSampleRate = 130;
    final int pastECGbufferDurationSec = 5;
    final int totalECGsamples = pastECGbufferDurationSec * ecgSampleRate;
    final int totalECGpackets = totalECGsamples / ecgPacketSize;
    // 73 samples per slot at 125hz is very roughly 0.5s
    Queue<PolarEcgData> lastPolarEcgData = new ConcurrentLinkedQueue<PolarEcgData>();

    Integer lastECGpeak = 0;
    Integer peakECGuVolts = 0;
    private void ecgCallback(PolarEcgData polarEcgData) {
            // startup: record relative timestamps
            if (!ecgMonitoring) {
                ecgStartTSmillis = System.currentTimeMillis();
                ecgStartInternalTSnanos = polarEcgData.getTimeStamp();
                ecgMonitoring = true;
            }
            for (com.polar.sdk.api.model.PolarEcgData.PolarEcgDataSample ecgSample : polarEcgData.getSamples()) { int microVolts = ecgSample.getVoltage();
                peakECGuVolts = max(abs(microVolts), peakECGuVolts);
            }
            lastPolarEcgData.add(polarEcgData);
            // throw away ECG logs, oldest-first, but only if not already logging
            if (!ecgLogging && lastPolarEcgData.size()>totalECGpackets) {
                PolarEcgData ecgPacket = lastPolarEcgData.remove();
            }
    }

    int ecgSegment = 0;
    int ecgSample = 0;
    // log all recorded ecg data
    private void logAllEcgData() {
        if (sharedPreferences.getBoolean(ENABLE_ECG, true)) {
            Log.d(TAG,"logAllEcgData");
            // FIXME: Copied code
            if (!sessionLogger.hasWriter("ecg")) {
                sessionLogger.createLogFile("ecg");
                sessionLogger.writeLogFile("date,timestamp,elapsed,segmentNr,sampleNr,yV","ecg");
            }
            while (!lastPolarEcgData.isEmpty ()) {
                PolarEcgData ecgPacket = lastPolarEcgData.remove();
                // elapsed time since ecg start (nanosecs)
                long ecgElapsedNanos = ecgPacket.getTimeStamp() - ecgStartInternalTSnanos;
                // elapsed time since logging started (millisecs)
                long ecgElapsedMS = (ecgElapsedNanos / 1000000) + (ecgStartTSmillis - firstSampleTimestampMS);
                String elapsedStr = formatSecAsTime((long)(ecgElapsedMS / 1000.0));
                // nanoseconds since(?)
                Log.d(TAG,"logEcgData: logging packet "+ecgElapsedMS);
                Date d = new Date(firstSampleTimestampMS + ecgElapsedMS);
                String dateStr = sdf.format(d);
                int ecgSampleIdx = 0; for (com.polar.sdk.api.model.PolarEcgData.PolarEcgDataSample ecgSampleData : ecgPacket.getSamples()) { int microVolts = ecgSampleData.getVoltage();
                    sessionLogger.writeLogFile(dateStr + "," + ecgPacket.getTimeStamp() +"," +elapsedStr+ ","+ecgSegment+"," + ecgSampleIdx + "," + String.valueOf(microVolts), "ecg");
                    ecgSampleIdx++;
                }
            }
        }
    }
    private void logEcgSegmentEnd() {
        if (sharedPreferences.getBoolean(ENABLE_ECG, true)) {
            Log.d(TAG,"logEcgSegmentEnd");
            // FIXME: Copied code
            if (!sessionLogger.hasWriter("ecg")) {
                sessionLogger.createLogFile("ecg");
                sessionLogger.writeLogFile("date,timestamp,elapsed,segmentNr,sampleNr,yV","ecg");
            }
            for (int i=0;i<10;i++) {
                sessionLogger.writeLogFile("" + "," + "" + "," + "" + "," + ecgSegment + "," + ecgSample + "," + "1000.0", "ecg");
                sessionLogger.writeLogFile("" + "," + "" + "," + "" + "," + ecgSegment + "," + ecgSample + "," + "-1000.0", "ecg");
            }
        }
    }

    boolean ecgMonitoring = false;

    private void startECG() {
        if (ecgDisposable == null) {
            Log.d(TAG, "startECG create ecgDisposable");
            ecgDisposable = api.requestStreamSettings(SENSOR_ID, com.polar.sdk.api.PolarBleApi.PolarDeviceDataType.ECG)
                    .toFlowable()
                    .flatMap((Function<PolarSensorSetting, Publisher<PolarEcgData>>) polarEcgSettings -> {
                        PolarSensorSetting sensorSetting = polarEcgSettings.maxSettings();
                        Log.d(TAG, "api.startEcgStreaming "+sensorSetting.toString());
                        return api.startEcgStreaming(SENSOR_ID, sensorSetting);
                    })
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                            polarEcgData -> ecgCallback(polarEcgData),
                            //throwable -> Log.e(TAG, "ECG throwable " + throwable),
                            throwable -> {
                                Log.d(TAG, "ECG throwable " + throwable.getClass());
                                ecgMonitoring = false;
                            },
                            () -> {
                                Log.d(TAG, "complete");
                                ecgMonitoring = false;
                            }
                    );
        }
    }

    private void ensurePreferenceSet(String key, String defaultValue) {
        if (!sharedPreferences.contains(key)) {
            sharedPreferences.edit().putString(key, defaultValue).apply();
        }
    }


    private int getNrSamples() {
        int nrSamples = (newestSample < oldestSample) ? (newestSample + maxrrs - oldestSample) : (newestSample - oldestSample);
        return nrSamples;
    }

    // extract feature window from circular buffer (ugh), allowing for sample buffer after end of feature window
    // FIXME: requires invariants
    public double[] copySamplesFeatureWindow() {
        int next = 0;
        // rewind just past sample buffer
        int newestSampleIndex = (newestSample - sampleBufferMarginSec) % rrInterval.length;
        long newestTimestamp = rrIntervalTimestamp[newestSampleIndex];
        // rewind by the size of the window in seconds
        int oldestSampleIndex = newestSampleIndex;
        while (rrIntervalTimestamp[oldestSampleIndex] > (newestTimestamp - rrWindowSizeSec)) {
            oldestSampleIndex = (oldestSampleIndex - 1) % rrInterval.length;
        }
        return copySamplesRange(oldestSampleIndex,newestSampleIndex);
    }

    public double[] copySamplesAll() {
        return copySamplesRange(oldestSample, newestSample);
    }

    public double[] copySamplesRange(int oldest, int newest) {
        double[] result = new double[getNrSamples()];
        int next = 0;
        // FIXME: unverified
        for (int i = oldest; i != newest; i = (i + 1) % rrInterval.length) {
            result[next] = rrInterval[i];
            next++;
        }
        return result;
    }

    private int getNrArtifacts() {
        int result = 0;
        for (int i = oldestArtifactSample; i != newestArtifactSample; i = (i + 1) % artifactTimestamp.length) {
            result++;
        }
        return result;
    }

    int hrNotificationCount = 0;
    private void updateTrackedFeatures(@NotNull PolarHrData data, long currentVirtualTimeMS, boolean realTime) {
        wakeLock.acquire(60 * 1000L); // 60s timeout to prevent stuck wakelocks
        hrNotificationCount++;
        currentTimeMS = currentVirtualTimeMS;
        if (currentTimeMS <= prevTimeMS) {
            throw new IllegalStateException("assertion failed: cur "+currentTimeMS+" "+prevTimeMS);
        }
        prevTimeMS = currentTimeMS;
        if (!started) {
            Log.d(TAG, "hrNotificationReceived: started!");
            started = true;
            starting = true;
            thisIsFirstSample = true;
            firstSampleTimestampMS = currentTimeMS;
            // FIXME: why does the scroller not start with the top visible?
            // scrollView.scrollTo(0,0); // removed - using CoordinatorLayout
        }
        elapsedMS = (currentTimeMS - firstSampleTimestampMS);
        //Log.d(TAG, "====================");
        elapsedSecondsTrunc = elapsedMS / 1000;
        //Log.d(TAG, "updateTrackedFeatures cur "+currentTimeMS+" elapsed "+elapsedMS+" hr notifications "+hrNotificationCount+" calcElapsed"+10*hrNotificationCount);
        boolean timeForUIupdate = timeForUIupdate(realTime);
        if (timeForUIupdate) {
            String lambdaPref = sharedPreferences.getString(LAMBDA_PREFERENCE_STRING, "500");
            lambdaSetting = Integer.valueOf(lambdaPref);
            dfaCalculator.setLambda(lambdaSetting);
            //experimental = sharedPreferences.getBoolean(EXPERIMENTAL_PREFERENCE_STRING, false);
            if (sharedPreferences.getBoolean(KEEP_SCREEN_ON_PREFERENCE_STRING, false)) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        }
        currentHR = data.getSamples().get(0).getHr();
        if (timeForUIupdate) {
            String artifactCorrectionThresholdSetting = sharedPreferences.getString(ARTIFACT_REJECTION_THRESHOLD_PREFERENCE_STRING, "Auto");
            if (artifactCorrectionThresholdSetting.equals("Auto")) {
                if (data.getSamples().get(0).getHr() > 95) {
                exerciseMode = getString(R.string.Workout);
                artifactCorrectionThreshold = 0.05;
                } else if (data.getSamples().get(0).getHr() < 80) {
                    exerciseMode = getString(R.string.Light);
                    artifactCorrectionThreshold = 0.25;
                }
            } else if (artifactCorrectionThresholdSetting.equals("0.25")) {
                exerciseMode = getString(R.string.Light);
                artifactCorrectionThreshold = 0.25;
            } else if (artifactCorrectionThresholdSetting.equals("0.05")) {
                exerciseMode = getString(R.string.Workout);
                artifactCorrectionThreshold = 0.05;
            }
            boolean removeArtifactsSetting = sharedPreferences.getBoolean("removeArtifacts", true);
            disableArtifactCorrection = !removeArtifactsSetting;
        }
        String notificationDetailSetting = "";
        String alpha1EvalPeriodSetting = "";
        // preference updates
        if (timeForUIupdate) {
            //Log.d(TAG,"timeForUIupdate");
            //Log.d(TAG,"graphFeaturesSelected "+graphFeaturesSelected);
            notificationDetailSetting = sharedPreferences.getString(NOTIFICATION_DETAIL_PREFERENCE_STRING, "full");
            alpha1EvalPeriodSetting = sharedPreferences.getString(ALPHA_1_CALC_PERIOD_PREFERENCE_STRING, "20");
            try {
                alpha1EvalPeriodSec = Integer.parseInt(alpha1EvalPeriodSetting);
            } catch (final NumberFormatException e) {
                //Log.d(TAG, "Number format exception alpha1EvalPeriod " + alpha1EvalPeriodSetting + " " + e.toString());
                alpha1EvalPeriodSec = 20;
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putString(ALPHA_1_CALC_PERIOD_PREFERENCE_STRING, "20");
                editor.apply();
                //Log.d(TAG, "alpha1CalcPeriod wrote " + sharedPreferences.getString(ALPHA_1_CALC_PERIOD_PREFERENCE_STRING, "??"));
            }
            if (alpha1EvalPeriodSec < 5) {
                //Log.d(TAG, "alpha1EvalPeriod<5");
                alpha1EvalPeriodSec = 5;
                sharedPreferences.edit().putString(ALPHA_1_CALC_PERIOD_PREFERENCE_STRING, "5").apply();
            }
        }
        // test: use ONLY for RRs
        long timestamp = currentTimeMS;
        for (int rr : data.getSamples().get(0).getRrsMs()) {
            String msg = "" + timestamp + "," + rr + "," + logRRelapsedMS;
            sessionLogger.writeLogFile(msg, "rr");
            logRRelapsedMS += rr;
            timestamp += rr;
        }
        //
        // FILTERING / RECORDING RR intervals
        //
        String rejected = "";
        boolean haveArtifacts = false;
        List<Integer> rrsMs = data.getSamples().get(0).getRrsMs();
        for (int si = 0; si < data.getSamples().get(0).getRrsMs().size(); si++) {
            double newRR = data.getSamples().get(0).getRrsMs().get(si);
            double lowerBound = prevrr * (1 - artifactCorrectionThreshold);
            double upperBound = prevrr * (1 + artifactCorrectionThreshold);
            //Log.d(TAG, "prevrr " + prevrr + " lowerBound " + lowerBound + " upperBound " + upperBound);
            boolean artifactFound = lowerBound >= newRR || newRR >= upperBound;
            if (thisIsFirstSample || !artifactFound) {
                //Log.d(TAG, "accept RR within threshold" + newrr);
                // if in_RRs[(i-1)]*(1-artifact_correction_threshold) < in_RRs[i] < in_RRs[(i-1)]*(1+artifact_correction_threshold):
                if (!disableArtifactCorrection) {
                    rrInterval[newestSample] = newRR;
                    rrIntervalTimestamp[newestSample] = currentTimeMS;
                    newestSample = (newestSample + 1) % maxrrs;
                }
                thisIsFirstSample = false;
            }
            if (artifactFound) {
                //Log.d(TAG, "drop...");
                artifactTimestamp[newestArtifactSample] = currentTimeMS;
                newestArtifactSample = (newestArtifactSample + 1) % maxrrs;
                //Log.d(TAG, "reject artifact " + newrr);
                rejected += "" + newRR;
                haveArtifacts = true;
                totalRejected++;
            }
            prevrr = newRR;
        }
        String rejMsg = haveArtifacts ? (", Rejected: " + rejected) : "";
        int expired = 0;
        //Log.d(TAG, "updateTrackedFeatures expire old samples");
        while (oldestSample != newestSample && rrIntervalTimestamp[oldestSample] < currentTimeMS - rrWindowSizeSec * 1000) {
                oldestSample = (oldestSample + 1) % maxrrs;
                expired++;
        }
        //Log.d(TAG, "updateTrackedFeatures expire old artifacts");
        while (oldestArtifactSample != newestArtifactSample && artifactTimestamp[oldestArtifactSample] < currentTimeMS - rrWindowSizeSec * 1000) {
            //Log.d(TAG, "Expire at " + oldestArtifactSample);
            oldestArtifactSample = (oldestArtifactSample + 1) % maxrrs;
        }
        long absSeconds = Math.abs(elapsedSecondsTrunc);
        boolean timeForHRplot = timeForHRplot(realTime);
        //Log.d(TAG, "updateTrackedFeatures timeForHRplot");
        if (timeForHRplot) {
            String positive = formatSecAsTime(absSeconds);
            if (text_mode != null) text_mode.setText(exerciseMode);
            text_time.setText(positive);
            if (text_batt != null) text_batt.setText("\uD83D\uDD0B" + batteryLevel);
        }

        //
        // Automatic beat correction
        // https://www.kubios.com/hrv-preprocessing/
        //
        long elapsedSecondsTrunc = elapsedMS / 1000;
        //Log.d(TAG,"elapsed seconds (trunc) = "+elapsedSecondsTrunc);
        int nrSamples = getNrSamples();
        int nrArtifacts = getNrArtifacts();
        // get full window (c. 220sec)
        double[] allSamples = copySamplesAll();
        // get sample window (c. 120sec)
        double[] featureWindowSamples;
        double[] samples = allSamples;

        if (samples.length==0) {
            starting = false;
            wakeLock.release();
            return;
        }

        //Log.d(TAG, "updateTrackedFeatures windowed features");
        // ******************
        // WINDOWED FEATURES
        // ******************
        if (ecgMonitoring && (haveArtifacts || hrNotificationCount == 10) && hrNotificationCount >= pastECGbufferDurationSec) {
            Log.d(TAG,"Artifacts: (re)start ECG logging @"+hrNotificationCount);
            // New artifact - EVENT: start ECG logging
            lastObservedHRNotificationWithArtifacts = hrNotificationCount;
            ecgLogging = true;
        }
        if (ecgMonitoring && ecgLogging && hrNotificationCount < lastObservedHRNotificationWithArtifacts + pastECGbufferDurationSec) {
            Log.d(TAG,"ECG logging @"+hrNotificationCount);
            logAllEcgData();
        } else if (ecgMonitoring && ecgLogging && hrNotificationCount == lastObservedHRNotificationWithArtifacts + pastECGbufferDurationSec) {
            // EVENT: stop ECG logging
            Log.d(TAG,"Stop ECG logging @"+hrNotificationCount);
            logEcgSegmentEnd();
            //
            ecgLogging = false;
            ecgSegment++;
            ecgSample = 0;
        }
        rmssdWindowed = dfaCalculator.getRMSSD(samples);
        rrMeanWindowed = DFACalculator.vMean(samples);
        //Log.d(TAG,"rrMeanWindowed "+rrMeanWindowed);
        hrMeanWindowed = round(60 * 1000 * 100 / rrMeanWindowed) / 100.0;
        //Log.d(TAG,"hrMeanWindowed "+hrMeanWindowed);
        // Periodic actions: check alpha1 and issue voice update
        // - skip one period's worth after first HR update
        // - only within the first two seconds of this period window
        // - only when at least three seconds have elapsed since last invocation
        // FIXME: what precisely is required for alpha1 to be well-defined?
        // FIXME: The prev_a1_check now seems redundant
        if (timeForUIupdate) Log.d(TAG,"Elapsed "+elapsedSecondsTrunc+" currentTimeMS "+currentTimeMS+ " a1evalPeriod "+ alpha1EvalPeriodSec +" prevA1Timestamp "+ prevA1TimestampMS);
        boolean graphEnabled = realTime;
//        boolean enoughElapsedSinceStart = elapsedSecondsTrunc > alpha1EvalPeriodSec;
//        boolean oncePerPeriod = true; //elapsed % alpha1EvalPeriod <= 2;
//        boolean enoughSinceLast = currentTimeMS >= (prevA1TimestampMS + alpha1EvalPeriodSec *1000);
        //Log.d(TAG,"graphEnabled antecedents "+enoughElapsedSinceStart+" "+oncePerPeriod+" "+enoughElapsedSinceStart);
        // Logging must not be throttled during replay
        if (hrNotificationCount % alpha1EvalPeriodSec == 0) {
//            graphEnabled = true;
            //Log.d(TAG,"alpha1...");
            zonePrev = zone;
            alpha1V2Windowed = dfaCalculator.dfaAlpha1V2(samples, 2, 4, 30);
            float a1v2x100 = (int)(100.0 * alpha1V2Windowed);
            alpha1V2RoundedWindowed = round(alpha1V2Windowed * 100) / 100.0;
            if (sharedPreferences.getBoolean(ENABLE_SENSOR_EMULATION, false) && bleService != null) {
                bleService.lastHR = (int) a1v2x100;
            }
            Log.d(TAG,"a1v2windowed "+alpha1V2Windowed+" a1v2x100 "+a1v2x100);
            prevA1TimestampMS = currentTimeMS;
            if (elapsedSecondsTrunc > 120) {
                String dateStr = sdf.format(new Date(currentTimeMS));
                //         date,timestamp,elapsedSec,heartrate,rmssd,sdnn,alpha1v1,filtered,samples,droppedPercent,artifactThreshold,alpha1v2", "features");
                sessionLogger.writeLogFile(
                                dateStr
                                + "," + currentTimeMS
                                + "," + hrNotificationCount
                                + "," + hrMeanWindowed
                                + "," + rmssdWindowed
                                + ","
                                + "," //+ alpha1V1RoundedWindowed
                                + "," + nrArtifacts
                                + "," + nrSamples
                                + "," + artifactsPercentWindowed
                                + "," + artifactCorrectionThreshold
                                + "," + alpha1V2RoundedWindowed
                        ,
                        "features");
            }
            if (timeForUIupdate) {
                if (sharedPreferences.getBoolean(NOTIFICATIONS_ENABLED_PREFERENCE_STRING, false)) {
                    //Log.d(TAG, "Feature notification...");
                    // https://stackoverflow.com/questions/5502427/resume-application-and-stack-from-notification
                    final Intent notificationIntent = new Intent(this, MainActivity.class);
                    notificationIntent.setAction(Intent.ACTION_MAIN);
                    notificationIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                    notificationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
                    //notification.setContentIntent(pendingIntent)

                    uiNotificationBuilder = new NotificationCompat.Builder(this, UI_CHANNEL_ID)
                            .setOngoing(true)
                            .setSmallIcon(R.mipmap.ic_launcher)
                            .setPriority(NotificationManager.IMPORTANCE_HIGH)
                            .setCategory(Notification.CATEGORY_MESSAGE)
                            .setContentIntent(pendingIntent)
                            .setContentTitle(getString(R.string.Alpha1AbbrevNonUnicode)+" " + alpha1V2RoundedWindowed + " "+getString(R.string.Drop)+" " + artifactsPercentWindowed + "%");
                    if (notificationDetailSetting.equals("full")) {
                        uiNotificationBuilder.setContentText(getString(R.string.HeartRateAbbrev)+" " + currentHR + " "+getString(R.string.BatteryAbbrev)+" " + batteryLevel + "% "+getString(R.string.RootMeanSquareSuccessiveDifferencesAbbrevLowerCase)+" " + rmssdWindowed);
                    } else if (notificationDetailSetting.equals("titleHR")) {
                        uiNotificationBuilder.setContentText(getString(R.string.HeartRateAbbrev)+" " + currentHR);
                    }
                    uiNotificationManager.notify(NOTIFICATION_TAG, NOTIFICATION_ID, uiNotificationBuilder.build());
                }
            }
        }

        //
        // UI (DISPLAY // AUDIO // LOGGING)
        //
        // Device Display
        // if (timeForUIupdate()) {
        if (timeForHRplot) {
            if (haveArtifacts) {
                audioFeedback.playArtifactSound();
            }
            StringBuilder logmsg = new StringBuilder();
            if (ecgLogging) {
                logmsg.append("*");
            }
            if (!lastPolarEcgData.isEmpty()) {
                logmsg.append("ECG ");
            }
            logmsg.append("RRs: " + data.getSamples().get(0).getRrsMs()+" ");
            logmsg.append(rejMsg);
            logmsg.append("Total rejected: " + totalRejected+" ");
            logmsg.append("ECG Peak: "+ lastECGpeak +" uV");
            lastECGpeak = peakECGuVolts;
            if (elapsedSecondsTrunc % 2 == 0) {
                peakECGuVolts = 0;
            }
            String logstring = logmsg.toString();

            artifactsPercentWindowed = (int) round(nrArtifacts * 100 / (double) (nrArtifacts + nrSamples));
            if (text_artifacts != null) text_artifacts.setText("" + artifactsPercentWindowed);
            if (haveArtifacts) {
                text_artifacts.setBackgroundResource(R.color.colorHighlight);
            } else {
                text_artifacts.setBackgroundResource(R.color.colorBackground);
            }
            if (text_view != null) text_view.setText(logstring);
            text_hr.setText("" + data.getSamples().get(0).getHr());
            if (text_secondary_label != null) text_secondary_label.setText(R.string.RootMeanSquareSuccessiveDifferencesAbbreviation);
            text_secondary.setText("" + round(rmssdWindowed));
            text_a1.setText("" + alpha1V2RoundedWindowed);
            text_a1_label.setText(getString(R.string.alpha1) + " [" + dfaCalculator.getCacheMisses() + "]");
            // configurable top-of-optimal threshold for alpha1
            double alpha1MaxOptimal = Double.parseDouble(sharedPreferences.getString("alpha1MaxOptimal", "1.0"));
            if (elapsedSecondsTrunc < 20) {
                int undefColor = getFatMaxxerColor(R.color.colorTextUndefinedData);
                text_a1.setTextColor(undefColor);
                text_secondary.setTextColor(undefColor);
            } else if (elapsedSecondsTrunc < 120) {
                int unreliableColor = getFatMaxxerColor(R.color.colorTextUnreliableData);
                text_a1.setTextColor(unreliableColor);
                text_secondary.setTextColor(unreliableColor);
            } else {
                text_a1.setTextColor(getFatMaxxerColor(R.color.colorTextData));
                text_secondary.setTextColor(getFatMaxxerColor(R.color.colorTextData));
            }
            zonePrev = zone;
            if (elapsedSecondsTrunc > 30) {
                    if (alpha1V2RoundedWindowed < alpha1HRVvt2) {
                        text_a1.setBackgroundResource(R.color.colorMaxIntensity);
                        zone = 3;
                    } else if (alpha1V2RoundedWindowed < alpha1HRVvt1) {
                        text_a1.setBackgroundResource(R.color.colorMedIntensity);
                        zone = 2;
                    } else if (alpha1V2RoundedWindowed < alpha1MaxOptimal) {
                        text_a1.setBackgroundResource(R.color.colorFatMaxIntensity);
                        zone = 1;
                    } else {
                        text_a1.setBackgroundResource(R.color.colorEasyIntensity);
                        zone = 0;
                    }
            }
            Log.d(TAG, "HR "+data.getSamples().get(0).getHr() + " " + alpha1V2RoundedWindowed + " " + rmssdWindowed);
            Log.d(TAG, logstring);
            Log.d(TAG, "Elapsed % alpha1EvalPeriod " + (elapsedSecondsTrunc % alpha1EvalPeriodSec));
        }
        elapsedMin = this.elapsedMS / 60000.0;
        double elapsedMinRound = round(this.elapsedMin * 1000) / 1000.0;
        chartHelper.addDataPoint((float)elapsedMinRound, data.getSamples().get(0).getHr(),
                (float)(alpha1V2RoundedWindowed * 100.0),
                (float)round(rmssdWindowed),
                data.getSamples().get(0).getRrsMs().isEmpty() ? 0 : data.getSamples().get(0).getRrsMs().get(data.getSamples().get(0).getRrsMs().size()-1),
                artifactsPercentWindowed);

        audioFeedback.update(new AudioFeedbackManager.TrainingState(
                alpha1V2RoundedWindowed, data.getSamples().get(0).getHr(),
                (int) round(rmssdWindowed), artifactsPercentWindowed,
                elapsedSecondsTrunc, zone, zonePrev), currentTimeMS);

        starting = false;
        wakeLock.release();

        if (realTime && sharedPreferences.getBoolean(ENABLE_ECG, true)) {
            startECG();
        }
    }

    private String formatSecAsTime(long absSeconds) {
        return String.format(
                "%2d:%02d:%02d",
                absSeconds / 3600,
                (absSeconds % 3600) / 60,
                absSeconds % 60);
    }

    private String formatMinAsTime(double elapsedMins) {
        int hours = ((int)elapsedMins) / 60;
        int mins = ((int)elapsedMins) % 60;
        double fracMins = elapsedMins - ((long)elapsedMins);
        int secs =  (int)(fracMins * 60);
        return String.format(
                "%2d:%02d:%02d",
                hours,
                (mins % 60),
                secs % 60);
    }

    private int getFatMaxxerColor(int p) {
        return ContextCompat.getColor(this, p);
    }

    private void updateFabState(boolean connected) {
        if (binding == null || binding.fabConnect == null) return;
        if (connected) {
            binding.fabConnect.setText("Disconnect");
            binding.fabConnect.setIconResource(android.R.drawable.stat_sys_data_bluetooth);
            binding.fabConnect.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    getFatMaxxerColor(R.color.colorMedIntensity)));
        } else {
            binding.fabConnect.setText("Connect");
            binding.fabConnect.setIconResource(android.R.drawable.stat_sys_data_bluetooth);
            binding.fabConnect.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor("#00E676")));
        }
    }

    private boolean timeForHRplot(boolean realTime) {
        // FIXME: do a true rolling average here?
        //Log.d(TAG,"since prev HR plot "+(currentTimeMS - prevHRPlotTimestampMS));
        boolean result = starting || realTime || (currentTimeMS - prevHRPlotTimestampMS) >= 1000;
        if (result) {
            prevHRPlotTimestampMS = currentTimeMS;
        }
        return result;
    }

    private boolean timeForRRplot(boolean realTime) {
        boolean result = starting || realTime || (currentTimeMS - prevRRPlotTimestampMS) >= 10000;
        prevRRPlotTimestampMS = currentTimeMS;
        return result;
    }

    private boolean timeForUIupdate(boolean realTime) {
        //Log.d(TAG,"since prev feature plot "+(currentTimeMS - prevFeatPlotTimestampMS));
        boolean result = starting || realTime || (currentTimeMS - prevFeatPlotTimestampMS) >= 60000;
        if (result) {
            //Log.d(TAG, "...time for UI update");
            prevFeatPlotTimestampMS = currentTimeMS;
        }
        return result;
    }

    private void tryPolarConnect(String tmpDeviceID) {
        Log.d(TAG,"tryPolarConnect to "+tmpDeviceID);
        try {
            String text = getString(R.string.TryingToConnectToHeartRateSensor)+": " + tmpDeviceID;
            //if (text_view != null) text_view.setText(text);
            Toast.makeText(this, text, Toast.LENGTH_LONG).show();
            api.connectToDevice(tmpDeviceID);
        } catch (PolarInvalidArgument polarInvalidArgument) {
            String msg = "PolarInvalidArgument: " + polarInvalidArgument;
            if (text_view != null) text_view.setText(msg);
            logException("tryPolarConnect Exception", polarInvalidArgument);
        }
    }

    private void setConnectedDeviceAsPreferred() {
        Log.d(TAG,"Set connected device as preferred...");
        sharedPreferences.edit().putString(POLAR_DEVICE_ID_PREFERENCE_STRING, SENSOR_ID).apply();
    }

    private void tryPolarConnectToPreferredDevice() {
        Log.d(TAG,"tryPolarConnect to preferred device...");
        String tmpDeviceID = sharedPreferences.getString(POLAR_DEVICE_ID_PREFERENCE_STRING,"");
        if (tmpDeviceID.length()>0) {
            tryPolarConnect(tmpDeviceID);
        } else {
            if (text_view != null) if (text_view != null) text_view.setText("No device ID set");
        }
    }

    private File getLogsDir() {
        return sessionLogger.getLogsDir();
    }



    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
            if (text_view != null) text_view.setText("Permission update: "+requestCode);
            if (requestCode == 1) {
                Log.d(TAG, "bt ready");
            }
    }

    @Override
    public void onPause() {
            if (text_view != null) text_view.setText("Paused");
            super.onPause();
            // // api.backgroundEntered(); // removed in SDK 5.x // removed in SDK 5.x
    }

    @Override
    public void onResume() {
            if (text_view != null) text_view.setText("Resumed");
            super.onResume();
            // // api.foregroundEntered(); // removed in SDK 5.x // removed in SDK 5.x
    }

    private void takeScreenshot() {
            Date now = new Date();
            android.text.format.DateFormat.format("yyyy-MM-dd_hh:mm:ss", now);
            try {
                // create bitmap screen capture
                View v1 = getWindow().getDecorView().getRootView();
                v1.setDrawingCacheEnabled(true);
                Bitmap bitmap = Bitmap.createBitmap(v1.getDrawingCache());
                v1.setDrawingCacheEnabled(false);
                // image naming and path  to include sd card  appending name you choose for file
                String mPath = "FatMaxxer_"+ now + ".jpg";
                File imageDir = this.getExternalFilesDir(null);
                // minimum version
                //File imageDir = this.getExternalFilesDir(Environment.DIRECTORY_SCREENSHOTS);
                File imageFile = new File(imageDir, mPath);
                FileOutputStream outputStream = new FileOutputStream(imageFile);
                int quality = 100;
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream);
                outputStream.flush();
                outputStream.close();
                String msg = getString(R.string.ScreenshotSavedIn) +imageFile.getCanonicalPath();
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                Log.i(TAG,msg);
                //openScreenshot(imageFile);
            } catch (Throwable e) {
                logException("screenShot ",e);
            }
    }

    @Override
    public void onDestroy() {
            if (text_view != null) text_view.setText("Destroyed");
            Toast.makeText(this, R.string.FatMaxxerAppClosed, Toast.LENGTH_SHORT).show();
            super.onDestroy();
            Intent i = new Intent(MainActivity.this, LocalService.class);
            i.setAction("STOP");
            Log.d(TAG,"intent to stop local service "+i);
            MainActivity.this.stopService(i);
            api.shutDown();
    }

}
