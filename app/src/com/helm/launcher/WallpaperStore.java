package com.helm.launcher;

import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Helm draws its own wallpaper so translucent surfaces can show real frost: Android doesn't let apps
 * read the system wallpaper's pixels. Keeps the sharp image, a small blurred copy for frost, and the
 * colour-engine accent picked from it.
 */
final class WallpaperStore {
    private static final float FROST_SCALE = 1f / 6f;
    private static WallpaperStore instance;

    static WallpaperStore get(Context c) {
        if (instance == null) instance = new WallpaperStore(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Runnable> waiting = new ArrayList<>();
    private int screenW = 1080, screenH = 2340;
    private Bitmap sharp, frost;
    private String signature = "";
    private boolean loading, wantDark;
    private int hue = -1;
    private float sat;
    private boolean light;

    private WallpaperStore(Context c) {
        ctx = c;
        Prefs p = Prefs.get(c);
        int cached = p.sp.getInt(Prefs.WP_ACCENT_CACHE, 0);
        if (cached != 0) setAccentFrom(cached);
        light = p.sp.getBoolean(Prefs.WP_LIGHT_CACHE, true);
    }

    void setScreen(int w, int h) {
        if (w > 0 && h > 0) {
            screenW = Math.min(w, 1440);
            screenH = Math.round(h * (screenW / (float) w));
        }
    }

    boolean hasOwnBitmap() { return Prefs.get(ctx).wpSource() != Prefs.WP_SYSTEM; }

    Bitmap sharp() { return sharp; }

    Bitmap frost() { return frost; }

    float frostScale() { return frost == null || sharp == null ? 1f : frost.getWidth() / (float) sharp.getWidth(); }

    int screenWidth() { return screenW; }

    boolean wallpaperLight() { return light; }

    /** The colour engine: the wallpaper's dominant colour, toned for legibility in this theme. */
    int accent(boolean dark) {
        if (hue < 0 || sat < 0.12f) return Palette.RED;
        float s = Math.max(0.42f, Math.min(0.75f, sat));
        float v = dark ? 0.82f : 0.60f;
        return Color.HSVToColor(new float[]{hue, s, v});
    }

    private String wantedSignature(boolean dark) {
        Prefs p = Prefs.get(ctx);
        int src = p.wpSource();
        StringBuilder s = new StringBuilder().append(src).append('|').append(screenW).append('x').append(screenH)
                .append('|').append(p.frost());
        switch (src) {
            case Prefs.WP_ATMOSPHERE:
                s.append('|').append(p.wpMood()).append('|').append(p.wpSeed()).append('|').append(p.wpGrain())
                        .append('|').append(p.wpLines()).append('|').append(dark);
                break;
            case Prefs.WP_PHOTO: s.append('|').append(p.wpPhotoVersion()); break;
            case Prefs.WP_SOLID_COLOUR: s.append('|').append(p.wpSolid()); break;
            default: break;
        }
        return s.toString();
    }

    /** Makes sure the current wallpaper is loaded or generated; {@code done} runs on the main thread. */
    void ensure(boolean dark, Runnable done) {
        wantDark = dark;
        String want = wantedSignature(dark);
        // The system wallpaper can change behind our back, so its colours are always re-read (cheap).
        if (want.equals(signature) && sharp != null && hasOwnBitmap()) {
            if (done != null) done.run();
            return;
        }
        if (done != null) waiting.add(done);
        if (loading) return;
        loading = true;
        final int src = Prefs.get(ctx).wpSource();
        worker.execute(() -> {
            Bitmap s = null, f = null;
            int primary = 0;
            boolean isLight = light;
            try {
                if (src == Prefs.WP_SYSTEM) {
                    WallpaperColors wc = WallpaperManager.getInstance(ctx).getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
                    if (wc != null) {
                        primary = wc.getPrimaryColor().toArgb();
                        isLight = (wc.getColorHints() & WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0;
                    }
                } else {
                    s = loadOrMake(want, src, dark);
                    Bitmap small = Bitmap.createScaledBitmap(s, Math.max(8, Math.round(s.getWidth() * FROST_SCALE)),
                            Math.max(8, Math.round(s.getHeight() * FROST_SCALE)), true);
                    int radius = 2 + Math.round(Prefs.get(ctx).frost() * 0.14f);
                    f = Blur.boxBlur(small, radius);
                    primary = dominant(small);
                    isLight = averageLuminance(small) > 0.58f;
                }
            } catch (Throwable t) {
                s = null;
                f = null;
            }
            final Bitmap fs = s, ff = f;
            final int fp = primary;
            final boolean fl = isLight;
            main.post(() -> {
                sharp = fs;
                frost = ff;
                signature = want;
                light = fl;
                if (fp != 0) setAccentFrom(fp); else hue = -1;
                Prefs.get(ctx).sp.edit().putInt(Prefs.WP_ACCENT_CACHE, fp).putBoolean(Prefs.WP_LIGHT_CACHE, fl).apply();
                loading = false;
                // Settings changed while we were busy: keep everyone waiting for the newest wallpaper,
                // otherwise they'd take this stale one's colours and never hear about the right one.
                if (!want.equals(wantedSignature(wantDark))) {
                    ensure(wantDark, null);
                    return;
                }
                List<Runnable> run = new ArrayList<>(waiting);
                waiting.clear();
                for (Runnable r : run) r.run();
            });
        });
    }

    private Bitmap loadOrMake(String want, int src, boolean dark) throws Exception {
        File img = new File(ctx.getFilesDir(), "wallpaper.png");
        File sig = new File(ctx.getFilesDir(), "wallpaper.sig");
        if (img.exists() && sig.exists()
                && new String(Files.readAllBytes(sig.toPath()), StandardCharsets.UTF_8).equals(want)) {
            Bitmap b = BitmapFactory.decodeFile(img.getPath());
            if (b != null) return b;
        }
        Prefs p = Prefs.get(ctx);
        Bitmap b;
        if (src == Prefs.WP_PHOTO) {
            b = BitmapFactory.decodeFile(new File(ctx.getFilesDir(), "photo_src.jpg").getPath());
            if (b == null) b = Atmosphere.generate(screenW, screenH, p.wpSeed(), p.wpMood(), dark, p.wpGrain(), p.wpLines());
            else b = cover(b, screenW, screenH);
        } else if (src == Prefs.WP_SOLID_COLOUR) {
            b = Bitmap.createBitmap(screenW / 4, screenH / 4, Bitmap.Config.ARGB_8888);
            b.eraseColor(p.wpSolid());
        } else {
            b = Atmosphere.generate(screenW, screenH, p.wpSeed(), p.wpMood(), dark, p.wpGrain(), p.wpLines());
        }
        try (OutputStream out = new FileOutputStream(img)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        Files.write(sig.toPath(), want.getBytes(StandardCharsets.UTF_8));
        return b;
    }

    /** Copies a picked photo in, cropped to the screen. Runs {@code done(ok)} on the main thread. */
    void importPhoto(Uri uri, java.util.function.Consumer<Boolean> done) {
        worker.execute(() -> {
            boolean ok = false;
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                    BitmapFactory.decodeStream(in, null, o);
                }
                int sample = 1;
                while (o.outWidth / (sample * 2) >= screenW && o.outHeight / (sample * 2) >= screenH) sample *= 2;
                BitmapFactory.Options d = new BitmapFactory.Options();
                d.inSampleSize = sample;
                Bitmap raw;
                try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                    raw = BitmapFactory.decodeStream(in, null, d);
                }
                if (raw != null) {
                    Bitmap fitted = cover(raw, screenW, screenH);
                    try (OutputStream out = new FileOutputStream(new File(ctx.getFilesDir(), "photo_src.jpg"))) {
                        fitted.compress(Bitmap.CompressFormat.JPEG, 92, out);
                    }
                    ok = true;
                }
            } catch (Exception ignored) {
            }
            final boolean result = ok;
            main.post(() -> done.accept(result));
        });
    }

    /** Sets the phone's own wallpaper (home and lock) to Helm's, so the lock screen matches. */
    void applyToSystem(java.util.function.Consumer<Boolean> done) {
        final Bitmap b = sharp;
        if (b == null) {
            done.accept(false);
            return;
        }
        worker.execute(() -> {
            boolean ok;
            try {
                WallpaperManager.getInstance(ctx).setBitmap(b, null, true,
                        WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
                ok = true;
            } catch (Exception e) {
                ok = false;
            }
            final boolean r = ok;
            main.post(() -> done.accept(r));
        });
    }

    static Bitmap cover(Bitmap src, int w, int h) {
        float scale = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate((w - src.getWidth() * scale) / 2f, (h - src.getHeight() * scale) / 2f);
        new Canvas(out).drawBitmap(src, m, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        return out;
    }

    private void setAccentFrom(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hue = Math.round(hsv[0]);
        sat = hsv[1];
    }

    /** Most common saturated hue, weighted by how vivid each pixel is. */
    private static int dominant(Bitmap b) {
        Bitmap s = Bitmap.createScaledBitmap(b, 48, Math.max(8, 48 * b.getHeight() / b.getWidth()), true);
        int w = s.getWidth(), h = s.getHeight();
        int[] px = new int[w * h];
        s.getPixels(px, 0, w, 0, 0, w, h);
        float[] weight = new float[36];
        float[][] sum = new float[36][3];
        float[] hsv = new float[3];
        for (int c : px) {
            Color.colorToHSV(c, hsv);
            if (hsv[1] < 0.18f || hsv[2] < 0.18f) continue;
            int bin = Math.min(35, (int) (hsv[0] / 10f));
            float wgt = hsv[1] * hsv[2];
            weight[bin] += wgt;
            sum[bin][0] += Color.red(c) * wgt;
            sum[bin][1] += Color.green(c) * wgt;
            sum[bin][2] += Color.blue(c) * wgt;
        }
        int best = -1;
        for (int i = 0; i < 36; i++) if (best < 0 || weight[i] > weight[best]) best = i;
        if (best < 0 || weight[best] < px.length * 0.02f) return 0;
        float wsum = weight[best];
        return Color.rgb(Math.round(sum[best][0] / wsum), Math.round(sum[best][1] / wsum), Math.round(sum[best][2] / wsum));
    }

    private static float averageLuminance(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        double total = 0;
        for (int c : px) total += Palette.luminance(c);
        return (float) (total / px.length);
    }
}
