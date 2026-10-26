package com.helm.launcher;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Nothing's toggle: a quiet track when off; the accent with a white knob when on. */
final class DotSwitch extends View {
    interface Listener { void changed(boolean on); }

    private static final ArgbEvaluator ARGB = new ArgbEvaluator();

    private final Palette p;
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private Listener listener;
    private boolean on;
    private float t;
    private ValueAnimator anim;

    DotSwitch(Context c, Palette palette, boolean checked) {
        super(c);
        p = palette;
        on = checked;
        t = checked ? 1f : 0f;
        setOnClickListener(v -> toggle());
    }

    void setListener(Listener l) { listener = l; }

    boolean isOn() { return on; }

    void toggle() {
        on = !on;
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(t, on ? 1f : 0f);
        anim.setDuration(Ui.ms(180));
        anim.setInterpolator(Ui.EASE);
        anim.addUpdateListener(a -> {
            t = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
        Ui.tick(this);
        if (listener != null) listener.changed(on);
    }

    @Override
    public CharSequence getAccessibilityClassName() { return "android.widget.Switch"; }

    @Override
    protected void onMeasure(int ws, int hs) {
        setMeasuredDimension(Ui.dp(52), Ui.dp(30));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        track.setColor((int) ARGB.evaluate(t, p.solidHigh, p.accent));
        box.set(0, 0, w, h);
        c.drawRoundRect(box, h / 2f, h / 2f, track);
        knob.setColor((int) ARGB.evaluate(t, p.onSolid2, 0xFFFFFFFF));
        float r = h / 2f - Ui.dpf(4);
        c.drawCircle(h / 2f + (w - h) * t, h / 2f, r, knob);
    }
}
