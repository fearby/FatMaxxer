# FatMaxxer — Modernisation Changes Guide
## fearby/FatMaxxer fork

This guide covers all changes in this improvement pack, why each was made,
and step-by-step instructions for applying them to your fork.

---

## Files in this package

```
build.gradle                                          ← root Gradle (1)
app/build.gradle                                      ← app Gradle (2)
gradle/wrapper/gradle-wrapper.properties              ← Gradle version (3)
app/src/main/AndroidManifest_CHANGES.xml              ← manifest diff (4)
app/src/main/java/online/fatmaxxer/fatmaxxer/
    FileExportHelper.java                             ← fixed log export (5)
    ChartHelper.java                                  ← MPAndroidChart setup (6)
app/src/main/res/
    xml/file_paths.xml                                ← FileProvider paths (7)
    layout/activity_main.xml                          ← modernised UI (8)
    values/themes.xml                                 ← Material 3 theme (9)
    values/colors.xml                                 ← colour palette (10)
```

---

## Change 1 — Root `build.gradle`

**What changed:** Android Gradle Plugin bumped from ~4.x to 8.4.0. `jcenter()`
removed (it's been read-only since 2021 and will eventually 404). Replaced with
`mavenCentral()` + `google()` + `jitpack.io` (for Polar SDK).

**How to apply:**
Replace the contents of `build.gradle` (root, not app/) with the provided file.

---

## Change 2 — `app/build.gradle`

**What changed:**

| Setting | Before | After | Why |
|---|---|---|---|
| compileSdk | 33 | 35 | Access Android 15 APIs |
| targetSdk | 33 | 35 | Required by Play Store from Aug 2025 |
| AGP | ~4.x | 8.4.0 | Gradle 8 compatibility, namespace support |
| Java version | 8 | 17 | Required by AGP 8+; enables modern language features |
| ViewBinding | off | on | Eliminates all `findViewById()` calls |
| GraphView | included | REMOVED | Abandoned library; author noted it as buggy |
| MPAndroidChart | — | 3.1.0 | Actively maintained replacement |
| Polar SDK | old | 6.1.0 | Android 12+ BLE permission support |
| EJML | ~0.38 | 0.43.1 | Bug fixes, performance improvements |
| Material | 1.x | 1.12.0 | Material 3 support |

**How to apply:**
Replace `app/build.gradle` entirely with the provided file.

**Important:** After replacing, sync Gradle in Android Studio (File → Sync Project
with Gradle Files). You will likely see compilation errors in `MainActivity.java`
because `GraphView` is gone — see Change 6 for the replacement.

---

## Change 3 — `gradle/wrapper/gradle-wrapper.properties`

**What changed:** Gradle version updated from 7.x to 8.8.

**How to apply:**
Replace `gradle/wrapper/gradle-wrapper.properties` with the provided file,
OR in Android Studio go to File → Project Structure → Project → Gradle Version
and set it to 8.8.

---

## Change 4 — `AndroidManifest.xml` (permissions + FileProvider)

**What changed:** This is a diff/guide file, not a direct replacement.

The key changes needed in your existing `AndroidManifest.xml`:

### 4a. Remove deprecated permissions:
```xml
<!-- REMOVE these: -->
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"/>
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"/>
<uses-permission android:name="android.permission.BLUETOOTH"/>
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN"/>
```

### 4b. Add new BLE permissions (Android 12+):
```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"
    android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<!-- Keep storage only for Android 9 and below: -->
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />
```

### 4c. Add FileProvider inside `<application>`:
```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

---

## Change 5 — `FileExportHelper.java` (NEW FILE)

**What changed:** New helper class that replaces the broken log export code.

**The problem:** The original app wrote log files to
`Environment.getExternalStorageDirectory()` and shared them as `file://` URIs.
- `file://` URIs cause `FileUriExposedException` on Android 7+
- `getExternalStorageDirectory()` is inaccessible on Android 10+ without a special
  permission that Google no longer grants

**The fix:** Write logs to `Context.getExternalFilesDir()` and share via
`FileProvider.getUriForFile()` which produces a `content://` URI.

**How to apply:**

1. Copy `FileExportHelper.java` to `app/src/main/java/online/fatmaxxer/fatmaxxer/`

2. In `MainActivity.java`, find where log files are written and update the path:
```java
// BEFORE (broken on Android 10+):
File logDir = Environment.getExternalStorageDirectory();
File rrLog = new File(logDir, "rr.log");

// AFTER:
File logDir = FileExportHelper.getLogDirectory(this);
File rrLog = new File(logDir, "rr.log");
```

3. Find where logs are shared/exported and replace:
```java
// BEFORE (causes crash on Android 7+):
Intent intent = new Intent(Intent.ACTION_SEND);
intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(logFile));

// AFTER:
Intent intent = FileExportHelper.buildShareIntent(this, logFile);
if (intent != null) {
    startActivity(Intent.createChooser(intent, "Export log"));
}
```

4. To export all logs at once:
```java
List<File> allLogs = FileExportHelper.getAllLogFiles(this);
Intent intent = FileExportHelper.buildShareMultipleIntent(this, allLogs);
if (intent != null) {
    startActivity(Intent.createChooser(intent, "Export all logs"));
}
```

---

## Change 6 — `ChartHelper.java` (NEW FILE)

**What changed:** New helper class wrapping MPAndroidChart to replace GraphView.

Matches the original data series exactly:
- Red: Heart rate (BPM) — left axis
- Green: α1 × 100 — left axis (with yellow/red threshold lines at 75 and 50)
- Magenta: RR / 5 — left axis
- Cyan: RMSSD — left axis
- Blue: Artifacts (%) — right axis

**How to apply:**

1. Copy `ChartHelper.java` to `app/src/main/java/online/fatmaxxer/fatmaxxer/`

2. In `MainActivity.java`, replace GraphView setup with:
```java
// Remove all GraphView imports and setup code
// Add:
private ChartHelper chartHelper;

// In onCreate():
chartHelper = new ChartHelper(binding.lineChart);

// When new data arrives (in your existing RR data callback):
chartHelper.addDataPoint(
    elapsedMinutes,   // float: elapsed time in minutes
    heartRate,        // float: HR in BPM
    alpha1 * 100f,    // float: α1 scaled to 0–100 for display
    rmssd,            // float: RMSSD in ms
    rrInterval,       // float: RR interval in ms
    artifactPct       // float: artifact percentage
);
```

3. Remove all `import com.jjoe64.graphview.*` imports from MainActivity.java.

---

## Change 7 — `res/xml/file_paths.xml` (NEW FILE)

**What changed:** FileProvider configuration file.

**How to apply:**
Copy to `app/src/main/res/xml/file_paths.xml`.
This file is automatically picked up by the FileProvider declaration in AndroidManifest.xml.
No code changes needed — FileExportHelper.java references it implicitly.

---

## Change 8 — `res/layout/activity_main.xml`

**What changed:** Complete layout redesign with Material 3 components.

Key improvements:
- `CoordinatorLayout` root enables proper AppBar scroll behaviour
- `MaterialToolbar` replaces the old action bar
- Connection status and elapsed time shown as `Chip` widgets in the AppBar
- α1 value given a large, colour-coded hero card (changes colour by zone)
- HR / RMSSD / Artifacts in a clean 3-column card row
- `MPAndroidChart LineChart` replaces GraphView
- `BottomAppBar` + `FloatingActionButton` for connect/settings/export

**How to apply:**
Replace `app/src/main/res/layout/activity_main.xml` entirely.

**Then update MainActivity.java to use ViewBinding:**
```java
// Remove: setContentView(R.layout.activity_main);
// Add at class level:
private ActivityMainBinding binding;

// In onCreate():
binding = ActivityMainBinding.inflate(getLayoutInflater());
setContentView(binding.getRoot());
setSupportActionBar(binding.toolbar);

// Example: update α1 display
binding.textAlpha1Value.setText(String.format(Locale.getDefault(), "%.2f", alpha1));
binding.chipZoneLabel.setText(getZoneLabel(alpha1));
updateZoneColour(alpha1);
```

**Zone colour helper:**
```java
private void updateZoneColour(float alpha1) {
    int bgColor;
    String label;
    if (alpha1 > 0.75f) {
        bgColor = getColor(R.color.zone_fat_burning);
        label = "Below VT1 — Fat burning zone";
    } else if (alpha1 > 0.50f) {
        bgColor = getColor(R.color.zone_aerobic);
        label = "VT1–VT2 — Aerobic zone";
    } else {
        bgColor = getColor(R.color.zone_anaerobic);
        label = "Above VT2 — Anaerobic zone";
    }
    binding.cardAlpha1.setCardBackgroundColor(bgColor);
    binding.chipZoneLabel.setText(label);
}
```

---

## Change 9 & 10 — `themes.xml` and `colors.xml`

**What changed:** Updated to Material 3 theme with dynamic colour support.
On Android 12+ the app will adapt its colours to the user's wallpaper automatically.

**How to apply:**
Replace `res/values/themes.xml` and `res/values/colors.xml`.
Update `AndroidManifest.xml` to reference `@style/Theme.FatMaxxer` if it doesn't already.

---

## Applying everything — recommended order

1. `gradle/wrapper/gradle-wrapper.properties`
2. Root `build.gradle`
3. `app/build.gradle`
4. Sync Gradle (expect errors — that's fine at this stage)
5. `res/xml/file_paths.xml` (new file)
6. Update `AndroidManifest.xml` (Change 4 above)
7. `FileExportHelper.java` (new file)
8. `ChartHelper.java` (new file)
9. `res/values/themes.xml` + `res/values/colors.xml`
10. `res/layout/activity_main.xml`
11. Update `MainActivity.java`:
    - Switch to ViewBinding
    - Replace GraphView with ChartHelper
    - Replace log export with FileExportHelper
    - Handle new BLE permissions (BLUETOOTH_SCAN / BLUETOOTH_CONNECT) at runtime
12. Build and test

---

## Runtime permission handling (MainActivity.java)

Android 12+ requires runtime permission requests for Bluetooth.
Add this to MainActivity's permission request flow:

```java
// In your existing permission check, add:
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    // Android 12+
    requiredPermissions = new String[]{
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.POST_NOTIFICATIONS
    };
} else {
    // Android 11 and below
    requiredPermissions = new String[]{
        Manifest.permission.ACCESS_FINE_LOCATION
    };
}
```

---

## What's NOT changed (out of scope for this pack)

- **Java → Kotlin migration** — significant effort; recommended as a follow-up
- **Architecture refactoring** (ViewModel, Repository) — recommended after Kotlin migration
- **Jetpack Compose UI** — recommended after ViewModel refactor
- **Foreground Service** for keeping BLE alive in background — consider this next
  after the above changes are stable

---

*Generated for fearby/FatMaxxer — March 2026*
