package com.thecarlauncher.radio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import com.thecarlauncher.media.MediaHub;
import com.thecarlauncher.util.Prefs;

import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Controller for Internet Radio streaming using AndroidX Media3 ExoPlayer.
 * Handles playback, audio focus, ICY metadata, error recovery, and 18 online presets.
 */
public final class InternetRadioController {
    public interface Listener {
        void onInternetRadioChanged();
    }

    public static final int PRESETS = 18;
    private static final String P_INTERNET_PRESETS = "radio_internet_presets";
    private static final String P_LAST_URL = "radio_internet_last_url";
    private static final String P_LAST_NAME = "radio_internet_last_name";

    private static InternetRadioController sInstance;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager audioManager;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final RadioStation[] presets = new RadioStation[PRESETS];

    private ExoPlayer player;
    private RadioStation currentStation;
    private String trackTitle = "";
    private String artist = "";
    private String errorMessage = null;

    private boolean active;
    private boolean buffering;
    private boolean errorState;
    private int retryCount;

    private AudioFocusRequest focusRequest;
    private boolean focusPaused;

    private final AudioManager.OnAudioFocusChangeListener focusListener = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int change) {
            if (change == AudioManager.AUDIOFOCUS_LOSS) {
                stop();
            } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                if (active && player != null && player.isPlaying()) {
                    focusPaused = true;
                    player.pause();
                }
            } else if (change == AudioManager.AUDIOFOCUS_GAIN && focusPaused) {
                focusPaused = false;
                if (active && player != null) {
                    player.play();
                }
            }
        }
    };

    public static synchronized InternetRadioController get(Context c) {
        if (sInstance == null) sInstance = new InternetRadioController(c.getApplicationContext());
        return sInstance;
    }

    private InternetRadioController(Context c) {
        app = c.getApplicationContext();
        audioManager = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        loadPresets();

        // Restore last station if saved
        String lastUrl = Prefs.str(app, P_LAST_URL, "");
        String lastName = Prefs.str(app, P_LAST_NAME, "");
        if (RadioStation.isValidUrl(lastUrl)) {
            currentStation = new RadioStation(lastName, lastUrl, "", "", "", "", "");
        }
    }

    public void addListener(Listener l) {
        listeners.addIfAbsent(l);
        l.onInternetRadioChanged();
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyChanged() {
        for (Listener l : listeners) {
            l.onInternetRadioChanged();
        }
        MediaHub.get(app).radioChanged();
    }

    private void ensurePlayer() {
        if (player == null) {
            ExoPlayer.Builder builder = new ExoPlayer.Builder(app);
            player = builder.build();

            androidx.media3.common.AudioAttributes media3Attr = new androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build();
            player.setAudioAttributes(media3Attr, true);

            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    switch (state) {
                        case Player.STATE_BUFFERING:
                            buffering = true;
                            errorState = false;
                            errorMessage = null;
                            notifyChanged();
                            break;

                        case Player.STATE_READY:
                            buffering = false;
                            errorState = false;
                            errorMessage = null;
                            retryCount = 0;
                            notifyChanged();
                            break;

                        case Player.STATE_ENDED:
                            buffering = false;
                            notifyChanged();
                            break;

                        case Player.STATE_IDLE:
                        default:
                            break;
                    }
                }

                @Override
                public void onIsPlayingChanged(boolean isPlaying) {
                    if (isPlaying) {
                        active = true;
                        buffering = false;
                        errorState = false;
                    }
                    notifyChanged();
                }

                @Override
                public void onMediaMetadataChanged(MediaMetadata mediaMetadata) {
                    extractMetadata(mediaMetadata);
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    handleError(error);
                }
            });
        }
    }

    private void extractMetadata(MediaMetadata metadata) {
        if (metadata == null) return;
        CharSequence titleSeq = metadata.title;
        CharSequence artistSeq = metadata.artist;

        String t = titleSeq != null ? titleSeq.toString().trim() : "";
        String a = artistSeq != null ? artistSeq.toString().trim() : "";

        if (!t.isEmpty() || !a.isEmpty()) {
            this.trackTitle = t;
            this.artist = a;
            notifyChanged();
        }
    }

    private void handleError(PlaybackException error) {
        buffering = false;
        errorState = true;
        if (error != null && error.getMessage() != null) {
            errorMessage = error.getMessage();
        } else {
            errorMessage = "Playback error";
        }

        // Retry limited times on connection drop
        if (active && currentStation != null && retryCount < 2) {
            retryCount++;
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (active && currentStation != null) {
                        play(currentStation);
                    }
                }
            }, 3000);
        } else {
            notifyChanged();
        }
    }

    // ---- Audio Focus -------------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    private void requestFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            android.media.AudioAttributes aa = new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(aa)
                    .setOnAudioFocusChangeListener(focusListener, main)
                    .build();
            audioManager.requestAudioFocus(focusRequest);
        } else {
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
    }


    @SuppressWarnings("deprecation")
    private void abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
        } else {
            audioManager.abandonAudioFocus(focusListener);
        }
    }

    // ---- Controls ----------------------------------------------------------------------------

    public boolean isActive() {
        return active;
    }

    public boolean isPlaying() {
        return active && player != null && player.isPlaying();
    }

    public boolean isBuffering() {
        return buffering;
    }

    public boolean isError() {
        return errorState;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public RadioStation currentStation() {
        return currentStation;
    }

    public String trackTitle() {
        return trackTitle;
    }

    public String artist() {
        return artist;
    }

    public void play(RadioStation station) {
        if (station == null || !RadioStation.isValidUrl(station.streamUrl)) {
            errorState = true;
            errorMessage = "Invalid stream URL";
            notifyChanged();
            return;
        }

        // If FM radio is running, stop it
        RadioController fmRadio = RadioController.get(app);
        if (fmRadio.isActive()) {
            fmRadio.stop();
        }

        MediaHub.get(app).onRadioStarting();
        ensurePlayer();
        requestFocus();

        currentStation = station;
        trackTitle = "";
        artist = "";
        errorMessage = null;
        errorState = false;
        buffering = true;
        active = true;

        Prefs.put(app, P_LAST_URL, station.streamUrl);
        Prefs.put(app, P_LAST_NAME, station.name);

        // Fetch logo in background if station has name/logo
        if (!station.name.isEmpty()) {
            RadioLogos.get(app).logo(station.name);
        }

        MediaItem item = MediaItem.fromUri(Uri.parse(station.streamUrl));
        player.setMediaItem(item);
        player.prepare();
        player.play();

        notifyChanged();
    }

    public void pause() {
        if (player != null && player.isPlaying()) {
            player.pause();
        }
        notifyChanged();
    }

    public void resume() {
        if (currentStation != null && RadioStation.isValidUrl(currentStation.streamUrl)) {
            if (player != null) {
                requestFocus();
                player.play();
            } else {
                play(currentStation);
            }
        }
    }

    public void stop() {
        active = false;
        buffering = false;
        focusPaused = false;
        if (player != null) {
            player.stop();
        }
        abandonFocus();
        notifyChanged();
    }

    public void toggle() {
        if (isPlaying()) {
            pause();
        } else if (currentStation != null) {
            resume();
        }
    }

    public void release() {
        stop();
        if (player != null) {
            player.release();
            player = null;
        }
    }

    // ---- Presets -----------------------------------------------------------------------------

    public RadioStation preset(int i) {
        if (i < 0 || i >= PRESETS) return null;
        return presets[i];
    }

    public int presetIndexOfCurrent() {
        if (currentStation == null) return -1;
        for (int i = 0; i < PRESETS; i++) {
            if (presets[i] != null && presets[i].equals(currentStation)) return i;
        }
        return -1;
    }

    public void savePreset(int i) {
        if (i < 0 || i >= PRESETS || currentStation == null) return;
        presets[i] = currentStation;
        savePresets();
        notifyChanged();
    }

    public void savePreset(int i, RadioStation station) {
        if (i < 0 || i >= PRESETS) return;
        presets[i] = station;
        savePresets();
        notifyChanged();
    }

    public void clearPresets() {
        Arrays.fill(presets, null);
        Prefs.put(app, P_INTERNET_PRESETS, "");
        notifyChanged();
    }

    public void recallPreset(int i) {
        if (i >= 0 && i < PRESETS && presets[i] != null) {
            play(presets[i]);
        }
    }

    private void loadPresets() {
        String raw = Prefs.str(app, P_INTERNET_PRESETS, "");
        if (raw == null || raw.isEmpty()) return;
        String[] parts = raw.split(";", -1);
        for (int i = 0; i < parts.length && i < PRESETS; i++) {
            presets[i] = RadioStation.deserialize(parts[i]);
        }
    }

    private void savePresets() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < PRESETS; i++) {
            if (i > 0) sb.append(';');
            if (presets[i] != null) {
                sb.append(presets[i].serialize());
            }
        }
        Prefs.put(app, P_INTERNET_PRESETS, sb.toString());
    }
}
