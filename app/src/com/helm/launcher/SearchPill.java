package com.helm.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Essential Search's pill on home: frosted, a line magnifier on the left, "Search" centred. */
final class SearchPill extends View {
    private final Palette p;
    private final Material.Frost frost = new Material.Frost();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final String label;

    SearchPill(Context c, Palette p, String label) {
        super(c);
        this.p = p;
        this.label = label;
        text.setTypeface(Ui.SANS_MEDIUM);
        text.setTextSize(Ui.sp(15));
        text.setTextAlign(Paint.Align.CENTER);
        glyph.setStyle(Paint.Style.STROKE);
        glyph.setStrokeCap(Paint.Cap.ROUND);
        glyph.setStrokeWidth(Ui.dpf(1.8f));
        setContentDescription(label);
    }

    @Override
    protected void onDraw(Canvas c) {
        int material = Material.resolve(Material.AUTO, p);
        box.set(0, 0, getWidth(), getHeight());
        Material.roundRect(c, this, frost, box, getHeight() / 2f, material, p);
        int ink = p.fg(material, 1);
        text.setColor(ink);
        glyph.setColor(ink);
        float cy = getHeight() / 2f, gx = Ui.dpf(26);
        float r = Ui.dpf(7);
        c.drawCircle(gx, cy - Ui.dpf(1.5f), r, glyph);
        c.drawLine(gx + r * 0.72f, cy - Ui.dpf(1.5f) + r * 0.72f, gx + r * 1.45f, cy - Ui.dpf(1.5f) + r * 1.45f, glyph);
        Paint.FontMetrics fm = text.getFontMetrics();
        c.drawText(label, getWidth() / 2f, cy - (fm.ascent + fm.descent) / 2f, text);
    }

    @Override
    public void setPressed(boolean pressed) {
        boolean changed = pressed != isPressed();
        super.setPressed(pressed);
        if (!changed) return;
        animate().scaleX(pressed ? 0.97f : 1f).scaleY(pressed ? 0.97f : 1f)
                .setDuration(Ui.ms(pressed ? 90 : 220)).setInterpolator(Ui.EASE).start();
    }
}
