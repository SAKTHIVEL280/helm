package com.helm.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

import java.util.List;

/** A–Z down the drawer's edge in Geist Mono. Ticks under the finger like a detent. */
final class AlphabetScrubber extends View {
    interface Listener {
        void onLetter(char letter, float y);

        void onRelease();
    }

    private final Listener listener;
    private final Paint idle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint active = new Paint(Paint.ANTI_ALIAS_FLAG);
    private char[] letters = new char[0];
    private int current = -1;
    private float top, slot;

    AlphabetScrubber(Context c, Palette p, Listener l) {
        super(c);
        listener = l;
        idle.setColor(p.translucent ? p.onGlass2 : p.onSolid2);
        active.setColor(p.accent);
        for (Paint paint : new Paint[]{idle, active}) {
            paint.setTypeface(Ui.MONO_MEDIUM);
            paint.setTextAlign(Paint.Align.CENTER);
        }
    }

    void setLetters(List<Character> ls) {
        letters = new char[ls.size()];
        for (int i = 0; i < letters.length; i++) letters[i] = ls.get(i);
        current = -1;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int n = letters.length;
        if (n == 0) return;
        slot = Math.min(Ui.dpf(18), getHeight() / (float) n);
        top = (getHeight() - slot * n) / 2f;
        float size = Math.min(Ui.sp(11), slot * 0.8f);
        idle.setTextSize(size);
        active.setTextSize(size * 1.15f);
        for (int i = 0; i < n; i++) {
            Paint p = i == current ? active : idle;
            c.drawText(String.valueOf(letters[i]), getWidth() / 2f, top + i * slot + slot / 2f + size * 0.36f, p);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int n = letters.length;
        if (n == 0 || slot <= 0) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                // fall through
            case MotionEvent.ACTION_MOVE: {
                int i = Ui.clamp((int) ((ev.getY() - top) / slot), 0, n - 1);
                if (i != current) {
                    current = i;
                    Ui.tick(this);
                    listener.onLetter(letters[i], top + i * slot + slot / 2f);
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                current = -1;
                listener.onRelease();
                invalidate();
                return true;
            default:
                return true;
        }
    }
}
