package com.helm.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/** A slider drawn as a row of dots, lit up to the value, with a tick per step. */
final class DotSlider extends View {
    interface Listener {
        void moved(int value);

        default void released(int value) {}
    }

    private final Palette p;
    private final int min, max, step;
    private int value;
    private Listener listener;
    private final Paint on = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint off = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);

    DotSlider(Context c, Palette palette, int min, int max, int step, int value) {
        super(c);
        p = palette;
        this.min = min;
        this.max = max;
        this.step = Math.max(1, step);
        this.value = Ui.clamp(value, min, max);
        on.setColor(p.onSolid);
        off.setColor(p.onSolid3);
        knob.setColor(p.accent);
    }

    void setListener(Listener l) { listener = l; }

    @Override
    protected void onMeasure(int ws, int hs) {
        setMeasuredDimension(MeasureSpec.getSize(ws), Ui.dp(40));
    }

    @Override
    protected void onDraw(Canvas c) {
        int dots = Math.min(32, (max - min) / step + 1);
        float pad = Ui.dpf(12);
        float w = getWidth() - pad * 2, cy = getHeight() / 2f;
        float frac = (value - min) / (float) Math.max(1, max - min);
        for (int i = 0; i < dots; i++) {
            float t = dots == 1 ? 0 : i / (float) (dots - 1);
            c.drawCircle(pad + w * t, cy, Ui.dpf(2.2f), t <= frac + 0.0001f ? on : off);
        }
        c.drawCircle(pad + w * frac, cy, Ui.dpf(9), knob);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        float pad = Ui.dpf(12);
        float frac = Ui.clamp((ev.getX() - pad) / Math.max(1f, getWidth() - pad * 2), 0f, 1f);
        int v = min + Math.round(frac * (max - min) / step) * step;
        v = Ui.clamp(v, min, max);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                // fall through
            case MotionEvent.ACTION_MOVE:
                if (v != value) {
                    value = v;
                    Ui.tick(this);
                    if (listener != null) listener.moved(value);
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (listener != null) listener.released(value);
                return true;
            default:
                return true;
        }
    }
}
