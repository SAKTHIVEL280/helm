package com.helm.launcher;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.TextPaint;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.util.function.Consumer;

/**
 * Helm's own widgets, drawn in Nothing OS 5's language: 1×1 circles, 2×1 pills, deep-radius tiles;
 * dot-matrix for data, Geist Mono for labels, the serif for big numbers; accent used sparingly.
 */
abstract class HelmWidget extends View {
    interface Host {
        void start(Intent i, View from);

        void save();

        ViewGroup sheetHost();

        void needPermission(String permission, Runnable granted);

        void pickImage(Consumer<Uri> picked);

        void lockScreen();

        /** Picks a phone number from Contacts; the Uri carries a one-time read grant. */
        default void pickContact(Consumer<Uri> picked) {}

        /** Helm's recent apps (the Recents toggle). */
        default void openRecentsView() {}
    }

    static final String ANALOG = "analog", DIGITAL = "digital", WORLD = "world", DATE = "date",
            WEATHER = "weather", BATTERY = "battery", AWAY = "away", PHOTO = "photo", TOGGLE = "toggle",
            AGENDA = "agenda", NOTE = "note", MUSIC = "music", MONTH = "month", TIMER = "timer", COMPASS = "compass",
            SCREEN = "screentime", COUNTDOWN = "countdown", STEPS = "steps", CONTACT = "contact";
    static final String[] KINDS = {MUSIC, ANALOG, DIGITAL, WEATHER, AWAY, PHOTO, TOGGLE, BATTERY, DATE, MONTH,
            WORLD, AGENDA, SCREEN, STEPS, CONTACT, COUNTDOWN, TIMER, COMPASS, NOTE};

    static final int CIRCLE = 0, PILL = 1, RECT = 2;

    final Item item;
    final Host host;
    Palette p;
    final Material.Frost frost = new Material.Frost();
    final RectF box = new RectF();
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private boolean shown;
    /** Where the last touch went down, for widgets with their own buttons. */
    float touchX = -1, touchY = -1;

    HelmWidget(Context c, Item item, Palette p, Host host) {
        super(c);
        this.item = item;
        this.p = p;
        this.host = host;
        setHapticFeedbackEnabled(false);
        setOnClickListener(v -> onTap());
        setContentDescription(title(item.kind));
    }

    static HelmWidget create(Context c, Item item, Palette p, Host host) {
        switch (item.kind == null ? "" : item.kind) {
            case ANALOG: return new Widgets.Analog(c, item, p, host);
            case DIGITAL: return new Widgets.Digital(c, item, p, host);
            case WORLD: return new Widgets.World(c, item, p, host);
            case DATE: return new Widgets.DateTile(c, item, p, host);
            case WEATHER: return new Widgets.WeatherTile(c, item, p, host);
            case BATTERY: return new Widgets.Battery(c, item, p, host);
            case AWAY: return new Widgets.Away(c, item, p, host);
            case PHOTO: return new Widgets.Photo(c, item, p, host);
            case TOGGLE: return new Widgets.Toggle(c, item, p, host);
            case AGENDA: return new Widgets.Agenda(c, item, p, host);
            case NOTE: return new Widgets.Note(c, item, p, host);
            case MUSIC: return new MoreWidgets.Music(c, item, p, host);
            case MONTH: return new MoreWidgets.Month(c, item, p, host);
            case TIMER: return new MoreWidgets.Timer(c, item, p, host);
            case COMPASS: return new MoreWidgets.Compass(c, item, p, host);
            case SCREEN: return new ExtraWidgets.ScreenTime(c, item, p, host);
            case COUNTDOWN: return new ExtraWidgets.Countdown(c, item, p, host);
            case STEPS: return new ExtraWidgets.Steps(c, item, p, host);
            case CONTACT: return new ExtraWidgets.Contact(c, item, p, host);
            default: return new Widgets.Note(c, item, p, host);
        }
    }

