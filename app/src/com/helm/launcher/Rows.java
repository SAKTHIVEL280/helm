package com.helm.launcher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Builds the setting rows used by Settings and by sheets: one look everywhere. */
final class Rows {
    final Context c;
    final Palette p;

    Rows(Context c, Palette p) {
        this.c = c;
        this.p = p;
    }

    LinearLayout column() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    /** A grouped card (Nothing OS settings groups). */
    LinearLayout card(LinearLayout parent) {
        LinearLayout card = column();
        card.setBackground(Ui.rounded(p.solid, 22));
        card.setPadding(0, Ui.dp(4), 0, Ui.dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(12);
        parent.addView(card, lp);
        return card;
    }

    void section(LinearLayout parent, String name) {
        TextView t = Ui.mono(c, name, p.onSolid2, 11);
        t.setLetterSpacing(0.14f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(20);
        lp.bottomMargin = Ui.dp(10);
        lp.leftMargin = Ui.dp(6);
        parent.addView(t, lp);
    }

    View labels(String title, String sub) {
        LinearLayout col = column();
        col.addView(Ui.text(c, title, p.onSolid, 16, Ui.SANS));
        if (sub != null) {
            TextView s = Ui.text(c, sub, p.onSolid2, 13, Ui.SANS);
            s.setPadding(0, Ui.dp(4), 0, 0);
            col.addView(s);
        }
        return col;
    }

    void segment(LinearLayout card, String title, String sub, String[] options, int selected, IntConsumer picked) {
        LinearLayout row = column();
        row.setPadding(Ui.dp(18), Ui.dp(14), Ui.dp(18), Ui.dp(14));
        row.addView(labels(title, sub));
        Segmented s = new Segmented(c, p, options, selected, picked::accept);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = Ui.dp(12);
        row.addView(s, sl);
        card.addView(row);
    }

    DotSwitch toggle(LinearLayout card, String title, String sub, boolean on, Consumer<Boolean> changed) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(18), Ui.dp(14), Ui.dp(18), Ui.dp(14));
        row.setMinimumHeight(Ui.dp(62));
        row.addView(labels(title, sub), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        DotSwitch sw = new DotSwitch(c, p, on);
        sw.setListener(changed::accept);
        sw.setContentDescription(title);
        LinearLayout.LayoutParams swl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        swl.leftMargin = Ui.dp(16);
        row.addView(sw, swl);
        row.setOnClickListener(v -> sw.toggle());
        card.addView(row);
        return sw;
    }

    void slider(LinearLayout card, String title, String unit, int min, int max, int step, int value, IntConsumer moved, IntConsumer released) {
        LinearLayout row = column();
        row.setPadding(Ui.dp(18), Ui.dp(14), Ui.dp(18), Ui.dp(8));
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.addView(Ui.text(c, title, p.onSolid, 16, Ui.SANS), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView val = Ui.mono(c, value + unit, p.onSolid2, 12);
        head.addView(val);
        row.addView(head);
        DotSlider s = new DotSlider(c, p, min, max, step, value);
        s.setListener(new DotSlider.Listener() {
            @Override
            public void moved(int v) {
                val.setText((v + unit).toUpperCase());
                if (moved != null) moved.accept(v);
            }

            @Override
            public void released(int v) {
                if (released != null) released.accept(v);
            }
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = Ui.dp(4);
        row.addView(s, sl);
        card.addView(row);
    }

    View link(LinearLayout card, String title, String sub, Runnable action) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(18), Ui.dp(14), Ui.dp(18), Ui.dp(14));
        row.setMinimumHeight(Ui.dp(62));
        row.addView(labels(title, sub), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(Ui.text(c, "→", p.onSolid2, 18, Ui.SANS));
        row.setOnClickListener(v -> action.run());
        card.addView(row);
        return row;
    }

    /** A row of colour dots; the chosen one gets a ring. */
    void swatches(LinearLayout card, String title, int[] colours, int selected, IntConsumer picked) {
        LinearLayout row = column();
        row.setPadding(Ui.dp(18), Ui.dp(14), Ui.dp(18), Ui.dp(14));
        row.addView(labels(title, null));
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout dots = new LinearLayout(c);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        View[] views = new View[colours.length];
        for (int i = 0; i < colours.length; i++) {
            final int col = colours[i];
            View d = new View(c);
            views[i] = d;
            d.setOnClickListener(v -> {
                for (int k = 0; k < views.length; k++) views[k].setBackground(swatch(colours[k], colours[k] == col));
                Ui.tick(v);
                picked.accept(col);
            });
            d.setBackground(swatch(col, col == selected));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(36), Ui.dp(36));
            lp.rightMargin = Ui.dp(10);
            dots.addView(d, lp);
        }
        hs.addView(dots);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.topMargin = Ui.dp(12);
        row.addView(hs, hl);
        card.addView(row);
    }

    private GradientDrawable swatch(int colour, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(colour);
        if (on) g.setStroke(Ui.dp(3), p.dark ? Color.WHITE : Color.BLACK);
        else g.setStroke(Math.max(1, Ui.dp(1)), Palette.alpha(p.onSolid, 0.12f));
        return g;
    }

    /** A full-width pill button. */
    TextView button(LinearLayout parent, String label, boolean primary, Runnable action) {
        TextView b = Ui.mono(c, label, primary ? p.onAccent : p.onSolid, 12);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.pill(primary ? p.accent : p.solidHigh));
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(48));
        lp.topMargin = Ui.dp(10);
        parent.addView(b, lp);
        return b;
    }
}
