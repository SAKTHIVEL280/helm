package com.helm.launcher;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityOptions;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Helm's quick panel and recent apps, in Nothing's style. One view, hosted two ways: inside home
 * (pulled down with the finger, over frosted wallpaper) and inside the {@link Overlay} activity
 * (over other apps, for the assistant shortcut). Android keeps the real shade and recents screen for
 * the phone's built-in launcher; these are Helm's own.
 */
final class Shade extends FrameLayout implements HelmWidget.Host {
    interface Host {
        /** The shade has finished closing; remove it (or finish the activity). */
        void shadeClosed();

        ViewGroup sheetHost();

        void lockScreen();
    }

    static final String PANEL = "panel", RECENTS = "recents";
    static final String PREFIX = "panel_";
    private static final String DISMISSED = PREFIX + "recents_dismissed";
    static final String[] DEFAULT_TILES = {"torch", "dnd", "sound", "rotate", "location", "saver", "camera", "recents"};

    /** The panel's round tiles, in order. */
    static List<String> tiles(Prefs p) {
        String s = p.sp.getString(Prefs.PANEL_TILES, null);
        if (s == null) return new ArrayList<>(Arrays.asList(DEFAULT_TILES));
        List<String> out = new ArrayList<>();
        for (String t : s.split(",")) if (!t.isEmpty() && Arrays.asList(Widgets.TOGGLES).contains(t)) out.add(t);
        return out;
    }

    private final Host host;
    private final Palette p;
    private final Prefs prefs;
    private final boolean inHome;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final View backdrop;
    private ViewGroup body;
    private LinearLayout content;
    private String mode;
    private int gen;
    private boolean closing;
    private float progress;
    private int insetTop, insetBottom;
    private IconFactory.Spec iconSpec;
    private AppRepository.Listener repoWait;

