package com.helm.launcher;

import android.app.DatePickerDialog;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Screen Time and Countdown: two more of Nothing's everyday widgets. */
final class ExtraWidgets {
    private ExtraWidgets() {}

    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // ---------------------------------------------------------------- Screen time

    static final class ScreenTime extends HelmWidget {
        private long total = -1;
        private final List<String[]> top = new ArrayList<>(); // {label, millis}
        private final Runnable refresh = this::load;

        ScreenTime(Context c, Item i, Palette p, Host h) { super(c, i, p, h); }

        @Override
        boolean circleAtOne() { return false; }

        @Override
        void onShow() { load(); }

        @Override
        void onHide() { removeCallbacks(refresh); }

        @Override
        void onTap() {
            if (!AwayTime.allowed(getContext())) {
                host.start(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), this);
                return;
            }
            // Samsung's Digital Wellbeing, else Android's usage settings.
            Intent dw = new Intent().setClassName("com.samsung.android.forest", "com.samsung.android.forest.launcher.LauncherActivity");
            if (getContext().getPackageManager().resolveActivity(dw, 0) == null) dw = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            host.start(dw, this);
        }

        private void load() {
            removeCallbacks(refresh);
            if (!AwayTime.allowed(getContext())) {
                total = -1;
                invalidate();
                return;
            }
            Context app = getContext().getApplicationContext();
            WORK.execute(() -> {
                long sum = 0;
                List<String[]> best = new ArrayList<>();
                try {
                    Calendar cal = Calendar.getInstance();
                    cal.set(Calendar.HOUR_OF_DAY, 0);
                    cal.set(Calendar.MINUTE, 0);
                    cal.set(Calendar.SECOND, 0);
                    cal.set(Calendar.MILLISECOND, 0);
                    UsageStatsManager usm = app.getSystemService(UsageStatsManager.class);
                    Set<String> skip = new HashSet<>();
                    skip.add(app.getPackageName());
                    skip.add("com.android.systemui");
                    for (ResolveInfo ri : app.getPackageManager().queryIntentActivities(
                            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)) {
                        skip.add(ri.activityInfo.packageName);
                    }
                    // Foreground time since local midnight, from resume/pause events (daily buckets overlap yesterday).
                    long now = System.currentTimeMillis();
                    java.util.Map<String, Long> open = new java.util.HashMap<>();
                    java.util.Map<String, Long> used = new java.util.HashMap<>();
                    if (usm != null) {
                        android.app.usage.UsageEvents ev = usm.queryEvents(cal.getTimeInMillis(), now);
                        android.app.usage.UsageEvents.Event e = new android.app.usage.UsageEvents.Event();
                        while (ev != null && ev.hasNextEvent()) {
                            ev.getNextEvent(e);
                            String pkg = e.getPackageName();
                            int type = e.getEventType();
                            if (type == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                                open.put(pkg, e.getTimeStamp());
                            } else if (type == android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED
                                    || type == android.app.usage.UsageEvents.Event.ACTIVITY_STOPPED) {
                                Long start = open.remove(pkg);
                                if (start != null) used.merge(pkg, e.getTimeStamp() - start, Long::sum);
                            }
                        }
                    }
                    for (java.util.Map.Entry<String, Long> en : open.entrySet()) used.merge(en.getKey(), now - en.getValue(), Long::sum);
                    List<String[]> list = new ArrayList<>();
                    for (java.util.Map.Entry<String, Long> en : used.entrySet()) {
                        if (skip.contains(en.getKey()) || en.getValue() < 60_000) continue;
                        sum += en.getValue();
                        list.add(new String[]{en.getKey(), String.valueOf(en.getValue())});
                    }
                    list.sort((x, y) -> Long.compare(Long.parseLong(y[1]), Long.parseLong(x[1])));
                    AppRepository repo = AppRepository.get(app);
                    for (String[] u : list) {
                        if (best.size() >= 3) break;
                        AppEntry e = repo.firstForPackage(u[0]);
                        if (e == null) continue;
                        best.add(new String[]{e.label, u[1]});
                    }
                } catch (RuntimeException ignored) {
                    // Usage stats unavailable right now; show what we have.
                }
                long fs = sum;
                MAIN.post(() -> {
                    total = fs;
                    top.clear();
                    top.addAll(best);
                    invalidate();
                    if (isShown2()) postDelayed(refresh, 5 * 60_000);
                });
            });
        }

