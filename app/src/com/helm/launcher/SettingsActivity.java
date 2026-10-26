package com.helm.launcher;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Helm's settings in Nothing OS 5's layout: serif page titles, grouped cards, mono section labels. */
public final class SettingsActivity extends Activity {
    private static final int REQ_HOME = 7, REQ_PHOTO = 8, REQ_EXPORT = 9, REQ_IMPORT = 10;
    private static final int[] ACCENTS = {0xFFD71921, 0xFFE8542F, 0xFFF2A93B, 0xFFC9C63A, 0xFF2E9E7E, 0xFF1F8A70,
            0xFF2F80ED, 0xFF5B5BD6, 0xFF9B51E0, 0xFFE0559B, 0xFF8E8E93, 0xFF000000};
    private static final int[] SOLIDS = {0xFF000000, 0xFF1B1B1D, 0xFF3A3A3C, 0xFF8E8E93, 0xFFE6E6E3, 0xFFFFFFFF,
            0xFFD71921, 0xFF1F3A5F, 0xFF2E5E4E, 0xFF5B4B8A, 0xFFF2E3C6, 0xFFE8C4C4};
    private static final String[] ACTIONS = {"None", "App drawer", "Notifications", "Quick settings", "Search",
            "Lock screen", "Open an app", "Helm settings", "Torch", "Edit home", "Quick panel", "Recent apps"};

    private Prefs prefs;
    private Palette p;
    private Rows r;
    private ScrollView scroll;
    private LinearLayout content;
    private FrameLayout frame;
    private final ArrayDeque<String> stack = new ArrayDeque<>();
    private long roleAskedAt;
    private View preview;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        prefs = Prefs.get(this);
        Rect b = getWindowManager().getMaximumWindowMetrics().getBounds();
        WallpaperStore.get(this).setScreen(b.width(), b.height());
        p = Palette.resolve(this);
        r = new Rows(this, p);

        Window w = getWindow();
        Ui.edgeToEdge(w);
        w.setBackgroundDrawable(new ColorDrawable(p.dark ? 0xFF000000 : 0xFFF0F0EE));

        frame = new FrameLayout(this);
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        content = r.column();
        content.setPadding(Ui.dp(16), 0, Ui.dp(16), Ui.dp(28));
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        frame.addView(scroll);
        setContentView(frame);

