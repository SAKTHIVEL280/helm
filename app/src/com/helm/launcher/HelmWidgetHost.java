package com.helm.launcher;

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.graphics.Outline;
import android.os.Build;
import android.os.Bundle;
import android.util.SizeF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;

import java.util.Collections;

/** Hosts other apps' widgets; its views notice long-presses so they can be moved and resized. */
final class HelmWidgetHost extends AppWidgetHost {
    static final int ID = 0x4E4F53;

    HelmWidgetHost(Context c) { super(c, ID); }

    @Override
    protected AppWidgetHostView onCreateView(Context c, int appWidgetId, AppWidgetProviderInfo info) {
        return new HostView(c);
    }

    static final class HostView extends AppWidgetHostView {
        Item item;
        private final int slop;
        private float downX, downY;
        private boolean longPressed;
        private float radius;
        private final Runnable check = () -> {
            if (performLongClick()) longPressed = true;
        };

        HostView(Context c) {
            super(c);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            setClipToOutline(true);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    int in = Ui.dp(6);
                    o.setRoundRect(in, in, v.getWidth() - in, v.getHeight() - in, radius);
                }
            });
        }

        void setRadius(float r) {
            radius = r;
            invalidateOutline();
        }

        /**
         * Watches every event (not just intercepts), so the long-press check is always cancelled
         * by a lift or a move, even over parts of the widget that don't handle touches.
         */
        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    longPressed = false;
                    downX = ev.getX();
                    downY = ev.getY();
                    removeCallbacks(check);
                    postDelayed(check, ViewConfiguration.getLongPressTimeout());
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (Math.hypot(ev.getX() - downX, ev.getY() - downY) > slop) removeCallbacks(check);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(check);
                    break;
                default:
                    break;
            }
            boolean handled = super.dispatchTouchEvent(ev);
            // Keep receiving this gesture so the check above is always resolved.
            return handled || ev.getActionMasked() == MotionEvent.ACTION_DOWN;
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) { return longPressed; }

        @Override
        protected void onDetachedFromWindow() {
            removeCallbacks(check);
            super.onDetachedFromWindow();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            invalidateOutline();
            if (w > 0 && h > 0 && Build.VERSION.SDK_INT >= 31) {
                float d = Ui.density();
                try {
                    updateAppWidgetSize(new Bundle(), Collections.singletonList(new SizeF(w / d, h / d)));
                } catch (RuntimeException ignored) {
                }
            }
        }
    }
}
