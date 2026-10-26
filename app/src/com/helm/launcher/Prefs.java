package com.helm.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONException;
import org.json.JSONObject;

/** Every setting Helm has. Defaults follow Nothing OS 5's new home theme. */
final class Prefs {
    // Appearance
    static final String THEME = "theme";
    static final String STYLE = "style";
    static final String ACCENT_MODE = "accent_mode";
    static final String ACCENT_CUSTOM = "accent_custom";
    static final String HEADLINE = "headline";
    static final String CORNER = "corner";
    static final String GLASS = "glass";
    static final String FROST = "frost";
    static final String MOTION = "motion";
    static final String HIDE_STATUS = "hide_status";
    static final String HAPTICS = "haptics";
    // Wallpaper
    static final String WP_SOURCE = "wp_source";
    static final String WP_MOOD = "wp_mood";
    static final String WP_SEED = "wp_seed";
    static final String WP_GRAIN = "wp_grain";
    static final String WP_LINES = "wp_lines";
    static final String WP_DIM = "wp_dim";
    static final String WP_SOLID = "wp_solid";
    static final String WP_PHOTO_VERSION = "wp_photo_version";
    static final String WP_ACCENT_CACHE = "wp_accent_cache";
    static final String WP_LIGHT_CACHE = "wp_light_cache";
    // Icons
    static final String ICON_STYLE = "icon_style";
    static final String ICON_SHAPE = "icon_shape";
    static final String ICON_SCALE = "icon_scale";
    static final String ICON_PACK = "icon_pack";
    static final String FALLBACK = "fallback";
    static final String LABELS_HOME = "labels_home";
    static final String LABELS_DOCK = "labels_dock";
    static final String LABEL_FONT = "label_font";
    // Home
    static final String COLUMNS = "columns";
    static final String ROWS = "rows";
    /** Not "dock": Helm 1 stored its dock app list under that key, as a string. */
    static final String DOCK = "dock_on";
    static final String SEARCH_POS = "search_pos";
    static final String PAGE_DOTS = "page_dots";
    // Drawer & search
    static final String DRAWER_LAYOUT = "drawer_layout";
    static final String DRAWER_COLUMNS = "drawer_columns";
    static final String DRAWER_SORT = "drawer_sort";
    static final String SUGGESTIONS = "suggestions";
    static final String CATEGORIES = "categories";
    static final String DRAWER_KEYBOARD = "drawer_keyboard";
    static final String HIDDEN = "hidden";
    static final String HIDDEN_LOCK = "hidden_lock";
    static final String ENGINE = "engine";
    static final String CALCULATOR = "calculator";
    static final String SETTINGS_RESULTS = "settings_results";
    // Gestures
    static final String G_UP = "g_up";
    static final String G_DOWN = "g_down";
    static final String G_DOUBLE = "g_double";
    static final String G_UP_APP = "g_up_app";
    static final String G_DOWN_APP = "g_down_app";
    static final String G_DOUBLE_APP = "g_double_app";
    // Weather
    static final String WEATHER_CITY = "weather_city";
    static final String WEATHER_LAT = "weather_lat";
    static final String WEATHER_LON = "weather_lon";
    static final String WEATHER_UNIT = "weather_unit";
    static final String WEATHER_CACHE = "weather_cache";

    static final String DOTS = "notification_dots";
    static final String DOCK_BG = "dock_bg";
    static final String LABEL_SIZE = "label_size";
    static final String DRAWER_BG = "drawer_bg";
    static final String DRAWER_GROUPS = "drawer_groups";
    static final String GROUPS_HIDE = "groups_hide_members";
    static final String PANEL_TILES = "panel_tiles";
    static final String PANEL_MEDIA = "panel_media";
    static final String LOCK_CLOCK = "lock_clock";
    static final String LOCK_POS = "lock_pos";
    static final String LOCK_DATE = "lock_date";
    static final String LOCK_SOURCE = "lock_source";
    static final String LOCK_TINT = "lock_tint";

    static final String LABEL_PREFIX = "label:";

