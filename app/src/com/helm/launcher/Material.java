package com.helm.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import java.util.Random;

/**
 * Nothing OS 5's three software materials. Translucent is the new one: the wallpaper behind,
 * frosted, under a tint of about 60%, with a fine grain and a hairline edge.
 */
final class Material {
    static final int AUTO = -1, OPAQUE = 0, TRANSLUCENT = 1, TRANSPARENT = 2;

    private static final Paint FILL = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint STROKE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static Paint grain;

    static {
        STROKE.setStyle(Paint.Style.STROKE);
    }

    private Material() {}

    /** A setting of "auto" follows the theme: frosted in Translucent, solid in Classic. */
    static int resolve(int chosen, Palette p) {
        if (chosen == AUTO) return p.translucent ? TRANSLUCENT : OPAQUE;
        return chosen;
    }

    /** Frost state owned by one view, so each surface keeps its own shader matrix. */
    static final class Frost {
        private Bitmap source;
        private BitmapShader shader;
        private final Matrix m = new Matrix();
        private final Matrix g = new Matrix();
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        boolean prepare(View v) {
            Bitmap f = WallpaperStore.get(v.getContext()).frost();
            if (f == null) return false;
            if (f != source) {
                source = f;
                shader = new BitmapShader(f, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                paint.setShader(shader);
            }
            View root = v.getRootView();
            float rw = Math.max(1, root.getWidth()), rh = Math.max(1, root.getHeight());
            m.setScale(rw / f.getWidth(), rh / f.getHeight());
            g.reset();
            v.transformMatrixToGlobal(g);
            g.invert(g);
            m.postConcat(g);
            shader.setLocalMatrix(m);
            return true;
        }
    }

    static void roundRect(Canvas c, View v, Frost f, RectF r, float radius, int material, Palette p) {
        surface(c, v, f, r, radius, material, p);
    }

    static void circle(Canvas c, View v, Frost f, float cx, float cy, float radius, int material, Palette p) {
        RectF r = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
        surface(c, v, f, r, radius, material, p);
    }

    private static void surface(Canvas c, View v, Frost f, RectF r, float radius, int material, Palette p) {
        if (material == OPAQUE) {
            FILL.setColor(p.solid);
            c.drawRoundRect(r, radius, radius, FILL);
            return;
        }
        if (material == TRANSPARENT) {
            FILL.setColor(p.transparent);
            c.drawRoundRect(r, radius, radius, FILL);
            edge(c, r, radius, p);
            return;
        }
        boolean frosted = f != null && f.prepare(v);
        if (frosted) c.drawRoundRect(r, radius, radius, f.paint);
        // Without frost (system wallpaper) the tint carries more weight.
        FILL.setColor(frosted ? p.glass : Palette.alpha(p.glass, Math.min(1f, (p.glass >>> 24) / 255f * 1.35f)));
        c.drawRoundRect(r, radius, radius, FILL);
        c.drawRoundRect(r, radius, radius, grain());
        edge(c, r, radius, p);
    }

    /** Fills a whole view with frosted wallpaper and the given tint (the drawer, folder scrims). */
    static void screen(Canvas c, View v, Frost f, int tint, Palette p) {
        RectF r = new RectF(0, 0, v.getWidth(), v.getHeight());
        if (f != null && f.prepare(v)) c.drawRect(r, f.paint);
        FILL.setColor(tint);
        c.drawRect(r, FILL);
        if (p.translucent) c.drawRect(r, grain());
    }

    private static void edge(Canvas c, RectF r, float radius, Palette p) {
        STROKE.setStrokeWidth(Math.max(1f, Ui.dpf(0.8f)));
        STROKE.setColor(p.glassStroke);
        float h = STROKE.getStrokeWidth() / 2f;
        RectF in = new RectF(r.left + h, r.top + h, r.right - h, r.bottom - h);
        c.drawRoundRect(in, Math.max(0, radius - h), Math.max(0, radius - h), STROKE);
    }

    private static Paint grain() {
        if (grain == null) {
            int n = 96;
            Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
            Random r = new Random(5);
            int[] px = new int[n * n];
            for (int i = 0; i < px.length; i++) {
                int a = r.nextInt(22);
                px[i] = r.nextBoolean() ? (a << 24) | 0xFFFFFF : (a << 24);
            }
            b.setPixels(px, 0, n, 0, 0, n, n);
            grain = new Paint(Paint.ANTI_ALIAS_FLAG);
            grain.setShader(new BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        }
        return grain;
    }
}
