package com.helm.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Third-party icon packs in the common ADW / Nova format (an appfilter.xml mapping components to drawables). */
final class IconPack {
    private static final String[] INTENTS = {"org.adw.launcher.THEMES", "com.novalauncher.THEME"};
    private static final Map<String, IconPack> LOADED = new HashMap<>();

    private final String pkg;
    private final Resources res;
    private final Map<String, String> map;

    private IconPack(String pkg, Resources res, Map<String, String> map) {
        this.pkg = pkg;
        this.res = res;
        this.map = map;
    }

    /** Installed packs as package → label. */
    static Map<String, String> installed(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, String> out = new LinkedHashMap<>();
        for (String action : INTENTS) {
            List<ResolveInfo> found = pm.queryIntentActivities(new Intent(action), PackageManager.GET_META_DATA);
            for (ResolveInfo ri : found) {
                String p = ri.activityInfo.packageName;
                if (!out.containsKey(p)) out.put(p, String.valueOf(ri.loadLabel(pm)));
            }
        }
        return out;
    }

    /** Loads (once) and returns the pack, or null if it can't be read. Safe off the main thread. */
    static synchronized IconPack get(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        if (LOADED.containsKey(pkg)) return LOADED.get(pkg);
        IconPack pack = null;
        try {
            Resources res = c.getPackageManager().getResourcesForApplication(pkg);
            Map<String, String> map = new HashMap<>();
            int id = res.getIdentifier("appfilter", "xml", pkg);
            if (id != 0) {
                parse(res.getXml(id), map);
            } else {
                try (InputStream in = c.createPackageContext(pkg, 0).getAssets().open("appfilter.xml")) {
                    XmlPullParser parser = Xml.newPullParser();
                    parser.setInput(in, "UTF-8");
                    parse(parser, map);
                }
            }
            pack = new IconPack(pkg, res, map);
        } catch (Exception ignored) {
        }
        LOADED.put(pkg, pack);
        return pack;
    }

    private static void parse(XmlPullParser p, Map<String, String> map) throws Exception {
        int ev = p.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && "item".equals(p.getName())) {
                String comp = p.getAttributeValue(null, "component");
                String drawable = p.getAttributeValue(null, "drawable");
                if (comp != null && drawable != null) map.put(comp, drawable);
            }
            ev = p.next();
        }
    }

    Drawable icon(ComponentName cn, int densityDpi) {
        String name = map.get("ComponentInfo{" + cn.flattenToString() + "}");
        if (name == null) return null;
        int id = res.getIdentifier(name, "drawable", pkg);
        if (id == 0) return null;
        try {
            return res.getDrawableForDensity(id, densityDpi, null);
        } catch (Exception e) {
            return null;
        }
    }

    static List<String> names(Map<String, String> packs) { return new ArrayList<>(packs.values()); }
}
