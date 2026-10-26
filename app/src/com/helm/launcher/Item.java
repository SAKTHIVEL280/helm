package com.helm.launcher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** One thing on the home screen: an app, a folder, a system widget or a Helm widget. */
final class Item {
    static final int APP = 0, FOLDER = 1, WIDGET = 2, HELM = 3;
    static final int DOCK = -1;
    /** Not on home: a transient folder standing for an app-drawer group. */
    static final int DRAWER_GROUP = -2;

    long id;
    int type;
    int page;
    int x, y, w = 1, h = 1;
    /** APP: the app key. */
    String app;
    /** FOLDER: title and member app keys. */
    String title = "";
    final List<String> apps = new ArrayList<>();
    /** WIDGET: the system appWidgetId. */
    int widgetId = -1;
    /** HELM: widget kind and its settings. */
    String kind;
    JSONObject cfg = new JSONObject();

    boolean inDock() { return page == DOCK; }

    boolean isWidget() { return type == WIDGET || type == HELM; }

    Item copyPlacement(Item o) {
        page = o.page;
        x = o.x;
        y = o.y;
        return this;
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id).put("type", type).put("page", page).put("x", x).put("y", y).put("w", w).put("h", h);
        if (app != null) o.put("app", app);
        if (type == FOLDER) {
            o.put("title", title);
            o.put("apps", new JSONArray(apps));
        }
        if (type == WIDGET) o.put("widgetId", widgetId);
        if (type == HELM) {
            o.put("kind", kind);
            o.put("cfg", cfg);
        }
        return o;
    }

    static Item fromJson(JSONObject o) throws JSONException {
        Item i = new Item();
        i.id = o.getLong("id");
        i.type = o.getInt("type");
        i.page = o.getInt("page");
        i.x = o.getInt("x");
        i.y = o.getInt("y");
        i.w = Math.max(1, o.optInt("w", 1));
        i.h = Math.max(1, o.optInt("h", 1));
        i.app = o.optString("app", null);
        i.title = o.optString("title", "");
        JSONArray a = o.optJSONArray("apps");
        if (a != null) for (int k = 0; k < a.length(); k++) i.apps.add(a.getString(k));
        i.widgetId = o.optInt("widgetId", -1);
        i.kind = o.optString("kind", null);
        JSONObject cfg = o.optJSONObject("cfg");
        if (cfg != null) i.cfg = cfg;
        return i;
    }

    static Item app(String key) {
        Item i = new Item();
        i.type = APP;
        i.app = key;
        return i;
    }

    static Item helm(String kind, int w, int h) {
        Item i = new Item();
        i.type = HELM;
        i.kind = kind;
        i.w = w;
        i.h = h;
        return i;
    }
}
