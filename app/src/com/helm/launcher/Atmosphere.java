package com.helm.launcher;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import java.util.Random;

/**
 * Generated wallpapers in the manner of Nothing OS 5's wallpaper studies: soft colour fields,
 * a glowing ring or two, hairline circles and a dashed construction line, a faint dot grid and film grain.
 */
final class Atmosphere {
    static final String[] MOODS = {"Mist", "Ember", "Ocean", "Citrus", "Dusk", "Mono"};

    private static final int[][] COLOURS = {
            {0xFF9FD8A8, 0xFFB7E3C0, 0xFFA9A6D9, 0xFFE58FD6, 0xFF8FB6D9, 0xFF9ED67F},
            {0xFFFF7A59, 0xFFFFB36B, 0xFFF25C8A, 0xFFFFD27A, 0xFFE8654A, 0xFFFFA0B4},
            {0xFF4F7DF2, 0xFF7FB3FF, 0xFF5CE1E6, 0xFFA4B9FF, 0xFF3F5FCF, 0xFF9AD9F2},
            {0xFFE8F25C, 0xFFFF6FB5, 0xFFFFB0D9, 0xFFF5E663, 0xFFFF8FCF, 0xFFB8B8B8},
            {0xFF6B4FD8, 0xFFC86BE6, 0xFFFF8AB3, 0xFF4B3BA0, 0xFF9C7BFF, 0xFFE07BC8},
            {0xFFBDBDBD, 0xFF8E8E8E, 0xFFE8E8E8, 0xFFA6A6A6, 0xFF707070, 0xFFD2D2D2},
    };
    private static final int[] BASE_LIGHT = {0xFFDDDDD8, 0xFFF1E6DE, 0xFFDCE3EE, 0xFFE9E9E4, 0xFFE4DEEC, 0xFFD8D8D8};
    private static final int[] BASE_DARK = {0xFF0E100F, 0xFF120C0A, 0xFF080B12, 0xFF0F0F0A, 0xFF100C1A, 0xFF0B0B0B};

    private Atmosphere() {}

    static Bitmap generate(int w, int h, long seed, int mood, boolean dark, boolean grain, boolean lines) {
        mood = Ui.clamp(mood, 0, MOODS.length - 1);
        Random rnd = new Random(seed * 7919L + mood);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(dark ? BASE_DARK[mood] : BASE_LIGHT[mood]);
        int[] cols = COLOURS[mood];
        float dim = dark ? 0.62f : 1f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float s = Math.max(w, h);

        // Colour fields.
        int blobs = 5 + rnd.nextInt(3);
        for (int i = 0; i < blobs; i++) {
            float cx = rnd.nextFloat() * w, cy = rnd.nextFloat() * h;
            float r = s * (0.22f + rnd.nextFloat() * 0.32f);
            int col = shade(cols[rnd.nextInt(cols.length)], dim);
            p.setShader(new RadialGradient(cx, cy, r,
                    new int[]{col, Palette.alpha(col, 0.55f), Palette.alpha(col, 0f)},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, p);
        }
        p.setShader(null);

        // Glowing rings: the soft donuts in Nothing's boards.
        int rings = 1 + rnd.nextInt(2);
        for (int i = 0; i < rings; i++) {
            float cx = w * (0.2f + rnd.nextFloat() * 0.6f), cy = h * (0.1f + rnd.nextFloat() * 0.8f);
            float r = w * (0.22f + rnd.nextFloat() * 0.22f);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(r * 0.55f);
            p.setColor(Palette.alpha(shade(cols[rnd.nextInt(cols.length)], dim), 0.85f));
            p.setMaskFilter(new BlurMaskFilter(r * 0.35f, BlurMaskFilter.Blur.NORMAL));
            c.drawCircle(cx, cy, r, p);
        }
        p.setMaskFilter(null);

        if (lines) {
            int ink = dark ? Color.WHITE : Color.WHITE;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1.5f, w / 720f));
            p.setColor(Palette.alpha(ink, dark ? 0.16f : 0.42f));
            for (int i = 0; i < 3; i++) {
                c.drawCircle(w * (0.3f + rnd.nextFloat() * 0.9f), h * rnd.nextFloat(), w * (0.35f + rnd.nextFloat() * 0.4f), p);
            }
            p.setPathEffect(new DashPathEffect(new float[]{w / 45f, w / 30f}, 0));
            float x0 = w * rnd.nextFloat() * 0.4f;
            c.drawLine(x0, -10, x0 + w * (0.25f + rnd.nextFloat() * 0.3f), h + 10, p);
            p.setPathEffect(null);

            p.setStyle(Paint.Style.FILL);
            p.setColor(Palette.alpha(dark ? Color.WHITE : Color.BLACK, dark ? 0.07f : 0.05f));
            float step = w / 22f, dot = Math.max(1.2f, w / 900f);
            for (float y = step / 2; y < h; y += step) {
                for (float x = step / 2; x < w; x += step) c.drawCircle(x, y, dot, p);
            }
        }

        if (grain) addGrain(b, rnd, dark ? 9 : 12);
        return b;
    }

    private static int shade(int c, float k) {
        return Color.rgb(Math.round(Color.red(c) * k), Math.round(Color.green(c) * k), Math.round(Color.blue(c) * k));
    }

    private static void addGrain(Bitmap b, Random rnd, int amount) {
        int w = b.getWidth(), h = b.getHeight();
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            b.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                int n = rnd.nextInt(amount * 2 + 1) - amount;
                int c = row[x];
                int r = Ui.clamp(((c >> 16) & 0xFF) + n, 0, 255);
                int g = Ui.clamp(((c >> 8) & 0xFF) + n, 0, 255);
                int bl = Ui.clamp((c & 0xFF) + n, 0, 255);
                row[x] = 0xFF000000 | (r << 16) | (g << 8) | bl;
            }
            b.setPixels(row, 0, w, 0, y, w, 1);
        }
    }
}
