package com.helm.launcher;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/** What's playing, from Android's media sessions (needs Helm's notification access). */
final class Media {
    interface Listener { void onMediaChanged(); }

    private static Media instance;

    static Media get(Context c) {
        if (instance == null) instance = new Media(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private MediaController current;
    private boolean watching;
    // Snapshots refreshed only from callbacks: widgets read these every frame, so no binder calls there.
    private MediaMetadata meta;
    private PlaybackState state;
    private Bitmap art;
    private boolean granted;
    private long grantedAt;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = this::pick;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState s) {
            // Another session may have started playing while this one paused.
            pick(null);
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            snapshot();
            dispatch();
        }

        @Override
        public void onSessionDestroyed() { pick(null); }
    };

    private Media(Context c) { ctx = c; }

    void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
        watch();
    }

    void removeListener(Listener l) { listeners.remove(l); }

    void onListenerConnected() {
        main.post(() -> {
            watching = false;
            watch();
        });
    }

    private void watch() {
        if (watching || !HelmNotifications.granted(ctx)) return;
        granted = true;
        grantedAt = SystemClock.uptimeMillis();
        MediaSessionManager m = ctx.getSystemService(MediaSessionManager.class);
        if (m == null) return;
        try {
            m.addOnActiveSessionsChangedListener(sessions, HelmNotifications.component(ctx), main);
            watching = true;
            pick(m.getActiveSessions(HelmNotifications.component(ctx)));
        } catch (SecurityException e) {
            watching = false;
        }
    }

    /** Prefers a session that's playing; otherwise the most recent one. */
    private void pick(List<MediaController> list) {
        if (list == null) {
            MediaSessionManager m = ctx.getSystemService(MediaSessionManager.class);
            try {
                list = m == null ? null : m.getActiveSessions(HelmNotifications.component(ctx));
            } catch (SecurityException e) {
                list = null;
            }
        }
        MediaController best = null;
        if (list != null) {
            for (MediaController mc : list) {
                PlaybackState s = mc.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                    best = mc;
                    break;
                }
            }
            if (best == null && !list.isEmpty()) best = list.get(0);
        }
        boolean same = best != null && current != null && best.getSessionToken().equals(current.getSessionToken());
        if (!same) {
            if (current != null) current.unregisterCallback(callback);
            current = best;
            if (current != null) current.registerCallback(callback, main);
        }
        snapshot();
        dispatch();
    }

    private void snapshot() {
        try {
            meta = current == null ? null : current.getMetadata();
            state = current == null ? null : current.getPlaybackState();
        } catch (RuntimeException e) {
            meta = null;
            state = null;
        }
        Bitmap b = null;
        if (meta != null) {
            b = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (b == null) b = meta.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (b == null) b = meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        }
        art = b;
    }

    private void dispatch() {
        for (Listener l : new ArrayList<>(listeners)) l.onMediaChanged();
    }

    /** Whether notification access is on; re-checked at most every few seconds. */
    boolean available() {
        long now = SystemClock.uptimeMillis();
        if (now - grantedAt > 3000 || grantedAt == 0) {
            grantedAt = now;
            boolean was = granted;
            granted = HelmNotifications.granted(ctx);
            if (granted && !was) watch();
        }
        return granted;
    }

    boolean hasSession() { return current != null && meta != null; }

    boolean playing() {
        PlaybackState s = state;
        return s != null && (s.getState() == PlaybackState.STATE_PLAYING || s.getState() == PlaybackState.STATE_BUFFERING);
    }

    String title() { return meta(MediaMetadata.METADATA_KEY_TITLE); }

    String artist() {
        String a = meta(MediaMetadata.METADATA_KEY_ARTIST);
        if (a.isEmpty()) a = meta(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        if (a.isEmpty()) a = meta(MediaMetadata.METADATA_KEY_ALBUM);
        return a;
    }

    String packageName() { return current == null ? null : current.getPackageName(); }

    Bitmap art() { return art; }

    /** 0..1 through the track, or -1 when unknown. */
    float progress() {
        MediaMetadata m = meta;
        PlaybackState s = state;
        if (m == null || s == null) return -1f;
        long dur = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (dur <= 0) return -1f;
        long pos = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING) {
            pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        }
        return Math.max(0f, Math.min(1f, pos / (float) dur));
    }

    private String meta(String key) {
        MediaMetadata m = meta;
        CharSequence s = m == null ? null : m.getText(key);
        return s == null ? "" : s.toString();
    }

    void playPause() {
        if (current == null) {
            // Nothing in a session: wake the last player with a media key, as headphones do.
            key(KeyEvent.KEYCODE_MEDIA_PLAY);
            return;
        }
        if (playing()) current.getTransportControls().pause();
        else current.getTransportControls().play();
    }

    void next() {
        if (current != null) current.getTransportControls().skipToNext();
        else key(KeyEvent.KEYCODE_MEDIA_NEXT);
    }

    void previous() {
        if (current != null) current.getTransportControls().skipToPrevious();
        else key(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }

    private void key(int code) {
        android.media.AudioManager am = ctx.getSystemService(android.media.AudioManager.class);
        if (am == null) return;
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }

    /** Opens the player: its own "now playing" screen if it offers one, else the app. False if there's none. */
    @SuppressWarnings("deprecation")
    boolean openPlayer() {
        if (current != null && current.getSessionActivity() != null) {
            try {
                android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
                o.setPendingIntentBackgroundActivityStartMode(android.os.Build.VERSION.SDK_INT >= 36
                        ? android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                        : android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                current.getSessionActivity().send(ctx, 0, null, null, null, null, o.toBundle());
                return true;
            } catch (Exception ignored) {
                // Fall through to the app's launcher entry.
            }
        }
        String pkg = packageName();
        Intent i = pkg == null ? null : ctx.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static Intent accessSettings() {
        return new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
    }
}
