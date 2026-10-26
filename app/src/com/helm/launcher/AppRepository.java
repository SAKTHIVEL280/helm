package com.helm.launcher;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.LruCache;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Installed apps across every profile (main, work, Samsung Dual Messenger), their renames, hidden state
 * and launch history, plus a cache of rendered icons. Icons paint off the main thread, newest request first.
 */
final class AppRepository {
    interface Listener { void onAppsChanged(); }

    private static AppRepository instance;

    static AppRepository get(Context c) {
        if (instance == null) instance = new AppRepository(c.getApplicationContext());
        return instance;
    }

    final LauncherApps launcherApps;
    private final Context ctx;
    private final UserManager users;
    private final SharedPreferences usage;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final ExecutorService painter;
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, AppEntry> byKey = new HashMap<>();
    private final LruCache<String, Bitmap> icons;
    private final Map<String, List<Consumer<Bitmap>>> waiting = new HashMap<>();
    private final Runnable reloadSoon = this::reload;

    private List<AppEntry> apps = Collections.emptyList();
    private boolean loaded;
    private int generation;
    private IconFactory.Spec spec;
    private String specKey = "";
    private int iconPx = 1;

    private AppRepository(Context c) {
        ctx = c;
        launcherApps = c.getSystemService(LauncherApps.class);
        users = c.getSystemService(UserManager.class);
        usage = c.getSharedPreferences("usage", Context.MODE_PRIVATE);

        int kb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        icons = new LruCache<String, Bitmap>(kb) {
            @Override
            protected int sizeOf(String key, Bitmap b) { return b.getAllocationByteCount() / 1024; }
        };

        painter = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
                new LinkedBlockingDeque<Runnable>() {
                    @Override
                    public boolean offer(Runnable r) { return offerFirst(r); }
                },
                r -> new Thread(() -> {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    r.run();
                }, "helm-icons"));

        launcherApps.registerCallback(new LauncherApps.Callback() {
            @Override public void onPackageRemoved(String pkg, UserHandle user) { changed(pkg); }
            @Override public void onPackageAdded(String pkg, UserHandle user) { changed(pkg); }
            @Override public void onPackageChanged(String pkg, UserHandle user) { changed(pkg); }
            @Override public void onPackagesAvailable(String[] pkgs, UserHandle user, boolean replacing) { changed(null); }
            @Override public void onPackagesUnavailable(String[] pkgs, UserHandle user, boolean replacing) { changed(null); }
        }, main);

