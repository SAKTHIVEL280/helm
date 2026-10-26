package com.helm.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;

/**
 * Paints app icons. Monochrome is Nothing's own look: each app's themed-icon glyph on a disc.
 * Tinted takes the colour engine's accent; Original and Greyscale keep the app's art under Helm's shape.
 */
final class IconFactory {
    /** An adaptive layer is 108 units; the visible mask is the centre 72. */
    private static final float OUTSET = 0.25f;

    static final class Spec {
        final Palette p;
        final int style, fallback, shape;
        final String pack;

        Spec(Palette p, int style, int fallback, int shape, String pack) {
            this.p = p;
            this.style = style;
            this.fallback = fallback;
            this.shape = shape;
            this.pack = pack;
        }
    }

    private IconFactory() {}

    static Bitmap render(Context c, AppEntry e, int size, Spec s) {
        int dpi = c.getResources().getDisplayMetrics().densityDpi;
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        Path shape = shape(s.shape, size);
        Palette p = s.p;

        IconPack pack = IconPack.get(c, s.pack);
        Drawable packed = pack == null ? null : pack.icon(e.component, dpi);
        if (packed != null) {
            int inset = Math.round(size * 0.02f);
            packed.setBounds(inset, inset, size - inset, size - inset);
            packed.draw(canvas);
            badge(canvas, e, size, p, paint);
            return out;
        }

        Drawable d = e.info.getIcon(dpi);
        boolean tinted = s.style == Prefs.ICONS_TINTED;
        if (s.style == Prefs.ICONS_MONO || tinted) {
            int disc = tinted ? Palette.mix(p.iconBg, p.accent, p.dark ? 0.22f : 0.14f) : p.iconBg;
            int ink = tinted ? (p.dark ? Palette.mix(p.accent, Color.WHITE, 0.35f) : Palette.mix(p.accent, Color.BLACK, 0.25f)) : p.iconFg;
            Drawable mono = d instanceof AdaptiveIconDrawable ? ((AdaptiveIconDrawable) d).getMonochrome() : null;
            paint.setColor(disc);
            canvas.drawPath(shape, paint);
            if (mono != null) {
                Bitmap glyph = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                mono = mono.mutate();
                int o = Math.round(size * OUTSET);
                mono.setBounds(-o, -o, size + o, size + o);
                mono.setColorFilter(new PorterDuffColorFilter(ink, PorterDuff.Mode.SRC_IN));
                mono.draw(new Canvas(glyph));
                fill(canvas, shape, glyph, paint, null);
            } else if (s.fallback == Prefs.FALLBACK_LETTER) {
                char first = e.label.isEmpty() ? '?' : e.label.charAt(0);
                String ch = String.valueOf(e.section != '#' ? e.section : (DotFont.has(first) ? first : '#'));
                float pitch = size * 0.085f;
                float w = DotFont.columns(ch) * pitch;
                paint.setColor(ink);
                DotFont.draw(canvas, ch, size / 2f - w / 2f, size / 2f - DotFont.ROWS * pitch / 2f, pitch, paint, null, null, null);
            } else {
                fill(canvas, shape, fullBleed(d, size, disc), paint, greyscale());
            }
        } else {
            fill(canvas, shape, fullBleed(d, size, Color.WHITE), paint,
                    s.style == Prefs.ICONS_GREY ? greyscale() : null);
        }
        badge(canvas, e, size, p, paint);
        return out;
    }

    /** Work / Dual Messenger profile: a small accent dot instead of the stock briefcase. */
    private static void badge(Canvas canvas, AppEntry e, int size, Palette p, Paint paint) {
        if (e.mainUser) return;
        paint.setShader(null);
        paint.setColorFilter(null);
        paint.setColor(p.dark ? Color.BLACK : Color.WHITE);
        canvas.drawCircle(size * 0.83f, size * 0.83f, size * 0.15f, paint);
        paint.setColor(p.accent);
        canvas.drawCircle(size * 0.83f, size * 0.83f, size * 0.10f, paint);
    }

    static Path shape(int shape, float size) {
        Path path = new Path();
        if (shape == Prefs.SHAPE_ROUNDED) {
            path.addRoundRect(new RectF(0, 0, size, size), size * 0.26f, size * 0.26f, Path.Direction.CW);
        } else if (shape == Prefs.SHAPE_SQUIRCLE) {
            // Superellipse, n = 5.
            float r = size / 2f;
            for (int i = 0; i <= 96; i++) {
                double t = i * Math.PI * 2 / 96;
                double ct = Math.cos(t), st = Math.sin(t);
                float x = (float) (r + r * Math.signum(ct) * Math.pow(Math.abs(ct), 2.0 / 5));
                float y = (float) (r + r * Math.signum(st) * Math.pow(Math.abs(st), 2.0 / 5));
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            path.close();
        } else {
            path.addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW);
        }
        return path;
    }

    /** Shortcut icons in the long-press menu. */
    static Bitmap small(Drawable d, int size, Palette p, boolean grey) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        Path circle = shape(Prefs.SHAPE_CIRCLE, size);
        if (d == null) {
            paint.setColor(p.solidHigh);
            canvas.drawPath(circle, paint);
            return out;
        }
        fill(canvas, circle, fullBleed(d, size, grey ? p.iconBg : Color.WHITE), paint, grey ? greyscale() : null);
        return out;
    }

    static Bitmap blank(int size, Palette p) {
        int s = Math.max(1, size);
        Bitmap out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(p.iconBg);
        new Canvas(out).drawCircle(s / 2f, s / 2f, s / 2f, paint);
        return out;
    }

    private static Bitmap fullBleed(Drawable d, int size, int fill) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        if (d instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) d;
            int o = Math.round(size * OUTSET);
            Drawable bg = a.getBackground();
            Drawable fg = a.getForeground();
            if (bg != null) {
                bg.setBounds(-o, -o, size + o, size + o);
                bg.draw(c);
            }
            if (fg != null) {
                fg.setBounds(-o, -o, size + o, size + o);
                fg.draw(c);
            }
        } else if (d != null) {
            c.drawColor(fill);
            int inset = Math.round(size * 0.16f);
            d.setBounds(inset, inset, size - inset, size - inset);
            d.draw(c);
        }
        return b;
    }

    private static void fill(Canvas canvas, Path shape, Bitmap content, Paint paint, ColorFilter filter) {
        paint.setColor(Color.WHITE);
        paint.setShader(new BitmapShader(content, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        paint.setColorFilter(filter);
        canvas.drawPath(shape, paint);
        paint.setShader(null);
        paint.setColorFilter(null);
    }

    private static ColorFilter greyscale() {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(0f);
        float k = 1.15f, t = -0.07f * 255f;
        m.postConcat(new ColorMatrix(new float[]{
                k, 0, 0, 0, t,
                0, k, 0, 0, t,
                0, 0, k, 0, t,
                0, 0, 0, 1, 0}));
        return new ColorMatrixColorFilter(m);
    }
}