    static final int THEME_SYSTEM = 0, THEME_DARK = 1, THEME_LIGHT = 2;
    static final int STYLE_CLASSIC = 0, STYLE_TRANSLUCENT = 1;
    static final int ACCENT_RED = 0, ACCENT_WALLPAPER = 1, ACCENT_CUSTOM_COLOUR = 2;
    static final int HEADLINE_SERIF = 0, HEADLINE_SANS = 1;
    static final int WP_ATMOSPHERE = 0, WP_PHOTO = 1, WP_SOLID_COLOUR = 2, WP_SYSTEM = 3;
    static final int ICONS_MONO = 0, ICONS_TINTED = 1, ICONS_ORIGINAL = 2, ICONS_GREY = 3;
    static final int SHAPE_CIRCLE = 0, SHAPE_SQUIRCLE = 1, SHAPE_ROUNDED = 2;
    static final int FALLBACK_GREY = 0, FALLBACK_LETTER = 1;
    static final int SEARCH_BOTTOM = 0, SEARCH_TOP = 1, SEARCH_HIDDEN = 2;
    static final int LAYOUT_GRID = 0, LAYOUT_LIST = 1;
    static final int SORT_AZ = 0, SORT_USAGE = 1;
    static final int ENGINE_GOOGLE = 0, ENGINE_DDG = 1, ENGINE_BING = 2, ENGINE_BRAVE = 3;

    static final int ACTION_NONE = 0, ACTION_DRAWER = 1, ACTION_NOTIFICATIONS = 2, ACTION_QUICK_SETTINGS = 3,
            ACTION_SEARCH = 4, ACTION_LOCK = 5, ACTION_APP = 6, ACTION_SETTINGS = 7, ACTION_TORCH = 8, ACTION_EDIT = 9,
            ACTION_PANEL = 10, ACTION_RECENTS = 11;
    static final int DOCK_BG_NONE = 0, DOCK_BG_GLASS = 1;
    static final int DRAWER_BG_FROST = 0, DRAWER_BG_SOLID = 1, DRAWER_BG_CLEAR = 2;
    static final int LOCK_CLOCK_NONE = 0, LOCK_CLOCK_DOT = 1, LOCK_CLOCK_SERIF = 2, LOCK_CLOCK_ANALOG = 3;

    private static Prefs instance;

    final SharedPreferences sp;

    private Prefs(Context c) {
        sp = c.getSharedPreferences("helm", Context.MODE_PRIVATE);
    }

    static Prefs get(Context c) {
        if (instance == null) instance = new Prefs(c.getApplicationContext());
        return instance;
    }

    private int i(String k, int d) { return sp.getInt(k, d); }

    private boolean b(String k, boolean d) { return sp.getBoolean(k, d); }

    int theme() { return i(THEME, THEME_SYSTEM); }
    int style() { return i(STYLE, STYLE_TRANSLUCENT); }
    int accentMode() { return i(ACCENT_MODE, ACCENT_WALLPAPER); }
    int accentCustom() { return i(ACCENT_CUSTOM, 0xFF2E9E7E); }
    int headline() { return i(HEADLINE, HEADLINE_SERIF); }
    int corner() { return i(CORNER, 26); }
    int glass() { return i(GLASS, 60); }
    int frost() { return i(FROST, 70); }
    float motion() { return i(MOTION, 100) / 100f; }
    boolean hideStatus() { return b(HIDE_STATUS, false); }
    boolean haptics() { return b(HAPTICS, true); }

    int wpSource() { return i(WP_SOURCE, WP_ATMOSPHERE); }
    int wpMood() { return i(WP_MOOD, 0); }
    long wpSeed() { return sp.getLong(WP_SEED, 26L); }
    boolean wpGrain() { return b(WP_GRAIN, true); }
    boolean wpLines() { return b(WP_LINES, true); }
    int wpDim() { return i(WP_DIM, 0); }
    int wpSolid() { return i(WP_SOLID, 0xFF000000); }
    int wpPhotoVersion() { return i(WP_PHOTO_VERSION, 0); }

    int iconStyle() { return i(ICON_STYLE, ICONS_MONO); }
    int iconShape() { return i(ICON_SHAPE, SHAPE_CIRCLE); }
    int iconScale() { return i(ICON_SCALE, 100); }
    String iconPack() { return sp.getString(ICON_PACK, ""); }
    int fallback() { return i(FALLBACK, FALLBACK_GREY); }
    boolean labelsHome() { return b(LABELS_HOME, true); }
    boolean labelsDock() { return b(LABELS_DOCK, false); }
    int labelFont() { return i(LABEL_FONT, 0); }

    int columns() { return Ui.clamp(i(COLUMNS, 4), 4, 6); }
    int rows() { return Ui.clamp(i(ROWS, 6), 5, 8); }
    boolean dock() { return b(DOCK, true); }
    int searchPos() { return i(SEARCH_POS, SEARCH_BOTTOM); }
    boolean pageDots() { return b(PAGE_DOTS, true); }

