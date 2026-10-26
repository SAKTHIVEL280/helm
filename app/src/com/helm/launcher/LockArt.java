package com.helm.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Draws Helm's lock screen: the wallpaper, then a Nothing-style clock and date. Shared by the live wallpaper and its preview. */
final class LockArt {
    static final String[] CLOCKS = {"None", "Dots", "Stacked", "Serif", "Analog", "Gooey", "Terminal"};
    static final int NONE = 0, DOTS = 1, STACKED = 2, SERIF = 3, ANALOG = 4, GOOEY = 5, TERMINAL = 6;
    static final String[] TINTS = {"Mono", "Wallpaper"};
    static final String[] POSITIONS = {"Top", "Centre"};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accent = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();

    /**
     * @param wall    the wallpaper, or null for a plain field
     * @param light   whether the wallpaper is light (dark ink then)
     * @param showClock false on the home screen: only the wallpaper
     */
    void draw(Context ctx, Canvas c, int w, int h, Bitmap wall, boolean light, int accentColour, boolean showClock) {
        if (wall != null) {
            float scale = Math.max(w / (float) wall.getWidth(), h / (float) wall.getHeight());
            float bw = wall.getWidth() * scale, bh = wall.getHeight() * scale;
            dst.set((w - bw) / 2f, (h - bh) / 2f, (w + bw) / 2f, (h + bh) / 2f);
            c.drawBitmap(wall, null, dst, bmp);
        } else {
            c.drawColor(light ? 0xFFE6E6E3 : 0xFF000000);
        }
        if (!showClock) return;
        Prefs p = Prefs.get(ctx);
        int style = Ui.clamp(p.lockClock(), 0, CLOCKS.length - 1);
        int ink = light ? 0xFF000000 : 0xFFFFFFFF;
        // NOS 5's wallpaper-matched clock colours: the ink leans to the colour engine's accent.
        if (p.lockTint() == 1) ink = Palette.mix(ink, accentColour, 0.7f);
        int ink2 = Palette.alpha(ink, 0.62f);
        paint.setColor(ink);
        accent.setColor(accentColour);
        Date now = new Date();
        String t = Widgets.time(ctx, now, null);
        boolean centre = p.lockPos() == 1;
        float side = w * 0.1f;
        float top = centre ? h * 0.36f : h * 0.17f;
        float bottom = top;

        switch (style) {
            case DOTS: {
                float pitch = (w - side * 2) / DotFont.columns(t);
                pitch = Math.min(pitch, h * 0.02f);
                float tw = DotFont.columns(t) * pitch;
                DotFont.draw(c, t, (w - tw) / 2f, top, pitch, paint, null, accent, ":");
                bottom = top + DotFont.ROWS * pitch;
                break;
            }
            case STACKED: {
                String[] hm = t.split(":");
                String hh = hm[0].length() == 1 ? "0" + hm[0] : hm[0];
                float pitch = Math.min((w * 0.55f) / DotFont.columns("00"), h * 0.022f);
                float tw = DotFont.columns("00") * pitch;
                DotFont.draw(c, hh, (w - tw) / 2f, top, pitch, paint, null, null, null);
                float y2 = top + (DotFont.ROWS + 1.5f) * pitch;
                DotFont.draw(c, hm[1], (w - tw) / 2f, y2, pitch, accent, null, null, null);
                bottom = y2 + DotFont.ROWS * pitch;
                break;
            }
            case SERIF: {
                text.setTypeface(Ui.SERIF);
                text.setTextAlign(Paint.Align.CENTER);
                text.setLetterSpacing(-0.02f);
                text.setTextSize(100f); // Measured large: tiny sizes round their advances.
                float size = Math.min((w - side * 2) * 100f / Math.max(1f, text.measureText(t)), h * 0.16f);
                text.setTextSize(size);
                text.setColor(ink);
                float base = top + size * 0.74f;
                c.drawText(t, w / 2f, base, text);
                bottom = base + size * 0.08f;
                break;
            }
            case ANALOG: {
                float r = Math.min(w * 0.3f, h * 0.15f);
                float cx = w / 2f, cy = top + r;
                drawAnalog(c, cx, cy, r, ink, ink2, accentColour);
                bottom = cy + r;
                break;
            }
            case GOOEY: {
                float pitch = Math.min((w - side * 2) / DotFont.columns(t), h * 0.024f);
                float tw = DotFont.columns(t) * pitch;
                gooey(c, t, (w - tw) / 2f, top, pitch, paint, accent);
                bottom = top + DotFont.ROWS * pitch;
                break;
            }
            case TERMINAL: {
                bottom = terminal(ctx, c, w, top, side, t, now, ink, ink2, accentColour);
                break;
            }
            default:
                break;
        }

        if (p.lockDate() && style != TERMINAL) {
            text.setTypeface(Ui.MONO);
            text.setTextAlign(Paint.Align.CENTER);
            text.setLetterSpacing(0.14f);
            text.setTextSize(Math.min(w * 0.034f, Ui.sp(14)));
            text.setColor(style == NONE ? ink : ink2);
            String date = new SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(now).replace(".", "").toUpperCase(Locale.getDefault());
            c.drawText(date, w / 2f, (style == NONE ? top : bottom) + text.getTextSize() * 2.4f, text);
        }
    }

