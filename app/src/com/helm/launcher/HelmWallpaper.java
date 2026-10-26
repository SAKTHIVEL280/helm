package com.helm.launcher;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

/**
 * Helm as a live wallpaper: Helm's wallpaper everywhere, plus a live Nothing-style clock and date
 * while the phone is locked. Android doesn't let apps replace the lock screen; this is the honest way
 * to put a real, ticking clock on it. It redraws once a minute, only while visible.
 */
public final class HelmWallpaper extends WallpaperService {
    @Override
    public Engine onCreateEngine() { return new LockEngine(); }

    private final class LockEngine extends Engine implements SharedPreferences.OnSharedPreferenceChangeListener {
        private final Handler main = new Handler(Looper.getMainLooper());
        private final LockArt art = new LockArt();
        private boolean visible, registered;
        private Bitmap generated;
        private int w, h, genId;
        private boolean generating;
        private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        private android.app.WallpaperColors colors;

        private final Runnable tick = new Runnable() {
            @Override
            public void run() {
                draw();
                if (visible) main.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000 + 20);
            }
        };

        private final BroadcastReceiver screen = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) { draw(); }
        };

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            Ui.init(HelmWallpaper.this);
            Prefs.get(HelmWallpaper.this).sp.registerOnSharedPreferenceChangeListener(this);
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_USER_PRESENT);
            f.addAction(Intent.ACTION_TIME_CHANGED);
            f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            registerReceiver(screen, f, Context.RECEIVER_NOT_EXPORTED);
            registered = true;
            load();
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            main.removeCallbacks(tick);
            Prefs.get(HelmWallpaper.this).sp.unregisterOnSharedPreferenceChangeListener(this);
            if (registered) unregisterReceiver(screen);
            registered = false;
            worker.shutdownNow();
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
            if (key == null) return;
            if (key.startsWith("lock_") || key.startsWith("wp_") || key.equals(Prefs.THEME) || key.equals(Prefs.ACCENT_MODE)
                    || key.equals(Prefs.ACCENT_CUSTOM)) {
                if (key.startsWith("wp_") && !key.endsWith("_cache")) {
                    generated = null;
                    genId++;
                }
                load();
            }
        }

        /** Makes sure Helm's wallpaper is ready, or generates an Atmosphere when Helm uses the phone's. */
        private void load() {
            Palette p = Palette.resolve(HelmWallpaper.this);
            WallpaperStore ws = WallpaperStore.get(HelmWallpaper.this);
            Prefs prefs = Prefs.get(HelmWallpaper.this);
            if (prefs.lockSource() == 0 && ws.hasOwnBitmap()) {
                ws.ensure(p.dark, this::draw);
                return;
            }
            if (prefs.lockSource() == 2 || generated != null || generating || w == 0) {
                draw();
                return;
            }
            final int gw = Math.min(w, 1080), gh = Math.round(h * (gw / (float) w));
            final int id = genId;
            generating = true;
            try {
                worker.execute(() -> {
                    Bitmap b;
                    try {
                        b = Atmosphere.generate(gw, gh, prefs.wpSeed(), prefs.wpMood(), true, prefs.wpGrain(), prefs.wpLines());
                    } catch (Throwable t) {
                        b = null; // Out of memory or similar: the plain field is drawn instead.
                    }
                    Bitmap done = b;
                    main.post(() -> {
                        generating = false;
                        if (id != genId) {
                            load(); // Settings changed while generating: make the newer one.
                            return;
                        }
                        generated = done;
                        draw();
                    });
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                generating = false;
            }
        }

        @Override
        public android.app.WallpaperColors onComputeColors() { return colors; }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            boolean first = w == 0;
            w = width;
            h = height;
            if (first) load(); else draw();
        }

        @Override
        public void onSurfaceRedrawNeeded(SurfaceHolder holder) { draw(); }

        @Override
        public void onVisibilityChanged(boolean v) {
            visible = v;
            main.removeCallbacks(tick);
            if (v) tick.run();
        }

        /** Tells One UI the wallpaper's colours, so its clock and status bar pick readable ink. */
        private Bitmap colorsFor;

        private void updateColors(Bitmap wall, boolean light) {
            if (colors != null && wall == colorsFor) return;
            colorsFor = wall;
            if (wall != null) {
                try {
                    colors = android.app.WallpaperColors.fromBitmap(wall);
                } catch (RuntimeException e) {
                    colors = null;
                }
            }
            if (colors == null || wall == null) {
                int base = light ? 0xFFE6E6E3 : 0xFF000000;
                colors = new android.app.WallpaperColors(android.graphics.Color.valueOf(base), null, null);
            }
            notifyColorsChanged();
        }

        private void draw() {
            if (w == 0 || h == 0) return;
            SurfaceHolder holder = getSurfaceHolder();
            Canvas c = null;
            try {
                c = holder.lockHardwareCanvas();
                if (c == null) return;
                Prefs prefs = Prefs.get(HelmWallpaper.this);
                WallpaperStore ws = WallpaperStore.get(HelmWallpaper.this);
                Palette p = Palette.resolve(HelmWallpaper.this);
                Bitmap wall;
                boolean light;
                if (prefs.lockSource() == 2) {
                    wall = null;
                    light = false;
                } else if (prefs.lockSource() == 0 && ws.hasOwnBitmap() && ws.sharp() != null) {
                    wall = ws.sharp();
                    light = ws.wallpaperLight();
                } else {
                    wall = generated;
                    light = false;
                }
                KeyguardManager km = getSystemService(KeyguardManager.class);
                boolean locked = isPreview() || (km != null && km.isKeyguardLocked());
                art.draw(HelmWallpaper.this, c, w, h, wall, light, p.accent, locked);
                updateColors(wall, light);
            } catch (RuntimeException ignored) {
                // Surface went away mid-frame; the next tick redraws.
            } finally {
                if (c != null) {
                    try {
                        holder.unlockCanvasAndPost(c);
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        }
    }
}
