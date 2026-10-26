package com.helm.launcher;

import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.os.UserHandle;

import java.text.Normalizer;
import java.util.Locale;

final class AppEntry {
    final LauncherActivityInfo info;
    final ComponentName component;
    final UserHandle user;
    final boolean mainUser;
    final boolean system;
    /** The app's own name, before any rename. */
    final String realLabel;
    /** What Helm shows: a rename if there is one. */
    final String label;
    /** Stable across launches: component plus the profile's serial number. */
    final String key;
    /** Lower-cased, accent-stripped label (and real name) for matching. */
    final String search;
    /** A-Z, or '#' for anything else. */
    final char section;
    /** {@link ApplicationInfo#category}, used for the drawer's category chips. */
    final int category;

    AppEntry(LauncherActivityInfo info, long userSerial, boolean mainUser, String rename) {
        this.info = info;
        this.component = info.getComponentName();
        this.user = info.getUser();
        this.mainUser = mainUser;
        ApplicationInfo ai = info.getApplicationInfo();
        this.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        this.category = ai.category;
        CharSequence l = info.getLabel();
        this.realLabel = l == null ? component.getPackageName() : l.toString().trim();
        this.label = rename != null ? rename : realLabel;
        this.key = component.flattenToShortString() + "#" + userSerial;
        this.search = normalize(rename != null ? label + " " + realLabel : label);
        this.section = sectionOf(normalize(label));
    }

    String packageName() { return component.getPackageName(); }

    static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
    }

    private static char sectionOf(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isWhitespace(ch)) continue;
            return ch >= 'a' && ch <= 'z' ? Character.toUpperCase(ch) : '#';
        }
        return '#';
    }
}
