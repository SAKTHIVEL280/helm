package com.helm.launcher;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** A pill of options; the chosen one is filled solid. Scrolls sideways when there are many. */
final class Segmented extends HorizontalScrollView {
    interface Listener { void picked(int index); }

    private final Palette p;
    private final TextView[] options;
    private int selected;

    Segmented(Context c, Palette palette, String[] labels, int initial, Listener listener) {
        super(c);
        p = palette;
        setHorizontalScrollBarEnabled(false);
        setFillViewport(true);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackground(Ui.pill(p.dark ? 0xFF000000 : 0xFFEDEDEB));
        row.setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(4));
        options = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            TextView t = Ui.text(c, labels[i].toUpperCase(Locale.getDefault()), p.onSolid2, 11, Ui.MONO);
            t.setLetterSpacing(0.06f);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setPadding(Ui.dp(14), 0, Ui.dp(14), 0);
            final int index = i;
            t.setOnClickListener(v -> {
                if (index == selected) return;
                select(index);
                Ui.tick(v);
                listener.picked(index);
            });
            // Natural width, stretched to share spare room: short sets fill the pill, long ones scroll, none clip.
            row.addView(t, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(38), 1f));
            options[i] = t;
        }
        addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        select(Ui.clamp(initial, 0, labels.length - 1));
    }

    private void select(int index) {
        selected = index;
        for (int i = 0; i < options.length; i++) {
            boolean on = i == index;
            options[i].setBackground(on ? Ui.pill(p.onSolid) : new ColorDrawable(0));
            options[i].setTextColor(on ? (p.dark ? 0xFF000000 : 0xFFFFFFFF) : p.onSolid2);
        }
    }
}