    /** @param inHome true inside the launcher (frosted wallpaper behind); false over other apps (window blur behind). */
    Shade(Context c, Palette palette, Host host, String mode, boolean inHome) {
        super(c);
        this.host = host;
        this.p = palette;
        this.prefs = Prefs.get(c);
        this.inHome = inHome;
        this.mode = mode;
        setClickable(true); // Nothing underneath gets touches while the shade is up.
        backdrop = new View(c) {
            private final Material.Frost frost = new Material.Frost();

            @Override
            protected void onDraw(Canvas canvas) {
                int tint = Palette.alpha(p.dark ? 0xFF050506 : 0xFFF2F2F0, Shade.this.inHome ? 0.78f : 0.62f);
                if (Shade.this.inHome) Material.screen(canvas, this, frost, tint, p);
                else canvas.drawColor(tint);
            }
        };
        backdrop.setWillNotDraw(false);
        addView(backdrop, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        build();
    }

    String mode() { return mode; }

    private float iDownX, iDownY;
    private boolean pulling;
    private android.view.VelocityTracker vt;

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (!PANEL.equals(mode) || closing) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                iDownX = e.getRawX();
                iDownY = e.getRawY();
                pulling = false;
                if (vt == null) vt = android.view.VelocityTracker.obtain();
                vt.clear();
                break;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getRawX() - iDownX, dy = e.getRawY() - iDownY;
                int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                // Upward, mostly vertical, and the panel has nothing more to scroll: it's a close.
                if (dy < -slop && Math.abs(dy) > Math.abs(dx) * 1.3f && (body == null || !body.canScrollVertically(1))) {
                    pulling = true;
                    iDownY = e.getRawY();
                    return true;
                }
                break;
            }
            default:
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!pulling) return super.onTouchEvent(e);
        MotionEvent raw = MotionEvent.obtain(e);
        raw.setLocation(e.getRawX(), e.getRawY());
        if (vt == null) vt = android.view.VelocityTracker.obtain();
        vt.addMovement(raw);
        raw.recycle();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                setProgress(1f + (e.getRawY() - iDownY) / Math.max(1f, getHeight() * 0.42f));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pulling = false;
                vt.computeCurrentVelocity(1000);
                release(e.getActionMasked() == MotionEvent.ACTION_CANCEL ? 0f : vt.getYVelocity());
                return true;
            default:
                return true;
        }
    }

    void setInsets(int top, int bottom) {
        insetTop = top;
        insetBottom = bottom;
        if (content != null) content.setPadding(Ui.dp(18), insetTop + Ui.dp(14), Ui.dp(18), insetBottom + Ui.dp(18));
    }

    void setMode(String m) {
        if (m == null || m.equals(mode)) return;
        mode = m;
        build();
        setProgress(1f);
        enterBody();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (repoWait != null) AppRepository.get(getContext()).removeListener(repoWait);
        repoWait = null;
        worker.shutdownNow();
        if (vt != null) {
            vt.recycle();
            vt = null;
        }
    }

    private void background(Runnable r) {
        if (worker.isShutdown()) return;
        try {
            worker.execute(r);
        } catch (RejectedExecutionException ignored) {
            // Closed meanwhile.
        }
    }

    private int ink(int level) { return p.fg(Material.OPAQUE, level); }

    // ------------------------------------------------------------------ Showing and hiding

    /** 0 = hidden, 1 = fully open. The panel follows the finger with this while being pulled down. */
    void setProgress(float f) {
        progress = Ui.clamp(f, 0f, 1f);
        backdrop.setAlpha(progress);
        if (body != null) {
            boolean panel = PANEL.equals(mode);
            body.setAlpha(Math.min(1f, progress * 1.3f));
            body.setTranslationY((1f - progress) * Ui.dp(panel ? -110 : 80));
        }
    }

    float progress() { return progress; }

    /** Animates from the current progress to fully open. */
    void open() {
        if (closing) return;
        animateTo(1f, null);
    }

    /** Ends a finger drag: opens on a downward fling or past a third of the way, otherwise closes. */
    void release(float velocityY) {
        float fling = Ui.dpf(500);
        if (velocityY > fling || (velocityY > -fling && progress > 0.5f)) open();
        else close();
    }

    /** A new request arrived while closing (Overlay): stay, and show this mode. */
    void reopen(String m) {
        if (anim != null) anim.cancel();
        closing = false;
        finished = false;
        if (m != null && !m.equals(mode)) {
            mode = m;
            build();
        }
        open();
    }

    void close() {
        if (closing) return;
        closing = true;
        animateTo(0f, this::finished);
    }

    /** Closes at once (Home pressed, an app launched). */
    void closeNow() {
        closing = true;
        if (anim != null) anim.cancel();
        setProgress(0f);
        finished();
    }

    private boolean finished;

    private void finished() {
        if (finished) return;
        finished = true;
        host.shadeClosed();
    }

    boolean closing() { return closing; }

    private android.animation.ValueAnimator anim;

    private void animateTo(float target, Runnable done) {
        if (anim != null) anim.cancel();
        if (Ui.motionOff()) {
            setProgress(target);
            if (done != null) done.run();
            return;
        }
        anim = android.animation.ValueAnimator.ofFloat(progress, target);
        anim.setDuration(Ui.ms((long) (160 + 200 * Math.abs(target - progress))));
        anim.setInterpolator(target > progress ? Ui.EASE : Ui.EXIT);
        anim.addUpdateListener(a -> setProgress((float) a.getAnimatedValue()));
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator a) { cancelled = true; }

            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (!cancelled && done != null) done.run();
            }
        });
        anim.start();
    }

    private void enterBody() {
        if (Ui.motionOff() || body == null) return;
        body.setAlpha(0f);
        body.setTranslationY(Ui.dp(PANEL.equals(mode) ? -40 : 40));
        body.animate().alpha(1f).translationY(0).setDuration(Ui.ms(260)).setInterpolator(Ui.EASE).start();
    }

    // ------------------------------------------------------------------ Building

    private void build() {
        gen++;
        if (body != null) removeView(body);
        content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(18), insetTop + Ui.dp(14), Ui.dp(18), insetBottom + Ui.dp(18));
        if (RECENTS.equals(mode)) {
            buildRecents();
            body = content;
        } else {
            buildPanel();
            ScrollView sv = new ScrollView(getContext());
            sv.setVerticalScrollBarEnabled(false);
            sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
            sv.setFillViewport(true);
            sv.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            body = sv;
        }
        addView(body, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setProgress(progress);
    }

    // ------------------------------------------------------------------ Quick panel

    private void buildPanel() {
        Context c = getContext();
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout when = new LinearLayout(c);
        when.setOrientation(LinearLayout.VERTICAL);
        when.addView(new DotClock(c), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(40)));
        TextView date = Ui.mono(c, new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(new Date())
                .toUpperCase(Locale.getDefault()), ink(2), 11);
        date.setLetterSpacing(0.1f);
        date.setSingleLine(true);
        date.setEllipsize(TextUtils.TruncateAt.END);
        date.setPadding(0, Ui.dp(8), 0, 0);
        when.addView(date);
        head.addView(when, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View edit = roundButton("plus", () -> startAndClose(new Intent(c, SettingsActivity.class).putExtra("page", "panel")));
        edit.setContentDescription("Edit quick panel");
        head.addView(edit);
        View gear = roundButton("gear", () -> startAndClose(new Intent(Settings.ACTION_SETTINGS)));
        gear.setContentDescription("Phone settings");
        ((LinearLayout.LayoutParams) gear.getLayoutParams()).leftMargin = Ui.dp(8);
        head.addView(gear);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.bottomMargin = Ui.dp(20);
        content.addView(head, hl);

        // Nothing's two wide pills: Internet and Bluetooth.
        LinearLayout pills = new LinearLayout(c);
        pills.setOrientation(LinearLayout.HORIZONTAL);
        WifiManager wifi = c.getSystemService(WifiManager.class);
        boolean wifiOn = false;
        try {
            wifiOn = wifi != null && wifi.isWifiEnabled();
        } catch (RuntimeException ignored) {
        }
        boolean btOn = false;
        try {
            android.bluetooth.BluetoothManager bm = c.getSystemService(android.bluetooth.BluetoothManager.class);
            btOn = bm != null && bm.getAdapter() != null && bm.getAdapter().isEnabled();
        } catch (RuntimeException ignored) {
        }
        pills.addView(new PillTile(c, "wifi", "Internet", wifiOn ? "Wi-Fi on" : "Wi-Fi off", wifiOn,
                () -> startAndClose(new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))), pillLp(true));
        pills.addView(new PillTile(c, "bluetooth", "Bluetooth", btOn ? "On" : "Off", btOn,
                () -> startAndClose(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS))), pillLp(false));
        content.addView(pills, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(72)));

        // Round 1×1 tiles, four to a row.
        List<String> tiles = tiles(prefs);
        LinearLayout row = null;
        for (int i = 0; i < tiles.size(); i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(84));
                rl.topMargin = Ui.dp(i == 0 ? 14 : 4);
                content.addView(row, rl);
            }
            Item item = Item.helm(HelmWidget.TOGGLE, 1, 1);
            put(item, "toggle", tiles.get(i));
            put(item, "material", Material.OPAQUE);
            row.addView(new Widgets.Toggle(c, item, p, this), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        if (row != null) {
            for (int k = tiles.size() % 4; k != 0 && k < 4; k++) row.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
        }

        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(60));
        bl.topMargin = Ui.dp(14);
        content.addView(new Brightness(c), bl);

        if (prefs.panelMedia() && Media.get(c).available()) {
            Item m = Item.helm(HelmWidget.MUSIC, 4, 2);
            put(m, "material", Material.OPAQUE);
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(156));
            ml.topMargin = Ui.dp(10);
            ml.leftMargin = ml.rightMargin = -Ui.dp(6);
            content.addView(new MoreWidgets.Music(c, m, p, this), ml);
        }

        LinearLayout actions = new LinearLayout(c);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.addView(chip("Notifications", () -> {
            closeNow();
            if (!systemShade(c)) {
                Toast.makeText(c, "Swipe down from the very top edge for notifications, or turn on Helm's accessibility "
                        + "service so this button can open them", Toast.LENGTH_LONG).show();
            }
        }), new LinearLayout.LayoutParams(0, Ui.dp(48), 1f));
        actions.addView(new View(c), new LinearLayout.LayoutParams(Ui.dp(10), 1));
        actions.addView(chip("Recent apps", this::openRecents), new LinearLayout.LayoutParams(0, Ui.dp(48), 1f));
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        al.topMargin = Ui.dp(18);
        content.addView(actions, al);

        View handle = new View(c);
        handle.setBackground(Ui.pill(Palette.alpha(ink(1), 0.3f)));
        LinearLayout.LayoutParams hd = new LinearLayout.LayoutParams(Ui.dp(40), Ui.dp(4));
        hd.gravity = Gravity.CENTER_HORIZONTAL;
        hd.topMargin = Ui.dp(22);
        content.addView(handle, hd);

        content.setOnTouchListener(new SwipeClose(true));
        content.setClickable(true);
    }

    private LinearLayout.LayoutParams pillLp(boolean first) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        if (first) l.rightMargin = Ui.dp(5); else l.leftMargin = Ui.dp(5);
        return l;
    }

    private static void put(Item i, String k, Object v) {
        try {
            i.cfg.put(k, v);
        } catch (org.json.JSONException ignored) {
        }
    }

    private TextView chip(String label, Runnable r) {
        TextView t = Ui.mono(getContext(), label.toUpperCase(Locale.getDefault()), ink(1), 11);
        t.setLetterSpacing(0.08f);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(Ui.dp(10), 0, Ui.dp(10), 0);
        t.setBackground(Ui.pill(p.solid));
        t.setOnClickListener(v -> {
            Ui.tick(v);
            r.run();
        });
        return t;
    }

    private View roundButton(String icon, Runnable r) {
        View v = new View(getContext()) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(Canvas c) {
                paint.setColor(p.solid);
                float rr = Math.min(getWidth(), getHeight()) / 2f;
                c.drawCircle(getWidth() / 2f, getHeight() / 2f, rr, paint);
                paint.setColor(ink(1));
                DotIcons.draw(c, icon, getWidth() / 2f, getHeight() / 2f, rr * 0.9f, paint);
            }
        };
        v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(48), Ui.dp(48)));
        v.setOnClickListener(x -> {
            Ui.tick(x);
            r.run();
        });
        return v;
    }

    /** The phone's own notification shade. */
    static boolean systemShade(Context c) {
        if (HelmAccessibility.act(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)) return true;
        try {
            Object sb = c.getSystemService("statusbar");
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb);
            return true;
        } catch (Throwable ignored) {
            return false; // Not exposed on this build.
        }
    }

    private void startAndClose(Intent i) {
        if (closing) return;
        try {
            getContext().startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Toast.makeText(getContext(), "Nothing on this phone can open that", Toast.LENGTH_SHORT).show();
            return;
        }
        closeNow();
    }

    /** The time in Nothing's dot-matrix, accent colon. */
    private final class DotClock extends View {
        private final Paint on = new Paint(Paint.ANTI_ALIAS_FLAG), acc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Runnable tick = new Runnable() {
            @Override
            public void run() {
                invalidate();
                postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000 + 5);
            }
        };

        DotClock(Context c) { super(c); }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            tick.run();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            removeCallbacks(tick);
        }

        @Override
        protected void onDraw(Canvas c) {
            String t = Widgets.time(getContext(), new Date(), null);
            float pitch = getHeight() / (float) DotFont.ROWS;
            on.setColor(ink(1));
            acc.setColor(p.accent);
            DotFont.draw(c, t, 0, 0, pitch, on, null, acc, ":");
        }
    }

    /** A wide pill: round icon (accent when on), a name and its state. */
    private final class PillTile extends View {
        private final String icon, title, state;
        private final boolean on;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        PillTile(Context c, String icon, String title, String state, boolean on, Runnable tap) {
            super(c);
            this.icon = icon;
            this.title = title;
            this.state = state;
            this.on = on;
            setOnClickListener(v -> {
                Ui.tick(v);
                tap.run();
            });
            setContentDescription(title + ", " + state);
        }

        @Override
        protected void onDraw(Canvas c) {
            r.set(0, 0, getWidth(), getHeight());
            paint.setColor(p.solid);
            c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, paint);
            float d = getHeight() - Ui.dp(16);
            float cx = Ui.dp(8) + d / 2f, cy = getHeight() / 2f;
            paint.setColor(on ? p.accent : p.solidHigh);
            c.drawCircle(cx, cy, d / 2f, paint);
            paint.setColor(on ? p.onAccent : ink(1));
            DotIcons.draw(c, icon, cx, cy, d * 0.46f, paint);
            float x = cx + d / 2f + Ui.dp(10);
            float room = Math.max(0, getWidth() - x - Ui.dp(14));
            text.setTypeface(Ui.SANS);
            text.setTextSize(Ui.sp(15));
            text.setColor(ink(1));
            text.setLetterSpacing(0f);
            CharSequence a = TextUtils.ellipsize(title, text, room, TextUtils.TruncateAt.END);
            c.drawText(a, 0, a.length(), x, cy - Ui.dp(2), text);
            text.setTypeface(Ui.MONO);
            text.setTextSize(Ui.sp(10));
            text.setLetterSpacing(0.08f);
            text.setColor(ink(2));
            CharSequence b = TextUtils.ellipsize(state.toUpperCase(Locale.getDefault()), text, room, TextUtils.TruncateAt.END);
            c.drawText(b, 0, b.length(), x, cy + Ui.sp(14), text);
        }
    }

    /** Nothing's brightness bar: a wide pill filled to the level, sun at the start, AUTO at the end. */
    private final class Brightness extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF(), auto = new RectF();
        private float level;
        private boolean dragging;
        private float downX;

        Brightness(Context c) {
            super(c);
            int v = Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128);
            level = (float) Math.sqrt(Ui.clamp(v, 0, 255) / 255f);
            setContentDescription("Brightness");
        }

        private boolean autoOn() {
            return Settings.System.getInt(getContext().getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
                    == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
        }

        @Override
        protected void onDraw(Canvas c) {
            r.set(0, 0, getWidth(), getHeight());
            float rad = r.height() / 2f;
            paint.setColor(p.solid);
            c.drawRoundRect(r, rad, rad, paint);
            float fillW = Math.max(r.height(), r.width() * level);
            paint.setColor(ink(1));
            c.save();
            c.clipRect(0, 0, fillW, getHeight());
            c.drawRoundRect(r, rad, rad, paint);
            c.restore();
            paint.setColor(p.dark ? 0xFF000000 : 0xFFFFFFFF);
            DotIcons.draw(c, "brightness", rad, rad, r.height() * 0.42f, paint);
            text.setTypeface(Ui.MONO);
            text.setTextSize(Ui.sp(10));
            text.setLetterSpacing(0.08f);
            text.setTextAlign(Paint.Align.CENTER);
            float aw = Ui.dp(56);
            auto.set(r.right - aw - Ui.dp(6), Ui.dp(6), r.right - Ui.dp(6), r.bottom - Ui.dp(6));
            boolean a = autoOn();
            paint.setColor(a ? p.accent : Palette.alpha(ink(1), 0.12f));
            c.drawRoundRect(auto, auto.height() / 2f, auto.height() / 2f, paint);
            text.setColor(a ? p.onAccent : (fillW > auto.centerX() ? (p.dark ? 0xFF000000 : 0xFFFFFFFF) : ink(1)));
            c.drawText("AUTO", auto.centerX(), auto.centerY() + Ui.sp(3.5f), text);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            Context c = getContext();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    dragging = false;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging && Math.abs(e.getX() - downX) > ViewConfiguration.get(c).getScaledTouchSlop()) dragging = true;
                    if (dragging && Settings.System.canWrite(c)) set(e.getX() / Math.max(1, getWidth()));
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!Settings.System.canWrite(c)) askToWrite();
                    else if (!dragging) {
                        if (auto.contains(e.getX(), e.getY())) toggleAuto();
                        else set(e.getX() / Math.max(1, getWidth()));
                    }
                    return true;
                default:
                    return true;
            }
        }

        private void askToWrite() {
            Toast.makeText(getContext(), "Allow Helm to modify system settings for brightness", Toast.LENGTH_LONG).show();
            startAndClose(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getContext().getPackageName())));
        }

        private void toggleAuto() {
            try {
                Settings.System.putInt(getContext().getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                        autoOn() ? Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL : Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
            } catch (RuntimeException ignored) {
            }
            Ui.tick(this);
            invalidate();
        }

        private void set(float f) {
            level = Ui.clamp(f, 0.02f, 1f);
            // Perceptual: the bar's middle is a comfortable indoor level, not half the panel's power.
            int v = Math.max(1, Math.round(level * level * 255f));
            try {
                if (autoOn()) {
                    Settings.System.putInt(getContext().getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                }
                Settings.System.putInt(getContext().getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, v);
            } catch (RuntimeException ignored) {
            }
            invalidate();
        }
    }

    /** Swipe up (panel) or down (recents) on empty space to close; a plain tap closes too. */
    private final class SwipeClose implements View.OnTouchListener {
        private final boolean up;
        private float y0;

        SwipeClose(boolean up) { this.up = up; }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    y0 = e.getRawY();
                    return true;
                case MotionEvent.ACTION_UP: {
                    float dy = e.getRawY() - y0;
                    if (Math.abs(dy) < Ui.dp(10) || (up ? dy < -Ui.dp(60) : dy > Ui.dp(60))) close();
                    return true;
                }
                default:
                    return true;
            }
        }
    }

    // ------------------------------------------------------------------ Recents

    void openRecents() { setMode(RECENTS); }

    private static final class Recent {
        AppEntry entry;
        long when;
    }

    private void buildRecents() {
        Context c = getContext();
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(c, "Recent", ink(1), 38, Ui.headline(c));
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(roundButton("close", this::close));
        content.addView(head);

        content.setOnTouchListener(new SwipeClose(false));
        content.setClickable(true);

        if (!AwayTime.allowed(c)) {
            TextView t = Ui.text(c, "Helm builds this list from Android's app-usage history. Allow Usage access for Helm, "
                    + "then come back. Nothing leaves your phone.", ink(2), 15, Ui.SANS);
            t.setLineSpacing(Ui.dpf(3), 1f);
            t.setPadding(Ui.dp(4), Ui.dp(24), Ui.dp(4), Ui.dp(18));
            content.addView(t);
            content.addView(chip("Allow usage access", () -> startAndClose(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))),
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(48)));
            return;
        }

        TextView loading = Ui.mono(c, "LOADING", ink(3), 11);
        loading.setPadding(Ui.dp(4), Ui.dp(24), 0, 0);
        content.addView(loading);
        AppRepository repo = AppRepository.get(c);
        final int myGen = gen;
        final LinearLayout target = content;
        Runnable query = () -> background(() -> {
            Map<String, Long> last;
            try {
                last = lastUsed(c);
            } catch (RuntimeException e) {
                last = new HashMap<>();
            }
            Map<String, Long> result = last;
            main.post(() -> {
                if (myGen != gen || target != content || !isAttachedToWindow()) return;
                content.removeView(loading);
                showRecents(result);
            });
        });
        if (repo.loaded()) {
            query.run();
        } else {
            if (repoWait != null) repo.removeListener(repoWait);
            AppRepository.Listener wait = new AppRepository.Listener() {
                @Override
                public void onAppsChanged() {
                    repo.removeListener(this);
                    if (repoWait == this) repoWait = null;
                    query.run();
                }
            };
            repoWait = wait;
            repo.addListener(wait);
            repo.reload();
            main.postDelayed(() -> {
                if (repoWait != wait) return; // Already loaded, or replaced by a newer screen.
                repo.removeListener(wait);
                repoWait = null;
                query.run();
            }, 4000);
        }
    }

    /** Package → last time it came to the front, over the past two days. */
    private static Map<String, Long> lastUsed(Context c) {
        Map<String, Long> out = new HashMap<>();
        UsageStatsManager usm = c.getSystemService(UsageStatsManager.class);
        if (usm == null) return out;
        long now = System.currentTimeMillis();
        UsageEvents ev = usm.queryEvents(now - 2 * DateUtils.DAY_IN_MILLIS, now);
        if (ev == null) return out;
        UsageEvents.Event e = new UsageEvents.Event();
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            int t = e.getEventType();
            if (t == UsageEvents.Event.ACTIVITY_RESUMED || t == UsageEvents.Event.ACTIVITY_PAUSED) {
                out.merge(e.getPackageName(), e.getTimeStamp(), Math::max);
            }
        }
        return out;
    }

    private Map<String, Long> dismissed() {
        Map<String, Long> m = new HashMap<>();
        for (String s : prefs.sp.getString(DISMISSED, "").split(";")) {
            int i = s.indexOf('=');
            if (i <= 0) continue;
            try {
                m.put(s.substring(0, i), Long.parseLong(s.substring(i + 1)));
            } catch (NumberFormatException ignored) {
            }
        }
        return m;
    }

    private void dismiss(List<String> pkgs) {
        Map<String, Long> m = dismissed();
        long now = System.currentTimeMillis();
        for (String k : pkgs) m.put(k, now);
        StringBuilder b = new StringBuilder();
        long cutoff = now - 3 * DateUtils.DAY_IN_MILLIS;
        for (Map.Entry<String, Long> en : m.entrySet()) {
            if (en.getValue() < cutoff) continue;
            b.append(en.getKey()).append('=').append(en.getValue()).append(';');
        }
        prefs.put(DISMISSED, b.toString());
    }

    private void showRecents(Map<String, Long> last) {
        Context c = getContext();
        Set<String> skip = new HashSet<>();
        skip.add(c.getPackageName());
        skip.add("com.android.systemui");
        try {
            for (ResolveInfo ri : c.getPackageManager().queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)) {
                skip.add(ri.activityInfo.packageName);
            }
        } catch (RuntimeException ignored) {
        }
        Map<String, Long> gone = dismissed();
        AppRepository repo = AppRepository.get(c);
        Set<String> hidden = prefs.hidden();
        List<Recent> list = new ArrayList<>();
        for (Map.Entry<String, Long> en : last.entrySet()) {
            String pkg = en.getKey();
            if (pkg == null || skip.contains(pkg)) continue;
            Long d = gone.get(pkg);
            if (d != null && d >= en.getValue()) continue;
            AppEntry e = repo.firstForPackage(pkg);
            if (e == null || hidden.contains(e.key)) continue;
            Recent r = new Recent();
            r.entry = e;
            r.when = en.getValue();
            list.add(r);
        }
        list.sort((a, b) -> Long.compare(b.when, a.when));
        if (list.size() > 15) list = new ArrayList<>(list.subList(0, 15));

        if (list.isEmpty()) {
            TextView t = Ui.text(c, "No recent apps", ink(2), 17, Ui.SANS);
            t.setPadding(Ui.dp(4), Ui.dp(28), 0, 0);
            content.addView(t);
            return;
        }

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int cardW = Math.round(screenW * 0.66f), cardH = Math.round(screenH * 0.54f);
        HorizontalScrollView hs = new HorizontalScrollView(c) {
            private float sx, sy;

            @Override
            public boolean onInterceptTouchEvent(MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    sx = e.getX();
                    sy = e.getY();
                } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE
                        && Math.abs(e.getY() - sy) > Math.abs(e.getX() - sx)) {
                    return false;
                }
                return super.onInterceptTouchEvent(e);
            }
        };
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout strip = new LinearLayout(c);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        int side = Math.max(0, (screenW - cardW) / 2 - Ui.dp(7));
        strip.setPadding(side, 0, side, 0);
        hs.addView(strip, new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        List<String> shown = new ArrayList<>();
        for (Recent r : list) {
            shown.add(r.entry.packageName());
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(cardW, cardH);
            cl.leftMargin = cl.rightMargin = Ui.dp(7);
            strip.addView(new Card(c, r, strip, shown), cl);
        }
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        hl.leftMargin = hl.rightMargin = -Ui.dp(18);
        hl.topMargin = Ui.dp(10);
        content.addView(hs, hl);

        TextView clear = chip("Clear all", () -> {
            dismiss(shown);
            close();
        });
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(Ui.dp(160), Ui.dp(48));
        cl.gravity = Gravity.CENTER_HORIZONTAL;
        cl.topMargin = Ui.dp(12);
        content.addView(clear, cl);
        TextView hint = Ui.mono(c, "SWIPE A CARD UP TO REMOVE IT", ink(3), 10);
        hint.setLetterSpacing(0.1f);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, Ui.dp(10), 0, 0);
        content.addView(hint);
    }

    /** One recent app: a tall card with its icon large, its name and when you last used it. */
    private final class Card extends View {
        private final Recent r;
        private final LinearLayout strip;
        private final List<String> shown;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bmp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF(), dst = new RectF();
        private Bitmap icon;
        private float x0, y0;
        private boolean vertical, decided, removing;
        private final int slop;

        Card(Context c, Recent r, LinearLayout strip, List<String> shown) {
            super(c);
            this.r = r;
            this.strip = strip;
            this.shown = shown;
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            setContentDescription(r.entry.label);
            icon = AppRepository.get(c).peekIcon(r.entry);
            // Rendered at card size in Helm's icon style, so it stays sharp.
            if (iconSpec == null) {
                iconSpec = new IconFactory.Spec(p, prefs.iconStyle(), prefs.fallback(), prefs.iconShape(), prefs.iconPack());
            }
            int px = Math.round(getResources().getDisplayMetrics().widthPixels * 0.3f);
            AppEntry e = r.entry;
            IconFactory.Spec spec = iconSpec;
            background(() -> {
                Bitmap b;
                try {
                    b = IconFactory.render(c, e, px, spec);
                } catch (Throwable t) {
                    b = null;
                }
                Bitmap done = b;
                main.post(() -> {
                    if (done != null) {
                        icon = done;
                        invalidate();
                    }
                });
            });
        }

        @Override
        protected void onDraw(Canvas c) {
            box.set(0, 0, getWidth(), getHeight());
            float rad = Ui.dpf(32);
            paint.setColor(p.solid);
            c.drawRoundRect(box, rad, rad, paint);
            float pad = Ui.dpf(18);
            float s = Ui.dpf(30);
            if (icon != null) {
                dst.set(pad, pad, pad + s, pad + s);
                c.drawBitmap(icon, null, dst, bmp);
            }
            text.setTypeface(Ui.SANS);
            text.setTextSize(Ui.sp(15));
            text.setLetterSpacing(0f);
            text.setTextAlign(Paint.Align.LEFT);
            text.setColor(ink(1));
            float tx = pad + s + Ui.dpf(10);
            CharSequence name = TextUtils.ellipsize(r.entry.label, text, Math.max(0, getWidth() - tx - pad), TextUtils.TruncateAt.END);
            c.drawText(name, 0, name.length(), tx, pad + s / 2f + Ui.sp(5), text);
            paint.setColor(Palette.alpha(ink(1), 0.07f));
            float pitch = Ui.dpf(14);
            for (float y = pad * 2 + s + pitch; y < getHeight() - pad * 3; y += pitch) {
                for (float x = pad + pitch / 2f; x < getWidth() - pad; x += pitch) c.drawCircle(x, y, Ui.dpf(1.4f), paint);
            }
            float big = Math.min(getWidth(), getHeight()) * 0.42f;
            if (icon != null) {
                dst.set(getWidth() / 2f - big / 2f, getHeight() / 2f - big / 2f, getWidth() / 2f + big / 2f, getHeight() / 2f + big / 2f);
                c.drawBitmap(icon, null, dst, bmp);
            }
            text.setTypeface(Ui.MONO);
            text.setTextSize(Ui.sp(10));
            text.setLetterSpacing(0.1f);
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(ink(2));
            String ago = DateUtils.getRelativeTimeSpanString(r.when, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE).toString().toUpperCase(Locale.getDefault());
            CharSequence line = TextUtils.ellipsize("USED " + ago, text, getWidth() - pad * 2, TextUtils.TruncateAt.END);
            c.drawText(line, 0, line.length(), getWidth() / 2f, getHeight() - pad - Ui.sp(2), text);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (removing) return true;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    x0 = e.getRawX();
                    y0 = e.getRawY();
                    decided = vertical = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - x0, dy = e.getRawY() - y0;
                    if (!decided && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                        decided = true;
                        vertical = Math.abs(dy) > Math.abs(dx);
                        // Sideways belongs to the strip; up and down belong to the card.
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(vertical);
                    }
                    if (vertical) {
                        setTranslationY(dy < 0 ? dy : dy * 0.25f);
                        setAlpha(1f - Math.min(0.7f, Math.max(0, -dy) / Math.max(1, getHeight())));
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    float dy = e.getRawY() - y0;
                    if (!decided) {
                        Ui.tick(this);
                        launch();
                    } else if (vertical && dy < -getHeight() * 0.22f) {
                        remove();
                    } else {
                        animate().translationY(0).alpha(1f).setDuration(Ui.ms(200)).setInterpolator(Ui.EASE).start();
                    }
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    animate().translationY(0).alpha(1f).setDuration(Ui.ms(200)).start();
                    return true;
                default:
                    return true;
            }
        }

        private void launch() {
            android.graphics.Rect b = new android.graphics.Rect();
            getGlobalVisibleRect(b);
            try {
                AppRepository.get(getContext()).launcherApps.startMainActivity(r.entry.component, r.entry.user, b,
                        ActivityOptions.makeScaleUpAnimation(this, 0, 0, getWidth(), getHeight()).toBundle());
                AppRepository.get(getContext()).recordLaunch(r.entry.key);
            } catch (RuntimeException ex) {
                Toast.makeText(getContext(), "Couldn't open " + r.entry.label, Toast.LENGTH_SHORT).show();
                return;
            }
            closeNow();
        }

        private void remove() {
            removing = true;
            Ui.confirm(this);
            dismiss(Collections.singletonList(r.entry.packageName()));
            shown.remove(r.entry.packageName());
            animate().translationY(-getHeight()).alpha(0f).setDuration(Ui.ms(180)).withEndAction(() -> {
                strip.removeView(this);
                if (strip.getChildCount() == 0) close();
            }).start();
        }
    }

    // ------------------------------------------------------------------ HelmWidget.Host (tiles, player)

    @Override
    public void start(Intent i, View from) { startAndClose(i); }

    @Override
    public void save() {}

    @Override
    public ViewGroup sheetHost() { return host.sheetHost(); }

    @Override
    public void needPermission(String permission, Runnable granted) {
        if (getContext().checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) granted.run();
    }

    @Override
    public void pickImage(Consumer<Uri> picked) {}

    @Override
    public void lockScreen() {
        closeNow();
        host.lockScreen();
    }

    @Override
    public void openRecentsView() { openRecents(); }
}
