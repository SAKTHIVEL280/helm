package com.helm.launcher;

import android.Manifest;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Shader;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** The Helm widget set. */
final class Widgets {
    private Widgets() {}

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static String time(Context c, Date d, TimeZone zone) {
        SimpleDateFormat f = new SimpleDateFormat(DateFormat.is24HourFormat(c) ? "HH:mm" : "h:mm", Locale.US);
        if (zone != null) f.setTimeZone(zone);
        return f.format(d);
    }

    /** Repaints on the next minute (or second) boundary while visible. */
    abstract static class Ticking extends HelmWidget {
        private final Runnable tick = new Runnable() {
            @Override
            public void run() {
                invalidate();
                long now = System.currentTimeMillis();
                long period = everySecond() ? 1000 : 60_000;
                postDelayed(this, period - now % period + 5);
            }
        };

        Ticking(Context c, Item i, Palette p, Host h) { super(c, i, p, h); }

        boolean everySecond() { return false; }

        @Override
        void onShow() {
            removeCallbacks(tick);
            tick.run();
        }

        @Override
        void onHide() { removeCallbacks(tick); }

        void restartTick() {
            if (isShown2()) onShow();
        }
    }

    // ---------------------------------------------------------------- Analog clock

    static final class Analog extends Ticking {
        Analog(Context c, Item i, Palette p, Host h) { super(c, i, p, h); }

        @Override
        boolean roundFace() { return true; }

        boolean seconds() { return item.cfg.optBoolean("seconds", item.w >= 2); }

        @Override
        boolean everySecond() { return seconds(); }

        @Override
        void onTap() {
            host.start(new Intent(AlarmClock.ACTION_SHOW_ALARMS), this);
        }

        static final String[] FACES = {"Lines", "Dots", "Numerals", "Quarters", "Bare"};

        int face() { return Ui.clamp(item.cfg.optInt("face", 0), 0, FACES.length - 1); }

        @Override
        void buildOptions(Rows r, LinearLayout card) {
            r.segment(card, "Face", null, FACES, face(), i -> {
                put("face", i);
                invalidate();
            });
            r.toggle(card, "Second hand", "Ticks once a second while on screen", seconds(), on -> {
                put("seconds", on);
                restartTick();
            });
        }

        @Override
        void drawContent(Canvas c) {
            float cx = box.centerX(), cy = box.centerY(), r = box.width() / 2f;
            paint.setStrokeCap(Paint.Cap.BUTT);
            int face = face();
            for (int i = 0; i < 60; i++) {
                boolean major = i % 5 == 0;
                double a = Math.toRadians(i * 6 - 90);
                float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
                if (face == 0) {
                    if (r < Ui.dpf(40) && !major) continue;
                    float in = r * (major ? 0.79f : 0.86f), out = r * 0.92f;
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(major ? r * 0.03f : r * 0.012f);
                    paint.setColor(major ? ink(1) : ink(2));
                    c.drawLine(cx + in * ca, cy + in * sa, cx + out * ca, cy + out * sa, paint);
                } else if (face == 1) {
                    // Nothing's dot dial: one dot a minute, bigger on the hour, red at twelve.
                    if (r < Ui.dpf(40) && !major) continue;
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(i == 0 ? p.accent : major ? ink(1) : ink(3));
                    c.drawCircle(cx + r * 0.86f * ca, cy + r * 0.86f * sa, major ? r * 0.035f : r * 0.016f, paint);
                } else if (face == 3 && i % 15 == 0) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(r * 0.05f);
                    paint.setColor(i == 0 ? p.accent : ink(1));
                    c.drawLine(cx + r * 0.72f * ca, cy + r * 0.72f * sa, cx + r * 0.9f * ca, cy + r * 0.9f * sa, paint);
                }
            }
            if (face == 2) {
                text.setTypeface(Ui.headline(getContext()));
                text.setTextSize(r * 0.3f);
                text.setLetterSpacing(0f);
                text.setTextAlign(Paint.Align.CENTER);
                String[] n = {"12", "3", "6", "9"};
                for (int k = 0; k < 4; k++) {
                    double a = Math.toRadians(k * 90 - 90);
                    float x = cx + r * 0.72f * (float) Math.cos(a), y = cy + r * 0.72f * (float) Math.sin(a);
                    text.setColor(k == 0 ? p.accent : ink(1));
                    c.drawText(n[k], x, y - (text.descent() + text.ascent()) / 2f, text);
                }
            }
            paint.setStyle(Paint.Style.STROKE);
            Calendar now = Calendar.getInstance();
            float sec = now.get(Calendar.SECOND);
            float min = now.get(Calendar.MINUTE) + (seconds() ? sec / 60f : 0f);
            float hour = now.get(Calendar.HOUR) + min / 60f;
            paint.setStrokeCap(Paint.Cap.ROUND);
            hand(c, cx, cy, hour * 30f, r * 0.50f, r * 0.12f, r * 0.055f, ink(1));
            hand(c, cx, cy, min * 6f, r * 0.80f, r * 0.14f, r * 0.035f, ink(1));
            if (seconds()) {
                hand(c, cx, cy, sec * 6f, r * 0.84f, r * 0.22f, Math.max(Ui.dpf(1.2f), r * 0.012f), p.accent);
                double a = Math.toRadians(sec * 6f + 90);
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(cx + (float) (r * 0.22f * Math.cos(a)), cy + (float) (r * 0.22f * Math.sin(a)), r * 0.05f, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(p.accent);
            c.drawCircle(cx, cy, r * 0.045f, paint);
            paint.setColor(ink(1));
            c.drawCircle(cx, cy, r * 0.018f, paint);
        }

        private void hand(Canvas c, float cx, float cy, float deg, float len, float tail, float width, int colour) {
            double a = Math.toRadians(deg - 90);
            float dx = (float) Math.cos(a), dy = (float) Math.sin(a);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(width);
            paint.setColor(colour);
            c.drawLine(cx - dx * tail, cy - dy * tail, cx + dx * len, cy + dy * len, paint);
        }
    }

    // ---------------------------------------------------------------- Dot clock

    static final class Digital extends Ticking {
        Digital(Context c, Item i, Palette p, Host h) { super(c, i, p, h); }

        @Override
        void onTap() { host.start(new Intent(AlarmClock.ACTION_SHOW_ALARMS), this); }

        static final String[] TYPES = {"Dot", "Serif", "Geist", "Mono"};

        int type() { return Ui.clamp(item.cfg.optInt("type", 0), 0, TYPES.length - 1); }

