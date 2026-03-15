package online.fatmaxxer.publicRelease1;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

import online.fatmaxxer.fatmaxxer.R;

/**
 * Manages audio feedback for FatMaxxer: TTS voice updates and artifact alert sounds.
 *
 * Determines when and what to speak based on current training metrics,
 * zone changes, and configurable thresholds from SharedPreferences.
 */
public class AudioFeedbackManager {

    private TextToSpeech tts;
    private MediaPlayer artifactSound;
    private final SharedPreferences prefs;
    private final Context context;
    private long prevSpokenUpdateMS = 0;
    private boolean initialized = false;

    public AudioFeedbackManager(Context context, SharedPreferences prefs) {
        this.context = context;
        this.prefs = prefs;
    }

    /**
     * Initialize TTS engine and artifact sound. Call from onCreate.
     */
    public void initialize(Runnable onReady) {
        tts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.UK);
                initialized = true;
                if (onReady != null) onReady.run();
            }
        });
        artifactSound = MediaPlayer.create(context, R.raw.artifact);
        artifactSound.setVolume(100, 100);
    }

    /**
     * Play the artifact alert sound if audio is enabled.
     */
    public void playArtifactSound() {
        if (prefs.getBoolean(MainActivity.AUDIO_OUTPUT_ENABLED, false)) {
            artifactSound.start();
        }
    }

    /**
     * Determine whether to speak an audio update and what to say,
     * based on current training metrics and user preferences.
     */
    public void update(TrainingState state, long currentTimeMS) {
        long timeSinceLastUpdate = (currentTimeMS - prevSpokenUpdateMS) / 1000;

        int minWait = Integer.parseInt(prefs.getString("minUpdateWaitSeconds", "15"));
        int maxWait = Integer.parseInt(prefs.getString("maxUpdateWaitSeconds", "60"));
        int upperOptimalHR = Integer.parseInt(prefs.getString("upperOptimalHRthreshold", "130"));
        int upperRestingHR = Integer.parseInt(prefs.getString("upperRestingHRthreshold", "90"));
        double artifactAlarm = Double.parseDouble(prefs.getString("artifactsRateAlarmThreshold", "5"));
        double upperOptimalA1 = Double.parseDouble(prefs.getString("upperOptimalAlpha1Threshold", "1.0"));
        double lowerOptimalA1 = Double.parseDouble(prefs.getString("lowerOptimalAlpha1Threshold", "0.85"));
        boolean onZoneChange = prefs.getBoolean("audioOutputOnZoneChange", false);

        String artifactsUpdate = "";
        String featuresUpdate = "";

        if (onZoneChange) {
            if (state.zone != state.zonePrev) {
                featuresUpdate = state.alpha1 + " " + state.hr;
                artifactsUpdate = context.getString(R.string.Dropped_TextToSpeech) + " "
                        + state.artifactsPercent + " " + context.getString(R.string.Percent_TextToSpeech);
            }
        } else if (state.elapsedSeconds > 30 && timeSinceLastUpdate > minWait) {
            if (state.artifactsPercent > 0) {
                artifactsUpdate = context.getString(R.string.Dropped_TextToSpeech) + " "
                        + state.artifactsPercent + " " + context.getString(R.string.Percent_TextToSpeech);
            }
            if (state.hr > upperOptimalHR || state.alpha1 < lowerOptimalA1) {
                featuresUpdate = state.alpha1 + " " + state.hr;
            } else if (state.hr > (upperOptimalHR - 10) || state.alpha1 < upperOptimalA1) {
                featuresUpdate = context.getString(R.string.AlphaOne_TextToSpeech) + ", "
                        + state.alpha1 + " " + context.getString(R.string.HeartRate_TextToSpeech) + " " + state.hr;
            } else if (state.artifactsPercent > artifactAlarm
                    || (state.hr > upperRestingHR && timeSinceLastUpdate >= maxWait)) {
                featuresUpdate = context.getString(R.string.AlphaOne_TextToSpeech) + " "
                        + state.alpha1 + " " + context.getString(R.string.HeartRate_TextToSpeechFull) + " " + state.hr;
            } else if (state.artifactsPercent > artifactAlarm || timeSinceLastUpdate >= maxWait) {
                featuresUpdate = context.getString(R.string.HeartRateFull_TextToSpeech) + " "
                        + state.hr + ". " + context.getString(R.string.HeartRateVariabilityAbbrev_TextToSpeech)
                        + " " + state.rmssd;
            }
        }

        if (featuresUpdate.length() > 0) {
            prevSpokenUpdateMS = currentTimeMS;
            String message;
            if (state.artifactsPercent > artifactAlarm) {
                message = artifactsUpdate + " " + featuresUpdate;
            } else {
                message = featuresUpdate + ", " + artifactsUpdate;
            }
            speak(message);
        }
    }

    private void speak(String text) {
        if (initialized && prefs.getBoolean(MainActivity.AUDIO_OUTPUT_ENABLED, false)) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null);
        }
    }

    /**
     * Speak a message regardless of timing constraints (e.g. "Voice output ready").
     */
    public void speakImmediate(String text) {
        speak(text);
    }

    /**
     * Snapshot of current training state, passed to audio update logic.
     */
    public static class TrainingState {
        public final double alpha1;
        public final int hr;
        public final int rmssd;
        public final int artifactsPercent;
        public final long elapsedSeconds;
        public final int zone;
        public final int zonePrev;

        public TrainingState(double alpha1, int hr, int rmssd, int artifactsPercent,
                             long elapsedSeconds, int zone, int zonePrev) {
            this.alpha1 = alpha1;
            this.hr = hr;
            this.rmssd = rmssd;
            this.artifactsPercent = artifactsPercent;
            this.elapsedSeconds = elapsedSeconds;
            this.zone = zone;
            this.zonePrev = zonePrev;
        }
    }
}
