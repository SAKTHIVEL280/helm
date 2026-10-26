package com.helm.launcher;

import android.content.pm.ApplicationInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Folders inside the app drawer, like Nothing OS's drawer groups. Stored as JSON in one preference. */
final class DrawerGroups {
    static final class Group {
        String name;
        final List<String> apps = new ArrayList<>();

        Group(String name) { this.name = name; }
    }

    private DrawerGroups() {}

    static List<Group> load(Prefs p) {
        List<Group> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.sp.getString(Prefs.DRAWER_GROUPS, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Group g = new Group(o.optString("name", "Group"));
                JSONArray apps = o.optJSONArray("apps");
                if (apps != null) for (int k = 0; k < apps.length(); k++) g.apps.add(apps.getString(k));
                out.add(g);
            }
        } catch (JSONException ignored) {
            // A damaged value: start over rather than crash the drawer.
        }
        return out;
    }

    static void save(Prefs p, List<Group> groups) {
        JSONArray a = new JSONArray();
        try {
            for (Group g : groups) {
                if (g.apps.isEmpty()) continue;
                a.put(new JSONObject().put("name", g.name).put("apps", new JSONArray(g.apps)));
            }
        } catch (JSONException ignored) {
        }
        p.put(Prefs.DRAWER_GROUPS, a.toString());
    }

    static Group find(List<Group> groups, String name) {
        for (Group g : groups) if (g.name.equals(name)) return g;
        return null;
    }

    /** The group an app is in, or null. */
    static String groupOf(Prefs p, String appKey) {
        for (Group g : load(p)) if (g.apps.contains(appKey)) return g.name;
        return null;
    }

    /** Moves an app into the named group, creating it if needed. An app lives in one group at a time. */
    static void addTo(Prefs p, String name, String appKey) {
        List<Group> groups = load(p);
        for (Group g : groups) g.apps.remove(appKey);
        Group g = find(groups, name);
        if (g == null) {
            g = new Group(name);
            groups.add(g);
        }
        g.apps.add(appKey);
        save(p, groups);
    }

    static void removeFrom(Prefs p, String appKey) {
        List<Group> groups = load(p);
        for (Group g : groups) g.apps.remove(appKey);
        save(p, groups);
    }

    static void rename(Prefs p, String from, String to) {
        List<Group> groups = load(p);
        Group g = find(groups, from);
        if (g == null || to.isEmpty() || from.equals(to)) return;
        Group clash = find(groups, to);
        if (clash != null) {
            // Renaming onto an existing name merges the two.
            for (String k : g.apps) if (!clash.apps.contains(k)) clash.apps.add(k);
            groups.remove(g);
        } else {
            g.name = to;
        }
        save(p, groups);
    }

    static void delete(Prefs p, String name) {
        List<Group> groups = load(p);
        Group g = find(groups, name);
        if (g != null) groups.remove(g);
        save(p, groups);
    }

    /** Smart grouping: each app's own Android category, for apps not already in a group. */
    static void autoGroup(Prefs p, List<AppEntry> apps) {
        List<Group> groups = load(p);
        java.util.Set<String> placed = new java.util.HashSet<>();
        for (Group g : groups) placed.addAll(g.apps);
        Map<String, List<String>> byCat = new LinkedHashMap<>();
        for (AppEntry e : apps) {
            if (placed.contains(e.key)) continue;
            String cat = category(e.category);
            if (cat == null) continue;
            byCat.computeIfAbsent(cat, k -> new ArrayList<>()).add(e.key);
        }
        for (Map.Entry<String, List<String>> en : byCat.entrySet()) {
            if (en.getValue().size() < 2) continue; // A group of one is just an app.
            Group g = find(groups, en.getKey());
            if (g == null) {
                g = new Group(en.getKey());
                groups.add(g);
            }
            g.apps.addAll(en.getValue());
        }
        save(p, groups);
    }

    static String category(int c) {
        switch (c) {
            case ApplicationInfo.CATEGORY_GAME: return "Games";
            case ApplicationInfo.CATEGORY_AUDIO: return "Music";
            case ApplicationInfo.CATEGORY_VIDEO: return "Video";
            case ApplicationInfo.CATEGORY_IMAGE: return "Photos";
            case ApplicationInfo.CATEGORY_SOCIAL: return "Social";
            case ApplicationInfo.CATEGORY_NEWS: return "News";
            case ApplicationInfo.CATEGORY_MAPS: return "Travel";
            case ApplicationInfo.CATEGORY_PRODUCTIVITY: return "Work";
            default: return null;
        }
    }
}
