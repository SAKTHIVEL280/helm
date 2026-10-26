package com.helm.launcher;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.provider.AlarmClock;
import android.widget.LinearLayout;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/** Music, Calendar, Timer and Compass, in the same language as the rest of the set. */
final class MoreWidgets {
    private MoreWidgets() {}

    // ---------------------------------------------------------------- Music

    static final class Music extends HelmWidget implements Media.Listener {
        static final String[] STYLES = {"Cover", "Disc", "Dots"};
        private final RectF prev = new RectF(), play = new RectF(), next = new RectF(), artBox = new RectF();
        private final Paint artPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Matrix artMatrix = new Matrix();
        private Bitmap art, tiny, tinyFor;
        private float spin;
        private final Runnable ticker = new Runnable() {
            @Override
            public void run() {
                invalidate();
                if (Media.get(getContext()).playing()) postDelayed(this, style() == 1 ? 50 : 1000);
            }
        };

        private final Runnable refreshSoon = this::onMediaChanged;

        Music(Context c, Item i, Palette p, Host h) { super(c, i, p, h); }

        int style() { return Ui.clamp(item.cfg.optInt("style", 0), 0, STYLES.length - 1); }

        @Override
        void onShow() {
            Media.get(getContext()).addListener(this);
            onMediaChanged();
        }

        @Override
        void onHide() {
            Media.get(getContext()).removeListener(this);
            removeCallbacks(ticker);
            removeCallbacks(refreshSoon);
        }