        frame.setOnApplyWindowInsetsListener((v, in) -> {
            Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            scroll.setPadding(i.left, i.top, i.right, i.bottom);
            return in;
        });
        WindowInsetsController bars = w.getInsetsController();
        if (bars != null) {
            int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            bars.setSystemBarsAppearance(p.dark ? 0 : light, light);
        }
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> {
            if (Sheet.dismissActive()) return;
            if (stack.size() > 1) {
                stack.pop();
                show(stack.peek(), false);
            } else {
                finish();
            }
        });

        String start = getIntent().getStringExtra("page");
        if (state != null && state.getString("page") != null) start = state.getString("page");
        stack.push("main");
        if (start != null && !"main".equals(start)) stack.push(start);
        show(stack.peek(), false);
        // Opened from its own icon before home ever ran: load the app list, then redraw the page once.
        AppRepository repo = AppRepository.get(this);
        if (!repo.loaded()) {
            repo.addListener(new AppRepository.Listener() {
                @Override
                public void onAppsChanged() {
                    repo.removeListener(this);
                    if (!isDestroyed() && Sheet.active() == null) show(stack.peek(), false);
                }
            });
            repo.reload();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("page", stack.peek());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Sheet.forget(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if ("main".equals(stack.peek())) show("main", false);
    }

    private void open(String page) {
        stack.push(page);
        show(page, true);
    }

    /** Settings that change colours rebuild the whole screen in the new palette. */
    private void restyle() {
        String page = stack.peek();
        getIntent().putExtra("page", page);
        recreate();
    }

    private void show(String page, boolean forward) {
        content.removeAllViews();
        preview = null;
        switch (page) {
            case "appearance": appearance(); break;
            case "wallpaper": wallpaper(); break;
            case "icons": icons(); break;
            case "home": homeScreen(); break;
            case "drawer": drawerPage(); break;
            case "hidden": hiddenPage(); break;
            case "gestures": gestures(); break;
            case "backup": backup(); break;
            case "about": about(); break;
            case "lock": lockPage(); break;
            case "panel": panelPage(); break;
            case "pip": pipPage(); break;
            default: main(); break;
        }
        scroll.scrollTo(0, 0);
        if (forward && !Ui.motionOff()) {
            content.setAlpha(0f);
            content.setTranslationX(Ui.dp(24));
            content.animate().alpha(1f).translationX(0).setDuration(Ui.ms(240)).setInterpolator(Ui.EASE).start();
        }
    }

    private void header(String title, boolean back) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(Ui.dp(6), Ui.dp(back ? 12 : 40), 0, Ui.dp(18));
        if (back) {
            TextView arrow = Ui.text(this, "←", p.onSolid, 24, Ui.SANS);
            arrow.setPadding(0, Ui.dp(8), Ui.dp(20), Ui.dp(14));
            arrow.setContentDescription("Back");
            arrow.setOnClickListener(v -> {
                stack.pop();
                show(stack.peek(), false);
            });
            head.addView(arrow);
        }
        head.addView(Ui.text(this, title, p.onSolid, 40, Ui.headline(this)));
        content.addView(head);
    }

    private void note(LinearLayout parent, String text) {
        TextView t = Ui.text(this, text, p.onSolid2, 13, Ui.SANS);
        t.setLineSpacing(Ui.dpf(3), 1f);
        t.setPadding(Ui.dp(6), Ui.dp(4), Ui.dp(6), Ui.dp(12));
        parent.addView(t);
    }

    // ---------------------------------------------------------------- Main

    private void main() {
        header("Settings", false);
        if (!isDefaultHome()) {
            LinearLayout b = r.column();
            b.setBackground(Ui.rounded(p.solid, 22));
            b.setPadding(Ui.dp(20), Ui.dp(18), Ui.dp(20), Ui.dp(18));
            LinearLayout head = new LinearLayout(this);
            head.setGravity(Gravity.CENTER_VERTICAL);
            View dot = new View(this);
            dot.setBackground(Ui.pill(p.accent));
            head.addView(dot, new LinearLayout.LayoutParams(Ui.dp(8), Ui.dp(8)));
            TextView t = Ui.text(this, "Helm isn't your home app yet", p.onSolid, 16, Ui.SANS);
            LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            hl.leftMargin = Ui.dp(12);
            head.addView(t, hl);
            b.addView(head);
            r.button(b, "Set as default", true, this::requestHome);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bl.bottomMargin = Ui.dp(12);
            content.addView(b, bl);
        }
        LinearLayout look = r.card(content);
        String style = prefs.style() == Prefs.STYLE_TRANSLUCENT ? "Translucent" : "Classic";
        String accent = new String[]{"Nothing red", "From wallpaper", "Custom accent"}[Ui.clamp(prefs.accentMode(), 0, 2)];
        r.link(look, "Appearance", style + " · " + accent, () -> open("appearance"));
        String[] sources = {"Atmosphere", "Your photo", "Solid colour", "Phone wallpaper"};
        String src = sources[Ui.clamp(prefs.wpSource(), 0, 3)]
                + (prefs.wpSource() == Prefs.WP_ATMOSPHERE ? " · " + Atmosphere.MOODS[Ui.clamp(prefs.wpMood(), 0, 5)] : "");
        r.link(look, "Wallpaper", src, () -> open("wallpaper"));
        String[] iconStyles = {"Monochrome", "Tinted", "Original", "Greyscale"};
        String[] shapes = {"Circle", "Squircle", "Rounded"};
        r.link(look, "Icons", iconStyles[Ui.clamp(prefs.iconStyle(), 0, 3)] + " · " + shapes[Ui.clamp(prefs.iconShape(), 0, 2)],
                () -> open("icons"));

        LinearLayout layoutCard = r.card(content);
        r.link(layoutCard, "Home screen", prefs.columns() + " × " + prefs.rows() + " grid"
                + (prefs.dock() ? " · Dock" : ""), () -> open("home"));
        r.link(layoutCard, "App drawer", (prefs.drawerLayout() == Prefs.LAYOUT_GRID ? "Grid" : "List")
                + (prefs.suggestions() ? " · Suggestions" : ""), () -> open("drawer"));
        r.link(layoutCard, "Gestures", "Swipe up: " + ACTIONS[Ui.clamp(prefs.gesture(Prefs.G_UP), 0, ACTIONS.length - 1)],
                () -> open("gestures"));

        LinearLayout system = r.card(content);
        r.link(system, "Lock screen", "Clock: " + LockArt.CLOCKS[Ui.clamp(prefs.lockClock(), 0, LockArt.CLOCKS.length - 1)],
                () -> open("lock"));
        r.link(system, "Quick panel & recents", Shade.tiles(prefs).size() + " tiles", () -> open("panel"));
        r.link(system, "Music & notification dots", HelmNotifications.granted(this) ? "Allowed" : "Needs notification access",
                () -> startSafely(Media.accessSettings()));
        if (!HelmNotifications.granted(this) || !HelmAccessibility.enabled()) restrictedHint(system);
        r.link(system, "Picture-in-picture", "Why videos may not shrink when you go home", () -> open("pip"));

        LinearLayout more = r.card(content);
        r.link(more, "Backup & restore", "Layout and settings as a file", () -> open("backup"));
        r.link(more, "About Helm", "Version 3.0", () -> open("about"));
    }

    // ---------------------------------------------------------------- Appearance

    private void appearance() {
        header("Appearance", true);
        LinearLayout card = r.card(content);
        r.segment(card, "Style", "Translucent is Nothing OS 5's frosted home theme. Classic is opaque, monochrome and red.",
                new String[]{"Classic", "Translucent"}, prefs.style(), i -> {
                    prefs.put(Prefs.STYLE, i);
                    restyle();
                });
        r.segment(card, "Mode", null, new String[]{"System", "Dark", "Light"}, prefs.theme(), i -> {
            prefs.put(Prefs.THEME, i);
            restyle();
        });

        r.section(content, "Colour");
        LinearLayout colour = r.card(content);
        r.segment(colour, "Accent", "From wallpaper is Nothing OS 5's colour engine",
                new String[]{"Nothing red", "Wallpaper", "Custom"}, prefs.accentMode(), i -> {
                    prefs.put(Prefs.ACCENT_MODE, i);
                    restyle();
                });
        if (prefs.accentMode() == Prefs.ACCENT_CUSTOM_COLOUR) {
            r.swatches(colour, "Custom accent", ACCENTS, prefs.accentCustom(), col -> {
                prefs.put(Prefs.ACCENT_CUSTOM, col);
                restyle();
            });
        }

        r.section(content, "Type & shape");
        LinearLayout type = r.card(content);
        r.segment(type, "Headline face", "For titles and big numbers", new String[]{"Serif", "Geist"}, prefs.headline(), i -> {
            prefs.put(Prefs.HEADLINE, i);
            restyle();
        });
        r.slider(type, "Corner radius", "dp", 12, 36, 2, prefs.corner(), null, v -> prefs.put(Prefs.CORNER, v));

        r.section(content, "Material");
        LinearLayout mat = r.card(content);
        r.slider(mat, "Glass", "%", 20, 90, 5, prefs.glass(), null, v -> prefs.put(Prefs.GLASS, v));
        r.slider(mat, "Frost", "%", 0, 100, 10, prefs.frost(), null, v -> prefs.put(Prefs.FROST, v));
        note(content, "Frost needs a Helm wallpaper (Atmosphere, your photo or a solid colour). "
                + "Android doesn't let apps read the phone's own wallpaper, so with it surfaces are tinted, not frosted.");

        r.section(content, "Feel");
        LinearLayout feel = r.card(content);
        int[] speeds = {0, 70, 100, 130};
        int cur = Math.round(prefs.motion() * 100);
        int sel = 2;
        for (int i = 0; i < speeds.length; i++) if (speeds[i] == cur) sel = i;
        r.segment(feel, "Motion", null, new String[]{"Off", "Quick", "Normal", "Relaxed"}, sel, i -> prefs.put(Prefs.MOTION, speeds[i]));
        r.toggle(feel, "Haptics", "Ticks on the scrubber, paging and toggles", prefs.haptics(), on -> prefs.put(Prefs.HAPTICS, on));
        r.toggle(feel, "Hide status bar", "Swipe down from the top to peek", prefs.hideStatus(), on -> prefs.put(Prefs.HIDE_STATUS, on));
    }

    // ---------------------------------------------------------------- Wallpaper

    private void wallpaper() {
        header("Wallpaper", true);
        preview = new View(this) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

            @Override
            protected void onDraw(Canvas c) {
                RectF box = new RectF(0, 0, getWidth(), getHeight());
                Bitmap bmp = WallpaperStore.get(getContext()).sharp();
                if (bmp == null || prefs.wpSource() == Prefs.WP_SYSTEM) {
                    paint.setShader(null);
                    paint.setColor(p.solidHigh);
                    c.drawRoundRect(box, Ui.dpf(24), Ui.dpf(24), paint);
                    paint.setColor(p.onSolid2);
                    paint.setTextAlign(Paint.Align.CENTER);
                    paint.setTypeface(Ui.MONO);
                    paint.setTextSize(Ui.sp(12));
                    c.drawText(prefs.wpSource() == Prefs.WP_SYSTEM ? "PHONE WALLPAPER" : "PREPARING", box.centerX(), box.centerY(), paint);
                    return;
                }
                BitmapShader sh = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                Matrix m = new Matrix();
                float s = Math.max(getWidth() / (float) bmp.getWidth(), getHeight() / (float) bmp.getHeight());
                m.setScale(s, s);
                m.postTranslate((getWidth() - bmp.getWidth() * s) / 2f, (getHeight() - bmp.getHeight() * s) / 2f);
                sh.setLocalMatrix(m);
                paint.setShader(sh);
                c.drawRoundRect(box, Ui.dpf(24), Ui.dpf(24), paint);
                paint.setShader(null);
                if (prefs.wpDim() > 0) {
                    paint.setColor(Math.round(prefs.wpDim() / 100f * 255) << 24);
                    c.drawRoundRect(box, Ui.dpf(24), Ui.dpf(24), paint);
                }
                // A hint of the home screen on top.
                paint.setColor(Palette.alpha(p.dark ? 0xFF18181A : 0xFFFFFFFF, 0.6f));
                float pill = Ui.dpf(30);
                c.drawRoundRect(new RectF(Ui.dpf(26), getHeight() - Ui.dpf(26) - pill, getWidth() - Ui.dpf(26), getHeight() - Ui.dpf(26)), pill / 2, pill / 2, paint);
                float d = Ui.dpf(38);
                for (int i = 0; i < 4; i++) {
                    float cx = getWidth() * (0.2f + 0.2f * i);
                    c.drawCircle(cx, getHeight() - Ui.dpf(26) - pill - Ui.dpf(16) - d / 2f, d / 2f, paint);
                }
            }
        };
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(300));
        pl.bottomMargin = Ui.dp(14);
        content.addView(preview, pl);
        refreshPreview();

        LinearLayout card = r.card(content);
        r.segment(card, "Source", null, new String[]{"Atmosphere", "Photo", "Solid", "Phone"}, prefs.wpSource(), i -> {
            if (i == Prefs.WP_PHOTO) {
                pickPhoto();
                return;
            }
            prefs.put(Prefs.WP_SOURCE, i);
            show("wallpaper", false);
        });
        int src = prefs.wpSource();
        if (src == Prefs.WP_ATMOSPHERE) {
            r.segment(card, "Mood", null, Atmosphere.MOODS, prefs.wpMood(), i -> {
                prefs.put(Prefs.WP_MOOD, i);
                refreshPreview();
            });
            r.toggle(card, "Grain", null, prefs.wpGrain(), on -> {
                prefs.put(Prefs.WP_GRAIN, on);
                refreshPreview();
            });
            r.toggle(card, "Construction lines", "Hairline circles, a dashed rule, the dot grid", prefs.wpLines(), on -> {
                prefs.put(Prefs.WP_LINES, on);
                refreshPreview();
            });
            r.button(content, "Shuffle", false, () -> {
                prefs.put(Prefs.WP_SEED, SystemClock.uptimeMillis());
                refreshPreview();
            });
        } else if (src == Prefs.WP_PHOTO) {
            r.link(card, "Choose another photo", null, this::pickPhoto);
        } else if (src == Prefs.WP_SOLID_COLOUR) {
            r.swatches(card, "Colour", SOLIDS, prefs.wpSolid(), col -> {
                prefs.put(Prefs.WP_SOLID, col);
                refreshPreview();
            });
        } else {
            note(card, "Helm shows the phone's wallpaper. Translucent surfaces are tinted without frost.");
        }
        if (src != Prefs.WP_SYSTEM) {
            LinearLayout more = r.card(content);
            r.slider(more, "Dim", "%", 0, 60, 5, prefs.wpDim(), null, v -> {
                prefs.put(Prefs.WP_DIM, v);
                preview.invalidate();
            });
            r.button(content, "Lock screen with live clock…", true, () -> open("lock"));
            r.button(content, "Use as still lock wallpaper", false, () -> {
                WallpaperStore ws = WallpaperStore.get(this);
                ws.ensure(p.dark, () -> ws.applyToSystem(ok ->
                        toast(ok ? "Set for home and lock screen" : "Couldn't set the phone's wallpaper")));
            });
        }
    }

    private void refreshPreview() {
        WallpaperStore.get(this).ensure(p.dark, () -> {
            if (preview != null) preview.invalidate();
        });
        if (preview != null) preview.invalidate();
    }

    private void pickPhoto() {
        try {
            startActivityForResult(new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"), REQ_PHOTO);
        } catch (RuntimeException e) {
            toast("No photo picker available");
        }
    }

    // ---------------------------------------------------------------- Icons

    private void icons() {
        header("Icons", true);
        LinearLayout card = r.card(content);
        r.segment(card, "Style", "Monochrome uses each app's themed-icon glyph, like Nothing's icon pack",
                new String[]{"Mono", "Tinted", "Original", "Grey"}, prefs.iconStyle(), i -> prefs.put(Prefs.ICON_STYLE, i));
        r.segment(card, "Shape", null, new String[]{"Circle", "Squircle", "Rounded"}, prefs.iconShape(), i -> prefs.put(Prefs.ICON_SHAPE, i));
        r.slider(card, "Size", "%", 80, 130, 5, prefs.iconScale(), null, v -> prefs.put(Prefs.ICON_SCALE, v));
        r.segment(card, "Apps without a themed icon", null, new String[]{"Greyscale", "Dot letter"}, prefs.fallback(),
                i -> prefs.put(Prefs.FALLBACK, i));

        r.section(content, "Icon pack");
        LinearLayout packs = r.card(content);
        Map<String, String> installed = IconPack.installed(this);
        if (installed.isEmpty()) {
            note(packs, "No icon packs installed. Any pack that supports Nova or ADW works here.");
        } else {
            List<String> pkgs = new ArrayList<>(installed.keySet());
            String[] names = new String[pkgs.size() + 1];
            names[0] = "None";
            int sel = 0;
            for (int i = 0; i < pkgs.size(); i++) {
                names[i + 1] = installed.get(pkgs.get(i));
                if (pkgs.get(i).equals(prefs.iconPack())) sel = i + 1;
            }
            r.segment(packs, "Pack", "Icons the pack doesn't cover use the style above", names, sel,
                    i -> prefs.put(Prefs.ICON_PACK, i == 0 ? "" : pkgs.get(i - 1)));
        }

        r.section(content, "Labels");
        LinearLayout labels = r.card(content);
        r.toggle(labels, "Labels on home", null, prefs.labelsHome(), on -> prefs.put(Prefs.LABELS_HOME, on));
        r.toggle(labels, "Labels in the dock", null, prefs.labelsDock(), on -> prefs.put(Prefs.LABELS_DOCK, on));
        r.segment(labels, "Label face", null, new String[]{"Geist", "Geist Mono"}, prefs.labelFont(), i -> prefs.put(Prefs.LABEL_FONT, i));
        r.slider(labels, "Label size", "sp", 10, 15, 1, prefs.labelSize(), null, v -> prefs.put(Prefs.LABEL_SIZE, v));

        r.section(content, "Notification dots");
        LinearLayout dots = r.card(content);
        r.toggle(dots, "Dots on icons", "A small accent dot on apps with notifications", prefs.dots(), on -> prefs.put(Prefs.DOTS, on));
        if (!HelmNotifications.granted(this)) {
            r.link(dots, "Allow notification access", "Helm counts notifications per app; it never reads them",
                    () -> startSafely(Media.accessSettings()));
        }
    }

    // ---------------------------------------------------------------- Home

    private void homeScreen() {
        header("Home screen", true);
        LinearLayout grid = r.card(content);
        r.segment(grid, "Columns", null, new String[]{"4", "5", "6"}, prefs.columns() - 4, i -> prefs.put(Prefs.COLUMNS, i + 4));
        r.segment(grid, "Rows", null, new String[]{"5", "6", "7", "8"}, prefs.rows() - 5, i -> prefs.put(Prefs.ROWS, i + 5));
        LinearLayout parts = r.card(content);
        r.toggle(parts, "Dock", null, prefs.dock(), on -> prefs.put(Prefs.DOCK, on));
        r.segment(parts, "Dock background", "Glass puts a frosted shelf behind the dock", new String[]{"None", "Glass"},
                prefs.dockBg(), i -> prefs.put(Prefs.DOCK_BG, i));
        r.segment(parts, "Search bar", null, new String[]{"Bottom", "Top", "Hidden"}, prefs.searchPos(), i -> prefs.put(Prefs.SEARCH_POS, i));
        r.toggle(parts, "Page dots", null, prefs.pageDots(), on -> prefs.put(Prefs.PAGE_DOTS, on));
        note(content, "Long-press empty space to edit: add widgets, resize them, or Arrange to select several items "
                + "and move them together. Drop one app on another to make a folder; long-press a folder for a big folder.");
        r.button(content, "Reset home layout", false, () -> {
            Rows sr = new Rows(this, p);
            LinearLayout col = sr.column();
            note(col, "Pages, widgets and folders go back to how Helm started. Apps stay installed.");
            sr.button(col, "Reset", true, () -> {
                //noinspection ResultOfMethodCallIgnored
                new File(getFilesDir(), "layout.json").delete();
                prefs.put("layout_stamp", SystemClock.uptimeMillis());
                Sheet.dismissActive();
                toast("Home layout reset");
            });
            Sheet.show(frame, p, "Reset home?", col);
        });
    }

    // ---------------------------------------------------------------- Drawer

    private void drawerPage() {
        header("App drawer", true);
        LinearLayout card = r.card(content);
        r.segment(card, "Layout", null, new String[]{"Grid", "List"}, prefs.drawerLayout(), i -> prefs.put(Prefs.DRAWER_LAYOUT, i));
        r.segment(card, "Columns", null, new String[]{"4", "5"}, prefs.drawerColumns() >= 5 ? 1 : 0, i -> prefs.put(Prefs.DRAWER_COLUMNS, i + 4));
        r.segment(card, "Order", null, new String[]{"A–Z", "Most used"}, prefs.drawerSort(), i -> prefs.put(Prefs.DRAWER_SORT, i));
        LinearLayout extras = r.card(content);
        r.toggle(extras, "Suggestions", "Apps you're likely to open now, learned on this phone", prefs.suggestions(),
                on -> prefs.put(Prefs.SUGGESTIONS, on));
        r.toggle(extras, "Categories", "Social, Media, Work… from each app's own category", prefs.categories(),
                on -> prefs.put(Prefs.CATEGORIES, on));
        r.toggle(extras, "Keyboard on open", null, prefs.drawerKeyboard(), on -> prefs.put(Prefs.DRAWER_KEYBOARD, on));
        r.link(extras, "Hidden apps", prefs.hidden().size() + " hidden", () -> open("hidden"));
        r.segment(card, "Background", null, new String[]{"Frosted", "Solid", "Clear"}, prefs.drawerBg(),
                i -> prefs.put(Prefs.DRAWER_BG, i));

        r.section(content, "Groups");
        LinearLayout groups = r.card(content);
        int count = DrawerGroups.load(prefs).size();
        r.toggle(groups, "Keep grouped apps out of A–Z", "They live only in their group, like folders",
                prefs.groupsHideMembers(), on -> prefs.put(Prefs.GROUPS_HIDE, on));
        r.link(groups, "Group by category", "Makes groups from each app's own category; keeps yours", () -> {
            DrawerGroups.autoGroup(prefs, AppRepository.get(this).visibleApps());
            toast("Grouped. Open the drawer to see them.");
            show("drawer", false);
        });
        if (count > 0) {
            r.link(groups, "Remove all groups", count + (count == 1 ? " group" : " groups"), () -> {
                prefs.put(Prefs.DRAWER_GROUPS, "[]");
                show("drawer", false);
            });
        }
        note(content, "Long-press any app in the drawer and choose Add to group. Long-press a group to ungroup it.");

        r.section(content, "Search");
        LinearLayout s = r.card(content);
        r.segment(s, "Web search", null, new String[]{"Google", "DuckDuckGo", "Bing", "Brave"}, prefs.engine(), i -> prefs.put(Prefs.ENGINE, i));
        r.toggle(s, "Calculator", "Type a sum, see the answer", prefs.calculator(), on -> prefs.put(Prefs.CALCULATOR, on));
        r.toggle(s, "Settings results", "Wi-Fi, Bluetooth, Display…", prefs.settingsResults(), on -> prefs.put(Prefs.SETTINGS_RESULTS, on));
    }

    private void hiddenPage() {
        header("Hidden apps", true);
        LinearLayout card = r.card(content);
        r.toggle(card, "Ask for fingerprint or PIN", "Before showing hidden apps in the drawer", prefs.hiddenLock(),
                on -> prefs.put(Prefs.HIDDEN_LOCK, on));
        AppRepository repo = AppRepository.get(this);
        List<AppEntry> hidden = repo.hiddenApps();
        if (hidden.isEmpty()) {
            note(content, "Nothing hidden. Long-press an app in the drawer and choose Hide.");
            return;
        }
        LinearLayout list = r.card(content);
        for (AppEntry e : hidden) {
            r.link(list, e.label, "Tap to unhide", () -> {
                prefs.setHidden(e.key, false);
                show("hidden", false);
            });
        }
    }

    // ---------------------------------------------------------------- Gestures

    private void gestures() {
        header("Gestures", true);
        String[][] g = {{Prefs.G_UP, "Swipe up"}, {Prefs.G_DOWN, "Swipe down"}, {Prefs.G_DOUBLE, "Double tap"}};
        LinearLayout card = r.card(content);
        for (String[] gesture : g) {
            String key = gesture[0];
            AppEntry app = AppRepository.get(this).find(prefs.gestureApp(key));
            String sub = prefs.gesture(key) == Prefs.ACTION_APP && app != null ? "Opens " + app.label : null;
            r.segment(card, gesture[1], sub, ACTIONS, prefs.gesture(key), i -> {
                prefs.put(key, i);
                if (i == Prefs.ACTION_APP) pickApp(key);
            });
        }
        note(content, "Quick panel and Recent apps are Helm's own, in Nothing's style. See Quick panel & recents "
                + "in Settings to open recents by long-pressing Home.");
        r.section(content, "Accessibility");
        LinearLayout acc = r.card(content);
        r.link(acc, "Helm gestures service", HelmAccessibility.enabled() ? "On" : "Off · needed for Lock screen",
                () -> startSafely(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        if (!HelmAccessibility.enabled()) restrictedHint(acc);
        note(content, "Helm's accessibility service only performs Lock screen, Notifications and Quick settings when you "
                + "use a gesture. It subscribes to no events and reads nothing on screen.");
    }

    private void pickApp(String gestureKey) {
        Rows sr = new Rows(this, p);
        LinearLayout col = sr.column();
        LinearLayout list = sr.card(col);
        for (AppEntry e : AppRepository.get(this).visibleApps()) {
            sr.link(list, e.label, null, () -> {
                prefs.put(gestureKey + "_app", e.key);
                Sheet.dismissActive();
                show("gestures", false);
            });
        }
        Sheet.show(frame, p, "Choose app", col);
    }

    // ---------------------------------------------------------------- Backup

    private void backup() {
        header("Backup", true);
        note(content, "Saves your settings and home layout to a file you choose. Photos and widgets from other apps "
                + "aren't included; other apps' widgets need adding again after a restore.");
        r.button(content, "Export", true, () -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json").putExtra(Intent.EXTRA_TITLE, "helm-backup.json");
            startForResult(i, REQ_EXPORT);
        });
        r.button(content, "Import", false, () -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json");
            startForResult(i, REQ_IMPORT);
        });
    }

    private void export(Uri uri) {
        try {
            JSONObject o = new JSONObject();
            o.put("helm", 2);
            o.put("prefs", prefs.export());
            File f = new File(getFilesDir(), "layout.json");
            if (f.exists()) o.put("layout", new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                out.write(o.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            toast("Backup saved");
        } catch (Exception e) {
            toast("Couldn't save the backup");
        }
    }

    private void restore(Uri uri) {
        try {
            String json;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) buf.write(b, 0, n);
                json = new String(buf.toByteArray(), StandardCharsets.UTF_8);
            }
            JSONObject o = new JSONObject(json);
            JSONObject l = o.optJSONObject("layout");
            if (l != null) {
                JSONArray items = l.optJSONArray("items");
                JSONArray kept = new JSONArray();
                if (items != null) for (int i = 0; i < items.length(); i++) {
                    if (items.getJSONObject(i).optInt("type") != Item.WIDGET) kept.put(items.get(i));
                }
                l.put("items", kept);
                java.nio.file.Files.write(new File(getFilesDir(), "layout.json").toPath(), l.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (o.has("prefs")) prefs.restore(o.getJSONObject("prefs"));
            prefs.put("layout_stamp", SystemClock.uptimeMillis());
            toast("Restored");
            restyle();
        } catch (Exception e) {
            toast("That file isn't a Helm backup");
        }
    }

    // ---------------------------------------------------------------- Lock screen

    private void lockPage() {
        header("Lock screen", true);
        LockArt art = new LockArt();
        preview = new View(this) {
            private final RectF box = new RectF();
            private final Path clip = new Path();

            @Override
            protected void onDraw(Canvas c) {
                box.set(0, 0, getWidth(), getHeight());
                clip.reset();
                clip.addRoundRect(box, Ui.dpf(24), Ui.dpf(24), Path.Direction.CW);
                c.save();
                c.clipPath(clip);
                WallpaperStore ws = WallpaperStore.get(getContext());
                int src = prefs.lockSource();
                Bitmap wall = src == 2 ? null : src == 0 && ws.hasOwnBitmap() ? ws.sharp() : lockPreviewArt();
                boolean light = src == 0 && ws.hasOwnBitmap() && ws.wallpaperLight();
                // Drawn at phone size, scaled down, so type sizes match the real thing.
                int pw = getResources().getDisplayMetrics().widthPixels;
                float scale = getWidth() / (float) pw;
                c.scale(scale, scale);
                art.draw(getContext(), c, pw, Math.round(getHeight() / scale), wall, light, p.accent, true);
                c.restore();
            }
        };
        int ph = Math.round(Ui.dp(300) * 1f);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(Math.round(ph / 2.16f), ph);
        pl.gravity = Gravity.CENTER_HORIZONTAL;
        pl.bottomMargin = Ui.dp(14);
        content.addView(preview, pl);
        refreshPreview();

        LinearLayout card = r.card(content);
        r.segment(card, "Clock", "Live: it ticks, unlike a clock in a picture", LockArt.CLOCKS, prefs.lockClock(), i -> {
            prefs.put(Prefs.LOCK_CLOCK, i);
            preview.invalidate();
        });
        r.segment(card, "Position", null, LockArt.POSITIONS, prefs.lockPos(), i -> {
            prefs.put(Prefs.LOCK_POS, i);
            preview.invalidate();
        });
        r.segment(card, "Clock colour", "Wallpaper tints the clock with the colour engine's accent", LockArt.TINTS,
                prefs.lockTint(), i -> {
                    prefs.put(Prefs.LOCK_TINT, i);
                    preview.invalidate();
                });
        r.toggle(card, "Date", null, prefs.lockDate(), on -> {
            prefs.put(Prefs.LOCK_DATE, on);
            preview.invalidate();
        });
        r.segment(card, "Background", "Helm's uses your home wallpaper; Atmosphere is a dark one made for the lock screen",
                new String[]{"Helm's", "Atmosphere", "Black"}, prefs.lockSource(), i -> {
                    prefs.put(Prefs.LOCK_SOURCE, i);
                    lockArt = null;
                    preview.invalidate();
                });
        r.button(content, "Set Helm live wallpaper", true, () -> {
            Intent i = new Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                    .putExtra(android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                            new android.content.ComponentName(this, HelmWallpaper.class));
            startSafely(i);
        });
        note(content, "Android doesn't let any app replace the lock screen itself, so Helm draws its clock as a live "
                + "wallpaper. Pick \"Home and lock screens\" when asked. One UI still shows its own clock and notifications "
                + "on top: in Settings › Lock screen › Edit lock screen, choose the smallest clock, or hide it with Good Lock.");
    }

    private Bitmap lockArt;
    private boolean lockArtBusy;

    /** The same dark Atmosphere the live wallpaper generates, small, for the preview. Made off the UI thread. */
    private Bitmap lockPreviewArt() {
        if (lockArt == null && !lockArtBusy) {
            lockArtBusy = true;
            int w = getResources().getDisplayMetrics().widthPixels / 3, h = getResources().getDisplayMetrics().heightPixels / 3;
            long seed = prefs.wpSeed();
            int mood = prefs.wpMood();
            boolean grain = prefs.wpGrain(), lines = prefs.wpLines();
            new Thread(() -> {
                Bitmap b;
                try {
                    b = Atmosphere.generate(w, h, seed, mood, true, grain, lines);
                } catch (Throwable t) {
                    b = null;
                }
                Bitmap done = b;
                runOnUiThread(() -> {
                    lockArtBusy = false;
                    lockArt = done;
                    if (preview != null && !isDestroyed()) preview.invalidate();
                });
            }, "helm-lock-preview").start();
        }
        return lockArt;
    }

    // ---------------------------------------------------------------- Quick panel & recents

    private void panelPage() {
        header("Quick panel", true);
        note(content, "Swipe down on home opens Helm's quick panel (change it in Gestures). Android keeps the real "
                + "notification shade for itself; the panel's Notifications button opens it.");
        r.section(content, "Tiles");
        LinearLayout tiles = r.card(content);
        List<String> on = Shade.tiles(prefs);
        for (int i = 0; i < Widgets.TOGGLES.length; i++) {
            String t = Widgets.TOGGLES[i];
            if ("wifi".equals(t) || "bluetooth".equals(t)) continue; // Always there, as the two big pills.
            r.toggle(tiles, Widgets.TOGGLE_NAMES[i], null, on.contains(t), checked -> {
                List<String> now = Shade.tiles(prefs);
                now.remove(t);
                if (checked) now.add(t);
                prefs.put(Prefs.PANEL_TILES, String.join(",", now));
            });
        }
        LinearLayout extra = r.card(content);
        r.toggle(extra, "Now playing", "A player card when music is on", prefs.panelMedia(), c -> prefs.put(Prefs.PANEL_MEDIA, c));
        r.link(extra, "Brightness & auto-rotate", Settings.System.canWrite(this) ? "Allowed" : "Allow Modify system settings",
                () -> startSafely(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName()))));
        r.button(content, "Try the panel", false, () -> Overlay.open(this, Overlay.PANEL));

        r.section(content, "Recent apps");
        LinearLayout rec = r.card(content);
        r.link(rec, "Usage access", AwayTime.allowed(this) ? "Allowed" : "Needed to list recent apps",
                () -> startSafely(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        r.link(rec, "Open with long-press Home", "Choose Helm as the digital assistant app",
                () -> startSafely(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)));
        note(content, "One UI only lets its own launcher draw the system recents screen. Helm's Recent apps shows "
                + "what you used lately as Nothing-style cards: tap to open, swipe a card up to remove it. Open it from "
                + "a gesture, the quick panel, or (with Helm as digital assistant app, under Default apps) by "
                + "long-pressing Home.");
        r.button(content, "Try recents", false, () -> Overlay.open(this, Overlay.RECENTS));
    }

    // ---------------------------------------------------------------- Picture-in-picture

    private void pipPage() {
        header("Picture-in-picture", true);
        note(content, "Videos shrink into a floating window when you go home only if the app allows it and you go home "
                + "the right way. With a third-party launcher like Helm, Android's swipe-home animation is run by "
                + "One UI Home, and on gesture navigation it doesn't hand playing videos over to picture-in-picture. "
                + "Pressing a Home button does.");
        LinearLayout card = r.card(content);
        r.link(card, "Navigation bar", "Switch to buttons, then press Home while a video plays",
                () -> startSafely(new Intent("android.settings.NAVIGATION_BAR_SETTINGS")));
        r.link(card, "Picture-in-picture apps", "Make sure the app is allowed",
                () -> startSafely(new Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS")));
        note(content, "Apps that never offer picture-in-picture (most games, many social apps) can't be forced into it "
                + "by any launcher. YouTube needs Premium for it outside some regions.");
    }

    // ---------------------------------------------------------------- About

    private void about() {
        header("About", true);
        LinearLayout card = r.card(content);
        r.link(card, "Default home app", "Choose which launcher Home opens", () -> startSafely(new Intent(Settings.ACTION_HOME_SETTINGS)));
        note(content, "Helm 3.0. A launcher in Nothing OS 5's design language, built for the Galaxy S25: "
                + "the Translucent material, the wallpaper colour engine, Geist, dot-matrix used for character, "
                + "and Nothing's widget shapes.");
        note(content, "Typefaces: Geist and Geist Mono (Vercel) and Instrument Serif, all under the SIL Open Font License 1.1. "
                + "Weather by Open-Meteo. Dot-matrix glyphs and icons are drawn by Helm.");
        note(content, "Unofficial. Not affiliated with or endorsed by Nothing Technology.");
    }

    // ---------------------------------------------------------------- Plumbing

    /**
     * Android greys out notification access and accessibility for apps installed from a file until you
     * allow "restricted settings" in the app's info page.
     */
    private void restrictedHint(LinearLayout card) {
        r.link(card, "Switch greyed out?", "App info › ⋮ menu › Allow restricted settings, then try again",
                () -> startSafely(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
    }

    private boolean isDefaultHome() {
        RoleManager rm = getSystemService(RoleManager.class);
        return rm != null && rm.isRoleHeld(RoleManager.ROLE_HOME);
    }

    private void requestHome() {
        RoleManager rm = getSystemService(RoleManager.class);
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
            roleAskedAt = SystemClock.uptimeMillis();
            startForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_HOME);
        } else {
            startSafely(new Intent(Settings.ACTION_HOME_SETTINGS));
        }
    }

    @SuppressWarnings("deprecation")
    private void startForResult(Intent i, int code) {
        try {
            startActivityForResult(i, code);
        } catch (RuntimeException e) {
            toast("Not available on this phone");
        }
    }

    private void startSafely(Intent i) {
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            toast("Not available on this phone");
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQ_HOME) {
            // The system silently refuses after repeated "no"s; an instant cancel means no dialog was shown.
            if (result != RESULT_OK && SystemClock.uptimeMillis() - roleAskedAt < 400) {
                startSafely(new Intent(Settings.ACTION_HOME_SETTINGS));
            }
            return;
        }
        if (request == REQ_PHOTO && result != RESULT_OK && "wallpaper".equals(stack.peek())) {
            show("wallpaper", false); // Picker cancelled: the Source row goes back to what's really set.
            return;
        }
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        switch (request) {
            case REQ_PHOTO:
                toast("Preparing your wallpaper…");
                WallpaperStore.get(this).importPhoto(uri, ok -> {
                    if (!ok) {
                        toast("Couldn't read that photo");
                        return;
                    }
                    prefs.put(Prefs.WP_PHOTO_VERSION, prefs.wpPhotoVersion() + 1);
                    prefs.put(Prefs.WP_SOURCE, Prefs.WP_PHOTO);
                    if ("wallpaper".equals(stack.peek())) show("wallpaper", false);
                });
                break;
            case REQ_EXPORT: export(uri); break;
            case REQ_IMPORT: restore(uri); break;
            default: break;
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
