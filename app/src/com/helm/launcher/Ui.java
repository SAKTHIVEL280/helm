package com.helm.launcher;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.Window;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

import java.util.Locale;

/** Shared measurements, type, motion and haptics. */
final class Ui {
    /** Geist: Nothing OS 5's system face. Geist Mono for labels and metadata. A display serif for headlines. */
    static Typeface SANS = Typeface.DEFAULT;
    static Typeface SANS_MEDIUM = Typeface.DEFAULT;
    static Typeface SANS_SEMI = Typeface.DEFAULT_BOLD;
    static Typeface MONO = Typeface.MONOSPACE;
    static Typeface MONO_MEDIUM = Typeface.MONOSPACE;
    static Typeface SERIF = Typeface.SERIF;

    /** Fast out, hard settle. Nothing's motion never overshoots. */
    static final PathInterpolator EASE = new PathInterpolator(0.2f, 0f, 0f, 1f);
    /** For things leaving the screen. */
    static final PathInterpolator EXIT = new PathInterpolator(0.3f, 0f, 1f, 1f);

    private static float density = 3f;
    private static float scaledDensity = 3f;
    private static float motion = 1f;
    private static boolean fontsLoaded;

    private static final Matrix TMP = new Matrix();
    private static final float[] PT = new float[2];

    private Ui() {}

    static void init(Context c) {
        Resources r = c.getResources();
        density = r.getDisplayMetrics().density;
        scaledDensity = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, r.getDisplayMetrics());
        motion = Prefs.get(c).motion();
        if (!fontsLoaded) {
            try {
                SANS = r.getFont(R.font.geist_regular);
                SANS_MEDIUM = r.getFont(R.font.geist_medium);
                SANS_SEMI = r.getFont(R.font.geist_semibold);
                MONO = r.getFont(R.font.geist_mono_regular);
                MONO_MEDIUM = r.getFont(R.font.geist_mono_medium);
                SERIF = r.getFont(R.font.instrument_serif);
                fontsLoaded = true;
            } catch (Resources.NotFoundException ignored) {
                // System fallbacks above stay in place.
            }
        }
    }

    static int dp(float v) { return Math.round(v * density); }

    static float dpf(float v) { return v * density; }

    static float sp(float v) { return v * scaledDensity; }

    static float density() { return density; }

    /** Durations scale with the "Motion" setting; 0 turns animation off. */
    static long ms(long base) { return (long) (base * motion); }

    static boolean motionOff() { return motion <= 0f; }

    /** The face for big numbers and titles, per the Headline setting. */
    static Typeface headline(Context c) {
        int h = Prefs.get(c).headline();
        return h == Prefs.HEADLINE_SANS ? SANS_MEDIUM : SERIF;
    }

    static GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dpf(radiusDp));
        return d;
    }

    static GradientDrawable pill(int color) { return rounded(color, 999f); }

    static GradientDrawable outline(int fill, int stroke, float radiusDp) {
        GradientDrawable d = rounded(fill, radiusDp);
        d.setStroke(Math.max(1, dp(1)), stroke);
        return d;
    }

    static TextView text(Context c, CharSequence s, int color, float sizeSp, Typeface face) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sizeSp);
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Small spaced capitals in Geist Mono: Nothing's metadata voice. */
    static TextView mono(Context c, String s, int color, float sizeSp) {
        TextView t = text(c, s.toUpperCase(Locale.getDefault()), color, sizeSp, MONO);
        t.setLetterSpacing(0.08f);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    /** Maps a point in root (window) coordinates into {@code v}'s local space, honouring scale. */
    static float[] toLocal(View v, float x, float y) {
        TMP.reset();
        v.transformMatrixToGlobal(TMP);
        TMP.invert(TMP);
        PT[0] = x;
        PT[1] = y;
        TMP.mapPoints(PT);
        return new float[]{PT[0], PT[1]};
    }

    /** Maps a point in {@code v}'s local space to root (window) coordinates. */
    static float[] toRoot(View v, float x, float y) {
        TMP.reset();
        v.transformMatrixToGlobal(TMP);
        PT[0] = x;
        PT[1] = y;
        TMP.mapPoints(PT);
        return new float[]{PT[0], PT[1]};
    }

    /** Android 15+ already draws apps edge-to-edge; older releases need asking. */
    @SuppressWarnings("deprecation")
    static void edgeToEdge(Window w) {
        if (Build.VERSION.SDK_INT < 35) w.setDecorFitsSystemWindows(false);
    }

    static void tick(View v) { haptic(v, HapticFeedbackConstants.CLOCK_TICK); }

    static void longPress(View v) { haptic(v, HapticFeedbackConstants.LONG_PRESS); }

    static void confirm(View v) { haptic(v, HapticFeedbackConstants.CONFIRM); }

    static void reject(View v) { haptic(v, HapticFeedbackConstants.REJECT); }

    private static void haptic(View v, int type) {
        if (v != null && Prefs.get(v.getContext()).haptics()) {
            v.performHapticFeedback(type, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
        }
    }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
