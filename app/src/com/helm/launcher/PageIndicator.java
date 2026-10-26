package com.helm.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Small dots above the dock; the active one follows the swipe. */
final class PageIndicator extends View {
    private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint on = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int count = 1;
    private float position;

    PageIndicator(Context c, Palette p) {
        super(c);
        on.setColor(p.onWall);
        dim.setColor(Palette.alpha(p.onWall, 0.35f));
    }

    void setCount(int n) {
        count = Math.max(1, n);
        invalidate();
    }

    void setPosition(float pos) {
        position = pos;
        invalidate();
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        setMeasuredDimension(MeasureSpec.getSize(ws), Ui.dp(18));
    }

    @Override
    protected void onDraw(Canvas c) {
        if (count < 2) return;
        float gap = Ui.dpf(10), r = Ui.dpf(2.6f);
        float total = (count - 1) * gap;
        float x0 = (getWidth() - total) / 2f, cy = getHeight() / 2f;
        for (int i = 0; i < count; i++) c.drawCircle(x0 + i * gap, cy, r, dim);
        float x = x0 + Ui.clamp(position, 0, count - 1) * gap;
        c.drawCircle(x, cy, r * 1.15f, on);
    }
}
