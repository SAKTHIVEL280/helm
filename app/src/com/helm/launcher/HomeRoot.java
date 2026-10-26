package com.helm.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/**
 * The root of home: paints Helm's wallpaper, reads home gestures (swipe up / down, double tap,
 * long-press on empty space) and hosts the drag layer.
 */
final class HomeRoot extends FrameLayout {
    interface Callbacks {
        boolean gesturesBlocked();

        boolean emptyAt(float x, float y);

        boolean swipeUpFollows();

        boolean swipeDownFollows();

        void dragPanel(float dy);

        void releasePanel(float velocityY);

        void dragDrawer(float dy);

        void releaseDrawer(float velocityY);

        void swipeUp();

        void swipeDown();

        void doubleTap();

        void longPressEmpty(float x, float y);

        /** In edit mode swipes and double taps are ignored; a tap on empty space leaves edit mode. */
        boolean editing();

        void tapEmpty(float x, float y);
    }

    private static final int NONE = 0, UP = 1, DOWN = 2;

    private final Callbacks cb;
    final DragController drag;
    private final int slop;
    private final GestureDetector detector;
    private final Paint wallPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect dst = new Rect();
    private VelocityTracker velocity;
    private float downX, downY;
    private int mode = NONE;
    private int dim;
    float lastX, lastY;

    HomeRoot(Context c, Callbacks callbacks) {
        super(c);
        cb = callbacks;
        drag = new DragController(this);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        setWillNotDraw(false);
        setClipChildren(false);
        detector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) { return true; }

            @Override
            public void onLongPress(MotionEvent e) {
                if (mode == NONE && !drag.busy() && !cb.gesturesBlocked() && !cb.editing() && cb.emptyAt(e.getX(), e.getY())) {
                    cb.longPressEmpty(e.getX(), e.getY());
                }
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (!drag.busy() && !cb.gesturesBlocked() && !cb.editing() && cb.emptyAt(e.getX(), e.getY())) cb.doubleTap();
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (!drag.busy() && !cb.gesturesBlocked() && cb.editing() && cb.emptyAt(e.getX(), e.getY())) {
                    cb.tapEmpty(e.getX(), e.getY());
                }
                return false;
            }
        });
    }

    void setDim(int percent) {
        dim = Math.round(Ui.clamp(percent, 0, 80) / 100f * 255f);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        Bitmap b = WallpaperStore.get(getContext()).sharp();
        if (b != null && WallpaperStore.get(getContext()).hasOwnBitmap()) {
            dst.set(0, 0, getWidth(), getHeight());
            c.drawBitmap(b, null, dst, wallPaint);
            if (dim > 0) c.drawColor(dim << 24);
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        lastX = ev.getX();
        lastY = ev.getY();
        if (drag.active()) {
            drag.onTouch(ev);
            return true;
        }
        if (drag.armed() && ev.getActionMasked() == MotionEvent.ACTION_MOVE && drag.shouldStart(ev.getX(), ev.getY())) {
            MotionEvent cancel = MotionEvent.obtain(ev);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
            drag.begin(ev.getX(), ev.getY());
            return true;
        }
        // A fresh touch can never continue an old long-press; arming always happens after DOWN.
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            drag.disarm();
        }
        if (!cb.gesturesBlocked()) detector.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    private void track(MotionEvent ev) {
        if (velocity == null) velocity = VelocityTracker.obtain();
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) velocity.clear();
        velocity.addMovement(ev);
    }

    private int classify(MotionEvent ev) {
        float dx = ev.getX() - downX, dy = ev.getY() - downY;
        if (Math.abs(dy) < slop * 1.5f || Math.abs(dy) < Math.abs(dx) * 1.2f) return NONE;
        if (dy < 0) {
            if (!cb.swipeUpFollows()) cb.swipeUp();
            return UP;
        }
        if (!cb.swipeDownFollows()) cb.swipeDown();
        return DOWN;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (cb.gesturesBlocked() || cb.editing() || drag.busy()) return false;
        track(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                mode = NONE;
                break;
            case MotionEvent.ACTION_MOVE:
                if (mode == NONE) mode = classify(ev);
                return mode != NONE;
            default:
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (mode == NONE && (cb.gesturesBlocked() || cb.editing())) return false;
        track(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                mode = NONE;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mode == NONE) mode = classify(ev);
                if (mode == UP && cb.swipeUpFollows()) cb.dragDrawer(ev.getY() - downY);
                if (mode == DOWN && cb.swipeDownFollows()) cb.dragPanel(ev.getY() - downY);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mode == UP && cb.swipeUpFollows()) {
                    velocity.computeCurrentVelocity(1000);
                    cb.releaseDrawer(velocity.getYVelocity());
                }
                if (mode == DOWN && cb.swipeDownFollows()) {
                    velocity.computeCurrentVelocity(1000);
                    cb.releasePanel(ev.getActionMasked() == MotionEvent.ACTION_CANCEL ? -1f : velocity.getYVelocity());
                }
                mode = NONE;
                break;
            default:
                break;
        }
        return true;
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        super.dispatchDraw(c);
        drag.draw(c);
    }
}
