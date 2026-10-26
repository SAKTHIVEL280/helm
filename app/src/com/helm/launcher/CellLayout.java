package com.helm.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;

/** One home page (or the dock): a cols × rows grid; children span cells. */
final class CellLayout extends ViewGroup {
    static final int PREVIEW_NONE = 0, PREVIEW_PLACE = 1, PREVIEW_FOLDER = 2;

    static final class LP extends ViewGroup.LayoutParams {
        int x, y, w, h;

        LP(int x, int y, int w, int h) {
            super(MATCH_PARENT, MATCH_PARENT);
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    final int cols, rows;
    final int page;
    private int cellW, cellH;
    private boolean editing;
    private Palette p;
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint previewFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint previewEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF preview = new RectF();
    private int previewMode = PREVIEW_NONE;

    CellLayout(Context c, int cols, int rows, int page, Palette p) {
        super(c);
        this.cols = cols;
        this.rows = rows;
        this.page = page;
        this.p = p;
        setClipChildren(false);
        setClipToPadding(false);
        setWillNotDraw(false);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(Ui.dpf(1));
        outline.setPathEffect(new DashPathEffect(new float[]{Ui.dpf(4), Ui.dpf(5)}, 0));
        previewEdge.setStyle(Paint.Style.STROKE);
        previewEdge.setStrokeWidth(Ui.dpf(1.5f));
        applyColours();
    }

    private void applyColours() {
        outline.setColor(Palette.alpha(p.onWall, 0.35f));
        previewFill.setColor(Palette.alpha(p.onWall, 0.12f));
        previewEdge.setColor(Palette.alpha(p.onWall, 0.7f));
    }

    int cellW() { return cellW; }

    int cellH() { return cellH; }

    void setEditing(boolean on) {
        editing = on;
        invalidate();
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws);
        int h = MeasureSpec.getSize(hs);
        cellW = Math.max(1, (w - getPaddingLeft() - getPaddingRight()) / cols);
        cellH = Math.max(1, (h - getPaddingTop() - getPaddingBottom()) / rows);
        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            LP lp = (LP) ch.getLayoutParams();
            ch.measure(MeasureSpec.makeMeasureSpec(lp.w * cellW, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(lp.h * cellH, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            LP lp = (LP) ch.getLayoutParams();
            Rect cr = cellRect(lp.x, lp.y, lp.w, lp.h);
            ch.layout(cr.left, cr.top, cr.right, cr.bottom);
        }
    }

    Rect cellRect(int x, int y, int w, int h) {
        int left = getPaddingLeft() + x * cellW, top = getPaddingTop() + y * cellH;
        return new Rect(left, top, left + w * cellW, top + h * cellH);
    }

    /** Cell under a local point, clamped to the grid. */
    int[] cellAt(float lx, float ly) {
        int x = (int) Math.floor((lx - getPaddingLeft()) / cellW);
        int y = (int) Math.floor((ly - getPaddingTop()) / cellH);
        return new int[]{Ui.clamp(x, 0, cols - 1), Ui.clamp(y, 0, rows - 1)};
    }

    /** Top-left cell for a w×h item whose top-left corner is at the local point. */
    int[] originFor(float lx, float ly, int w, int h) {
        int x = Math.round((lx - getPaddingLeft()) / (float) cellW);
        int y = Math.round((ly - getPaddingTop()) / (float) cellH);
        return new int[]{Ui.clamp(x, 0, Math.max(0, cols - w)), Ui.clamp(y, 0, Math.max(0, rows - h))};
    }

    View childAt(int x, int y) {
        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            LP lp = (LP) ch.getLayoutParams();
            if (x >= lp.x && x < lp.x + lp.w && y >= lp.y && y < lp.y + lp.h) return ch;
        }
        return null;
    }

    void setPreview(int x, int y, int w, int h, int mode) {
        Rect r = cellRect(x, y, w, h);
        float inset = mode == PREVIEW_FOLDER ? Ui.dpf(10) : Ui.dpf(5);
        preview.set(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset);
        previewMode = mode;
        invalidate();
    }

    void clearPreview() {
        if (previewMode == PREVIEW_NONE) return;
        previewMode = PREVIEW_NONE;
        invalidate();
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        if (editing) {
            float in = Ui.dpf(4);
            c.drawRoundRect(getPaddingLeft() + in, getPaddingTop() + in,
                    getWidth() - getPaddingRight() - in, getHeight() - getPaddingBottom() - in,
                    p.radius, p.radius, outline);
        }
        if (previewMode == PREVIEW_PLACE) {
            float r = Math.min(p.radius, Math.min(preview.width(), preview.height()) / 2f);
            c.drawRoundRect(preview, r, r, previewFill);
            c.drawRoundRect(preview, r, r, previewEdge);
        } else if (previewMode == PREVIEW_FOLDER) {
            float r = Math.min(preview.width(), preview.height()) / 2f;
            c.drawCircle(preview.centerX(), preview.centerY(), r, previewFill);
            c.drawCircle(preview.centerX(), preview.centerY(), r, previewEdge);
        }
        super.dispatchDraw(c);
    }
}
