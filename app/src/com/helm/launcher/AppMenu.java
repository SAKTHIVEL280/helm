package com.helm.launcher;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/** The long-press card: app shortcuts first, then actions set in small monospaced caps. */
final class AppMenu {
    static final int SHORTCUT = 0, ACTION = 1, DANGER = 2;

    static final class Item {
        final int kind;
        final CharSequence label;
        final Bitmap icon;
        final Runnable run;

        Item(int kind, CharSequence label, Bitmap icon, Runnable run) {
            this.kind = kind;
            this.label = label;
            this.icon = icon;
            this.run = run;
        }
    }

    private AppMenu() {}

    static PopupWindow show(View anchor, Palette p, String title, List<Item> items) {
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        return showAt(anchor, at[0] + anchor.getWidth() / 2, at[1], at[1] + anchor.getHeight(), p, title, items);
    }

    static PopupWindow showAt(View parent, int centerX, int top, int bottom, Palette p, String title, List<Item> items) {
        Context c = parent.getContext();
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, Ui.dp(8), 0, Ui.dp(8));
        box.setMinimumWidth(Ui.dp(220));
        PopupWindow pw = new PopupWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);

        if (title != null) {
            TextView t = mono(c, title.toUpperCase(Locale.getDefault()), p.onSolid3);
            t.setPadding(Ui.dp(20), Ui.dp(8), Ui.dp(20), Ui.dp(6));
            box.addView(t);
        }

        boolean sawShortcut = false, divided = false;
        for (Item it : items) {
            if (it.kind != SHORTCUT && sawShortcut && !divided) {
                View line = new View(c);
                line.setBackgroundColor(p.solidHigh);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(1)));
                lp.topMargin = lp.bottomMargin = Ui.dp(6);
                lp.leftMargin = lp.rightMargin = Ui.dp(20);
                box.addView(line, lp);
                divided = true;
            }
            if (it.kind == SHORTCUT) sawShortcut = true;
            View row = it.kind == SHORTCUT ? shortcutRow(c, p, it) : actionRow(c, p, it);
            row.setBackground(new RippleDrawable(ColorStateList.valueOf(Palette.alpha(p.onSolid, 0.10f)),
                    null, new ColorDrawable(Color.WHITE)));
            row.setOnClickListener(v -> {
                pw.dismiss();
                it.run.run();
            });
            box.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    Ui.dp(it.kind == SHORTCUT ? 48 : 44)));
        }

        View root = parent.getRootView();
        box.measure(View.MeasureSpec.makeMeasureSpec(Ui.dp(300), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int w = box.getMeasuredWidth(), h = box.getMeasuredHeight();
        int margin = Ui.dp(12);
        int x = Math.max(margin, Math.min(centerX - w / 2, root.getWidth() - w - margin));
        int y = top - h - Ui.dp(10);
        if (y < Ui.dp(56)) y = Math.max(margin, Math.min(bottom + Ui.dp(10), root.getHeight() - h - margin));

        pw.setBackgroundDrawable(Ui.rounded(p.solid, 24));
        pw.setElevation(Ui.dpf(18));
        pw.setOutsideTouchable(true);
        pw.showAtLocation(parent, Gravity.NO_GRAVITY, x, y);
        return pw;
    }

    private static View shortcutRow(Context c, Palette p, Item it) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(18), 0, Ui.dp(20), 0);
        ImageView icon = new ImageView(c);
        if (it.icon != null) icon.setImageBitmap(it.icon);
        row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(24), Ui.dp(24)));
        TextView t = new TextView(c);
        t.setText(it.label);
        t.setTextColor(p.onSolid);
        t.setTextSize(15);
        t.setTypeface(Ui.SANS);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = Ui.dp(14);
        row.addView(t, lp);
        return row;
    }

    private static View actionRow(Context c, Palette p, Item it) {
        TextView t = mono(c, it.label.toString().toUpperCase(Locale.getDefault()),
                it.kind == DANGER ? p.accent : p.onSolid);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(Ui.dp(20), 0, Ui.dp(20), 0);
        return t;
    }

    private static TextView mono(Context c, String text, int color) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(color);
        t.setTypeface(Ui.MONO);
        t.setTextSize(12);
        t.setLetterSpacing(0.1f);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }
}