        @Override
        void buildOptions(Rows r, LinearLayout card) {
            r.segment(card, "Type", "Dot-matrix, the display serif, or Geist", TYPES, type(), i -> {
                put("type", i);
                invalidate();
            });
            r.toggle(card, "Red colon", "The accent between hours and minutes", item.cfg.optBoolean("accent", true), on -> {
                put("accent", on);
                invalidate();
            });
        }

        /** The time set in a typeface instead of dots, sized to fill its tile. */
        private void drawTyped(Canvas c, String t, Date now) {
            android.graphics.Typeface face = type() == 1 ? Ui.headline(getContext()) : type() == 2 ? Ui.SANS : Ui.MONO;
            float in = inset();
            boolean colon = item.cfg.optBoolean("accent", true);
            text.setTypeface(face);
            text.setLetterSpacing(type() == 3 ? 0.04f : -0.02f);
            text.setTextAlign(Paint.Align.LEFT);
            if (item.h >= 2 && item.w <= 2) {
                String[] hm = t.split(":");
                text.setTextSize(100f); // Measured large: tiny sizes round their advances.
                float size = Math.min((box.width() - in * 2) * 100f / Math.max(1f, text.measureText("00")),
                        (box.height() - in * 2) / 2f / 0.74f);
                text.setTextSize(size);
                text.setColor(ink(1));
                float lineH = size * 0.74f;
                float top = box.centerY() - lineH - Ui.dpf(2);
                text.setTextAlign(Paint.Align.CENTER);
                c.drawText(hm[0], box.centerX(), top + lineH, text);
                text.setColor(colon ? p.accent : ink(1));
                c.drawText(hm[1], box.centerX(), top + lineH * 2 + Ui.dpf(4), text);
                return;
            }
            float availH = item.h >= 2 ? box.height() * 0.5f : box.height() - in;
            text.setTextSize(100f); // Measured large: tiny sizes round their advances.
            float size = Math.min((box.width() - in * 2) * 0.86f * 100f / Math.max(1f, text.measureText(t)), availH / 0.74f);
            text.setTextSize(size);
            float w = text.measureText(t);
            float x = item.h >= 2 ? box.left + in : box.centerX() - w / 2f;
            float base = item.h >= 2 ? box.top + in + size * 0.74f : box.centerY() + size * 0.37f;
            int i = t.indexOf(':');
            text.setColor(ink(1));
            c.drawText(t.substring(0, i), x, base, text);
            float x2 = x + text.measureText(t.substring(0, i));
            text.setColor(colon ? p.accent : ink(1));
            c.drawText(":", x2, base, text);
            text.setColor(ink(1));
            c.drawText(t.substring(i + 1), x2 + text.measureText(":"), base, text);
            if (item.h >= 2) {
                String date = new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now).toUpperCase(Locale.getDefault());
                label(c, date, box.left + in, box.bottom - in, 12, ink(2), Paint.Align.LEFT);
            }
        }