        @Override
        public void onMediaChanged() {
            Bitmap a = Media.get(getContext()).art();
            if (a != art) {
                art = a;
                artPaint.setShader(a == null ? null : new BitmapShader(a, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            }
            removeCallbacks(ticker);
            if (isShown2()) ticker.run(); else invalidate();
        }

        @Override
        void buildOptions(Rows r, LinearLayout card) {
            r.segment(card, "Style", "Cover fills the tile with the album art", STYLES, style(), i -> {
                put("style", i);
                onMediaChanged();
            });
            if (!Media.get(getContext()).available()) {
                r.link(card, "Allow notification access", "Android shows what's playing only to apps you allow",
                        () -> {
                            Sheet.dismissIn(getContext());
                            host.start(Media.accessSettings(), this);
                        });
            }
        }

        @Override
        boolean circleAtOne() { return false; }

        @Override
        boolean drawsSurface() { return !(style() == 0 && art != null && item.h >= 2 && item.w <= 2); }

        @Override
        void onTap() {
            Media m = Media.get(getContext());
            if (!m.available()) {
                host.start(Media.accessSettings(), this);
                return;
            }
            if (hit(play)) {
                Ui.tick(this);
                m.playPause();
            } else if (hit(next)) {
                Ui.tick(this);
                m.next();
            } else if (hit(prev)) {
                Ui.tick(this);
                m.previous();
            } else if (!m.openPlayer()) {
                openOptions();
            }
            removeCallbacks(refreshSoon);
            postDelayed(refreshSoon, 250);
        }

        private boolean hit(RectF r) {
            if (r.isEmpty()) return false;
            float slop = Ui.dpf(8);
            return touchX >= r.left - slop && touchX <= r.right + slop && touchY >= r.top - slop && touchY <= r.bottom + slop;
        }

        @Override
        void drawContent(Canvas c) {
            Media m = Media.get(getContext());
            prev.setEmpty();
            play.setEmpty();
            next.setEmpty();
            float in = inset();
            if (!m.available()) {
                paint.setColor(ink(1));
                float s = Math.min(box.width(), box.height()) * (item.h == 1 ? 0.42f : 0.26f);
                if (item.h == 1) {
                    DotIcons.draw(c, "music", box.left + in + s / 2f, box.centerY(), s, paint);
                    label(c, "TAP TO ALLOW MUSIC", box.left + in * 1.6f + s, box.centerY() + Ui.sp(4), 11, ink(2), Paint.Align.LEFT);
                } else {
                    DotIcons.draw(c, "music", box.left + in + s / 2f, box.top + in + s / 2f, s, paint);
                    label(c, "TAP TO ALLOW", box.left + in, box.bottom - in - Ui.sp(14), 11, ink(2), Paint.Align.LEFT);
                    label(c, "NOW PLAYING", box.left + in, box.bottom - in, 11, ink(2), Paint.Align.LEFT);
                }
                return;
            }
            String title = m.hasSession() ? m.title() : "Nothing playing";
            String artist = m.hasSession() ? m.artist() : "Tap play to resume";
            boolean playing = m.playing();

            if (item.h == 1) {
                // Pill: disc art, words, play.
                float d = box.height() - Ui.dpf(12);
                artBox.set(box.left + Ui.dpf(6), box.centerY() - d / 2f, box.left + Ui.dpf(6) + d, box.centerY() + d / 2f);
                drawArt(c, artBox, d / 2f);
                float bs = d * 0.9f;
                play.set(box.right - Ui.dpf(8) - bs, box.centerY() - bs / 2f, box.right - Ui.dpf(8), box.centerY() + bs / 2f);
                if (item.w >= 4) {
                    next.set(play.left - bs - Ui.dpf(4), play.top, play.left - Ui.dpf(4), play.bottom);
                    prev.set(next.left - bs - Ui.dpf(4), play.top, next.left - Ui.dpf(4), play.bottom);
                }
                float tx = artBox.right + Ui.dpf(12);
                float limit = (prev.isEmpty() ? play.left : prev.left) - Ui.dpf(8);
                drawWords(c, title, artist, tx, limit, box.centerY() - Ui.sp(2), box.centerY() + Ui.sp(14), ink(1), ink(2), 14);
                drawButtons(c, playing, ink(1));
                return;
            }

            if (item.w >= 4) {
                // Wide: square art on the left, everything else beside it.
                float d = box.height() - in * 2;
                artBox.set(box.left + in, box.top + in, box.left + in + d, box.top + in + d);
                drawArt(c, artBox, style() == 1 ? d / 2f : Math.min(p.radius * 0.6f, d / 4f));
                float tx = artBox.right + in;
                drawWords(c, title, artist, tx, box.right - in, box.top + in + Ui.sp(18), box.top + in + Ui.sp(36), ink(1), ink(2), 17);
                float bs = Math.min(Ui.dpf(44), (box.right - in - tx) / 4f);
                float by = box.bottom - in - bs;
                prev.set(tx, by, tx + bs, by + bs);
                play.set(prev.right + bs * 0.5f, by, prev.right + bs * 1.5f, by + bs);
                next.set(play.right + bs * 0.5f, by, play.right + bs * 1.5f, by + bs);
                drawButtons(c, playing, ink(1));
                drawProgress(c, m.progress(), tx, box.right - in, by - Ui.dpf(12), ink(1), ink(3));
                return;
            }

            // Square.
            if (style() == 0 && art != null) {
                drawArt(c, box, radius());
                // Legibility scrim at the bottom, then white type over the art.
                Paint scrim = new Paint(Paint.ANTI_ALIAS_FLAG);
                scrim.setShader(new LinearGradient(0, box.top + box.height() * 0.35f, 0, box.bottom,
                        0x00000000, 0xB0000000, Shader.TileMode.CLAMP));
                c.drawRoundRect(box, radius(), radius(), scrim);
                float bs = box.width() * 0.2f;
                play.set(box.right - in - bs, box.bottom - in - bs, box.right - in, box.bottom - in);
                drawWords(c, title, artist, box.left + in, play.left - Ui.dpf(6), box.bottom - in - Ui.sp(18), box.bottom - in,
                        Color.WHITE, 0x99FFFFFF, 15);
                paint.setColor(p.accent);
                c.drawCircle(play.centerX(), play.centerY(), bs / 2f, paint);
                paint.setColor(p.onAccent);
                DotIcons.draw(c, playing ? "pause" : "play", play.centerX(), play.centerY(), bs * 0.46f, paint);
                return;
            }
            float d = box.width() * 0.44f;
            artBox.set(box.left + in, box.top + in, box.left + in + d, box.top + in + d);
            if (style() == 2) drawDotArt(c, artBox);
            else drawArt(c, artBox, style() == 1 ? d / 2f : Math.min(p.radius * 0.5f, d / 4f));
            float bs = box.width() * 0.2f;
            play.set(box.right - in - bs, box.top + in, box.right - in, box.top + in + bs);
            paint.setColor(playing ? p.accent : Palette.alpha(ink(1), 0.1f));
            c.drawCircle(play.centerX(), play.centerY(), bs / 2f, paint);
            paint.setColor(playing ? p.onAccent : ink(1));
            DotIcons.draw(c, playing ? "pause" : "play", play.centerX(), play.centerY(), bs * 0.46f, paint);
            drawWords(c, title, artist, box.left + in, box.right - in, box.bottom - in - Ui.sp(26), box.bottom - in - Ui.sp(10),
                    ink(1), ink(2), 15);
            drawProgress(c, m.progress(), box.left + in, box.right - in, box.bottom - in, ink(1), ink(3));
        }

        private void drawWords(Canvas c, String title, String artist, float x, float right, float y1, float y2,
                               int c1, int c2, float sizeSp) {
            float keep = box.right;
            box.right = Math.max(x + Ui.dpf(10), right + Math.min(Ui.dpf(10), inset() * 0.6f));
            words(c, title, x, y1, Ui.sp(sizeSp), c1, Ui.SANS, Paint.Align.LEFT);
            label(c, artist.toUpperCase(Locale.getDefault()), x, y2, 10, c2, Paint.Align.LEFT);
            box.right = keep;
        }

        private void drawButtons(Canvas c, boolean playing, int colour) {
            paint.setColor(colour);
            if (!prev.isEmpty()) DotIcons.draw(c, "prev", prev.centerX(), prev.centerY(), prev.height() * 0.42f, paint);
            if (!next.isEmpty()) DotIcons.draw(c, "next", next.centerX(), next.centerY(), next.height() * 0.42f, paint);
            if (play.isEmpty()) return;
            paint.setColor(playing ? p.accent : Palette.alpha(colour, 0.1f));
            c.drawCircle(play.centerX(), play.centerY(), play.height() / 2f, paint);
            paint.setColor(playing ? p.onAccent : colour);
            DotIcons.draw(c, playing ? "pause" : "play", play.centerX(), play.centerY(), play.height() * 0.46f, paint);
        }

        /** A row of dots; the played part lit. */
        private void drawProgress(Canvas c, float progress, float left, float right, float y, int on, int off) {
            if (progress < 0) return;
            float pitch = Ui.dpf(6);
            int n = Math.max(4, (int) ((right - left) / pitch));
            int lit = Math.round(progress * n);
            for (int i = 0; i < n; i++) {
                paint.setColor(i < lit ? on : off);
                c.drawCircle(left + pitch * (i + 0.5f), y - pitch / 2f, pitch * 0.28f, paint);
            }
        }

        private void drawArt(Canvas c, RectF r, float radius) {
            if (art == null) {
                paint.setColor(Palette.alpha(ink(1), 0.08f));
                c.drawRoundRect(r, radius, radius, paint);
                paint.setColor(ink(2));
                DotIcons.draw(c, "music", r.centerX(), r.centerY(), r.width() * 0.4f, paint);
                return;
            }
            float scale = Math.max(r.width() / art.getWidth(), r.height() / art.getHeight());
            artMatrix.setScale(scale, scale);
            artMatrix.postTranslate(r.centerX() - art.getWidth() * scale / 2f, r.centerY() - art.getHeight() * scale / 2f);
            boolean disc = style() == 1 && Math.abs(radius - r.width() / 2f) < 1f;
            if (disc && Media.get(getContext()).playing() && !Ui.motionOff()) spin = (spin + 1.2f) % 360f;
            if (disc) artMatrix.postRotate(spin, r.centerX(), r.centerY());
            artPaint.getShader().setLocalMatrix(artMatrix);
            c.drawRoundRect(r, radius, radius, artPaint);
            if (disc) {
                // A record's spindle hole.
                paint.setColor(p.translucent ? 0x66000000 : p.solid);
                c.drawCircle(r.centerX(), r.centerY(), r.width() * 0.08f, paint);
            }
        }

        /** Album art resampled into dots: Nothing's Glyph-style rendering. */
        private void drawDotArt(Canvas c, RectF r) {
            if (art == null) {
                drawArt(c, r, r.width() / 4f);
                return;
            }
            int n = 14;
            if (tinyFor != art) {
                tiny = Bitmap.createScaledBitmap(art, n, n, true);
                tinyFor = art;
            }
            float pitch = r.width() / n;
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    int px = tiny.getPixel(x, y);
                    float lum = Palette.luminance(px);
                    paint.setColor(Palette.alpha(ink(1), 0.15f + 0.85f * lum));
                    c.drawCircle(r.left + pitch * (x + 0.5f), r.top + pitch * (y + 0.5f), pitch * (0.18f + 0.26f * lum), paint);
                }
            }
        }
    }

