package com.helm.launcher;

import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.OverScroller;

/** Home pages side by side; swipe sideways to page. */
final class Workspace extends ViewGroup {
    interface Listener {
        void onScroll(float pagePosition);

        void onPageSettled(int page);
    }

    private final OverScroller scroller;
    private final int slop, minFling;
    private Listener listener;
    private VelocityTracker velocity;
    private float downX, downY, lastX;
    private boolean swiping;
    private int current;
    private boolean locked;

    Workspace(Context c) {
        super(c);
        scroller = new OverScroller(c, Ui.EASE);
        ViewConfiguration vc = ViewConfiguration.get(c);
        slop = vc.getScaledPagingTouchSlop();
        minFling = vc.getScaledMinimumFlingVelocity() * 4;
        setClipChildren(false);
        setClipToPadding(false);
    }

    void setListener(Listener l) { listener = l; }

    /** While an item is being dragged, the user can't swipe pages; the drag drives paging instead. */
    void setLocked(boolean on) { locked = on; }

    int current() { return current; }

    int count() { return getChildCount(); }

    CellLayout page(int i) {
        return i >= 0 && i < getChildCount() ? (CellLayout) getChildAt(i) : null;
    }

    CellLayout currentPage() { return page(current); }

    @Override
    protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws), h = MeasureSpec.getSize(hs);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).layout(i * w, 0, (i + 1) * w, h);
        if (changed && scroller.isFinished()) scrollTo(current * w, 0);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (locked || getChildCount() < 2) return false;
        track(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = ev.getX();
                downY = ev.getY();
                swiping = !scroller.isFinished();
                if (swiping) scroller.forceFinished(true);
                return swiping;
            case MotionEvent.ACTION_MOVE:
                float dx = ev.getX() - downX, dy = ev.getY() - downY;
                if (!swiping && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.3f) {
                    swiping = true;
                    lastX = ev.getX();
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return swiping;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                swiping = false;
                return false;
            default:
                return swiping;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (locked || getChildCount() < 2) return false;
        track(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = ev.getX();
                downY = ev.getY();
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (!swiping) {
                    float dx = ev.getX() - downX;
                    if (Math.abs(dx) > slop) {
                        swiping = true;
                        lastX = ev.getX();
                    } else return true;
                }
                float delta = lastX - ev.getX();
                lastX = ev.getX();
                int max = (getChildCount() - 1) * getWidth();
                float x = getScrollX() + delta;
                // Resistance past the first and last page.
                if (x < 0 || x > max) delta *= 0.35f;
                scrollTo(Math.round(getScrollX() + delta), 0);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (swiping) {
                    velocity.computeCurrentVelocity(1000);
                    float vx = velocity.getXVelocity();
                    float pos = getScrollX() / (float) Math.max(1, getWidth());
                    int target = Math.round(pos);
                    if (vx < -minFling) target = (int) Math.floor(pos) + 1;
                    else if (vx > minFling) target = (int) Math.ceil(pos) - 1;
                    snapTo(target, true);
                }
                swiping = false;
                return true;
            }
            default:
                return true;
        }
    }

    private void track(MotionEvent ev) {
        if (velocity == null) velocity = VelocityTracker.obtain();
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) velocity.clear();
        velocity.addMovement(ev);
    }

    void snapTo(int page, boolean animate) {
        page = Ui.clamp(page, 0, Math.max(0, getChildCount() - 1));
        boolean changed = page != current;
        current = page;
        int target = page * getWidth();
        if (!animate || Ui.motionOff() || getWidth() == 0) {
            scroller.forceFinished(true);
            scrollTo(target, 0);
            if (listener != null) listener.onPageSettled(current);
        } else {
            int dx = target - getScrollX();
            scroller.startScroll(getScrollX(), 0, dx, 0, (int) Ui.ms(Math.min(420, 220 + Math.abs(dx) / 6)));
            postInvalidateOnAnimation();
        }
        if (changed) Ui.tick(this);
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.getCurrX(), 0);
            postInvalidateOnAnimation();
            if (scroller.isFinished() && listener != null) listener.onPageSettled(current);
        }
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        float pos = l / (float) Math.max(1, getWidth());
        if (listener != null) listener.onScroll(pos);
        // Frosted surfaces sample the wallpaper at their screen position, so they must redraw as pages move.
        int a = (int) Math.floor(pos), b = a + 1;
        refreshFrost(page(a));
        refreshFrost(page(b));
    }

    static void refreshFrost(ViewGroup g) {
        if (g == null) return;
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            v.invalidate();
        }
    }
}
