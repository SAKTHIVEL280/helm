package com.helm.launcher;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional, granted by you in Settings. Android only lets apps see what's playing (the Music widget,
 * the quick panel's player) through a notification listener; Helm also uses it for icon dots.
 * It keeps a count per app in memory and nothing else: no content is read, stored or sent.
 */
public final class HelmNotifications extends NotificationListenerService {
    interface Listener { void onNotificationsChanged(); }

    private static HelmNotifications instance;
    private static final Map<String, Integer> COUNTS = new HashMap<>();
    private static final List<Listener> LISTENERS = new ArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static ComponentName component(Context c) { return new ComponentName(c, HelmNotifications.class); }

    static boolean granted(Context c) {
        android.app.NotificationManager nm = c.getSystemService(android.app.NotificationManager.class);
        try {
            return nm != null && nm.isNotificationListenerAccessGranted(component(c));
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean connected() { return instance != null; }

    static int count(String pkg) {
        Integer n = COUNTS.get(pkg);
        return n == null ? 0 : n;
    }

    static void addListener(Listener l) { if (!LISTENERS.contains(l)) LISTENERS.add(l); }

    static void removeListener(Listener l) { LISTENERS.remove(l); }

    @Override
    public void onListenerConnected() {
        instance = this;
        recount();
        Media.get(this).onListenerConnected();
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
        MAIN.post(() -> {
            COUNTS.clear();
            dispatch();
        });
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) { recount(); }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) { recount(); }

    private void recount() {
        StatusBarNotification[] active;
        try {
            active = getActiveNotifications();
        } catch (RuntimeException e) {
            return;
        }
        Map<String, Integer> fresh = new HashMap<>();
        if (active != null) {
            for (StatusBarNotification n : active) {
                Notification no = n.getNotification();
                // Ongoing things (music, downloads, the charging notice) and group summaries aren't "unread".
                if (!n.isClearable() || (no.flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
                fresh.merge(n.getPackageName(), 1, Integer::sum);
            }
        }
        MAIN.post(() -> {
            COUNTS.clear();
            COUNTS.putAll(fresh);
            dispatch();
        });
    }

    private static void dispatch() {
        for (Listener l : new ArrayList<>(LISTENERS)) l.onNotificationsChanged();
    }
}