    /**
     * Nothing's Gooey face: the dot-matrix digits with neighbouring dots fused, so each figure reads
     * as one soft, liquid shape instead of separate points.
     */
    private void gooey(Canvas c, String s, float x, float y, float pitch, Paint ink, Paint acc) {
        float r = pitch * 0.5f;
        int col = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (i > 0) col++;
            boolean[][] g = DotFont.glyph(ch);
            Paint pnt = ch == ':' ? acc : ink;
            for (int row = 0; row < g.length; row++) {
                for (int k = 0; k < g[row].length; k++) {
                    if (!g[row][k]) continue;
                    float cx = x + (col + k) * pitch + r, cy = y + row * pitch + r;
                    c.drawCircle(cx, cy, r * 1.08f, pnt);
                    if (k + 1 < g[row].length && g[row][k + 1]) c.drawRect(cx, cy - r * 0.78f, cx + pitch, cy + r * 0.78f, pnt);
                    if (row + 1 < g.length && g[row + 1][k]) c.drawRect(cx - r * 0.78f, cy, cx + r * 0.78f, cy + pitch, pnt);
                }
            }
            col += g[0].length;
        }
    }

    /**
     * The detailed, hardware-inspired face: the time in Geist Mono with a hairline frame and small
     * readouts (date, week, battery) and a dot bar for how much of the day has gone.
     */
    private float terminal(Context ctx, Canvas c, int w, float top, float side, String t, Date now, int ink, int ink2, int acc) {
        float left = side, right = w - side;
        text.setTypeface(Ui.MONO);
        text.setTextAlign(Paint.Align.LEFT);
        text.setLetterSpacing(0.02f);
        text.setTextSize(100f); // Measured large: tiny sizes round their advances.
        float size = Math.min((right - left) * 0.9f * 100f / Math.max(1f, text.measureText(t)), w * 0.3f);
        float small = Math.max(Ui.sp(10), size * 0.11f);
        text.setLetterSpacing(0.14f);
        text.setTextSize(small);
        text.setColor(ink2);
        Locale loc = Locale.getDefault();
        Calendar cal = Calendar.getInstance();
        c.drawText("LOCAL TIME", left, top, text);
        text.setTextAlign(Paint.Align.RIGHT);
        c.drawText(String.format(loc, "WK %02d / D %03d", cal.get(Calendar.WEEK_OF_YEAR), cal.get(Calendar.DAY_OF_YEAR)), right, top, text);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, Ui.dpf(0.8f)));
        paint.setColor(ink2);
        float y1 = top + small * 0.9f;
        c.drawLine(left, y1, right, y1, paint);
        text.setLetterSpacing(0.02f);
        text.setTextSize(size);
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(ink);
        float base = y1 + size * 0.86f;
        int colon = t.indexOf(':');
        c.drawText(t.substring(0, colon), left, base, text);
        float cx = left + text.measureText(t.substring(0, colon));
        text.setColor(acc);
        c.drawText(":", cx, base, text);
        text.setColor(ink);
        c.drawText(t.substring(colon + 1), cx + text.measureText(":"), base, text);
        float y2 = base + size * 0.14f;
        c.drawLine(left, y2, right, y2, paint);
        paint.setStyle(Paint.Style.FILL);
        // Readouts.
        text.setLetterSpacing(0.14f);
        text.setTextSize(small);
        text.setColor(ink2);
        float ry = y2 + small * 1.9f;
        String date = new SimpleDateFormat("EEE dd.MM.yyyy", loc).format(now).toUpperCase(loc);
        c.drawText(date, left, ry, text);
        int battery = -1;
        try {
            android.os.BatteryManager bm = ctx.getSystemService(android.os.BatteryManager.class);
            if (bm != null) battery = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
        } catch (RuntimeException ignored) {
        }
        text.setTextAlign(Paint.Align.RIGHT);
        if (battery >= 0) c.drawText("BATT " + battery + "%", right, ry, text);
        // Day progress: 24 dots, one an hour, lit up to now.
        float dy = ry + small * 1.6f;
        float pitch = (right - left) / 24f;
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        for (int i = 0; i < 24; i++) {
            paint.setColor(i < hour ? ink : i == hour ? acc : Palette.alpha(ink, 0.25f));
            c.drawCircle(left + pitch * (i + 0.5f), dy, Math.min(pitch * 0.22f, small * 0.3f), paint);
        }
        return dy;
    }

    private void drawAnalog(Canvas c, float cx, float cy, float r, int ink, int ink2, int acc) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.BUTT);
        for (int i = 0; i < 60; i++) {
            boolean major = i % 5 == 0;
            double a = Math.toRadians(i * 6 - 90);
            float in = r * (major ? 0.82f : 0.88f), out = r * 0.94f;
            paint.setStrokeWidth(major ? r * 0.025f : r * 0.01f);
            paint.setColor(major ? ink : ink2);
            c.drawLine(cx + (float) (in * Math.cos(a)), cy + (float) (in * Math.sin(a)),
                    cx + (float) (out * Math.cos(a)), cy + (float) (out * Math.sin(a)), paint);
        }
        Calendar now = Calendar.getInstance();
        float min = now.get(Calendar.MINUTE);
        float hour = now.get(Calendar.HOUR) + min / 60f;
        paint.setStrokeCap(Paint.Cap.ROUND);
        hand(c, cx, cy, hour * 30f, r * 0.5f, r * 0.05f, ink);
        hand(c, cx, cy, min * 6f, r * 0.78f, r * 0.03f, ink);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(acc);
        c.drawCircle(cx, cy, r * 0.045f, paint);
        paint.setColor(ink);
    }

    private void hand(Canvas c, float cx, float cy, float deg, float len, float width, int colour) {
        double a = Math.toRadians(deg - 90);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setColor(colour);
        c.drawLine(cx, cy, cx + (float) Math.cos(a) * len, cy + (float) Math.sin(a) * len, paint);
    }
}