        @Override
        void drawContent(Canvas c) {
            Date now = new Date();
            String t = time(getContext(), now, null);
            if (type() != 0) {
                drawTyped(c, t, now);
                return;
            }
            String accent = item.cfg.optBoolean("accent", true) ? ":" : null;
            float in = inset();
            if (item.h >= 2 && item.w <= 2) {
                String[] hm = t.split(":");
                String hh = hm[0].length() == 1 ? " " + hm[0] : hm[0];
                float pitch = Math.min((box.width() - in * 2) / 11f, (box.height() - in * 2) / 16f);
                float x = box.centerX() - 11 * pitch / 2f;
                float y0 = box.centerY() - 15 * pitch / 2f;
                dots(c, hh, x, y0, pitch, ink(1));
                dots(c, hm[1], x, y0 + 8 * pitch, pitch, ink(1));
                return;
            }
            if (item.h >= 2) {
                float pitch = pitchFor("00:00", box.width() * 0.62f, box.height() * 0.42f);
                float x = box.left + in, y = box.top + in;
                dotsAccent(c, t, x, y, pitch, ink(1), accent);
                String date = new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now).toUpperCase(Locale.getDefault());
                label(c, date, x, box.bottom - in, 12, ink(2), Paint.Align.LEFT);
                return;
            }
            float pitch = pitchFor("00:00", box.width() * 0.72f, box.height() * 0.5f);
            float w = DotFont.columns(t) * pitch;
            dotsAccent(c, t, box.centerX() - w / 2f, box.centerY() - DotFont.ROWS * pitch / 2f, pitch, ink(1), accent);
        }
    }

    // ---------------------------------------------------------------- World clock

    static final String[][] ZONES = {
            {"London", "Europe/London"}, {"New York", "America/New_York"}, {"Los Angeles", "America/Los_Angeles"},
            {"Tokyo", "Asia/Tokyo"}, {"Seoul", "Asia/Seoul"}, {"Beijing", "Asia/Shanghai"},
            {"Singapore", "Asia/Singapore"}, {"Dubai", "Asia/Dubai"}, {"Mumbai", "Asia/Kolkata"},
            {"Paris", "Europe/Paris"}, {"Berlin", "Europe/Berlin"}, {"Sydney", "Australia/Sydney"},
            {"São Paulo", "America/Sao_Paulo"}, {"Toronto", "America/Toronto"}, {"Montreal", "America/Montreal"},
            {"Chicago", "America/Chicago"}, {"Istanbul", "Europe/Istanbul"}, {"Jakarta", "Asia/Jakarta"},
    };

    static final class World extends Ticking {
        World(Context c, Item i, Palette p, Host h) { super(c, i, p, h); }

        @Override
        void onTap() { openOptions(); }

        @Override
        void buildOptions(Rows r, LinearLayout card) {
            String[] names = new String[ZONES.length];
            int sel = 0;
            for (int i = 0; i < ZONES.length; i++) {
                names[i] = ZONES[i][0];
                if (ZONES[i][1].equals(item.cfg.optString("zone", "Europe/London"))) sel = i;
            }
            r.segment(card, "City", null, names, sel, i -> {
                put("zone", ZONES[i][1]);
                put("city", ZONES[i][0]);
                invalidate();
            });
        }

        @Override
        void drawContent(Canvas c) {
            TimeZone zone = TimeZone.getTimeZone(item.cfg.optString("zone", "Europe/London"));
            String city = item.cfg.optString("city", "London").toUpperCase(Locale.getDefault());
            Date now = new Date();
            String t = time(getContext(), now, zone);
            long diff = zone.getOffset(now.getTime()) - TimeZone.getDefault().getOffset(now.getTime());
            float hours = diff / 3_600_000f;
            String delta = hours == 0 ? "LOCAL" : (hours > 0 ? "+" : "") + (hours == Math.round(hours)
                    ? String.valueOf(Math.round(hours)) : String.format(Locale.US, "%.1f", hours)) + "H";
            float in = inset();
            if (item.h == 1) {
                float pitch = pitchFor("00:00", box.width() * 0.42f, box.height() * 0.42f);
                label(c, city.length() > 3 ? city.substring(0, 3) : city, box.left + in * 1.3f,
                        box.centerY() + Ui.sp(5), 13, ink(2), Paint.Align.LEFT);
                float w = DotFont.columns(t) * pitch;
                dots(c, t, box.right - in * 1.3f - w, box.centerY() - DotFont.ROWS * pitch / 2f, pitch, ink(1));
                return;
            }
            label(c, city, box.left + in, box.top + in + Ui.sp(10), 12, ink(2), Paint.Align.LEFT);
            label(c, delta, box.right - in, box.top + in + Ui.sp(10), 11, ink(3), Paint.Align.RIGHT);
            float pitch = pitchFor("00:00", box.width() - in * 2, box.height() * 0.32f);
            dots(c, t, box.left + in, box.bottom - in - DotFont.ROWS * pitch, pitch, ink(1));
        }
    }

    // ---------------------------------------------------------------- Date

    static final class DateTile extends Ticking {
        DateTile(Context c, Item i, Palette p, Host h) { super(c, i, p, h); }

        @Override
        void onTap() {
            Intent i = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR);
            host.start(i, this);
        }

        @Override
        void drawContent(Canvas c) {
            Date now = new Date();
            Locale loc = Locale.getDefault();
            float in = inset();
            if (item.h == 1) {
                String s = new SimpleDateFormat("EEE d MMM", loc).format(now).replace(".", "");
                words(c, s, box.centerX(), box.centerY() + box.height() * 0.15f, box.height() * 0.42f, ink(1),
                        Ui.headline(getContext()), Paint.Align.CENTER);
                return;
            }
            label(c, new SimpleDateFormat("EEEE", loc).format(now).toUpperCase(loc), box.left + in,
                    box.top + in + Ui.sp(10), 12, ink(2), Paint.Align.LEFT);
            words(c, new SimpleDateFormat("d", loc).format(now), box.left + in - Ui.dpf(2),
                    box.top + box.height() * 0.70f, box.height() * 0.52f, ink(1), Ui.headline(getContext()), Paint.Align.LEFT);
            label(c, new SimpleDateFormat("MMMM", loc).format(now).toUpperCase(loc), box.left + in,
                    box.bottom - in, 12, p.accent, Paint.Align.LEFT);
        }
    }

    // ---------------------------------------------------------------- Weather

    static final class WeatherTile extends HelmWidget {
        private Weather.Now now;

        WeatherTile(Context c, Item i, Palette p, Host h) {
            super(c, i, p, h);
            now = Weather.cached(c);
        }

        @Override
        void onShow() {
            Weather.refresh(getContext(), false, n -> {
                now = n;
                invalidate();
            });
        }

        @Override
        void onTap() {
            if (!Weather.configured(getContext())) {
                openOptions();
                return;
            }
            Ui.tick(this);
            Weather.refresh(getContext(), true, n -> {
                now = n;
                invalidate();
            });
        }

        @Override
        void buildOptions(Rows r, LinearLayout card) {
            Context c = getContext();
            Prefs prefs = Prefs.get(c);
            LinearLayout box = r.column();
            box.setPadding(Ui.dp(18), Ui.dp(14), Ui.dp(18), Ui.dp(14));
            box.addView(r.labels("City", Weather.configured(c)
                    ? "Now: " + prefs.sp.getString(Prefs.WEATHER_CITY, "") : "Type a city and pick it below"));
            EditText field = new EditText(c);
            field.setHint("Search cities");
            field.setSingleLine(true);
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
            field.setTextColor(p.onSolid);
            field.setHintTextColor(p.onSolid3);
            field.setTypeface(Ui.SANS);
            field.setBackground(Ui.pill(p.solidHigh));
            field.setPadding(Ui.dp(18), Ui.dp(12), Ui.dp(18), Ui.dp(12));
            LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            fl.topMargin = Ui.dp(12);
            box.addView(field, fl);
            LinearLayout results = r.column();
            box.addView(results);
            Runnable[] pending = new Runnable[1];
            field.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) {}

                @Override public void onTextChanged(CharSequence s, int a, int b, int d) {}

                @Override
                public void afterTextChanged(Editable s) {
                    if (pending[0] != null) field.removeCallbacks(pending[0]);
                    String q = s.toString().trim();
                    if (q.length() < 2) return;
                    pending[0] = () -> Weather.search(q, places -> {
                        results.removeAllViews();
                        for (Weather.Place place : places) {
                            TextView t = Ui.text(c, place.name, p.onSolid, 15, Ui.SANS);
                            t.setPadding(Ui.dp(6), Ui.dp(12), Ui.dp(6), Ui.dp(12));
                            t.setOnClickListener(v -> {
                                Weather.choose(c, place);
                                Ui.confirm(v);
                                Sheet.dismissIn(getContext());
                                Weather.refresh(c, true, n -> {
                                    now = n;
                                    invalidate();
                                });
                            });
                            results.addView(t);
                        }
                    });
                    field.postDelayed(pending[0], 450);
                }
            });
            card.addView(box);
            r.segment(card, "Units", null, new String[]{"°C", "°F"}, prefs.sp.getInt(Prefs.WEATHER_UNIT, 0), i -> {
                prefs.put(Prefs.WEATHER_UNIT, i);
                Weather.refresh(c, true, n -> {
                    now = n;
                    invalidate();
                });
            });
        }

        @Override
        void drawContent(Canvas c) {
            float in = inset();
            if (now == null) {
                float s = Math.min(box.height() * 0.4f, Ui.dpf(26));
                paint.setColor(ink(2));
                DotIcons.draw(c, "cloud", box.left + in + s / 2f, box.centerY(), s, paint);
                label(c, Weather.configured(getContext()) ? "LOADING" : "SET CITY", box.left + in * 1.6f + s,
                        box.centerY() + Ui.sp(4), 12, ink(2), Paint.Align.LEFT);
                return;
            }
            String temp = now.temp + "°";
            String icon = Weather.icon(now.code);
            if (item.h == 1) {
                float pitch = pitchFor("-00°", box.width() * 0.4f, box.height() * 0.38f);
                float tw = DotFont.columns(temp) * pitch;
                float iconS = DotFont.ROWS * pitch * 1.25f;
                float total = tw + pitch * 3 + iconS;
                float x = box.centerX() - total / 2f;
                dots(c, temp, x, box.centerY() - DotFont.ROWS * pitch / 2f, pitch, ink(1));
                paint.setColor(ink(1));
                DotIcons.draw(c, icon, x + tw + pitch * 3 + iconS / 2f, box.centerY(), iconS, paint);
                return;
            }
            float colW = item.w >= 4 ? box.width() * 0.5f : box.width();
            label(c, now.city.toUpperCase(Locale.getDefault()), box.left + in, box.top + in + Ui.sp(10), 12, ink(2), Paint.Align.LEFT);
            float iconS = Math.min(colW * 0.28f, box.height() * 0.28f);
            paint.setColor(ink(1));
            DotIcons.draw(c, icon, box.left + colW - in - iconS / 2f, box.top + in + iconS / 2f, iconS, paint);
            float pitch = pitchFor("-00°", colW - in * 2, box.height() * 0.3f);
            float ty = box.bottom - in - Ui.sp(26) - DotFont.ROWS * pitch;
            dots(c, temp, box.left + in, ty, pitch, ink(1));
            label(c, Weather.describe(now.code).toUpperCase(Locale.getDefault()), box.left + in,
                    box.bottom - in - Ui.sp(13), 11, ink(2), Paint.Align.LEFT);
            label(c, "H " + now.high + "°  L " + now.low + "°", box.left + in, box.bottom - in, 11, ink(3), Paint.Align.LEFT);
            if (item.w >= 4 && !now.days.isEmpty()) {
                int n = Math.min(4, now.days.size());
                float x0 = box.left + colW, w = (box.right - in - x0) / n;
                Calendar cal = Calendar.getInstance();
                for (int i = 0; i < n; i++) {
                    cal.add(Calendar.DAY_OF_YEAR, 1);
                    float cx = x0 + w * (i + 0.5f);
                    label(c, new SimpleDateFormat("EEE", Locale.getDefault()).format(cal.getTime()).toUpperCase(Locale.getDefault()),
                            cx, box.top + in + Ui.sp(10), 11, ink(2), Paint.Align.CENTER);
                    float s = Math.min(w * 0.5f, box.height() * 0.22f);
                    paint.setColor(ink(1));
                    DotIcons.draw(c, Weather.icon(now.days.get(i)[0]), cx, box.centerY(), s, paint);
                    label(c, now.days.get(i)[1] + "°", cx, box.bottom - in - Ui.sp(13), 12, ink(1), Paint.Align.CENTER);
                    label(c, now.days.get(i)[2] + "°", cx, box.bottom - in, 11, ink(3), Paint.Align.CENTER);
                }
            }
        }
    }

    // ---------------------------------------------------------------- Battery

    static final class Battery extends HelmWidget {
        private int level = -1;
        private boolean charging;
        private final BroadcastReceiver rx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) { update(i); }
        };

        Battery(Context c, Item i, Palette p, Host h) { super(c, i, p, h); }

        @Override
        void onShow() {
            Intent sticky = getContext().registerReceiver(rx, new IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED);
            if (sticky != null) update(sticky);
        }

        @Override
        void onHide() {
            try {
                getContext().unregisterReceiver(rx);
            } catch (IllegalArgumentException ignored) {
            }
        }

        private void update(Intent i) {
            int raw = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            level = raw >= 0 && scale > 0 ? Math.round(raw * 100f / scale) : -1;
            charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
            setContentDescription("Battery " + level + " percent" + (charging ? ", charging" : ""));
            invalidate();
        }

        @Override
        void onTap() { host.start(new Intent(Intent.ACTION_POWER_USAGE_SUMMARY), this); }

        @Override
        void drawContent(Canvas c) {
            boolean low = level >= 0 && level <= 20 && !charging;
            int lit = low || charging ? p.accent : ink(1);
            String num = level < 0 ? "--" : String.valueOf(level);
            float in = inset();
            Paint off = new Paint(Paint.ANTI_ALIAS_FLAG);
            off.setColor(ink(3));
            if (shape() == CIRCLE) {
                float cx = box.centerX(), cy = box.centerY(), r = box.width() / 2f;
                int n = 24, on = level < 0 ? 0 : Math.round(level / 100f * n);
                paint.setColor(lit);
                for (int i = 0; i < n; i++) {
                    double a = Math.toRadians(i * 360f / n - 90);
                    float x = cx + (float) (r * 0.78f * Math.cos(a)), y = cy + (float) (r * 0.78f * Math.sin(a));
                    c.drawCircle(x, y, r * 0.045f, i < on ? paint : off);
                }
                float pitch = pitchFor("100", r * 0.95f, r * 0.42f);
                float w = DotFont.columns(num) * pitch;
                dots(c, num, cx - w / 2f, cy - DotFont.ROWS * pitch / 2f, pitch, low ? p.accent : ink(1));
                return;
            }
            if (shape() == PILL) {
                float pitch = pitchFor("100%", box.width() * 0.4f, box.height() * 0.38f);
                String s = num + "%";
                dots(c, s, box.left + in * 1.4f, box.centerY() - DotFont.ROWS * pitch / 2f, pitch, low ? p.accent : ink(1));
                float x0 = box.left + box.width() * 0.52f, x1 = box.right - in * 1.4f;
                float slot = (x1 - x0) / 10f;
                int on = level < 0 ? 0 : Math.round(level / 10f);
                paint.setColor(lit);
                for (int i = 0; i < 10; i++) c.drawCircle(x0 + slot * (i + 0.5f), box.centerY(), slot * 0.28f, i < on ? paint : off);
                return;
            }
            label(c, charging ? "CHARGING" : "BATTERY", box.left + in, box.top + in + Ui.sp(10), 12, charging ? p.accent : ink(2), Paint.Align.LEFT);
            float inner = box.width() - in * 2;
            float pitch = inner / 21f;
            float top = box.centerY() - DotFont.ROWS * pitch / 2f - pitch;
            dots(c, num, box.left + in, top, pitch, low ? p.accent : ink(1));
            float small = pitch * 0.55f;
            dots(c, "%", box.left + in + (DotFont.columns(num) + 1) * pitch, top + DotFont.ROWS * (pitch - small), small, ink(2));
            float slot = inner / 10f;
            int on = level < 0 ? 0 : Math.round(level / 10f);
            paint.setColor(lit);
            for (int i = 0; i < 10; i++) c.drawCircle(box.left + in + slot * (i + 0.5f), box.bottom - in - slot / 2f, slot * 0.3f, i < on ? paint : off);
        }
    }

    // ---------------------------------------------------------------- Away Time

    static final class Away extends HelmWidget {
        private AwayTime.Stats stats;
        private boolean allowed;
        private final Runnable reload = new Runnable() {
            @Override
            public void run() {
                allowed = AwayTime.allowed(getContext());
                if (allowed) AwayTime.load(getContext(), s -> {
                    stats = s;
                    invalidate();
                });
                else invalidate();
                postDelayed(this, 5 * 60_000L);
            }
        };

        Away(Context c, Item i, Palette p, Host h) { super(c, i, p, h); }

        @Override
        boolean circleAtOne() { return false; }

        @Override
        void onShow() {
            removeCallbacks(reload);
            reload.run();
        }

        @Override
        void onHide() { removeCallbacks(reload); }

        @Override
        void onTap() {
            if (!AwayTime.allowed(getContext())) {
                host.start(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), this);
                return;
            }
            onShow();
        }

        @Override
        void drawContent(Canvas c) {
            float in = inset();
            if (!allowed) {
                paint.setColor(p.accent);
                float s = Math.min(box.width(), box.height()) * 0.28f;
                DotIcons.draw(c, "spark", box.left + in + s / 2f, box.bottom - in - s / 2f, s, paint);
                label(c, "TAP TO", box.left + in, box.top + in + Ui.sp(10), 11, ink(2), Paint.Align.LEFT);
                label(c, "ALLOW", box.left + in, box.top + in + Ui.sp(24), 11, ink(2), Paint.Align.LEFT);
                return;
            }
            String today = stats == null ? "--" : AwayTime.format(stats.today);
            if (item.w == 1) {
                float pitch = pitchFor(today.length() < 5 ? "00H00M" : today, box.width() - in * 2, box.height() * 0.3f);
                dots(c, today, box.left + in, box.top + in, pitch, ink(1));
                paint.setColor(p.accent);
                float s = box.height() * 0.24f;
                DotIcons.draw(c, "spark", box.left + in + s / 2f, box.bottom - in - s / 2f, s, paint);
                return;
            }
            label(c, "AWAY TIME", box.left + in, box.top + in + Ui.sp(10), 12, ink(2), Paint.Align.LEFT);
            paint.setColor(p.accent);
            float s = Ui.dpf(16);
            DotIcons.draw(c, "spark", box.right - in - s / 2f, box.top + in + s / 2f, s, paint);
            float pitch = pitchFor("00H00M", box.width() - in * 2, box.height() * 0.2f);
            float ty = box.top + in + Ui.sp(22);
            dots(c, today, box.left + in, ty, pitch, ink(1));
            if (stats != null) {
                label(c, "LONGEST " + AwayTime.format(stats.longest), box.left + in, ty + DotFont.ROWS * pitch + Ui.sp(16), 11, ink(2), Paint.Align.LEFT);
                long max = 1;
                for (long v : stats.week) max = Math.max(max, v);
                float bw = (box.width() - in * 2) / 7f;
                float bottom = box.bottom - in, height = box.height() * 0.28f;
                int rowsOfDots = 6;
                float step = height / rowsOfDots;
                Paint off = new Paint(Paint.ANTI_ALIAS_FLAG);
                off.setColor(ink(3));
                for (int d = 0; d < 7; d++) {
                    int lit = Math.round(stats.week[d] / (float) max * rowsOfDots);
                    paint.setColor(d == 6 ? p.accent : ink(1));
                    for (int k = 0; k < rowsOfDots; k++) {
                        c.drawCircle(box.left + in + bw * (d + 0.5f), bottom - step * (k + 0.5f), Math.min(bw, step) * 0.28f, k < lit ? paint : off);
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- Photo

    static final class Photo extends HelmWidget {
        private Bitmap image;
        private boolean loadedFor;

        Photo(Context c, Item i, Palette p, Host h) { super(c, i, p, h); }

        private File file() { return new File(getContext().getFilesDir(), "photo_" + item.id + ".png"); }

        @Override
        boolean drawsSurface() { return image == null; }

        @Override
        void onTap() {
            if (image == null) pick();
            else openOptions();
        }

        private void pick() {
            host.pickImage(uri -> {
                if (uri == null) return;
                new Thread(() -> {
                    try {
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inJustDecodeBounds = true;
                        try (InputStream in = getContext().getContentResolver().openInputStream(uri)) {
                            BitmapFactory.decodeStream(in, null, o);
                        }
                        int sample = 1;
                        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 1400) sample *= 2;
                        BitmapFactory.Options d = new BitmapFactory.Options();
                        d.inSampleSize = sample;
                        Bitmap b;
                        try (InputStream in = getContext().getContentResolver().openInputStream(uri)) {
                            b = BitmapFactory.decodeStream(in, null, d);
                        }
                        if (b == null) return;
                        int longest = Math.max(b.getWidth(), b.getHeight());
                        if (longest > 1400) {
                            // Big enough for a 4×4 tile; keeps several photo widgets well within memory.
                            float k = 1400f / longest;
                            Bitmap small = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * k), Math.round(b.getHeight() * k), true);
                            if (small != b) b.recycle();
                            b = small;
                        }
                        try (OutputStream out = new FileOutputStream(file())) {
                            // WebP keeps a sticker's transparency at a fraction of PNG's size.
                            b.compress(Bitmap.CompressFormat.WEBP_LOSSY, 92, out);
                        }
                        final Bitmap fb = b;
                        MAIN.post(() -> {
                            image = fb;
                            put("v", System.currentTimeMillis());
                            invalidate();
                        });
                    } catch (Exception ignored) {
                    }
                }).start();
            });
        }

        @Override
        void buildOptions(Rows r, LinearLayout card) {
            r.link(card, "Change photo", null, () -> {
                Sheet.dismissIn(getContext());
                pick();
            });
            r.segment(card, "Style", "Sticker suits cut-outs with a transparent background",
                    new String[]{"Frame", "Sticker"}, item.cfg.optInt("style", 0), i -> {
                        put("style", i);
                        invalidate();
                    });
            r.toggle(card, "Greyscale", null, item.cfg.optBoolean("grey", false), on -> {
                put("grey", on);
                invalidate();
            });
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            if (!loadedFor && file().exists()) {
                loadedFor = true;
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file().getPath(), o);
                int sample = 1;
                while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= Math.max(w, h)) sample *= 2;
                BitmapFactory.Options d = new BitmapFactory.Options();
                d.inSampleSize = sample;
                image = BitmapFactory.decodeFile(file().getPath(), d);
            }
        }

        @Override
        void drawContent(Canvas c) {
            if (image == null) {
                float s = Math.min(box.width(), box.height()) * 0.3f;
                paint.setColor(ink(2));
                DotIcons.draw(c, "photo", box.centerX(), box.centerY() - (item.h > 1 ? Ui.dpf(8) : 0), s, paint);
                if (item.h > 1) label(c, "ADD PHOTO", box.centerX(), box.centerY() + s / 2f + Ui.sp(14), 11, ink(2), Paint.Align.CENTER);
                return;
            }
            boolean grey = item.cfg.optBoolean("grey", false);
            Paint ip = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            if (grey) {
                ColorMatrix m = new ColorMatrix();
                m.setSaturation(0);
                ip.setColorFilter(new ColorMatrixColorFilter(m));
            }
            boolean sticker = item.cfg.optInt("style", 0) == 1;
            if (sticker && image.hasAlpha()) {
                // Fit inside, white keyline around the cut-out, like Nothing's Collector.
                float scale = Math.min(box.width() / image.getWidth(), box.height() / image.getHeight()) * 0.9f;
                float w = image.getWidth() * scale, h = image.getHeight() * scale;
                RectF dst = new RectF(box.centerX() - w / 2f, box.centerY() - h / 2f, box.centerX() + w / 2f, box.centerY() + h / 2f);
                Paint outline = new Paint(Paint.FILTER_BITMAP_FLAG);
                outline.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
                float k = Ui.dpf(4);
                for (int i = 0; i < 16; i++) {
                    double a = i * Math.PI / 8;
                    RectF o = new RectF(dst);
                    o.offset((float) (k * Math.cos(a)), (float) (k * Math.sin(a)));
                    c.drawBitmap(image, null, o, outline);
                }
                c.drawBitmap(image, null, dst, ip);
                return;
            }
            BitmapShader sh = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float scale = Math.max(box.width() / image.getWidth(), box.height() / image.getHeight());
            Matrix m = new Matrix();
            m.setScale(scale, scale);
            m.postTranslate(box.centerX() - image.getWidth() * scale / 2f, box.centerY() - image.getHeight() * scale / 2f);
            sh.setLocalMatrix(m);
            ip.setShader(sh);
            float r = radius();
            c.drawRoundRect(box, r, r, ip);
            if (sticker) {
                Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
                edge.setStyle(Paint.Style.STROKE);
                edge.setStrokeWidth(Ui.dpf(5));
                edge.setColor(Color.WHITE);
                c.drawRoundRect(box, r, r, edge);
            }
        }
    }

    // ---------------------------------------------------------------- Quick toggle

    static final String[] TOGGLES = {"torch", "dnd", "sound", "wifi", "bluetooth", "data", "lock",
            "rotate", "location", "saver", "hotspot", "camera", "recents"};
    static final String[] TOGGLE_NAMES = {"Torch", "Do Not Disturb", "Sound", "Wi-Fi", "Bluetooth", "Mobile data", "Lock screen",
            "Auto-rotate", "Location", "Battery saver", "Hotspot", "Camera", "Recent apps"};

    /** Toggles that only open something: never drawn as "on". */
    static boolean opensOnly(String which) {
        switch (which) {
            case "lock": case "data": case "hotspot": case "camera": case "recents": return true;
            default: return false;
        }
    }

    static final class Toggle extends HelmWidget {
        private boolean active;
        private String camera;
        private CameraManager.TorchCallback torchCb;
        private final BroadcastReceiver rx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) { refresh(); }
        };
        private final BroadcastReceiver btRx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(i.getAction())) refresh();
            }
        };

        Toggle(Context c, Item i, Palette p, Host h) {
            super(c, i, p, h);
            setContentDescription(name());
        }

        String which() { return item.cfg.optString("toggle", "torch"); }

        String name() {
            for (int i = 0; i < TOGGLES.length; i++) if (TOGGLES[i].equals(which())) return TOGGLE_NAMES[i];
            return "Toggle";
        }

        @Override
        void onShow() {
            Context c = getContext();
            IntentFilter f = new IntentFilter();
            f.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
            f.addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED);
            f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
            f.addAction(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
            f.addAction(android.location.LocationManager.MODE_CHANGED_ACTION);
            c.registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED);
            // Bluetooth's broadcast comes from the Bluetooth app, not the system, so it needs an exported receiver.
            c.registerReceiver(btRx, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), Context.RECEIVER_EXPORTED);
            if ("torch".equals(which())) {
                CameraManager cm = c.getSystemService(CameraManager.class);
                if (cm != null) {
                    torchCb = new CameraManager.TorchCallback() {
                        @Override
                        public void onTorchModeChanged(String id, boolean enabled) {
                            if (id.equals(torchCamera())) {
                                active = enabled;
                                invalidate();
                            }
                        }
                    };
                    cm.registerTorchCallback(torchCb, MAIN);
                }
            }
            refresh();
        }

        @Override
        void onHide() {
            try {
                getContext().unregisterReceiver(rx);
            } catch (IllegalArgumentException ignored) {
            }
            try {
                getContext().unregisterReceiver(btRx);
            } catch (IllegalArgumentException ignored) {
            }
            CameraManager cm = getContext().getSystemService(CameraManager.class);
            if (cm != null && torchCb != null) cm.unregisterTorchCallback(torchCb);
            torchCb = null;
        }

        private String torchCamera() {
            if (camera != null) return camera;
            CameraManager cm = getContext().getSystemService(CameraManager.class);
            try {
                for (String id : cm.getCameraIdList()) {
                    Boolean flash = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (Boolean.TRUE.equals(flash)) return camera = id;
                }
            } catch (CameraAccessException | RuntimeException ignored) {
            }
            return null;
        }

        private void refresh() {
            Context c = getContext();
            switch (which()) {
                case "dnd": {
                    NotificationManager nm = c.getSystemService(NotificationManager.class);
                    active = nm != null && nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL;
                    break;
                }
                case "sound": {
                    AudioManager am = c.getSystemService(AudioManager.class);
                    active = am != null && am.getRingerMode() == AudioManager.RINGER_MODE_NORMAL;
                    break;
                }
                case "wifi": {
                    WifiManager wm = c.getSystemService(WifiManager.class);
                    active = wm != null && wm.isWifiEnabled();
                    break;
                }
                case "bluetooth": {
                    BluetoothManager bm = c.getSystemService(BluetoothManager.class);
                    try {
                        active = bm != null && bm.getAdapter() != null && bm.getAdapter().isEnabled();
                    } catch (SecurityException e) {
                        active = false;
                    }
                    break;
                }
                case "rotate":
                    active = Settings.System.getInt(c.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 1;
                    break;
                case "location": {
                    android.location.LocationManager lm = c.getSystemService(android.location.LocationManager.class);
                    active = lm != null && lm.isLocationEnabled();
                    break;
                }
                case "saver": {
                    android.os.PowerManager pm = c.getSystemService(android.os.PowerManager.class);
                    active = pm != null && pm.isPowerSaveMode();
                    break;
                }
                default:
                    break;
            }
            invalidate();
        }

        @Override
        void onTap() {
            Context c = getContext();
            Ui.tick(this);
            switch (which()) {
                case "torch": {
                    String id = torchCamera();
                    if (id == null) return;
                    try {
                        c.getSystemService(CameraManager.class).setTorchMode(id, !active);
                    } catch (CameraAccessException | RuntimeException ignored) {
                    }
                    break;
                }
                case "dnd": {
                    NotificationManager nm = c.getSystemService(NotificationManager.class);
                    if (nm == null) return;
                    if (!nm.isNotificationPolicyAccessGranted()) {
                        host.start(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS), this);
                        return;
                    }
                    nm.setInterruptionFilter(active ? NotificationManager.INTERRUPTION_FILTER_ALL : NotificationManager.INTERRUPTION_FILTER_PRIORITY);
                    break;
                }
                case "sound": {
                    AudioManager am = c.getSystemService(AudioManager.class);
                    if (am == null) return;
                    int mode = am.getRingerMode();
                    NotificationManager nm = c.getSystemService(NotificationManager.class);
                    boolean canSilence = nm != null && nm.isNotificationPolicyAccessGranted();
                    int next = mode == AudioManager.RINGER_MODE_NORMAL ? AudioManager.RINGER_MODE_VIBRATE
                            : mode == AudioManager.RINGER_MODE_VIBRATE && canSilence ? AudioManager.RINGER_MODE_SILENT
                            : AudioManager.RINGER_MODE_NORMAL;
                    try {
                        am.setRingerMode(next);
                    } catch (SecurityException ignored) {
                    }
                    break;
                }
                case "wifi": host.start(new Intent(Settings.Panel.ACTION_WIFI), this); break;
                case "data": host.start(new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY), this); break;
                case "bluetooth": host.start(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS), this); break;
                case "lock": host.lockScreen(); break;
                case "rotate": {
                    if (!Settings.System.canWrite(c)) {
                        host.start(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + c.getPackageName())), this);
                        return;
                    }
                    Settings.System.putInt(c.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, active ? 0 : 1);
                    break;
                }
                case "location": host.start(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), this); break;
                case "saver": host.start(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS), this); break;
                case "hotspot": host.start(new Intent("android.settings.TETHER_SETTINGS"), this); break;
                case "camera": host.start(new Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), this); break;
                case "recents": host.openRecentsView(); break;
                default: break;
            }
            postDelayed(this::refresh, 300);
        }

        @Override
        void buildOptions(Rows r, LinearLayout card) {
            int sel = 0;
            for (int i = 0; i < TOGGLES.length; i++) if (TOGGLES[i].equals(which())) sel = i;
            r.segment(card, "Controls", null, TOGGLE_NAMES, sel, i -> {
                put("toggle", TOGGLES[i]);
                setContentDescription(TOGGLE_NAMES[i]);
                onHide();
                onShow();
            });
        }

        @Override
        public boolean performLongClick() { return super.performLongClick(); }

        @Override
        protected void onDraw(Canvas c) {
            float pad = Ui.dpf(6);
            float r = Math.min(getWidth(), getHeight()) / 2f - pad;
            box.set(getWidth() / 2f - r, getHeight() / 2f - r, getWidth() / 2f + r, getHeight() / 2f + r);
            boolean on = active && !opensOnly(which());
            if (on) {
                paint.setColor(p.accent);
                c.drawCircle(box.centerX(), box.centerY(), r, paint);
            } else {
                Material.roundRect(c, this, frost, box, r, material(), p);
            }
            drawContent(c);
        }

        @Override
        void drawContent(Canvas c) {
            boolean on = active && !opensOnly(which());
            String icon;
            switch (which()) {
                case "dnd": icon = "moon"; break;
                case "sound": {
                    AudioManager am = getContext().getSystemService(AudioManager.class);
                    int m = am == null ? AudioManager.RINGER_MODE_NORMAL : am.getRingerMode();
                    icon = m == AudioManager.RINGER_MODE_NORMAL ? "bell" : m == AudioManager.RINGER_MODE_VIBRATE ? "vibrate" : "mute";
                    break;
                }
                case "wifi": icon = "wifi"; break;
                case "bluetooth": icon = "bluetooth"; break;
                case "data": icon = "data"; break;
                case "lock": icon = "lock"; break;
                case "rotate": icon = "rotate"; break;
                case "location": icon = "location"; break;
                case "saver": icon = "saver"; break;
                case "hotspot": icon = "hotspot"; break;
                case "camera": icon = "camera"; break;
                case "recents": icon = "recents"; break;
                default: icon = "torch";
            }
            paint.setColor(on ? p.onAccent : ink(1));
            DotIcons.draw(c, icon, box.centerX(), box.centerY(), box.width() * 0.4f, paint);
        }
    }

    // ---------------------------------------------------------------- Agenda

    static final class Agenda extends HelmWidget {
        private final List<String[]> events = new ArrayList<>();
        private final Runnable reload = new Runnable() {
            @Override
            public void run() {
                load();
                postDelayed(this, 5 * 60_000L);
            }
        };

        Agenda(Context c, Item i, Palette p, Host h) { super(c, i, p, h); }

        private boolean granted() {
            return getContext().checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
        }

        @Override
        void onShow() {
            removeCallbacks(reload);
            reload.run();
        }

        @Override
        void onHide() { removeCallbacks(reload); }

        @Override
        void onTap() {
            if (!granted()) {
                host.needPermission(Manifest.permission.READ_CALENDAR, this::onShow);
                return;
            }
            host.start(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR), this);
        }

        private void load() {
            if (!granted()) {
                invalidate();
                return;
            }
            final Context c = getContext().getApplicationContext();
            new Thread(() -> {
                List<String[]> out = new ArrayList<>();
                long now = System.currentTimeMillis();
                Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
                ContentUris.appendId(b, now);
                ContentUris.appendId(b, now + 3L * 86_400_000L);
                String[] cols = {CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                        CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY};
                try (Cursor cur = c.getContentResolver().query(b.build(), cols,
                        CalendarContract.Instances.END + " > ?", new String[]{String.valueOf(now)},
                        CalendarContract.Instances.BEGIN + " ASC")) {
                    if (cur != null) {
                        java.text.DateFormat tf = DateFormat.getTimeFormat(c);
                        SimpleDateFormat day = new SimpleDateFormat("EEE", Locale.getDefault());
                        Calendar today = Calendar.getInstance();
                        while (cur.moveToNext() && out.size() < 4) {
                            String title = cur.getString(0);
                            long begin = cur.getLong(1);
                            boolean allDay = cur.getInt(3) == 1;
                            Calendar bc = Calendar.getInstance();
                            bc.setTimeInMillis(begin);
                            boolean sameDay = bc.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR);
                            String when = allDay ? "ALL DAY" : tf.format(new Date(begin));
                            if (!sameDay) when = day.format(new Date(begin)) + " " + when;
                            out.add(new String[]{TextUtils.isEmpty(title) ? "(No title)" : title, when.toUpperCase(Locale.getDefault())});
                        }
                    }
                } catch (Exception ignored) {
                }
                MAIN.post(() -> {
                    events.clear();
                    events.addAll(out);
                    invalidate();
                });
            }).start();
        }

        @Override
        void drawContent(Canvas c) {
            float in = inset();
            Date now = new Date();
            Locale loc = Locale.getDefault();
            boolean wide = item.w >= 4;
            float left = box.left + in;
            float dateW = wide ? box.width() * 0.32f : box.width();
            label(c, new SimpleDateFormat("EEE", loc).format(now).toUpperCase(loc), left, box.top + in + Ui.sp(10), 12, p.accent, Paint.Align.LEFT);
            float big = wide ? box.height() * 0.46f : box.height() * 0.26f;
            words(c, new SimpleDateFormat("d", loc).format(now), left - Ui.dpf(2), box.top + in + Ui.sp(14) + big * 0.9f, big, ink(1), Ui.headline(getContext()), Paint.Align.LEFT);
            if (wide) label(c, new SimpleDateFormat("MMMM", loc).format(now).toUpperCase(loc), left, box.bottom - in, 11, ink(2), Paint.Align.LEFT);
            float ex = wide ? box.left + dateW : left;
            float ey = wide ? box.top + in : box.top + in + Ui.sp(14) + big * 1.05f;
            float ew = box.right - in - ex;
            if (!granted()) {
                label(c, "TAP TO SHOW", ex, ey + Ui.sp(12), 11, ink(2), Paint.Align.LEFT);
                label(c, "YOUR CALENDAR", ex, ey + Ui.sp(26), 11, ink(2), Paint.Align.LEFT);
                return;
            }
            if (events.isEmpty()) {
                label(c, "NOTHING SCHEDULED", ex, ey + Ui.sp(12), 11, ink(2), Paint.Align.LEFT);
                return;
            }
            float rowH = Ui.sp(40);
            int fit = Math.max(1, (int) ((box.bottom - in - ey) / rowH));
            for (int i = 0; i < Math.min(fit, events.size()); i++) {
                float y = ey + i * rowH;
                paint.setColor(i == 0 ? p.accent : ink(3));
                c.drawCircle(ex + Ui.dpf(3), y + Ui.sp(8), Ui.dpf(3), paint);
                label(c, events.get(i)[1], ex + Ui.dpf(12), y + Ui.sp(12), 11, ink(2), Paint.Align.LEFT);
                text.setTypeface(Ui.SANS);
                text.setTextSize(Ui.sp(14));
                text.setLetterSpacing(0);
                CharSequence t = TextUtils.ellipsize(events.get(i)[0], text, ew - Ui.dpf(12), TextUtils.TruncateAt.END);
                words(c, t.toString(), ex + Ui.dpf(12), y + Ui.sp(30), Ui.sp(14), ink(1), Ui.SANS, Paint.Align.LEFT);
            }
        }
    }

    // ---------------------------------------------------------------- Note

    static final class Note extends HelmWidget {
        Note(Context c, Item i, Palette p, Host h) { super(c, i, p, h); }

        @Override
        boolean circleAtOne() { return false; }

        @Override
        void onTap() {
            Context c = getContext();
            Rows r = new Rows(c, p);
            LinearLayout col = r.column();
            EditText field = new EditText(c);
            field.setText(item.cfg.optString("text", ""));
            field.setHint("Write something");
            field.setTextColor(p.onSolid);
            field.setHintTextColor(p.onSolid3);
            field.setTypeface(Ui.SANS);
            field.setTextSize(17);
            field.setGravity(Gravity.TOP | Gravity.START);
            field.setMinLines(4);
            field.setBackground(Ui.rounded(p.solid, 20));
            field.setPadding(Ui.dp(18), Ui.dp(16), Ui.dp(18), Ui.dp(16));
            col.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            r.button(col, "Save", true, () -> {
                put("text", field.getText().toString());
                invalidate();
                Sheet.dismissIn(getContext());
            });
            LinearLayout card = r.card(col);
            r.segment(card, "Surface", null, new String[]{"Auto", "Solid", "Glass", "Clear"},
                    item.cfg.optInt("material", Material.AUTO) + 1, i -> {
                        put("material", i - 1);
                        invalidate();
                    });
            Sheet.show(host.sheetHost(), p, "Note", col);
            field.requestFocus();
        }

        @Override
        void drawContent(Canvas c) {
            float in = inset();
            String s = item.cfg.optString("text", "");
            boolean oneRow = item.h == 1;
            if (!oneRow) label(c, "NOTE", box.left + in, box.top + in + Ui.sp(10), 11, ink(3), Paint.Align.LEFT);
            if (s.isEmpty()) {
                label(c, "TAP TO WRITE", box.left + in, oneRow ? box.centerY() + Ui.sp(4) : box.top + in + Ui.sp(30), 11, ink(2), Paint.Align.LEFT);
                return;
            }
            text.setTypeface(Ui.SANS);
            text.setTextSize(Ui.sp(oneRow ? 15 : 16));
            text.setLetterSpacing(0);
            text.setColor(ink(1));
            text.setTextAlign(Paint.Align.LEFT);
            int w = Math.max(1, Math.round(box.width() - in * 2));
            StaticLayout layout = StaticLayout.Builder.obtain(s, 0, s.length(), text, w)
                    .setMaxLines(oneRow ? 1 : Math.max(1, (int) ((box.height() - in * 2 - Ui.sp(18)) / (Ui.sp(16) * 1.3f))))
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .build();
            c.save();
            float top = oneRow ? box.centerY() - layout.getHeight() / 2f : box.top + in + Ui.sp(20);
            c.translate(box.left + in, top);
            layout.draw(c);
            c.restore();
        }
    }
}
