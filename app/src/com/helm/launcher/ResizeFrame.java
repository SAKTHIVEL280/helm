package com.helm.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * Widget resizing: a frame around the widget with a dot handle on each edge. Dragging a handle
 * snaps the widget to whole cells; allowed sizes and free space are respected.
 */
final class ResizeFrame extends View {
    interface Host {
        Layout layout();

        int cols();

        int rows();

        /** Nearest allowed size to w × h for this item, or null to accept any. */
        int[] allowed(Item item, int w, int h);

        int[] minimum(Item item);

        void resized(Item item);

        void resizeClosed();
    }

    private static final int LEFT = 0, TOP = 1, RIGHT = 2, BOTTOM = 3;

    private final Host host;
    final Item item;
    private final CellLayout page;
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int handle = -1;
    private int x0, y0, w0, h0;

    ResizeFrame(Context c, Palette p, Item item, CellLayout page, Host host) {
        super(c);
        this.item = item;
        this.page = page;
        this.host = host;
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(Ui.dpf(2));
        frame.setColor(p.accent);
        dot.setColor(p.accent);
        setWillNotDraw(false);
    }

    private RectF cellsToRoot(int x, int y, int w, int h) {
        Rect r = page.cellRect(x, y, w, h);
        float[] a = Ui.toRoot(page, r.left, r.top);
        float[] b = Ui.toRoot(page, r.right, r.bottom);
        return new RectF(a[0], a[1], b[0], b[1]);
    }

    @Override
    protected void onDraw(Canvas c) {
        rect.set(cellsToRoot(item.x, item.y, item.w, item.h));
        float in = Ui.dpf(4);
        rect.inset(in, in);
        float r = Math.min(Ui.dpf(26), Math.min(rect.width(), rect.height()) / 2f);
        c.drawRoundRect(rect, r, r, frame);
        float d = Ui.dpf(7);
        c.drawCircle(rect.left, rect.centerY(), d, dot);
        c.drawCircle(rect.right, rect.centerY(), d, dot);
        c.drawCircle(rect.centerX(), rect.top, d, dot);
        c.drawCircle(rect.centerX(), rect.bottom, d, dot);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        float x = ev.getX(), y = ev.getY();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float t = Ui.dpf(30);
                handle = -1;
                if (Math.hypot(x - rect.left, y - rect.centerY()) < t) handle = LEFT;
                else if (Math.hypot(x - rect.right, y - rect.centerY()) < t) handle = RIGHT;
                else if (Math.hypot(x - rect.centerX(), y - rect.top) < t) handle = TOP;
                else if (Math.hypot(x - rect.centerX(), y - rect.bottom) < t) handle = BOTTOM;
                if (handle < 0) {
                    if (!rect.contains(x, y)) host.resizeClosed();
                    return true;
                }
                x0 = item.x;
                y0 = item.y;
                w0 = item.w;
                h0 = item.h;
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (handle < 0) return true;
                float[] l = Ui.toLocal(page, x, y);
                int col = Math.round((l[0] - page.getPaddingLeft()) / (float) page.cellW());
                int row = Math.round((l[1] - page.getPaddingTop()) / (float) page.cellH());
                int nx = x0, ny = y0, nw = w0, nh = h0;
                switch (handle) {
                    case LEFT: nx = Math.min(col, x0 + w0 - 1); nw = x0 + w0 - nx; break;
                    case RIGHT: nw = Math.max(1, col - x0); break;
                    case TOP: ny = Math.min(row, y0 + h0 - 1); nh = y0 + h0 - ny; break;
                    default: nh = Math.max(1, row - y0); break;
                }
                int[] min = host.minimum(item);
                nw = Math.max(min[0], nw);
                nh = Math.max(min[1], nh);
                int[] ok = host.allowed(item, nw, nh);
                if (ok != null) {
                    if (handle == LEFT) nx = x0 + w0 - ok[0];
                    if (handle == TOP) ny = y0 + h0 - ok[1];
                    nw = ok[0];
                    nh = ok[1];
                }
                if ((nx != item.x || ny != item.y || nw != item.w || nh != item.h)
                        && host.layout().fits(item.page, nx, ny, nw, nh, host.cols(), host.rows(), java.util.Collections.singleton(item))) {
                    item.x = nx;
                    item.y = ny;
                    item.w = nw;
                    item.h = nh;
                    Ui.tick(this);
                    host.resized(item);
                    invalidate();
                }
                return true;
            }
            default:
                handle = -1;
                return true;
        }
    }
}
