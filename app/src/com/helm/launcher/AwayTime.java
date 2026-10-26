package com.helm.launcher;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.util.Calendar;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Nothing OS 5's Away Time: how long the screen has been off today, the longest break, and the last week.
 * Built from Android's own screen on/off events (needs Usage access, granted by the user in Settings).
 */
final class AwayTime {
    static final class Stats {
        long today, longest;
        final long[] week = new long[7]; // oldest first; last is today
    }

    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AwayTime() {}

    @SuppressWarnings("deprecation")
    static boolean allowed(Context c) {
        AppOpsManager ops = c.getSystemService(AppOpsManager.class);
        if (ops == null) return false;
        int mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    static void load(Context c, Consumer<Stats> done) {
        final Context app = c.getApplicationContext();
        WORK.execute(() -> {
            Stats s = compute(app);
            MAIN.post(() -> done.accept(s));
        });
    }

    private static Stats compute(Context c) {
        Stats s = new Stats();
        UsageStatsManager usm = c.getSystemService(UsageStatsManager.class);
        if (usm == null) return s;
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long todayStart = cal.getTimeInMillis();
        long[] dayStart = new long[8];
        for (int i = 0; i < 8; i++) {
            Calendar d = (Calendar) cal.clone();
            d.add(Calendar.DAY_OF_YEAR, i - 6);
            dayStart[i] = d.getTimeInMillis(); // dayStart[6] = today, dayStart[7] = tomorrow
        }
        long now = System.currentTimeMillis();
        long from = dayStart[0] - 12 * 3_600_000L;
        UsageEvents events = usm.queryEvents(from, now);
        UsageEvents.Event e = new UsageEvents.Event();
        long offSince = -1;
        boolean seen = false;
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            int t = e.getEventType();
            if (t == UsageEvents.Event.SCREEN_NON_INTERACTIVE) {
                if (offSince < 0) offSince = e.getTimeStamp();
                seen = true;
            } else if (t == UsageEvents.Event.SCREEN_INTERACTIVE) {
                if (offSince >= 0) add(s, dayStart, todayStart, offSince, e.getTimeStamp());
                else if (!seen) add(s, dayStart, todayStart, from, e.getTimeStamp());
                offSince = -1;
                seen = true;
            }
        }
        // Screen is on now (the widget is being looked at), so any open "off" period ended moments ago.
        if (offSince >= 0) add(s, dayStart, todayStart, offSince, now);
        return s;
    }

    private static void add(Stats s, long[] dayStart, long todayStart, long a, long b) {
        if (b <= a) return;
        for (int i = 0; i < 7; i++) {
            long lo = Math.max(a, dayStart[i]), hi = Math.min(b, dayStart[i + 1]);
            if (hi > lo) s.week[i] += hi - lo;
        }
        long lo = Math.max(a, todayStart);
        if (b > lo) {
            s.today += b - lo;
            s.longest = Math.max(s.longest, b - lo);
        }
    }

    /** "8H19M" */
    static String format(long ms) {
        long m = ms / 60000L;
        long h = m / 60;
        m %= 60;
        return h > 0 ? h + "H" + (m < 10 ? "0" : "") + m + "M" : m + "M";
    }
}