    // ---------------------------------------------------------------- Calendar (month)

    static final class Month extends Widgets.Ticking {
        Month(Context c, Item i, Palette p, Host h) { super(c, i, p, h); }

        @Override
        void onTap() {
            host.start(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR), this);
        }

        @Override
        void buildOptions(Rows r, LinearLayout card) {
            r.segment(card, "Week starts", null, new String[]{"Monday", "Sunday"}, item.cfg.optInt("sunday", 0), i -> {
                put("sunday", i);
                invalidate();
            });
        }

        @Override
        void drawContent(Canvas c) {
            Locale loc = Locale.getDefault();
            Calendar now = Calendar.getInstance();
            int today = now.get(Calendar.DAY_OF_MONTH);
            boolean sunday = item.cfg.optInt("sunday", 0) == 1;
            float in = inset() * 0.9f;
            float head = Ui.sp(26);
            words(c, new SimpleDateFormat("MMMM", loc).format(now.getTime()), box.left + in, box.top + in + Ui.sp(16),
                    Ui.sp(19), ink(1), Ui.headline(getContext()), Paint.Align.LEFT);
            label(c, String.valueOf(now.get(Calendar.YEAR)), box.right - in, box.top + in + Ui.sp(14), 10, ink(3), Paint.Align.RIGHT);

            Calendar first = (Calendar) now.clone();
            first.set(Calendar.DAY_OF_MONTH, 1);
            int offset = (first.get(Calendar.DAY_OF_WEEK) - (sunday ? Calendar.SUNDAY : Calendar.MONDAY) + 7) % 7;
            int days = now.getActualMaximum(Calendar.DAY_OF_MONTH);
            int weeks = (offset + days + 6) / 7;
            float gridTop = box.top + in + head;
            float cw = (box.width() - in * 2) / 7f;
            float ch = (box.bottom - in - gridTop) / (weeks + 1);
            float size = Math.min(cw, ch);
            String[] names = sunday ? new String[]{"S", "M", "T", "W", "T", "F", "S"} : new String[]{"M", "T", "W", "T", "F", "S", "S"};
            text.setTypeface(Ui.MONO);
            text.setTextAlign(Paint.Align.CENTER);
            text.setLetterSpacing(0f);
            for (int i = 0; i < 7; i++) {
                text.setTextSize(Math.min(Ui.sp(10), size * 0.5f));
                text.setColor(ink(3));
                c.drawText(names[i], box.left + in + cw * (i + 0.5f), gridTop + ch * 0.62f, text);
            }
            for (int d = 1; d <= days; d++) {
                int idx = offset + d - 1;
                float cx = box.left + in + cw * (idx % 7 + 0.5f);
                float cy = gridTop + ch * (idx / 7 + 1.5f);
                boolean isToday = d == today;
                if (isToday) {
                    paint.setColor(p.accent);
                    c.drawCircle(cx, cy, size * 0.46f, paint);
                }
                text.setTextSize(Math.min(Ui.sp(12), size * 0.5f));
                text.setColor(isToday ? p.onAccent : d < today ? ink(3) : ink(1));
                c.drawText(String.valueOf(d), cx, cy - (text.descent() + text.ascent()) / 2f, text);
            }
        }
    }

    // ---------------------------------------------------------------- Timer

    static final class Timer extends Widgets.Ticking {
        static final int[] PRESETS = {1, 3, 5, 10, 15, 25, 45, 60};

        Timer(Context c, Item i, Palette p, Host h) { super(c, i, p, h); }

        @Override
        boolean roundFace() { return true; }

        @Override
        boolean everySecond() { return remaining() > 0; }

        long remaining() { return Math.max(0, item.cfg.optLong("end", 0) - System.currentTimeMillis()); }

        @Override
        void onTap() { openOptions(); }

        @Override
        void buildOptions(Rows r, LinearLayout card) {
            String[] names = new String[PRESETS.length];
            for (int i = 0; i < PRESETS.length; i++) names[i] = PRESETS[i] + " min";
            int last = item.cfg.optInt("minutes", 5);
            int sel = 0;
            for (int i = 0; i < PRESETS.length; i++) if (PRESETS[i] == last) sel = i;
            r.segment(card, "Length", "The Clock app rings when it's done", names, sel, i -> put("minutes", PRESETS[i]));
            LinearLayout col = (LinearLayout) card.getParent();
            r.button(col, remaining() > 0 ? "Start again" : "Start", true, () -> {
                Sheet.dismissIn(getContext());
                start(item.cfg.optInt("minutes", 5));
            });
            if (remaining() > 0) {
                r.button(col, "Clear countdown", false, () -> {
                    Sheet.dismissIn(getContext());
                    put("end", 0L);
                    restartTick();
                    invalidate();
                });
            }
        }

        private void start(int minutes) {
            Intent i = new Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, "Helm timer")
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
            host.start(i, null);
            put("minutes", minutes);
            put("end", System.currentTimeMillis() + minutes * 60_000L);
            put("total", minutes * 60_000L);
            restartTick();
            invalidate();
        }

        @Override
        void drawContent(Canvas c) {
            float cx = box.centerX(), cy = box.centerY(), r = box.width() / 2f;
            long left = remaining();
            long total = Math.max(1, item.cfg.optLong("total", 1));
            int n = 60;
            int lit = left > 0 ? (int) Math.ceil(n * left / (double) total) : 0;
            for (int i = 0; i < n; i++) {
                double a = Math.toRadians(i * 6 - 90);
                float rr = r * 0.82f;
                paint.setColor(i < lit ? p.accent : ink(3));
                c.drawCircle(cx + (float) (rr * Math.cos(a)), cy + (float) (rr * Math.sin(a)), Math.max(Ui.dpf(1.2f), r * 0.028f), paint);
            }
            if (left <= 0) {
                paint.setColor(ink(1));
                DotIcons.draw(c, "timer", cx, cy - (r > Ui.dpf(50) ? r * 0.12f : 0), r * 0.55f, paint);
                if (r > Ui.dpf(50)) label(c, item.cfg.optInt("minutes", 5) + " MIN", cx, cy + r * 0.42f, 11, ink(2), Paint.Align.CENTER);
                return;
            }
            long secs = (left + 999) / 1000;
            String t = secs >= 3600 ? (secs / 3600) + ":" + String.format(Locale.US, "%02d", secs % 3600 / 60)
                    : (secs / 60) + ":" + String.format(Locale.US, "%02d", secs % 60);
            float pitch = pitchFor(t, r * 1.1f, r * 0.5f);
            float w = DotFont.columns(t) * pitch;
            dots(c, t, cx - w / 2f, cy - DotFont.ROWS * pitch / 2f, pitch, ink(1));
        }
    }

    // ---------------------------------------------------------------- Compass

    static final class Compass extends HelmWidget implements SensorEventListener {
        private final float[] rot = new float[9], orient = new float[3];
        private float heading, shown;
        private boolean has;

        Compass(Context c, Item i, Palette p, Host h) { super(c, i, p, h); }

        @Override
        boolean roundFace() { return true; }

        @Override
        void onTap() { openOptions(); }

        @Override
        void onShow() {
            SensorManager sm = getContext().getSystemService(SensorManager.class);
            Sensor s = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            if (s != null) sm.registerListener(this, s, SensorManager.SENSOR_DELAY_UI);
            has = s != null;
        }

        @Override
        void onHide() {
            SensorManager sm = getContext().getSystemService(SensorManager.class);
            if (sm != null) sm.unregisterListener(this);
        }

        @Override
        public void onSensorChanged(SensorEvent e) {
            SensorManager.getRotationMatrixFromVector(rot, e.values);
            SensorManager.getOrientation(rot, orient);
            heading = (float) ((Math.toDegrees(orient[0]) + 360) % 360);
            // Ease toward the reading, the short way round, so the dial doesn't jitter.
            float d = ((heading - shown + 540) % 360) - 180;
            if (Math.abs(d) > 0.5f) {
                shown = (shown + d * 0.25f + 360) % 360;
                invalidate();
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}

        @Override
        void drawContent(Canvas c) {
            float cx = box.centerX(), cy = box.centerY(), r = box.width() / 2f;
            c.save();
            c.rotate(-shown, cx, cy);
            for (int i = 0; i < 36; i++) {
                double a = Math.toRadians(i * 10 - 90);
                boolean cardinal = i % 9 == 0;
                float rr = r * 0.8f;
                paint.setColor(i == 0 ? p.accent : cardinal ? ink(1) : ink(3));
                c.drawCircle(cx + (float) (rr * Math.cos(a)), cy + (float) (rr * Math.sin(a)),
                        r * (i == 0 ? 0.06f : cardinal ? 0.035f : 0.02f), paint);
            }
            if (r > Ui.dpf(50)) {
                String[] k = {"N", "E", "S", "W"};
                for (int i = 0; i < 4; i++) {
                    double a = Math.toRadians(i * 90 - 90);
                    float rr = r * 0.58f;
                    c.save();
                    float x = cx + (float) (rr * Math.cos(a)), y = cy + (float) (rr * Math.sin(a));
                    c.rotate(shown, x, y);
                    label(c, k[i], x, y + Ui.sp(4), 11, i == 0 ? p.accent : ink(2), Paint.Align.CENTER);
                    c.restore();
                }
            }
            c.restore();
            // The fixed needle: where the top of the phone points.
            Path needle = new Path();
            needle.moveTo(cx, cy - r * 0.38f);
            needle.lineTo(cx - r * 0.07f, cy);
            needle.lineTo(cx + r * 0.07f, cy);
            needle.close();
            paint.setColor(ink(1));
            c.drawPath(needle, paint);
            paint.setColor(p.accent);
            c.drawCircle(cx, cy, r * 0.06f, paint);
            if (!has) label(c, "NO SENSOR", cx, cy + r * 0.4f, 10, ink(2), Paint.Align.CENTER);
            else if (r > Ui.dpf(50)) label(c, Math.round(heading) % 360 + "°", cx, cy + r * 0.36f, 11, ink(2), Paint.Align.CENTER);
        }
    }
}
