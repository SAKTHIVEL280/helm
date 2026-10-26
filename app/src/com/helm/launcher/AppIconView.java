package com.helm.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

/** One app: an icon and, optionally, a label. Drawn directly, no child views. */
final class AppIconView extends View implements HelmNotifications.Listener {
    AppEntry entry;
    /** The home item this view shows, when it lives on the home screen. */
    Item item;
    private Bitmap icon;
    private int iconSize = Ui.dp(56);
    private boolean showLabel = true;
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint placeholder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint check = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private CharSequence ellipsized = "";
    private int ellipsizedFor = -1;
    private float scale = 1f;
    private ValueAnimator pressAnim;
    private boolean selectable, checked;
    private int accent = Palette.RED, ring = Color.WHITE;
    /** A full-size render arrived (big icons); a late small cached one mustn't replace it. */
    private boolean sharp;
    private final Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG);

    AppIconView(Context c) {
        super(c);
        label.setTypeface(Ui.SANS);
        label.setTextSize(Ui.sp(Prefs.get(c).labelSize()));
        label.setTextAlign(Paint.Align.CENTER);
        setHapticFeedbackEnabled(false);
    }

    void bind(AppEntry e, int sizePx, boolean withLabel, int labelColor, Typeface face, Palette p) {
        boolean relayout = sizePx != iconSize || withLabel != showLabel;
        entry = e;
        sharp = false;
        iconSize = sizePx;
        showLabel = withLabel;
        label.setColor(labelColor);
        label.setTypeface(face);
        // A whisper of shadow keeps white labels legible on busy wallpapers.
        if (Palette.luminance(labelColor) > 0.5f) label.setShadowLayer(Ui.dpf(3), 0, Ui.dpf(0.5f), 0x55000000);
        else label.clearShadowLayer();
        placeholder.setColor(p.iconBg);
        accent = p.accent;
        ring = labelColor;
        ellipsizedFor = -1;
        setContentDescription(e.label);
        AppRepository repo = AppRepository.get(getContext());
        icon = repo.peekIcon(e);
        if (icon == null) {
            repo.requestIcon(e, b -> {
                if (entry == e && !sharp) {
                    icon = b;
                    invalidate();
                }
            });
        }
        if (relayout) requestLayout();
        invalidate();
    }

    /** Big (2×2) home icons: render at full size instead of upscaling the cached one. */
    void renderSharp() {
        AppEntry e = entry;
        if (e == null) return;
        AppRepository.get(getContext()).renderLarge(e, iconSize, b -> {
            if (entry == e) {
                sharp = true;
                icon = b;
                invalidate();
            }
        });
    }

    void setSelectable(boolean on) {
        selectable = on;
        if (!on) checked = false;
        invalidate();
    }

    void setChecked(boolean on) {
        checked = on;
        invalidate();
    }

    boolean isChecked() { return checked; }

    /** Drops any press shrink immediately, e.g. before snapshotting for a drag. */
    void settle() {
        if (pressAnim != null) pressAnim.cancel();
        scale = 1f;
        invalidate();
    }

    RectF iconBounds() { return new RectF(dst); }

    @Override
    protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getMode(ws) == MeasureSpec.UNSPECIFIED ? iconSize + Ui.dp(24) : MeasureSpec.getSize(ws);
        int want = iconSize + Ui.dp(10);
        if (showLabel) {
            Paint.FontMetricsInt fm = label.getFontMetricsInt();
            want += Ui.dp(6) + fm.descent - fm.ascent;
        }
        int h = MeasureSpec.getMode(hs) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(hs) : want;
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas c) {
        Paint.FontMetricsInt fm = label.getFontMetricsInt();
        float content = iconSize + (showLabel ? Ui.dp(6) + fm.descent - fm.ascent : 0);
        float cx = getWidth() / 2f;
        float top = Math.max(Ui.dp(4), (getHeight() - content) / 2f);
        dst.set(cx - iconSize / 2f, top, cx + iconSize / 2f, top + iconSize);
        c.save();
        c.scale(scale, scale, dst.centerX(), dst.centerY());
        if (icon != null) c.drawBitmap(icon, null, dst, bitmapPaint);
        else c.drawCircle(dst.centerX(), dst.centerY(), iconSize / 2f, placeholder);
        c.restore();

        if (showLabel && entry != null) {
            int avail = getWidth() - Ui.dp(6);
            if (ellipsizedFor != avail) {
                ellipsized = TextUtils.ellipsize(entry.label, label, avail, TextUtils.TruncateAt.END);
                ellipsizedFor = avail;
            }
            c.drawText(ellipsized, 0, ellipsized.length(), cx, dst.bottom + Ui.dp(6) - fm.ascent, label);
        }

        if (selectable) drawCheck(c);
        else if (entry != null && HelmNotifications.count(entry.packageName()) > 0 && Prefs.get(getContext()).dots()) {
            // Nothing's notification dot: a small accent disc on the icon's shoulder, ringed to cut it out.
            float r = Math.max(Ui.dpf(4), iconSize * 0.085f);
            float x = dst.right - r * 1.1f, y = dst.top + r * 1.1f;
            badge.setColor(0x33000000);
            c.drawCircle(x, y, r + Ui.dpf(1.5f), badge);
            badge.setColor(accent);
            c.drawCircle(x, y, r, badge);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        HelmNotifications.addListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        HelmNotifications.removeListener(this);
    }

    @Override
    public void onNotificationsChanged() { invalidate(); }

    private void drawCheck(Canvas c) {
        float r = Ui.dpf(10);
        float x = dst.right - r * 0.6f, y = dst.top + r * 0.6f;
        if (checked) {
            check.setStyle(Paint.Style.FILL);
            check.setColor(accent);
            c.drawCircle(x, y, r, check);
            check.setColor(Palette.luminance(accent) > 0.55f ? Color.BLACK : Color.WHITE);
            check.setStyle(Paint.Style.STROKE);
            check.setStrokeWidth(Ui.dpf(2));
            check.setStrokeCap(Paint.Cap.ROUND);
            Path tick = new Path();
            tick.moveTo(x - r * 0.45f, y);
            tick.lineTo(x - r * 0.1f, y + r * 0.35f);
            tick.lineTo(x + r * 0.45f, y - r * 0.3f);
            c.drawPath(tick, check);
        } else {
            check.setStyle(Paint.Style.FILL);
            check.setColor(0x40000000);
            c.drawCircle(x, y, r, check);
            check.setStyle(Paint.Style.STROKE);
            check.setStrokeWidth(Ui.dpf(1.5f));
            check.setColor(ring);
            c.drawCircle(x, y, r - Ui.dpf(1), check);
        }
    }

    @Override
    public void setPressed(boolean pressed) {
        boolean changed = pressed != isPressed();
        super.setPressed(pressed);
        if (!changed || Ui.motionOff()) return;
        if (pressAnim != null) pressAnim.cancel();
        pressAnim = ValueAnimator.ofFloat(scale, pressed ? 0.88f : 1f);
        pressAnim.setDuration(pressed ? 90 : 220);
        pressAnim.setInterpolator(Ui.EASE);
        pressAnim.addUpdateListener(a -> {
            scale = (float) a.getAnimatedValue();
            invalidate();
        });
        pressAnim.start();
    }
}