        @Override
        void drawContent(Canvas c) {
            float in = inset();
            if (total < 0) {
                paint.setColor(ink(1));
                float s = Math.min(box.width(), box.height()) * 0.3f;
                DotIcons.draw(c, "timer", box.left + in + s / 2f, box.top + in + s / 2f, s, paint);
                label(c, "TAP TO ALLOW", box.left + in, box.bottom - in - Ui.sp(14), 11, ink(2), Paint.Align.LEFT);
                label(c, "SCREEN TIME", box.left + in, box.bottom - in, 11, ink(2), Paint.Align.LEFT);
                return;
            }
            String t = AwayTime.format(total).replace(" ", "");
            if (item.w == 1 && item.h == 1) {
                float pitch = pitchFor(t, box.width() - in * 1.4f, box.height() * 0.3f);
                float w = DotFont.columns(t) * pitch;
                dots(c, t, box.centerX() - w / 2f, box.centerY() - DotFont.ROWS * pitch / 2f, pitch, ink(1));
                return;
            }
            label(c, "SCREEN TIME", box.left + in, box.top + in + Ui.sp(10), 12, ink(2), Paint.Align.LEFT);
            float pitch = pitchFor(t, box.width() - in * 2, item.h >= 2 ? box.height() * 0.2f : box.height() * 0.36f);
            float ty = box.top + in + Ui.sp(20);
            dotsAccent(c, t, box.left + in, ty, pitch, ink(1), "HM");
            if (item.h < 2 || top.isEmpty()) return;
            // The top apps as dot bars, longest first.
            long most = Math.max(1, Long.parseLong(top.get(0)[1]));
            float y = ty + DotFont.ROWS * pitch + Ui.dpf(18);
            float rowH = Math.max(Ui.sp(26), (box.bottom - in - y) / 3f);
            for (String[] a : top) {
                if (y + rowH > box.bottom - in + Ui.dpf(4)) break;
                label(c, a[0].toUpperCase(Locale.getDefault()), box.left + in, y + Ui.sp(10), 10, ink(2), Paint.Align.LEFT);
                long ms = Long.parseLong(a[1]);
                float barPitch = Ui.dpf(6);
                int n = Math.max(1, (int) ((box.width() - in * 2) / barPitch));
                int lit = Math.max(1, Math.round(n * ms / (float) most));
                for (int k = 0; k < n; k++) {
                    paint.setColor(k < lit ? ink(1) : ink(3));
                    c.drawCircle(box.left + in + barPitch * (k + 0.5f), y + Ui.sp(18), barPitch * 0.26f, paint);
                }
                y += rowH;
            }
        }
    }

    // ---------------------------------------------------------------- Countdown

    static final class Countdown extends Widgets.Ticking {
        Countdown(Context c, Item i, Palette p, Host h) { super(c, i, p, h); }

        @Override
        boolean circleAtOne() { return false; }

        @Override
        void onTap() { openOptions(); }

        private long target() {
            long t = item.cfg.optLong("target", 0);
            if (t > 0) return t;
            Calendar c = Calendar.getInstance();
            c.add(Calendar.YEAR, 1);
            c.set(Calendar.MONTH, Calendar.JANUARY);
            c.set(Calendar.DAY_OF_MONTH, 1);
            return startOfDay(c);
        }

        private static long startOfDay(Calendar c) {
            c.set(Calendar.HOUR_OF_DAY, 0);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            return c.getTimeInMillis();
        }

        private String title() {
            String t = item.cfg.optString("title", "");
            return t.isEmpty() ? "New year" : t;
        }

        private long days() {
            long today = startOfDay(Calendar.getInstance());
            return Math.round((target() - today) / 86_400_000.0);
        }

        @Override
        void buildOptions(Rows r, LinearLayout card) {
            Context c = getContext();
            EditText field = new EditText(c);
            field.setText(item.cfg.optString("title", ""));
            field.setHint("What are you counting to?");
            field.setSingleLine(true);
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            field.setTextColor(p.onSolid);
            field.setHintTextColor(p.onSolid3);
            field.setTypeface(Ui.SANS);
            field.setTextSize(17);
            field.setBackground(null);
            field.setPadding(Ui.dp(18), Ui.dp(14), Ui.dp(18), Ui.dp(14));
            field.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) {}

                @Override public void onTextChanged(CharSequence s, int a, int b, int d) {}

                @Override
                public void afterTextChanged(android.text.Editable s) {
                    put("title", s.toString().trim());
                    invalidate();
                }
            });
            card.addView(field);
            Calendar t = Calendar.getInstance();
            t.setTimeInMillis(target());
            String when = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(t.getTime());
            r.link(card, "Date", when, () -> {
                Sheet.dismissIn(getContext());
                new DatePickerDialog(c, (view, y, m, d) -> {
                    Calendar pick = Calendar.getInstance();
                    pick.set(y, m, d);
                    put("target", startOfDay(pick));
                    invalidate();
                }, t.get(Calendar.YEAR), t.get(Calendar.MONTH), t.get(Calendar.DAY_OF_MONTH)).show();
            });
        }

        @Override
        void drawContent(Canvas c) {
            float in = inset();
            long d = days();
            String n = String.valueOf(Math.abs(d));
            String unit = d == 0 ? "TODAY" : (Math.abs(d) == 1 ? "DAY" : "DAYS") + (d < 0 ? " SINCE" : " TO");
            if (item.h == 1 && item.w >= 2) {
                float size = box.height() * 0.62f;
                words(c, n, box.left + in, box.centerY() + size * 0.36f, size, ink(1), Ui.headline(getContext()), Paint.Align.LEFT);
                text.setTextSize(size);
                float x = box.left + in + text.measureText(n) + Ui.dpf(10);
                label(c, unit, x, box.centerY() - Ui.sp(2), 10, p.accent, Paint.Align.LEFT);
                label(c, title().toUpperCase(Locale.getDefault()), x, box.centerY() + Ui.sp(12), 10, ink(2), Paint.Align.LEFT);
                return;
            }
            label(c, unit, box.left + in, box.top + in + Ui.sp(10), 11, p.accent, Paint.Align.LEFT);
            text.setTypeface(Ui.headline(getContext()));
            text.setTextSize(100f); // Measured large: tiny sizes round their advances.
            float size = Math.min((box.width() - in * 2) * 100f / Math.max(1f, text.measureText(n)), box.height() * 0.52f);
            words(c, n, box.left + in - Ui.dpf(2), box.top + box.height() * 0.70f, size, ink(1), Ui.headline(getContext()), Paint.Align.LEFT);
            label(c, title().toUpperCase(Locale.getDefault()), box.left + in, box.bottom - in, 11, ink(2), Paint.Align.LEFT);
        }
    }

    // ---------------------------------------------------------------- Steps

    /**
     * Today's steps from the phone's step counter (counts since boot). The day's baseline is the last
     * reading from the day before, kept in the widget, so steps taken before you look still count.
     */
    static final class Steps extends HelmWidget implements android.hardware.SensorEventListener {
        static final int[] GOALS = {5000, 8000, 10000, 12000};
        private long today = -1;
        private boolean noSensor;

        Steps(Context c, Item i, Palette p, Host h) { super(c, i, p, h); }

        @Override
        boolean roundFace() { return true; }

        private int goal() { return item.cfg.optInt("goal", 10000); }

        private boolean allowed() {
            return getContext().checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }

        @Override
        void onShow() {
            if (!allowed()) {
                invalidate();
                return;
            }
            android.hardware.SensorManager sm = getContext().getSystemService(android.hardware.SensorManager.class);
            android.hardware.Sensor s = sm == null ? null : sm.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_COUNTER);
            noSensor = s == null;
            if (s != null) sm.registerListener(this, s, android.hardware.SensorManager.SENSOR_DELAY_NORMAL);
            invalidate();
        }

        @Override
        void onHide() {
            android.hardware.SensorManager sm = getContext().getSystemService(android.hardware.SensorManager.class);
            if (sm != null) sm.unregisterListener(this);
            host.save();
        }

        @Override
        public void onSensorChanged(android.hardware.SensorEvent e) {
            long counter = (long) e.values[0];
            Calendar cal = Calendar.getInstance();
            int day = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR);
            int savedDay = item.cfg.optInt("day", 0);
            long last = item.cfg.optLong("last", -1);
            long base = item.cfg.optLong("base", -1);
            try {
                if (savedDay != day) {
                    // New day: start from yesterday's last reading, if the counter hasn't reset since.
                    base = last >= 0 && last <= counter ? last : counter;
                    item.cfg.put("day", day).put("base", base);
                } else if (base < 0 || counter < base) {
                    // First reading ever, or the phone restarted today (the counter starts again at 0).
                    base = base < 0 ? counter : 0;
                    item.cfg.put("base", base);
                }
                item.cfg.put("last", counter);
            } catch (org.json.JSONException ignored) {
            }
            long t = Math.max(0, counter - base);
            if (t != today) {
                today = t;
                invalidate();
            }
        }

        @Override
        public void onAccuracyChanged(android.hardware.Sensor sensor, int accuracy) {}

        @Override
        void onTap() {
            if (!allowed()) {
                host.needPermission(android.Manifest.permission.ACTIVITY_RECOGNITION, () -> {
                    onHide();
                    onShow();
                });
                return;
            }
            openOptions();
        }

        @Override
        void buildOptions(Rows r, LinearLayout card) {
            String[] names = new String[GOALS.length];
            int sel = 2;
            for (int i = 0; i < GOALS.length; i++) {
                names[i] = String.format(Locale.getDefault(), "%,d", GOALS[i]);
                if (GOALS[i] == goal()) sel = i;
            }
            r.segment(card, "Daily goal", null, names, sel, i -> {
                put("goal", GOALS[i]);
                invalidate();
            });
        }

        @Override
        void drawContent(Canvas c) {
            float cx = box.centerX(), cy = box.centerY(), r = box.width() / 2f;
            if (!allowed() || noSensor) {
                paint.setColor(ink(1));
                DotIcons.draw(c, "location", cx, cy - (r > Ui.dpf(50) ? r * 0.15f : 0), r * 0.5f, paint);
                if (r > Ui.dpf(50)) label(c, noSensor ? "NO SENSOR" : "TAP TO ALLOW", cx, cy + r * 0.45f, 10, ink(2), Paint.Align.CENTER);
                return;
            }
            long t = Math.max(0, today);
            int n = 40;
            int lit = (int) Math.min(n, Math.round(n * t / (double) Math.max(1, goal())));
            for (int i = 0; i < n; i++) {
                double a = Math.toRadians(i * (360.0 / n) - 90);
                paint.setColor(i < lit ? p.accent : ink(3));
                c.drawCircle(cx + (float) (r * 0.82f * Math.cos(a)), cy + (float) (r * 0.82f * Math.sin(a)),
                        Math.max(Ui.dpf(1.4f), r * 0.032f), paint);
            }
            String num = today < 0 ? "-" : String.valueOf(t);
            float pitch = pitchFor(num, r * 1.15f, r * 0.42f);
            float w = DotFont.columns(num) * pitch;
            dots(c, num, cx - w / 2f, cy - DotFont.ROWS * pitch / 2f - (r > Ui.dpf(50) ? r * 0.06f : 0), pitch, ink(1));
            if (r > Ui.dpf(50)) label(c, "STEPS", cx, cy + r * 0.42f, 10, ink(2), Paint.Align.CENTER);
        }
    }

    // ---------------------------------------------------------------- Quick dial

    /** One person, one tap: opens the dialler with their number (Helm never places calls itself). */
    static final class Contact extends HelmWidget {
        Contact(Context c, Item i, Palette p, Host h) { super(c, i, p, h); }

        private String name() { return item.cfg.optString("name", ""); }

        private String number() { return item.cfg.optString("number", ""); }

        @Override
        void onTap() {
            if (number().isEmpty()) {
                pick();
                return;
            }
            host.start(new Intent(Intent.ACTION_DIAL, android.net.Uri.fromParts("tel", number(), null)), this);
        }

        private void pick() {
            host.pickContact(uri -> {
                if (uri == null) return;
                try (android.database.Cursor cur = getContext().getContentResolver().query(uri, new String[]{
                        android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER}, null, null, null)) {
                    if (cur != null && cur.moveToFirst()) {
                        put("name", cur.getString(0) == null ? "" : cur.getString(0));
                        put("number", cur.getString(1) == null ? "" : cur.getString(1));
                        setContentDescription("Call " + name());
                        invalidate();
                    }
                } catch (RuntimeException ignored) {
                    // The picker's one-time read grant expired or the row vanished.
                }
            });
        }

        @Override
        void buildOptions(Rows r, LinearLayout card) {
            r.link(card, "Choose contact", name().isEmpty() ? null : name(), () -> {
                Sheet.dismissIn(getContext());
                pick();
            });
        }

        @Override
        void drawContent(Canvas c) {
            String n = name();
            if (n.isEmpty()) {
                paint.setColor(ink(2));
                float s = Math.min(box.width(), box.height()) * 0.3f;
                DotIcons.draw(c, "plus", box.centerX(), box.centerY() - (item.h > 1 ? Ui.dpf(8) : 0), s, paint);
                if (item.h > 1) label(c, "ADD CONTACT", box.centerX(), box.centerY() + s / 2f + Ui.sp(14), 11, ink(2), Paint.Align.CENTER);
                return;
            }
            // Initials in dots, the name in mono: Nothing's quick-contact tile.
            StringBuilder ini = new StringBuilder();
            for (String part : n.trim().split("\\s+")) {
                if (!part.isEmpty() && ini.length() < 2) ini.append(Character.toUpperCase(part.charAt(0)));
            }
            String initials = ini.length() == 0 ? "?" : ini.toString();
            for (int k = 0; k < initials.length(); k++) {
                if (!DotFont.has(initials.charAt(k))) {
                    initials = "?";
                    break;
                }
            }
            float in = inset();
            if (item.h == 1 && item.w == 1) {
                float pitch = pitchFor(initials, box.width() * 0.5f, box.height() * 0.4f);
                float w = DotFont.columns(initials) * pitch;
                dots(c, initials, box.centerX() - w / 2f, box.centerY() - DotFont.ROWS * pitch / 2f, pitch, ink(1));
                return;
            }
            float pitch = pitchFor(initials, box.width() * 0.45f, box.height() * 0.32f);
            dots(c, initials, box.left + in, box.top + in, pitch, ink(1));
            paint.setColor(p.accent);
            c.drawCircle(box.right - in - Ui.dpf(5), box.top + in + Ui.dpf(5), Ui.dpf(4), paint);
            label(c, n.toUpperCase(Locale.getDefault()), box.left + in, box.bottom - in, 11, ink(2), Paint.Align.LEFT);
        }
    }
}
