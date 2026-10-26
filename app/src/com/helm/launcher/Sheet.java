package com.helm.launcher;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** A bottom sheet: dimmed scrim, a solid card with a serif title, scrolling content. */
final class Sheet extends FrameLayout {
    private static Sheet active;

    private final View scrim;
    private final LinearLayout card;
    private Runnable onDismiss;
    private boolean closing;

    private Sheet(Context c, Palette p, String title, View content, boolean scroll) {
        super(c);
        scrim = new View(c);
        scrim.setBackgroundColor(0x66000000);
        scrim.setOnClickListener(v -> dismiss());
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(p.dark ? 0xFF141416 : 0xFFF6F6F4);
        float r = Ui.dpf(30);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        card.setBackground(bg);
        card.setClickable(true);
        card.setPadding(Ui.dp(20), Ui.dp(10), Ui.dp(20), Ui.dp(20));

        View handle = new View(c);
        handle.setBackground(Ui.pill(Palette.alpha(p.onSolid, 0.2f)));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(Ui.dp(36), Ui.dp(4));
        hl.gravity = Gravity.CENTER_HORIZONTAL;
        hl.bottomMargin = Ui.dp(14);
        card.addView(handle, hl);

        if (title != null) {
            TextView t = Ui.text(c, title, p.onSolid, 28, Ui.headline(c));
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tl.bottomMargin = Ui.dp(16);
            tl.leftMargin = Ui.dp(4);
            card.addView(t, tl);
        }
        if (scroll) {
            ScrollView sv = new ScrollView(c);
            sv.setVerticalScrollBarEnabled(false);
            sv.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            card.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            card.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        addView(card, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        // Never taller than 82% of the screen.
        int h = MeasureSpec.getSize(hs);
        LayoutParams lp = (LayoutParams) card.getLayoutParams();
        lp.topMargin = Math.round(h * 0.18f);
        super.onMeasure(ws, hs);
    }

    static Sheet show(ViewGroup host, Palette p, String title, View content) {
        return show(host, p, title, content, true);
    }

    static Sheet show(ViewGroup host, Palette p, String title, View content, boolean scroll) {
        if (active != null) active.dismissNow();
        Sheet s = new Sheet(host.getContext(), p, title, content, scroll);
        WindowInsets wi = host.getRootWindowInsets();
        if (wi != null) s.padFor(wi);
        // Follow the keyboard as it opens and closes, so fields and buttons stay above it.
        s.setOnApplyWindowInsetsListener((v, in) -> {
            s.padFor(in);
            return in;
        });
        host.addView(s, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        s.scrim.setAlpha(0f);
        s.scrim.animate().alpha(1f).setDuration(Ui.ms(200)).start();
        s.card.setTranslationY(Ui.dp(400));
        s.card.animate().translationY(0).setDuration(Ui.ms(320)).setInterpolator(Ui.EASE).start();
        active = s;
        return s;
    }

    static Sheet active() { return active; }

    /** The open sheet only if it belongs to this screen: one left open in Settings must not block home. */
    static Sheet activeIn(Context c) {
        return active != null && active.getContext() == c && !active.closing ? active : null;
    }

    static boolean dismissIn(Context c) {
        Sheet s = activeIn(c);
        if (s == null) return false;
        s.dismiss();
        return true;
    }

    /** An activity going away drops its sheet so it can't block the next one. */
    static void forget(Context c) {
        if (active != null && active.getContext() == c) active = null;
    }

    static boolean dismissActive() {
        if (active == null) return false;
        active.dismiss();
        return true;
    }

    private void padFor(WindowInsets wi) {
        Insets in = wi.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
        card.setPadding(card.getPaddingLeft(), card.getPaddingTop(), card.getPaddingRight(), Ui.dp(20) + in.bottom);
    }

    void setOnDismiss(Runnable r) { onDismiss = r; }

    void dismiss() {
        if (closing) return;
        closing = true;
        if (active == this) active = null;
        scrim.animate().alpha(0f).setDuration(Ui.ms(180)).start();
        card.animate().translationY(card.getHeight()).setDuration(Ui.ms(220)).setInterpolator(Ui.EXIT)
                .withEndAction(this::remove).start();
        if (Ui.motionOff()) remove();
    }

    private void dismissNow() {
        closing = true;
        if (active == this) active = null;
        remove();
    }

    private void remove() {
        ViewGroup parent = (ViewGroup) getParent();
        if (parent != null) parent.removeView(this);
        if (onDismiss != null) {
            Runnable r = onDismiss;
            onDismiss = null;
            r.run();
        }
    }
}
