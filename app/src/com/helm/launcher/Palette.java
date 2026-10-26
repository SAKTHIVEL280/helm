package com.helm.launcher;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;

/**
 * Colour tokens, modelled on Nothing OS 5's content table:
 * Content_onSolid 100/60/30, Content_onTranslucent 96/60/30, Content_Accent from the wallpaper's primary.
 * Surfaces come in three materials: opaque (α1), translucent (α≈0.6 + frost), transparent (α0.25).
 */
final class Palette {
    static final int RED = 0xFFD71921;

    final boolean dark;
    final boolean translucent;
    /** Page background behind everything when there's no wallpaper (Settings, classic black). */
    final int bg;
    final int solid, solidHigh;
    final int onSolid, onSolid2, onSolid3;
    /** Translucent tint, alpha included; drawn over the frosted wallpaper. */
    final int glass, glassStroke, transparent;
    final int onGlass, onGlass2, onGlass3;
    /** Text drawn straight on the wallpaper (home labels, page dots). */
    final int onWall, onWall2;
    final boolean wallLight;
    final int accent, onAccent;
    final int dotOff;
    final int iconBg, iconFg;
    final float radius;

    private Palette(boolean dark, boolean translucent, int glassPct, boolean wallLight, int accent, float radius) {
        this.dark = dark;
        this.translucent = translucent;
        this.wallLight = wallLight;
        this.accent = accent;
        this.radius = radius;
        int ink = dark ? Color.WHITE : Color.BLACK;
        bg = dark ? 0xFF000000 : 0xFFF0F0F0;
        solid = dark ? 0xFF1B1B1D : 0xFFFFFFFF;
        solidHigh = dark ? 0xFF2C2C2F : 0xFFE6E6E8;
        onSolid = alpha(ink, 1f);
        onSolid2 = alpha(ink, 0.6f);
        onSolid3 = alpha(ink, 0.3f);
        float g = Ui.clamp(glassPct, 10, 95) / 100f;
        glass = dark ? alpha(0xFF18181A, g * 0.92f) : alpha(0xFFFFFFFF, g);
        transparent = dark ? alpha(0xFF000000, 0.25f) : alpha(0xFFFFFFFF, 0.25f);
        glassStroke = dark ? alpha(Color.WHITE, 0.08f) : alpha(Color.WHITE, 0.35f);
        onGlass = alpha(ink, 0.96f);
        onGlass2 = alpha(ink, 0.6f);
        onGlass3 = alpha(ink, 0.3f);
        int wallInk = wallLight ? Color.BLACK : Color.WHITE;
        onWall = alpha(wallInk, 0.96f);
        onWall2 = alpha(wallInk, 0.55f);
        onAccent = luminance(accent) > 0.55f ? Color.BLACK : Color.WHITE;
        dotOff = dark ? 0xFF2A2A2D : 0xFFDCDCDF;
        // NOS 5's dock: light discs with black glyphs in light; dark discs with white glyphs in dark.
        iconBg = dark ? 0xFF1F1F21 : (translucent ? 0xFFF4F4F2 : 0xFFFFFFFF);
        iconFg = ink;
    }

    static Palette resolve(Context c) {
        Prefs p = Prefs.get(c);
        int theme = p.theme();
        boolean systemDark = (c.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        boolean dark = theme == Prefs.THEME_DARK || (theme == Prefs.THEME_SYSTEM && systemDark);
        boolean translucent = p.style() == Prefs.STYLE_TRANSLUCENT;
        WallpaperStore ws = WallpaperStore.get(c);
        int accent;
        switch (p.accentMode()) {
            case Prefs.ACCENT_WALLPAPER: accent = ws.accent(dark); break;
            case Prefs.ACCENT_CUSTOM_COLOUR: accent = p.accentCustom(); break;
            default: accent = RED;
        }
        boolean wallLight = ws.hasOwnBitmap() || p.wpSource() == Prefs.WP_SYSTEM ? ws.wallpaperLight() : !dark;
        return new Palette(dark, translucent, p.glass(), wallLight, accent, Ui.dpf(p.corner()));
    }

    /** Foreground for a surface of the given material. */
    int fg(int material, int level) {
        boolean glassy = material != Material.OPAQUE;
        if (level == 1) return glassy ? onGlass : onSolid;
        if (level == 2) return glassy ? onGlass2 : onSolid2;
        return glassy ? onGlass3 : onSolid3;
    }

    static int alpha(int color, float a) {
        return (color & 0x00FFFFFF) | (Math.round(Ui.clamp(a, 0f, 1f) * 255f) << 24);
    }

    static float luminance(int c) {
        return (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f;
    }

    static int mix(int a, int b, float t) {
        float u = 1f - t;
        return Color.argb(
                Math.round(Color.alpha(a) * u + Color.alpha(b) * t),
                Math.round(Color.red(a) * u + Color.red(b) * t),
                Math.round(Color.green(a) * u + Color.green(b) * t),
                Math.round(Color.blue(a) * u + Color.blue(b) * t));
    }
}