    int drawerLayout() { return i(DRAWER_LAYOUT, LAYOUT_GRID); }
    int drawerColumns() { return Ui.clamp(i(DRAWER_COLUMNS, 4), 3, 6); }
    int drawerSort() { return i(DRAWER_SORT, SORT_AZ); }
    boolean suggestions() { return b(SUGGESTIONS, true); }
    boolean categories() { return b(CATEGORIES, true); }
    boolean drawerKeyboard() { return b(DRAWER_KEYBOARD, false); }
    boolean hiddenLock() { return b(HIDDEN_LOCK, true); }
    int engine() { return i(ENGINE, ENGINE_GOOGLE); }
    boolean calculator() { return b(CALCULATOR, true); }
    boolean dots() { return b(DOTS, true); }
    int dockBg() { return i(DOCK_BG, DOCK_BG_NONE); }
    int labelSize() { return Ui.clamp(i(LABEL_SIZE, 12), 10, 15); }
    int drawerBg() { return i(DRAWER_BG, DRAWER_BG_FROST); }
    boolean groupsHideMembers() { return b(GROUPS_HIDE, true); }
    boolean panelMedia() { return b(PANEL_MEDIA, true); }
    int lockClock() { return i(LOCK_CLOCK, LOCK_CLOCK_DOT); }
    int lockPos() { return i(LOCK_POS, 0); }
    boolean lockDate() { return b(LOCK_DATE, true); }
    int lockSource() { return i(LOCK_SOURCE, 0); }
    int lockTint() { return i(LOCK_TINT, 0); }

    boolean settingsResults() { return b(SETTINGS_RESULTS, true); }

    int gesture(String key) {
        int def = G_UP.equals(key) ? ACTION_DRAWER : G_DOWN.equals(key) ? ACTION_PANEL : ACTION_LOCK;
        return i(key, def);
    }

    String gestureApp(String key) { return sp.getString(key + "_app", ""); }

    void put(String key, int value) { sp.edit().putInt(key, value).apply(); }

    void put(String key, long value) { sp.edit().putLong(key, value).apply(); }

    void put(String key, boolean value) { sp.edit().putBoolean(key, value).apply(); }

    void put(String key, String value) { sp.edit().putString(key, value).apply(); }

    Set<String> hidden() { return new HashSet<>(sp.getStringSet(HIDDEN, new HashSet<>())); }

    void setHidden(String appKey, boolean hide) {
        Set<String> s = hidden();
        if (hide) s.add(appKey); else s.remove(appKey);
        sp.edit().putStringSet(HIDDEN, s).apply();
    }

    String label(String appKey) { return sp.getString(LABEL_PREFIX + appKey, null); }

    void setLabel(String appKey, String label) {
        if (label == null || label.trim().isEmpty()) sp.edit().remove(LABEL_PREFIX + appKey).apply();
        else sp.edit().putString(LABEL_PREFIX + appKey, label.trim()).apply();
    }

    List<String> list(String key) {
        String s = sp.getString(key, "");
        return s.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(s.split("\n")));
    }

    /** Every preference as JSON, for backup. */
    JSONObject export() throws JSONException {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            Object v = e.getValue();
            JSONObject w = new JSONObject();
            if (v instanceof Integer) w.put("i", v);
            else if (v instanceof Long) w.put("l", v);
            else if (v instanceof Boolean) w.put("b", v);
            else if (v instanceof String) w.put("s", v);
            else if (v instanceof Set) w.put("set", new org.json.JSONArray((Set<?>) v));
            else continue;
            o.put(e.getKey(), w);
        }
        return o;
    }

    void restore(JSONObject o) throws JSONException {
        SharedPreferences.Editor ed = sp.edit().clear();
        java.util.Iterator<String> keys = o.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            JSONObject w = o.getJSONObject(k);
            if (w.has("i")) ed.putInt(k, w.getInt("i"));
            else if (w.has("l")) ed.putLong(k, w.getLong("l"));
            else if (w.has("b")) ed.putBoolean(k, w.getBoolean("b"));
            else if (w.has("s")) ed.putString(k, w.getString("s"));
            else if (w.has("set")) {
                Set<String> s = new HashSet<>();
                org.json.JSONArray a = w.getJSONArray("set");
                for (int i = 0; i < a.length(); i++) s.add(a.getString(i));
                ed.putStringSet(k, s);
            }
        }
        ed.apply();
    }
}