        IntentFilter profiles = new IntentFilter();
        profiles.addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE);
        profiles.addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE);
        profiles.addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED);
        c.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) { changed(null); }
        }, profiles, Context.RECEIVER_NOT_EXPORTED);
    }

    private void changed(String pkg) {
        if (pkg == null) {
            generation++;
            icons.evictAll();
            waiting.clear();
        } else {
            for (String k : new ArrayList<>(icons.snapshot().keySet())) {
                if (k.startsWith(pkg + "/")) icons.remove(k);
            }
        }
        main.removeCallbacks(reloadSoon);
        main.postDelayed(reloadSoon, 250);
    }

    void reload() {
        loader.execute(() -> {
            List<AppEntry> list;
            try {
                list = query();
            } catch (RuntimeException e) {
                return; // A profile locked mid-query; keep the current list, the next change reloads.
            }
            main.post(() -> {
                apps = list;
                byKey.clear();
                for (AppEntry e : list) byKey.put(e.key, e);
                loaded = true;
                for (Listener l : new ArrayList<>(listeners)) l.onAppsChanged();
            });
        });
    }

    private List<AppEntry> query() {
        List<AppEntry> out = new ArrayList<>();
        UserHandle me = Process.myUserHandle();
        String self = ctx.getPackageName();
        Prefs prefs = Prefs.get(ctx);
        for (UserHandle u : launcherApps.getProfiles()) {
            boolean mainUser = u.equals(me);
            if (!mainUser && users.isQuietModeEnabled(u)) continue;
            long serial = users.getSerialNumberForUser(u);
            for (LauncherActivityInfo i : launcherApps.getActivityList(null, u)) {
                ComponentName cn = i.getComponentName();
                if (cn.getPackageName().equals(self) && cn.getClassName().equals(HelmActivity.class.getName())) continue;
                String key = cn.flattenToShortString() + "#" + serial;
                out.add(new AppEntry(i, serial, mainUser, prefs.label(key)));
            }
        }
        Collator collator = Collator.getInstance();
        collator.setStrength(Collator.SECONDARY);
        out.sort((a, b) -> {
            if (a.section != b.section) return Character.compare(a.section, b.section);
            int c = collator.compare(a.label, b.label);
            if (c != 0) return c;
            return Boolean.compare(!a.mainUser, !b.mainUser);
        });
        return out;
    }

    boolean loaded() { return loaded; }

    List<AppEntry> apps() { return apps; }

    /** Apps not hidden by the user. */
    List<AppEntry> visibleApps() {
        Set<String> hidden = Prefs.get(ctx).hidden();
        if (hidden.isEmpty()) return apps;
        List<AppEntry> out = new ArrayList<>();
        for (AppEntry e : apps) if (!hidden.contains(e.key)) out.add(e);
        return out;
    }

    List<AppEntry> hiddenApps() {
        Set<String> hidden = Prefs.get(ctx).hidden();
        List<AppEntry> out = new ArrayList<>();
        for (AppEntry e : apps) if (hidden.contains(e.key)) out.add(e);
        return out;
    }

    AppEntry find(String key) { return key == null ? null : byKey.get(key); }

    AppEntry firstForPackage(String pkg) {
        if (pkg == null) return null;
        for (AppEntry e : apps) if (e.mainUser && e.packageName().equals(pkg)) return e;
        return null;
    }

    void addListener(Listener l) { listeners.add(l); }

    void removeListener(Listener l) { listeners.remove(l); }

    // Launch history: counts, recency and time of day drive suggestions and "most used" sorting.

    void recordLaunch(String key) {
        String[] f = usage.getString(key, "0|0|").split("\\|", -1);
        int count = parse(f[0]) + 1;
        int[] hours = hours(f.length > 2 ? f[2] : "");
        hours[Calendar.getInstance().get(Calendar.HOUR_OF_DAY)]++;
        StringBuilder hs = new StringBuilder();
        for (int i = 0; i < 24; i++) {
            if (i > 0) hs.append(',');
            hs.append(hours[i]);
        }
        usage.edit().putString(key, count + "|" + System.currentTimeMillis() + "|" + hs).apply();
    }

    int launches(String key) {
        return parse(usage.getString(key, "0|0|").split("\\|", -1)[0]);
    }

    /** Higher is more likely wanted right now. */
    double score(String key) {
        String[] f = usage.getString(key, "0|0|").split("\\|", -1);
        int count = parse(f[0]);
        if (count == 0) return 0;
        long last = f.length > 1 ? parseLong(f[1]) : 0;
        int[] hours = hours(f.length > 2 ? f[2] : "");
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int near = hours[h] + (hours[(h + 23) % 24] + hours[(h + 1) % 24]) / 2;
        long age = System.currentTimeMillis() - last;
        double recency = age < 3_600_000L ? 1.2 : age < 86_400_000L ? 0.6 : 0;
        return Math.log(1 + count) + 3.0 * near / count + recency;
    }

    List<AppEntry> suggestions(int n) {
        List<AppEntry> pool = new ArrayList<>(visibleApps());
        pool.removeIf(e -> score(e.key) <= 0);
        pool.sort((a, b) -> Double.compare(score(b.key), score(a.key)));
        return pool.size() > n ? new ArrayList<>(pool.subList(0, n)) : pool;
    }

    private static int[] hours(String s) {
        int[] out = new int[24];
        if (s.isEmpty()) return out;
        String[] parts = s.split(",");
        for (int i = 0; i < Math.min(24, parts.length); i++) out[i] = parse(parts[i]);
        return out;
    }

    private static int parse(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
    }

    // Icons

    void configure(IconFactory.Spec s, int px) {
        String k = s.p.dark + "|" + s.p.translucent + "|" + s.p.accent + "|" + s.style + "|" + s.fallback + "|" + s.shape + "|" + s.pack + "|" + px;
        if (k.equals(specKey)) return;
        specKey = k;
        spec = s;
        iconPx = px;
        generation++;
        icons.evictAll();
        waiting.clear();
    }

    int iconPx() { return iconPx; }

    Bitmap peekIcon(AppEntry e) { return icons.get(e.key); }

    /** Calls back on the main thread; {@code cb} may be null to just warm the cache. */
    void requestIcon(AppEntry e, Consumer<Bitmap> cb) {
        Bitmap hit = icons.get(e.key);
        if (hit != null) {
            if (cb != null) cb.accept(hit);
            return;
        }
        if (spec == null) return;
        List<Consumer<Bitmap>> queue = waiting.get(e.key);
        if (queue != null) {
            if (cb != null) queue.add(cb);
            return;
        }
        queue = new ArrayList<>();
        if (cb != null) queue.add(cb);
        waiting.put(e.key, queue);

        final int gen = generation;
        final IconFactory.Spec s = spec;
        final int px = iconPx;
        painter.execute(() -> {
            Bitmap b;
            try {
                b = IconFactory.render(ctx, e, px, s);
            } catch (Throwable t) {
                b = IconFactory.blank(px, s.p);
            }
            final Bitmap done = b;
            main.post(() -> {
                if (gen != generation) return;
                icons.put(e.key, done);
                List<Consumer<Bitmap>> w = waiting.remove(e.key);
                if (w != null) for (Consumer<Bitmap> c : w) c.accept(done);
            });
        });
    }

    /** A one-off icon at a larger size (big 2×2 home icons), same style; not cached. */
    void renderLarge(AppEntry e, int px, Consumer<Bitmap> cb) {
        if (spec == null) return;
        final IconFactory.Spec s = spec;
        final int gen = generation;
        painter.execute(() -> {
            Bitmap b;
            try {
                b = IconFactory.render(ctx, e, px, s);
            } catch (Throwable t) {
                b = null;
            }
            final Bitmap done = b;
            main.post(() -> {
                if (done != null && gen == generation) cb.accept(done);
            });
        });
    }

    void prewarm() {
        for (AppEntry e : apps) {
            if (icons.get(e.key) == null && !waiting.containsKey(e.key)) requestIcon(e, null);
        }
    }

    void trim() { icons.evictAll(); }
}
