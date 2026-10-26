package com.helm.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.util.List;

/** An open folder: a frosted card with an editable serif title and the apps in a grid. */
final class FolderPanel extends FrameLayout {
    interface Host {
        void launch(AppEntry e, View from);

        boolean longPressInFolder(AppIconView v, Item folder);

        void renameFolder(Item folder, String title);

        void folderClosed();
    }

    final Item folder;
    private final Host host;
    private final Palette p;
    private final LinearLayout card;
    private final EditText title;
    private boolean closing;

    FolderPanel(Context c, Palette palette, Item folder, List<AppEntry> apps, int iconPx, RectF from, Host host) {
        super(c);
        this.folder = folder;
        this.host = host;
        this.p = palette;
        setBackgroundColor(0x55000000);
        setOnClickListener(v -> close());
        int material = Material.resolve(Material.AUTO, p);

        card = new LinearLayout(c) {
            private final Material.Frost f = new Material.Frost();

            @Override
            protected void dispatchDraw(Canvas canvas) {
                RectF r = new RectF(0, 0, getWidth(), getHeight());
                Material.roundRect(canvas, this, f, r, Ui.dpf(32), material, p);
                super.dispatchDraw(canvas);
            }
        };
        card.setWillNotDraw(false);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(14), Ui.dp(20), Ui.dp(14), Ui.dp(18));
        card.setClickable(true);

        title = new EditText(c);
        title.setText(folder.title);
        title.setHint("Folder");
        title.setTypeface(Ui.headline(c));
        title.setTextSize(28);
        title.setTextColor(p.fg(material, 1));
        title.setHintTextColor(p.fg(material, 3));
        title.setBackground(null);
        title.setSingleLine(true);
        title.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        title.setImeOptions(EditorInfo.IME_ACTION_DONE);
        title.setPadding(Ui.dp(10), 0, Ui.dp(10), Ui.dp(10));
        title.setOnEditorActionListener((v, id, ev) -> {
            commitTitle();
            hideKeyboard();
            return true;
        });
        card.addView(title);

        int cols = 4;
        LinearLayout grid = new LinearLayout(c);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row);
            }
            AppIconView v = new AppIconView(c);
            v.bind(apps.get(i), iconPx, true, p.fg(material, 1), Ui.SANS, p);
            v.setOnClickListener(x -> host.launch(((AppIconView) x).entry, x));
            v.setOnLongClickListener(x -> host.longPressInFolder((AppIconView) x, folder));
            row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        if (row != null) {
            for (int k = apps.size() % cols; k != 0 && k < cols; k++) {
                row.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
            }
        }
        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(grid);
        card.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LayoutParams lp = new LayoutParams(Math.min(Ui.dp(400), (int) (c.getResources().getDisplayMetrics().widthPixels * 0.9f)),
                LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        addView(card, lp);

        if (!Ui.motionOff()) {
            setAlpha(0f);
            animate().alpha(1f).setDuration(Ui.ms(160)).start();
            card.post(() -> {
                float[] at = {card.getLeft() + card.getWidth() / 2f, card.getTop() + card.getHeight() / 2f};
                card.setPivotX(card.getWidth() / 2f);
                card.setPivotY(card.getHeight() / 2f);
                card.setTranslationX(from.centerX() - at[0]);
                card.setTranslationY(from.centerY() - at[1]);
                card.setScaleX(0.3f);
                card.setScaleY(0.3f);
                card.animate().translationX(0).translationY(0).scaleX(1f).scaleY(1f)
                        .setDuration(Ui.ms(300)).setInterpolator(Ui.EASE)
                        .setUpdateListener(a -> card.invalidate()).start();
            });
        }
    }

    private void commitTitle() {
        String t = title.getText().toString().trim();
        if (!t.equals(folder.title)) host.renameFolder(folder, t.isEmpty() ? "Folder" : t);
    }

    private void hideKeyboard() {
        InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
        title.clearFocus();
    }

    void close() {
        if (closing) return;
        closing = true;
        commitTitle();
        hideKeyboard();
        Runnable done = () -> {
            ViewGroup parent = (ViewGroup) getParent();
            if (parent != null) parent.removeView(this);
            host.folderClosed();
        };
        if (Ui.motionOff()) {
            done.run();
            return;
        }
        card.animate().scaleX(0.9f).scaleY(0.9f).alpha(0f).setDuration(Ui.ms(160)).setInterpolator(Ui.EXIT).start();
        animate().alpha(0f).setDuration(Ui.ms(180)).withEndAction(done).start();
    }

    /** Closes immediately, e.g. when a drag starts from inside the folder. */
    void closeNow() {
        if (closing) return;
        closing = true;
        commitTitle();
        hideKeyboard();
        ViewGroup parent = (ViewGroup) getParent();
        if (parent != null) parent.removeView(this);
        host.folderClosed();
    }
}