    static String title(String kind) {
        switch (kind == null ? "" : kind) {
            case ANALOG: return "Clock";
            case DIGITAL: return "Dot clock";
            case WORLD: return "World clock";
            case DATE: return "Date";
            case WEATHER: return "Weather";
            case BATTERY: return "Battery";
            case AWAY: return "Away Time";
            case PHOTO: return "Photo";
            case TOGGLE: return "Quick toggle";
            case AGENDA: return "Agenda";
            case MUSIC: return "Music";
            case MONTH: return "Calendar";
            case TIMER: return "Timer";
            case COMPASS: return "Compass";
            case SCREEN: return "Screen time";
            case COUNTDOWN: return "Countdown";
            case STEPS: return "Steps";
            case CONTACT: return "Quick dial";
            default: return "Note";
        }
    }

    static String blurb(String kind) {
        switch (kind) {
            case ANALOG: return "Round dial, accent second hand";
            case DIGITAL: return "The time in dot-matrix";
            case WORLD: return "Another city's time";
            case DATE: return "Today, set in the serif";
            case WEATHER: return "Open-Meteo, no account needed";
            case BATTERY: return "Charge as a dot gauge";
            case AWAY: return "Time spent off your phone";
            case PHOTO: return "A photo, framed or as a sticker";
            case TOGGLE: return "Torch, Do Not Disturb, sound and more";
            case AGENDA: return "Your next events";
            case MUSIC: return "What's playing, with controls";
            case MONTH: return "This month at a glance";
            case TIMER: return "One-tap timers with a dot ring";
            case COMPASS: return "North, in dots";
            case SCREEN: return "Today's phone time and top apps";
            case COUNTDOWN: return "Days to a date that matters";
            case STEPS: return "Today's steps on a dot ring";
            case CONTACT: return "One tap to call someone";
            default: return "A few words on home";
        }
    }

    /** Allowed sizes as {w, h}; the first is the default. */
    static int[][] sizes(String kind) {
        switch (kind) {
            case ANALOG: return new int[][]{{2, 2}, {1, 1}, {3, 3}};
            case DIGITAL: return new int[][]{{2, 1}, {4, 1}, {2, 2}, {4, 2}};
            case WORLD: return new int[][]{{2, 2}, {2, 1}};
            case DATE: return new int[][]{{2, 2}, {2, 1}};
            case WEATHER: return new int[][]{{2, 1}, {2, 2}, {4, 2}};
            case BATTERY: return new int[][]{{1, 1}, {2, 1}, {2, 2}};
            case AWAY: return new int[][]{{1, 1}, {2, 2}};
            case PHOTO: return new int[][]{{2, 2}, {1, 1}, {2, 1}, {4, 2}, {3, 3}, {4, 4}};
            case TOGGLE: return new int[][]{{1, 1}};
            case AGENDA: return new int[][]{{4, 2}, {2, 2}};
            case MUSIC: return new int[][]{{2, 2}, {4, 2}, {4, 1}, {2, 1}};
            case MONTH: return new int[][]{{2, 2}, {3, 3}};
            case TIMER: return new int[][]{{1, 1}, {2, 2}};
            case COMPASS: return new int[][]{{1, 1}, {2, 2}};
            case SCREEN: return new int[][]{{2, 2}, {2, 1}, {1, 1}, {4, 2}};
            case COUNTDOWN: return new int[][]{{2, 2}, {2, 1}};
            case STEPS: return new int[][]{{2, 2}, {1, 1}};
            case CONTACT: return new int[][]{{1, 1}, {2, 2}};
            default: return new int[][]{{2, 2}, {2, 1}, {4, 1}, {4, 2}};
        }
    }

    int material() { return Material.resolve(item.cfg.optInt("material", Material.AUTO), p); }

    int ink(int level) { return p.fg(material(), level); }

    /** Square faces that should be round at every size (the analog clock). */
    boolean roundFace() { return false; }

    /** 1×1 widgets are circles in NOS 5 (quick toggles); some prefer a rounded square. */
    boolean circleAtOne() { return true; }

    boolean drawsSurface() { return true; }

    int shape() {
        if (roundFace() && item.w == item.h) return CIRCLE;
        if (item.w == 1 && item.h == 1 && circleAtOne()) return CIRCLE;
        if (item.h == 1 && item.w >= 2) return PILL;
        return RECT;
    }

    float radius() {
        int s = shape();
        if (s == CIRCLE || s == PILL) return Math.min(box.width(), box.height()) / 2f;
        return Math.min(p.radius, Math.min(box.width(), box.height()) / 2f);
    }

