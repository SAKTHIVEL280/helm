package com.helm.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A folder on home. 1×1: a frosted disc holding four tiny icons. Bigger (Nothing's "big folder"):
 * a frosted tile whose first four apps open with a single tap.
 */
final class FolderIconView extends View {
    interface Host {
        void openFolder(FolderIconView v);

        void launchFromFolder(AppEntry e, View from);
    }

    Item item;
    private final Host host;
    private Palette p;
    private final Material.Frost frost = new Material.Frost();
    private final List<AppEntry> entries = new ArrayList<>();
    private final Bitmap[] minis = new Bitmap[4];
    private final Paint bmp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final RectF[] cells = {new RectF(), new RectF(), new RectF(), new RectF()};
    private int iconSize;
    private boolean showLabel;
    private float downX, downY;

    FolderIconView(Context c, Host host) {
        super(c);
        this.host = host;
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(Ui.sp(Prefs.get(c).labelSize()));
        title.setTypeface(Ui.MONO);
        title.setTextSize(Ui.sp(10));
        title.setLetterSpacing(0.08f);
        setHapticFeedbackEnabled(false);
        setOnClickListener(v -> onTap());
    }

    void bind(Item folder, List<AppEntry> apps, int iconPx, boolean withLabel, int labelColor, Palette palette) {
        item = folder;
        p = palette;
        iconSize = iconPx;
        showLabel = withLabel && !big();
        label.setColor(labelColor);
        label.setTypeface(Prefs.get(getContext()).labelFont() == 1 ? Ui.MONO : Ui.SANS);
        entries.clear();
        entries.addAll(apps);
        setContentDescription("Folder " + folder.title);
        AppRepository repo = AppRepository.get(getContext());
        for (int i = 0; i < 4; i++) {
            minis[i] = null;
            if (i >= entries.size()) continue;
            final int slot = i;
            final AppEntry e = entries.get(i);
            Bitmap b = repo.peekIcon(e);
            if (b != null) minis[i] = b;
            else repo.requestIcon(e, x -> {
                if (slot < entries.size() && entries.get(slot) == e) {
                    minis[slot] = x;
                    invalidate();
                }
            });
        }
        invalidate();
    }

    private boolean big() { return item != null && item.w >= 2 && item.h >= 2; }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = ev.getX();
            downY = ev.getY();
        }
        return super.onTouchEvent(ev);
    }

    private void onTap() {
        if (big()) {
            for (int i = 0; i < Math.min(4, entries.size()); i++) {
                if (cells[i].contains(downX, downY)) {
                    host.launchFromFolder(entries.get(i), this);
                    return;
                }
            }
        }
        host.openFolder(this);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (p == null) return;
        int material = Material.resolve(Material.AUTO, p);
        if (big()) {
            float pad = Ui.dpf(6);
            box.set(pad, pad, getWidth() - pad, getHeight() - pad);
            Material.roundRect(c, this, frost, box, p.radius, material, p);
            float inner = Ui.dpf(14);
            float titleH = Ui.dpf(18);
            title.setColor(p.fg(material, 2));
            CharSequence t = TextUtils.ellipsize(item.title.toUpperCase(Locale.getDefault()), title,
                    box.width() - inner * 2, TextUtils.TruncateAt.END);
            c.drawText(t, 0, t.length(), box.left + inner, box.top + inner - title.ascent(), title);
            float gw = (box.width() - inner * 2) / 2f, gh = (box.height() - inner * 2 - titleH) / 2f;
            float s = Math.min(gw, gh) * 0.78f;
            for (int i = 0; i < 4; i++) {
                float cx = box.left + inner + gw * (i % 2 + 0.5f);
                float cy = box.top + inner + titleH + gh * (i / 2 + 0.5f);
                cells[i].set(cx - s / 2f, cy - s / 2f, cx + s / 2f, cy + s / 2f);
                if (minis[i] != null) c.drawBitmap(minis[i], null, cells[i], bmp);
            }
            return;
        }
        Paint.FontMetricsInt fm = label.getFontMetricsInt();
        float content = iconSize + (showLabel ? Ui.dp(6) + fm.descent - fm.ascent : 0);
        float cx = getWidth() / 2f;
        float top = Math.max(Ui.dp(4), (getHeight() - content) / 2f);
        box.set(cx - iconSize / 2f, top, cx + iconSize / 2f, top + iconSize);
        float radius = Prefs.get(getContext()).iconShape() == Prefs.SHAPE_CIRCLE ? iconSize / 2f : iconSize * 0.3f;
        Material.roundRect(c, this, frost, box, radius, material, p);
        float s = iconSize * 0.3f, gap = iconSize * 0.05f;
        for (int i = 0; i < 4; i++) {
            float x = cx + (i % 2 == 0 ? -gap / 2f - s : gap / 2f);
            float y = box.centerY() + (i < 2 ? -gap / 2f - s : gap / 2f);
            cells[i].set(x, y, x + s, y + s);
            if (minis[i] != null) c.drawBitmap(minis[i], null, cells[i], bmp);
        }
        if (showLabel) {
            CharSequence t = TextUtils.ellipsize(item.title, label, getWidth() - Ui.dp(6), TextUtils.TruncateAt.END);
            c.drawText(t, 0, t.length(), cx, box.bottom + Ui.dp(6) - fm.ascent, label);
        }
    }

    RectF discBounds() { return new RectF(box); }
}