    void setPalette(Palette palette) {
        p = palette;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float pad = Ui.dpf(6);
        box.set(pad, pad, getWidth() - pad, getHeight() - pad);
        if (shape() == CIRCLE) {
            float r = Math.min(box.width(), box.height()) / 2f;
            float cx = box.centerX(), cy = box.centerY();
            box.set(cx - r, cy - r, cx + r, cy + r);
        }
        if (drawsSurface()) Material.roundRect(c, this, frost, box, radius(), material(), p);
        drawContent(c);
    }

    abstract void drawContent(Canvas c);

    void onTap() { openOptions(); }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent ev) {
        if (ev.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
            touchX = ev.getX();
            touchY = ev.getY();
        }
        return super.onTouchEvent(ev);
    }

    /** Kind-specific rows in the options sheet. */
    void buildOptions(Rows r, LinearLayout card) {}

    void openOptions() {
        Rows r = new Rows(getContext(), p);
        LinearLayout col = r.column();
        LinearLayout card = r.card(col);
        buildOptions(r, card);
        r.segment(card, "Surface", null, new String[]{"Auto", "Solid", "Glass", "Clear"},
                item.cfg.optInt("material", Material.AUTO) + 1, i -> {
                    put("material", i - 1);
                    invalidate();
                });
        Sheet.show(host.sheetHost(), p, title(item.kind), col);
    }

    void put(String key, Object value) {
        try {
            item.cfg.put(key, value);
        } catch (org.json.JSONException ignored) {
        }
        host.save();
    }

    @Override
    public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        if (visible == shown) return;
        shown = visible;
        if (visible) onShow(); else onHide();
    }

    boolean isShown2() { return shown; }

    void onShow() {}

    void onHide() {}

    // Drawing helpers

    /** Largest dot pitch at which {@code s} fits in w × h. */
    static float pitchFor(String s, float w, float h) {
        return Math.min(w / Math.max(1, DotFont.columns(s)), h / DotFont.ROWS);
    }

    void dots(Canvas c, String s, float x, float y, float pitch, int color) {
        paint.setColor(color);
        DotFont.draw(c, s, x, y, pitch, paint, null, null, null);
    }

    void dotsAccent(Canvas c, String s, float x, float y, float pitch, int color, String accentChars) {
        paint.setColor(color);
        Paint acc = new Paint(Paint.ANTI_ALIAS_FLAG);
        acc.setColor(p.accent);
        DotFont.draw(c, s, x, y, pitch, paint, null, acc, accentChars);
    }

    float label(Canvas c, String s, float x, float baseline, float sizeSp, int color, Paint.Align align) {
        text.setTypeface(Ui.MONO);
        text.setTextSize(Ui.sp(sizeSp));
        text.setLetterSpacing(0.08f);
        text.setColor(color);
        text.setTextAlign(align);
        s = fit(s, x, align);
        c.drawText(s, x, baseline, text);
        return text.measureText(s);
    }

    /** Ellipsizes {@code s} so it stays inside the tile from {@code x} with this alignment (no spill on long names). */
    String fit(String s, float x, Paint.Align align) {
        if (s == null || box.isEmpty()) return s == null ? "" : s;
        float edge = Math.min(Ui.dpf(10), inset() * 0.6f);
        float room;
        if (align == Paint.Align.LEFT) room = box.right - edge - x;
        else if (align == Paint.Align.RIGHT) room = x - box.left - edge;
        else room = 2f * Math.min(x - box.left, box.right - x) - 2f * edge;
        if (room <= 0 || text.measureText(s) <= room) return s;
        return android.text.TextUtils.ellipsize(s, text, room, android.text.TextUtils.TruncateAt.END).toString();
    }

    void words(Canvas c, String s, float x, float baseline, float sizePx, int color, Typeface face, Paint.Align align) {
        text.setTypeface(face);
        text.setTextSize(sizePx);
        text.setLetterSpacing(0f);
        text.setColor(color);
        text.setTextAlign(align);
        c.drawText(fit(s, x, align), x, baseline, text);
    }

    float inset() { return Math.max(Ui.dpf(12), Math.min(box.width(), box.height()) * 0.12f); }
}
