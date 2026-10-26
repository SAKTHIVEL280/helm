package com.helm.launcher;

import android.accessibilityservice.AccessibilityService;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityOptions;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Process;
import android.os.UserManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.provider.Telephony;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Home. */
public final class HelmActivity extends Activity implements AppRepository.Listener, HomeRoot.Callbacks,
        DrawerView.Host, DragController.Host, FolderIconView.Host, FolderPanel.Host, ResizeFrame.Host,
        WidgetPicker.Host, Workspace.Listener, SharedPreferences.OnSharedPreferenceChangeListener, Shade.Host {

    private static final int REQ_BIND = 11, REQ_CONFIG = 12, REQ_IMAGE = 13, REQ_PERMISSION = 14, REQ_CONTACT = 200;

    private static final String[] STARTER_APPS = {
            "com.sec.android.gallery3d", "com.google.android.apps.photos",
            "com.samsung.android.calendar", "com.google.android.calendar",
            "com.google.android.gm", "com.android.vending",
            "com.google.android.apps.maps", "com.google.android.youtube",
            "com.spotify.music", "com.whatsapp", "com.android.settings",
    };
    private static final String[] STARTER_DOCK = {
            "com.samsung.android.dialer", "com.samsung.android.messaging",
            "com.google.android.apps.messaging", "com.sec.android.app.sbrowser",
            "com.android.chrome", "com.sec.android.app.camera",
    };

    private Prefs prefs;
    private Palette palette;
    private AppRepository repo;
    private Layout layout;
    private HelmWidgetHost widgetHost;
    private AppWidgetManager awm;

    private HomeRoot root;
    private LinearLayout home;
    private Workspace workspace;
    private PageIndicator indicator;
    private CellLayout dock;
    private SearchPill search;
    private DrawerView drawer;
    private TextView removePill;
    private LinearLayout editBar;
    private FolderPanel folderPanel;
    private ResizeFrame resizeFrame;
    private PopupWindow popup;
    private Shade shade;
    private int insetTop, insetBottom;

    private int cols, rows, iconPx, dockIconPx, bigIconPx;
    private float drawerProgress;
    private ValueAnimator drawerAnim;
    private boolean editing, arranging, stale, torchOn;
    private final Set<Item> selected = new LinkedHashSet<>();
    private final Map<Long, View> views = new HashMap<>();
    private final Map<Long, View> widgetCache = new HashMap<>();
    private final Map<Integer, Runnable> permissionCallbacks = new HashMap<>();
    private int nextPermission;
    private Consumer<Uri> pendingImage, pendingContact;
    private AppWidgetProviderInfo pendingInfo;
    private int pendingId = -1;
    private OnBackInvokedCallback backCallback;
    private CameraManager.TorchCallback torchCallback;
    private android.app.WallpaperManager.OnColorsChangedListener wallColors;

    // ------------------------------------------------------------------ Lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        prefs = Prefs.get(this);
        migrate();
        repo = AppRepository.get(this);
        Rect bounds = getWindowManager().getMaximumWindowMetrics().getBounds();
        WallpaperStore.get(this).setScreen(bounds.width(), bounds.height());
        palette = Palette.resolve(this);
        cols = prefs.columns();
        rows = prefs.rows();
        float cellW = (bounds.width() - Ui.dp(24)) / (float) cols;
        float scale = prefs.iconScale() / 100f;
        iconPx = Math.round(Math.min(cellW * 0.86f, cellW * (prefs.labelsHome() ? 0.62f : 0.68f) * scale));
        dockIconPx = Math.round(Math.min(cellW * 0.86f, cellW * 0.74f * scale));
        // Nothing launcher's big icon: one app across a 2×2 space.
        bigIconPx = Math.round(Math.min(cellW * 1.7f, cellW * 1.42f * scale));
        repo.configure(new IconFactory.Spec(palette, prefs.iconStyle(), prefs.fallback(), prefs.iconShape(), prefs.iconPack()),
                Math.max(iconPx, dockIconPx));

        Window w = getWindow();
        Ui.edgeToEdge(w);
        if (prefs.wpSource() == Prefs.WP_SYSTEM) {
            w.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        } else {
            w.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
            w.setBackgroundDrawable(new ColorDrawable(palette.dark ? 0xFF000000 : 0xFFE6E6E3));
        }

        widgetHost = new HelmWidgetHost(this);
        awm = AppWidgetManager.getInstance(this);
        layout = Layout.load(this);

        buildViews();
        setContentView(root);
        applyBars();

        WallpaperStore.get(this).ensure(palette.dark, this::onWallpaperReady);
        repo.addListener(this);
        if (repo.loaded()) onAppsChanged();
        repo.reload();
        if (layout != null) {
            layout.normalize(cols, rows, cols);
            layout.trimPages();
            bind();
        }
        prefs.sp.registerOnSharedPreferenceChangeListener(this);
        wallColors = (colors, which) -> {
            if ((which & android.app.WallpaperManager.FLAG_SYSTEM) != 0 && prefs.wpSource() == Prefs.WP_SYSTEM) {
                WallpaperStore.get(this).ensure(palette.dark, this::onWallpaperReady);
            }
        };
        android.app.WallpaperManager.getInstance(this).addOnColorsChangedListener(wallColors, new android.os.Handler(getMainLooper()));
        setupBack();
    }

    /** One-time upgrades of saved settings. Runs before the preference listener is registered. */
    private void migrate() {
        if (prefs.sp.getBoolean("migrated_v3", false)) return;
        // Helm 2 sent swipe-down to the system shade; Helm 3's swipe-down is Nothing's quick panel.
        int down = prefs.gesture(Prefs.G_DOWN);
        android.content.SharedPreferences.Editor ed = prefs.sp.edit().putBoolean("migrated_v3", true);
        if (down == Prefs.ACTION_NOTIFICATIONS || down == Prefs.ACTION_QUICK_SETTINGS) ed.putInt(Prefs.G_DOWN, Prefs.ACTION_PANEL);
        ed.apply();
    }

    private void applyBars() {
        WindowInsetsController bars = getWindow().getInsetsController();
        if (bars == null) return;
        int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        bars.setSystemBarsAppearance(palette.wallLight ? light : 0, light);
        if (prefs.hideStatus()) {
            bars.hide(WindowInsets.Type.statusBars());
            bars.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        } else {
            bars.show(WindowInsets.Type.statusBars());
        }
    }

    private void onWallpaperReady() {
        if (isFinishing() || isDestroyed()) return;
        Palette now = Palette.resolve(this);
        // The colour engine found a different accent or brightness: rebuild once with the new tokens.
        if (now.accent != palette.accent || now.wallLight != palette.wallLight) {
            recreate();
            return;
        }
        root.invalidate();
        refreshFrostEverywhere();
    }

    private void refreshFrostEverywhere() {
        for (int i = 0; i < workspace.count(); i++) Workspace.refreshFrost(workspace.page(i));
        Workspace.refreshFrost(dock);
        search.invalidate();
        drawer.invalidate();
    }

    @Override
    protected void onStart() {
        super.onStart();
        try {
            widgetHost.startListening();
        } catch (RuntimeException ignored) {
        }
        CameraManager cm = getSystemService(CameraManager.class);
        if (cm != null) {
            torchCallback = new CameraManager.TorchCallback() {
                @Override
                public void onTorchModeChanged(String id, boolean enabled) { torchOn = enabled; }
            };
            cm.registerTorchCallback(torchCallback, null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (stale) {
            stale = false;
            recreate();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            widgetHost.stopListening();
        } catch (RuntimeException ignored) {
        }
        CameraManager cm = getSystemService(CameraManager.class);
        if (cm != null && torchCallback != null) cm.unregisterTorchCallback(torchCallback);
        dismissPopup();
        if (folderPanel != null) folderPanel.closeNow();
        if (drawerProgress > 0f) closeDrawer(false);
        if (shade != null) shade.closeNow();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Sheet.forget(this);
        repo.removeListener(this);
        prefs.sp.unregisterOnSharedPreferenceChangeListener(this);
        if (wallColors != null) android.app.WallpaperManager.getInstance(this).removeOnColorsChangedListener(wallColors);
        if (drawerAnim != null) drawerAnim.cancel();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!Intent.ACTION_MAIN.equals(intent.getAction())) return;
        boolean had = closeEverything();
        if (!had && hasWindowFocus() && workspace.current() != 0) workspace.snapTo(0, true);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_MODERATE || level == TRIM_MEMORY_RUNNING_CRITICAL) repo.trim();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if (key == null) {
            stale = true;
            return;
        }
        if (key.equals(Prefs.HIDDEN) || key.equals(Prefs.DRAWER_GROUPS)) {
            drawer.setApps(repo.apps());
            return;
        }
        if (key.startsWith(Shade.PREFIX) || key.equals(Prefs.PANEL_TILES) || key.equals(Prefs.PANEL_MEDIA)
                || key.startsWith("lock_")) return;
        if (key.startsWith("weather") || key.startsWith(Prefs.LABEL_PREFIX) || key.equals(Prefs.WP_ACCENT_CACHE)
                || key.equals(Prefs.WP_LIGHT_CACHE)) return;
        stale = true;
    }

    // ------------------------------------------------------------------ Views

    private void buildViews() {
        root = new HomeRoot(this, this);
        root.drag.setHost(this);
        root.setDim(prefs.wpDim());

        home = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL);
        home.setClipChildren(false);
        home.setClipToPadding(false);
        root.addView(home, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        workspace = new Workspace(this);
        workspace.setListener(this);
        indicator = new PageIndicator(this, palette);
        dock = new CellLayout(this, cols, 1, Item.DOCK, palette);
        dock.setPadding(Ui.dp(12), 0, Ui.dp(12), 0);
        if (prefs.dockBg() == Prefs.DOCK_BG_GLASS) dock.setBackground(new DockBackground(dock, palette));
        search = new SearchPill(this, palette, "Search");
        search.setOnClickListener(v -> openDrawer(true));

        int pos = prefs.searchPos();
        if (pos == Prefs.SEARCH_TOP) addSearch(true);
        home.addView(workspace, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        home.addView(indicator, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (prefs.dock()) {
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    dockIconPx + Ui.dp(prefs.labelsDock() ? 40 : 22));
            home.addView(dock, dl);
        }
        if (pos == Prefs.SEARCH_BOTTOM) addSearch(false);

        drawer = new DrawerView(this, palette, this);
        drawer.setVisibility(View.GONE);
        root.addView(drawer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        removePill = Ui.mono(this, "Remove", palette.onGlass, 12);
        removePill.setGravity(Gravity.CENTER);
        removePill.setPadding(Ui.dp(26), 0, Ui.dp(26), 0);
        removePill.setBackground(Ui.pill(palette.glass));
        removePill.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(44), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        root.addView(removePill, rl);

        editBar = new LinearLayout(this);
        editBar.setOrientation(LinearLayout.HORIZONTAL);
        editBar.setGravity(Gravity.CENTER);
        editBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams el = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(52), Gravity.BOTTOM);
        el.leftMargin = el.rightMargin = Ui.dp(12);
        root.addView(editBar, el);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets sys = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsets.Type.ime());
            int top = prefs.hideStatus() ? insets.getInsets(WindowInsets.Type.displayCutout()).top : sys.top;
            home.setPadding(sys.left, top + Ui.dp(8), sys.right, sys.bottom + Ui.dp(10));
            drawer.applyInsets(sys.top, Math.max(sys.bottom, ime.bottom));
            insetTop = sys.top;
            insetBottom = sys.bottom;
            if (shade != null) shade.setInsets(insetTop, insetBottom);
            FrameLayout.LayoutParams r = (FrameLayout.LayoutParams) removePill.getLayoutParams();
            r.topMargin = sys.top + Ui.dp(8);
            removePill.setLayoutParams(r);
            FrameLayout.LayoutParams e = (FrameLayout.LayoutParams) editBar.getLayoutParams();
            e.bottomMargin = sys.bottom + Ui.dp(16);
            editBar.setLayoutParams(e);
            // Not consumed: open sheets read the keyboard inset to stay above it.
            return insets;
        });
    }

    /** NOS-style dock shelf: a frosted pill behind the dock icons. */
    private static final class DockBackground extends Drawable {
        private final View owner;
        private final Palette p;
        private final Material.Frost frost = new Material.Frost();
        private final RectF r = new RectF();

        DockBackground(View owner, Palette p) {
            this.owner = owner;
            this.p = p;
        }

        @Override
        public void draw(android.graphics.Canvas c) {
            r.set(getBounds());
            r.inset(Ui.dpf(8), Ui.dpf(2));
            Material.roundRect(c, owner, frost, r, Math.min(r.height() / 2f, p.radius * 1.4f),
                    Material.resolve(Material.AUTO, p), p);
        }

        @Override public void setAlpha(int alpha) {}

        @Override public void setColorFilter(android.graphics.ColorFilter cf) {}

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private void addSearch(boolean top) {
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(54));
        sl.leftMargin = sl.rightMargin = Ui.dp(22);
        if (top) sl.bottomMargin = Ui.dp(8);
        else sl.topMargin = Ui.dp(6);
        home.addView(search, sl);
    }

    private CellLayout newPage(int index) {
        CellLayout page = new CellLayout(this, cols, rows, index, palette);
        page.setPadding(Ui.dp(12), 0, Ui.dp(12), 0);
        page.setEditing(editing);
        return page;
    }

    /** Rebuilds every page from the layout, reusing widget views so they don't reload. */
    private void bind() {
        if (layout == null) return;
        while (workspace.getChildCount() < layout.pages) workspace.addView(newPage(workspace.getChildCount()));
        while (workspace.getChildCount() > layout.pages) workspace.removeViewAt(workspace.getChildCount() - 1);
        for (int i = 0; i < workspace.count(); i++) workspace.page(i).removeAllViews();
        dock.removeAllViews();
        views.clear();
        Set<Long> live = new HashSet<>();
        for (Item item : new ArrayList<>(layout.items)) {
            if (item.inDock() && !prefs.dock()) continue;
            CellLayout target = item.inDock() ? dock : workspace.page(item.page);
            if (target == null) continue;
            View v = makeView(item);
            if (v == null) continue;
            ViewParent parent = v.getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(v);
            v.setAlpha(1f);
            v.setTranslationX(0);
            v.setTranslationY(0);
            target.addView(v, new CellLayout.LP(item.x, item.y, item.w, item.h));
            views.put(item.id, v);
            live.add(item.id);
        }
        widgetCache.keySet().retainAll(live);
        indicator.setCount(layout.pages);
        indicator.setVisibility(prefs.pageDots() && layout.pages > 1 ? View.VISIBLE : View.INVISIBLE);
        for (int i = 0; i < workspace.count(); i++) workspace.page(i).setEditing(editing);
        if (workspace.current() >= layout.pages) workspace.snapTo(layout.pages - 1, false);
        drawSelection();
    }

    private View makeView(Item item) {
        switch (item.type) {
            case Item.APP: {
                AppEntry e = repo.find(item.app);
                if (e == null) return null;
                AppIconView v = new AppIconView(this);
                v.item = item;
                boolean inDock = item.inDock();
                v.bind(e, inDock ? dockIconPx : item.w >= 2 ? bigIconPx : iconPx, inDock ? prefs.labelsDock() : prefs.labelsHome(),
                        palette.onWall, prefs.labelFont() == 1 ? Ui.MONO : Ui.SANS, palette);
                if (!inDock && item.w >= 2) v.renderSharp();
                v.setOnClickListener(x -> onItemTap(item, x));
                v.setOnLongClickListener(x -> onItemLongPress(item, x));
                return v;
            }
            case Item.FOLDER: {
                FolderIconView f = new FolderIconView(this, this);
                f.bind(item, entries(item.apps), item.inDock() ? dockIconPx : iconPx,
                        item.inDock() ? prefs.labelsDock() : prefs.labelsHome(), palette.onWall, palette);
                f.setOnLongClickListener(x -> onItemLongPress(item, x));
                return f;
            }
            case Item.HELM: {
                View cached = widgetCache.get(item.id);
                HelmWidget w = cached instanceof HelmWidget ? (HelmWidget) cached : HelmWidget.create(this, item, palette, this);
                widgetCache.put(item.id, w);
                w.setOnClickListener(x -> onItemTap(item, x));
                w.setOnLongClickListener(x -> onItemLongPress(item, x));
                return w;
            }
            case Item.WIDGET: {
                View cached = widgetCache.get(item.id);
                if (cached != null) return cached;
                AppWidgetProviderInfo info = awm.getAppWidgetInfo(item.widgetId);
                if (info == null) return null;
                HelmWidgetHost.HostView hv = (HelmWidgetHost.HostView) widgetHost.createView(this, item.widgetId, info);
                hv.item = item;
                hv.setRadius(palette.radius);
                hv.setOnLongClickListener(x -> onItemLongPress(item, x));
                widgetCache.put(item.id, hv);
                return hv;
            }
            default:
                return null;
        }
    }

    private List<AppEntry> entries(List<String> keys) {
        List<AppEntry> out = new ArrayList<>();
        for (String k : keys) {
            AppEntry e = repo.find(k);
            if (e != null) out.add(e);
        }
        return out;
    }

    // ------------------------------------------------------------------ Apps & first run

    @Override
    public void onAppsChanged() {
        if (layout == null) {
            seedLayout();
            layout.normalize(cols, rows, cols);
        } else {
            pruneMissing();
        }
        bind();
        drawer.setApps(repo.apps());
        repo.prewarm();
    }

    /** Drops home items for apps that were uninstalled (not ones in a paused work profile). */
    private void pruneMissing() {
        UserManager um = getSystemService(UserManager.class);
        String main = "#" + um.getSerialNumberForUser(Process.myUserHandle());
        boolean changed = false;
        for (Item i : new ArrayList<>(layout.items)) {
            if (i.type == Item.APP && repo.find(i.app) == null && i.app.endsWith(main)) {
                layout.items.remove(i);
                changed = true;
            } else if (i.type == Item.FOLDER) {
                if (i.apps.removeIf(k -> repo.find(k) == null && k.endsWith(main))) changed = true;
                if (i.apps.isEmpty()) {
                    layout.items.remove(i);
                    changed = true;
                } else if (i.apps.size() == 1) {
                    i.type = Item.APP;
                    i.app = i.apps.get(0);
                    i.apps.clear();
                    i.w = i.h = 1;
                    changed = true;
                }
            }
        }
        if (changed) layout.save(this);
    }

    /** First run: a Nothing OS 5 style page of widgets, a row of familiar apps, the usual dock. */
    private void seedLayout() {
        layout = new Layout();
        List<String> dockKeys = new ArrayList<>();
        for (String k : prefs.list("dock")) if (repo.find(k) != null) dockKeys.add(k); // Helm 1 dock
        if (dockKeys.isEmpty()) {
            addPackage(dockKeys, defaultPackage(new Intent(Intent.ACTION_DIAL)));
            addPackage(dockKeys, Telephony.Sms.getDefaultSmsPackage(this));
            addPackage(dockKeys, defaultPackage(new Intent(Intent.ACTION_VIEW, Uri.parse("https://"))));
            addPackage(dockKeys, defaultPackage(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)));
            for (String pkg : STARTER_DOCK) if (dockKeys.size() < cols) addPackage(dockKeys, pkg);
        }
        for (int i = 0; i < Math.min(cols, dockKeys.size()); i++) {
            Item a = Item.app(dockKeys.get(i));
            a.page = Item.DOCK;
            a.x = i;
            layout.add(a);
        }
        place(Item.helm(HelmWidget.ANALOG, 2, 2), 0, 0);
        place(Item.helm(HelmWidget.WEATHER, 2, 1), 2, 0);
        Item torch = Item.helm(HelmWidget.TOGGLE, 1, 1);
        place(torch, 2, 1);
        Item sound = Item.helm(HelmWidget.TOGGLE, 1, 1);
        try {
            sound.cfg.put("toggle", "sound");
        } catch (org.json.JSONException ignored) {
        }
        place(sound, 3, 1);
        place(Item.helm(HelmWidget.BATTERY, 2, 1), 0, 2);
        place(Item.helm(HelmWidget.AWAY, 1, 1), 2, 2);
        Item dnd = Item.helm(HelmWidget.TOGGLE, 1, 1);
        try {
            dnd.cfg.put("toggle", "dnd");
        } catch (org.json.JSONException ignored) {
        }
        place(dnd, 3, 2);

        List<String> homeKeys = new ArrayList<>();
        for (String k : prefs.list("home")) if (repo.find(k) != null) homeKeys.add(k); // Helm 1 home
        if (homeKeys.isEmpty()) {
            for (String pkg : STARTER_APPS) {
                AppEntry e = repo.firstForPackage(pkg);
                if (e != null && !dockKeys.contains(e.key) && !homeKeys.contains(e.key)) homeKeys.add(e.key);
            }
        }
        // Up to two rows of apps along the bottom of the page, nearest the thumb.
        int row = rows - 1;
        for (int i = 0; i < Math.min(cols * 2, homeKeys.size()); i++) {
            place(Item.app(homeKeys.get(i)), i % cols, i < cols ? row : row - 1);
        }
        layout.save(this);
    }

    private void place(Item i, int x, int y) {
        if (!layout.fits(0, x, y, i.w, i.h, cols, rows, null)) {
            int[] s = layout.findAnywhere(0, i.w, i.h, cols, rows);
            i.page = s[0];
            i.x = s[1];
            i.y = s[2];
        } else {
            i.page = 0;
            i.x = x;
            i.y = y;
        }
        layout.add(i);
    }

    private void addPackage(List<String> keys, String pkg) {
        AppEntry e = repo.firstForPackage(pkg);
        if (e != null && !keys.contains(e.key)) keys.add(e.key);
    }

    private String defaultPackage(Intent i) {
        ResolveInfo ri = getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
        if (ri == null || ri.activityInfo == null) return null;
        String pkg = ri.activityInfo.packageName;
        return "android".equals(pkg) ? null : pkg;
    }

    // ------------------------------------------------------------------ Taps, long-presses, menus

    private void onItemTap(Item item, View v) {
        if (arranging) {
            toggleSelect(item);
            return;
        }
        if (editing) {
            if (item.isWidget()) widgetMenu(item, v, true);
            return;
        }
        if (item.type == Item.APP) launch(repo.find(item.app), v);
        else if (item.type == Item.HELM && v instanceof HelmWidget) ((HelmWidget) v).onTap();
    }

    private boolean onItemLongPress(Item item, View v) {
        if (root.drag.busy()) return false;
        Ui.longPress(v);
        if (arranging && selected.contains(item) && selected.size() > 1) {
            root.drag.arm(DragController.SRC_HOME, new ArrayList<>(selected), item, v, null);
            return true;
        }
        root.drag.arm(DragController.SRC_HOME, Collections.singletonList(item), item, v, null);
        if (editing || arranging) return true;
        switch (item.type) {
            case Item.APP: appMenu(repo.find(item.app), v, item); break;
            case Item.FOLDER: folderMenu(item, v); break;
            default: widgetMenu(item, v, false); break;
        }
        return true;
    }

    private void appMenu(AppEntry e, View v, Item homeItem) {
        if (e == null) return;
        List<AppMenu.Item> items = shortcutItems(e, v);
        if (homeItem == null) items.add(action("Add to home", () -> addAppToHome(e)));
        if (homeItem == null) items.add(action(DrawerGroups.groupOf(prefs, e.key) == null ? "Add to group" : "Move to group",
                () -> pickGroup(e)));
        items.add(action("Rename", () -> renameApp(e)));
        if (homeItem == null) {
            boolean hidden = prefs.hidden().contains(e.key);
            items.add(action(hidden ? "Unhide" : "Hide", () -> {
                prefs.setHidden(e.key, !hidden);
                toast(hidden ? e.label + " is back in the drawer" : e.label + " is hidden. Find it under Hidden.");
            }));
        }
        items.add(action("App info", () -> appInfo(e, v)));
        if (homeItem != null && !homeItem.inDock()) {
            boolean big = homeItem.w >= 2;
            items.add(action(big ? "Small icon" : "Big icon", () -> {
                if (big) {
                    homeItem.w = homeItem.h = 1;
                } else if (layout.fits(homeItem.page, homeItem.x, homeItem.y, 2, 2, cols, rows, Collections.singleton(homeItem))) {
                    homeItem.w = homeItem.h = 2;
                } else {
                    toast("Needs a free 2×2 space here");
                    return;
                }
                commit();
            }));
        }
        if (homeItem != null) items.add(action("Remove", () -> deleteAndCommit(homeItem)));
        if (!e.system) items.add(new AppMenu.Item(AppMenu.DANGER, "Uninstall", null, () -> uninstall(e)));
        showMenu(v, e.label, items);
    }

    private void folderMenu(Item folder, View v) {
        List<AppMenu.Item> items = new ArrayList<>();
        items.add(action("Open", () -> openFolder((FolderIconView) v)));
        if (!folder.inDock()) {
            boolean big = folder.w >= 2;
            items.add(action(big ? "Small folder" : "Big folder", () -> {
                if (big) {
                    folder.w = folder.h = 1;
                } else if (layout.fits(folder.page, folder.x, folder.y, 2, 2, cols, rows, Collections.singleton(folder))) {
                    folder.w = folder.h = 2;
                } else {
                    toast("Needs a free 2×2 space here");
                    return;
                }
                commit();
            }));
        }
        items.add(action("Remove", () -> deleteAndCommit(folder)));
        showMenu(v, folder.title, items);
    }

    private void widgetMenu(Item item, View v, boolean fromEdit) {
        List<AppMenu.Item> items = new ArrayList<>();
        if (item.type == Item.HELM && v instanceof HelmWidget) items.add(action("Options", ((HelmWidget) v)::openOptions));
        if (!item.inDock()) items.add(action("Resize", () -> startResize(item)));
        if (item.type == Item.WIDGET) {
            AppWidgetProviderInfo info = awm.getAppWidgetInfo(item.widgetId);
            if (info != null && info.configure != null
                    && (info.widgetFeatures & AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE) != 0) {
                items.add(action("Reconfigure", () -> {
                    try {
                        widgetHost.startAppWidgetConfigureActivityForResult(this, item.widgetId, 0, 99, null);
                    } catch (RuntimeException ignored) {
                    }
                }));
            }
        }
        items.add(new AppMenu.Item(AppMenu.DANGER, "Remove", null, () -> deleteAndCommit(item)));
        String title = item.type == Item.HELM ? HelmWidget.title(item.kind) : "Widget";
        showMenu(v, title, items);
    }

    private List<AppMenu.Item> shortcutItems(AppEntry e, View v) {
        List<AppMenu.Item> items = new ArrayList<>();
        for (ShortcutInfo s : shortcuts(e)) {
            CharSequence label = s.getShortLabel() != null ? s.getShortLabel() : s.getLongLabel();
            if (label == null) continue;
            items.add(new AppMenu.Item(AppMenu.SHORTCUT, label, shortcutIcon(s), () -> startShortcut(s, v)));
        }
        return items;
    }

    private static AppMenu.Item action(String label, Runnable r) {
        return new AppMenu.Item(AppMenu.ACTION, label, null, r);
    }

    private void showMenu(View anchor, String title, List<AppMenu.Item> items) {
        dismissPopup();
        popup = AppMenu.show(anchor, palette, title, items);
        popup.setOnDismissListener(() -> popup = null);
    }

    private void dismissPopup() {
        if (popup != null) {
            PopupWindow p = popup;
            popup = null;
            p.dismiss();
        }
    }

    private List<ShortcutInfo> shortcuts(AppEntry e) {
        LauncherApps la = repo.launcherApps;
        try {
            if (!la.hasShortcutHostPermission()) return Collections.emptyList();
            LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery();
            q.setPackage(e.packageName());
            q.setActivity(e.component);
            q.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST);
            List<ShortcutInfo> found = la.getShortcuts(q, e.user);
            if (found == null) return Collections.emptyList();
            List<ShortcutInfo> sorted = new ArrayList<>(found);
            sorted.sort((a, b) -> a.isDeclaredInManifest() != b.isDeclaredInManifest()
                    ? (a.isDeclaredInManifest() ? -1 : 1) : Integer.compare(a.getRank(), b.getRank()));
            return sorted.size() > 4 ? sorted.subList(0, 4) : sorted;
        } catch (RuntimeException ex) {
            return Collections.emptyList();
        }
    }

    private Bitmap shortcutIcon(ShortcutInfo s) {
        try {
            Drawable d = repo.launcherApps.getShortcutIconDrawable(s, getResources().getDisplayMetrics().densityDpi);
            return IconFactory.small(d, Ui.dp(24), palette, prefs.iconStyle() == Prefs.ICONS_MONO);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private void startShortcut(ShortcutInfo s, View from) {
        Rect bounds = new Rect();
        from.getGlobalVisibleRect(bounds);
        try {
            repo.launcherApps.startShortcut(s, bounds, null);
        } catch (RuntimeException ex) {
            toast("Shortcut unavailable");
        }
    }

    private void appInfo(AppEntry e, View from) {
        Rect bounds = new Rect();
        from.getGlobalVisibleRect(bounds);
        try {
            repo.launcherApps.startAppDetailsActivity(e.component, e.user, bounds, null);
        } catch (RuntimeException ignored) {
        }
    }

    private void uninstall(AppEntry e) {
        start(new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", e.packageName(), null))
                .putExtra(Intent.EXTRA_USER, e.user), null);
    }

    private void renameApp(AppEntry e) {
        Rows r = new Rows(this, palette);
        LinearLayout col = r.column();
        EditText field = new EditText(this);
        field.setText(e.label);
        field.setSelectAllOnFocus(true);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        field.setTextColor(palette.onSolid);
        field.setTypeface(Ui.SANS);
        field.setTextSize(18);
        field.setBackground(Ui.pill(palette.solid));
        field.setPadding(Ui.dp(20), Ui.dp(14), Ui.dp(20), Ui.dp(14));
        col.addView(field);
        r.button(col, "Save", true, () -> {
            prefs.setLabel(e.key, field.getText().toString());
            Sheet.dismissIn(this);
            repo.reload();
        });
        r.button(col, "Use the app's own name", false, () -> {
            prefs.setLabel(e.key, null);
            Sheet.dismissIn(this);
            repo.reload();
        });
        Sheet.show(root, palette, "Rename", col);
        field.requestFocus();
    }

    /** Drawer groups: pick one, make a new one, or take the app out. */
    private void pickGroup(AppEntry e) {
        Rows r = new Rows(this, palette);
        LinearLayout col = r.column();
        String current = DrawerGroups.groupOf(prefs, e.key);
        List<DrawerGroups.Group> groups = DrawerGroups.load(prefs);
        if (!groups.isEmpty()) {
            LinearLayout card = r.card(col);
            for (DrawerGroups.Group g : groups) {
                r.link(card, g.name, g.name.equals(current) ? "In this group" : g.apps.size() + " apps", () -> {
                    DrawerGroups.addTo(prefs, g.name, e.key);
                    Sheet.dismissIn(this);
                });
            }
        }
        EditText field = new EditText(this);
        field.setHint("New group name");
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        field.setTextColor(palette.onSolid);
        field.setHintTextColor(palette.onSolid3);
        field.setTypeface(Ui.SANS);
        field.setTextSize(17);
        field.setBackground(Ui.pill(palette.solid));
        field.setPadding(Ui.dp(20), Ui.dp(14), Ui.dp(20), Ui.dp(14));
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fl.topMargin = Ui.dp(12);
        col.addView(field, fl);
        r.button(col, "Create group", true, () -> {
            String name = field.getText().toString().trim();
            if (name.isEmpty()) {
                Ui.reject(field);
                return;
            }
            DrawerGroups.addTo(prefs, name, e.key);
            Sheet.dismissIn(this);
        });
        if (current != null) {
            r.button(col, "Remove from " + current, false, () -> {
                DrawerGroups.removeFrom(prefs, e.key);
                Sheet.dismissIn(this);
            });
        }
        Sheet.show(root, palette, e.label, col);
    }

    @Override
    public void openGroup(FolderIconView v) {
        if (folderPanel != null) return;
        RectF disc = v.discBounds();
        float[] a = Ui.toRoot(v, disc.left, disc.top), b = Ui.toRoot(v, disc.right, disc.bottom);
        drawer.hideKeyboard();
        int px = Math.round(Math.max(iconPx, Ui.dp(52)));
        folderPanel = new FolderPanel(this, palette, v.item, entries(v.item.apps), px, new RectF(a[0], a[1], b[0], b[1]), this);
        root.addView(folderPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @Override
    public boolean groupLongPress(FolderIconView v) {
        Ui.longPress(v);
        String name = v.item.title;
        List<AppMenu.Item> items = new ArrayList<>();
        items.add(action("Open", () -> openGroup(v)));
        items.add(action("Ungroup", () -> {
            DrawerGroups.delete(prefs, name);
            toast(name + " ungrouped; its apps are back in A–Z");
        }));
        showMenu(v, name, items);
        return true;
    }

    private void addAppToHome(AppEntry e) {
        int[] spot = layout.findAnywhere(workspace.current(), 1, 1, cols, rows);
        Item i = Item.app(e.key);
        i.page = spot[0];
        i.x = spot[1];
        i.y = spot[2];
        layout.add(i);
        commit();
        toast(e.label + " added to page " + (spot[0] + 1));
    }

    private void deleteAndCommit(Item item) {
        deleteItems(Collections.singletonList(item));
        commit();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ------------------------------------------------------------------ Launching

    @Override
    public void launch(AppEntry e, View from) {
        if (e == null) return;
        Rect bounds = new Rect();
        from.getGlobalVisibleRect(bounds);
        Bundle opts = Ui.motionOff() ? null
                : ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.getWidth(), from.getHeight()).toBundle();
        try {
            repo.launcherApps.startMainActivity(e.component, e.user, bounds, opts);
            repo.recordLaunch(e.key);
        } catch (RuntimeException ex) {
            toast("Couldn't open " + e.label);
        }
    }

    @Override
    public void start(Intent i, View from) {
        try {
            Bundle opts = from == null || Ui.motionOff() ? null
                    : ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.getWidth(), from.getHeight()).toBundle();
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opts);
        } catch (RuntimeException ex) {
            toast("Nothing on this phone can open that");
        }
    }

    @Override
    public void openIntent(Intent i) { start(i, null); }

    @Override
    public void webSearch(String query) {
        String[] engines = {"https://www.google.com/search?q=", "https://duckduckgo.com/?q=",
                "https://www.bing.com/search?q=", "https://search.brave.com/search?q="};
        start(new Intent(Intent.ACTION_VIEW, Uri.parse(engines[Ui.clamp(prefs.engine(), 0, 3)] + Uri.encode(query))), null);
    }

    @Override
    public void copy(String text) {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Result", text));
        toast("Copied " + text);
    }

    private void expandNotifications() {
        if (HelmAccessibility.act(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)) return;
        statusBar("expandNotificationsPanel");
    }

    private void expandQuickSettings() {
        if (HelmAccessibility.act(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)) return;
        statusBar("expandSettingsPanel");
    }

    private void statusBar(String method) {
        try {
            Object sb = getSystemService("statusbar");
            Class.forName("android.app.StatusBarManager").getMethod(method).invoke(sb);
        } catch (Throwable ignored) {
            // Not exposed on this build; the gesture does nothing.
        }
    }

    @Override
    public void lockScreen() {
        if (HelmAccessibility.act(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) return;
        Rows r = new Rows(this, palette);
        LinearLayout col = r.column();
        TextView t = Ui.text(this, "Locking the screen from home needs Helm's accessibility service. "
                + "It only performs Lock, Notifications and Quick settings; it reads nothing on screen.", palette.onSolid2, 15, Ui.SANS);
        t.setLineSpacing(Ui.dpf(3), 1f);
        col.addView(t);
        r.button(col, "Open accessibility settings", true, () -> {
            Sheet.dismissIn(this);
            start(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), null);
        });
        Sheet.show(root, palette, "Lock screen", col);
    }

    private void toggleTorch() {
        CameraManager cm = getSystemService(CameraManager.class);
        if (cm == null) return;
        try {
            for (String id : cm.getCameraIdList()) {
                if (Boolean.TRUE.equals(cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE))) {
                    cm.setTorchMode(id, !torchOn);
                    return;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void runAction(String gestureKey) {
        switch (prefs.gesture(gestureKey)) {
            case Prefs.ACTION_DRAWER: openDrawer(false); break;
            case Prefs.ACTION_NOTIFICATIONS: expandNotifications(); break;
            case Prefs.ACTION_QUICK_SETTINGS: expandQuickSettings(); break;
            case Prefs.ACTION_SEARCH: openDrawer(true); break;
            case Prefs.ACTION_LOCK: lockScreen(); break;
            case Prefs.ACTION_APP: {
                AppEntry e = repo.find(prefs.gestureApp(gestureKey));
                if (e != null) launch(e, root);
                break;
            }
            case Prefs.ACTION_SETTINGS: start(new Intent(this, SettingsActivity.class), null); break;
            case Prefs.ACTION_TORCH: toggleTorch(); break;
            case Prefs.ACTION_EDIT: enterEdit(); break;
            case Prefs.ACTION_PANEL: openShade(Shade.PANEL); break;
            case Prefs.ACTION_RECENTS: openShade(Shade.RECENTS); break;
            default: break;
        }
    }

    // ------------------------------------------------------------------ Home gestures

    @Override
    public boolean gesturesBlocked() {
        return drawerProgress > 0f || Sheet.activeIn(this) != null || folderPanel != null || resizeFrame != null || popup != null
                || shade != null;
    }

    @Override
    public boolean editing() { return editing || arranging; }

    @Override
    public boolean swipeUpFollows() { return prefs.gesture(Prefs.G_UP) == Prefs.ACTION_DRAWER; }

    @Override
    public boolean swipeDownFollows() { return prefs.gesture(Prefs.G_DOWN) == Prefs.ACTION_PANEL; }

    @Override
    public void dragPanel(float dy) {
        if (shade == null) {
            dismissPopup();
            addShade(Shade.PANEL);
        }
        if (shade.closing()) return;
        // Full open at a bit under half the screen, like pulling Nothing's shade.
        shade.setProgress(dy / Math.max(1f, root.getHeight() * 0.42f));
    }

    @Override
    public void releasePanel(float velocityY) {
        if (shade != null) {
            if (!shade.closing()) shade.release(velocityY);
        } else if (velocityY > Ui.dpf(500)) {
            // A flick so quick the finger lifted before the panel could follow: open it anyway.
            openShade(Shade.PANEL);
        }
    }

    /** Helm's quick panel or recent apps, inside home over the frosted wallpaper. */
    private void openShade(String mode) {
        if (shade != null) {
            shade.setMode(mode);
            return;
        }
        dismissPopup();
        addShade(mode);
        shade.open();
    }

    private void addShade(String mode) {
        shade = new Shade(this, palette, this, mode, true);
        shade.setInsets(insetTop, insetBottom);
        shade.setProgress(0f);
        root.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @Override
    public void shadeClosed() {
        if (shade == null) return;
        Shade s = shade;
        shade = null;
        root.removeView(s);
    }

    @Override
    public void openRecentsView() { openShade(Shade.RECENTS); }

    @Override
    public void swipeUp() { runAction(Prefs.G_UP); }

    @Override
    public void swipeDown() { runAction(Prefs.G_DOWN); }

    @Override
    public void doubleTap() { runAction(Prefs.G_DOUBLE); }

    @Override
    public void longPressEmpty(float x, float y) {
        Ui.longPress(root);
        enterEdit();
    }

    @Override
    public void tapEmpty(float x, float y) {
        if (arranging) exitArrange();
        else exitEdit();
    }

    private static boolean inside(View v, float x, float y) {
        if (v == null || v.getVisibility() != View.VISIBLE || !v.isAttachedToWindow()) return false;
        float[] l = Ui.toLocal(v, x, y);
        return l[0] >= 0 && l[1] >= 0 && l[0] < v.getWidth() && l[1] < v.getHeight();
    }

    @Override
    public boolean emptyAt(float x, float y) {
        if (layout == null) return true;
        if (inside(editBar, x, y) || inside(search, x, y)) return false;
        if (prefs.dock() && inside(dock, x, y)) {
            float[] l = Ui.toLocal(dock, x, y);
            int col = dock.cellAt(l[0], l[1])[0];
            return layout.at(Item.DOCK, col, 0, null) == null;
        }
        CellLayout page = workspace.currentPage();
        if (page != null && inside(page, x, y)) {
            float[] l = Ui.toLocal(page, x, y);
            int[] cell = page.cellAt(l[0], l[1]);
            return layout.at(page.page, cell[0], cell[1], null) == null;
        }
        return true;
    }

    @Override
    public void onScroll(float pagePosition) { indicator.setPosition(pagePosition); }

    @Override
    public void onPageSettled(int page) { indicator.setPosition(page); }

    // ------------------------------------------------------------------ Edit & Arrange

    private void enterEdit() {
        if (editing) return;
        editing = true;
        closeDrawer(false);
        if (layout != null && !layout.onPage(layout.pages - 1).isEmpty()) {
            layout.pages++;
            bind();
        }
        for (int i = 0; i < workspace.count(); i++) workspace.page(i).setEditing(true);
        workspace.setPivotX(workspace.getWidth() / 2f);
        workspace.setPivotY(0);
        workspace.animate().scaleX(0.9f).scaleY(0.9f).setDuration(Ui.ms(260)).setInterpolator(Ui.EASE)
                .setUpdateListener(a -> refreshFrostEverywhere()).start();
        search.animate().alpha(0f).setDuration(Ui.ms(150)).start();
        search.setEnabled(false);
        renderEditBar();
        editBar.setVisibility(View.VISIBLE);
        editBar.setAlpha(0f);
        editBar.animate().alpha(1f).setDuration(Ui.ms(200)).start();
    }

    private void exitEdit() {
        if (!editing && !arranging) return;
        editing = false;
        arranging = false;
        selected.clear();
        if (layout != null) {
            layout.trimPages();
            layout.save(this);
        }
        bind();
        workspace.animate().scaleX(1f).scaleY(1f).setDuration(Ui.ms(260)).setInterpolator(Ui.EASE)
                .setUpdateListener(a -> refreshFrostEverywhere()).start();
        search.animate().alpha(1f).setDuration(Ui.ms(200)).start();
        search.setEnabled(true);
        editBar.setVisibility(View.GONE);
        closeResize();
    }

    private void enterArrange() {
        arranging = true;
        selected.clear();
        renderEditBar();
        drawSelection();
        toast("Tap to select. Hold a selected item to move them together.");
    }

    private void exitArrange() {
        arranging = false;
        selected.clear();
        renderEditBar();
        drawSelection();
    }

    private void toggleSelect(Item i) {
        if (!selected.remove(i)) selected.add(i);
        Ui.tick(root);
        renderEditBar();
        drawSelection();
    }

    private void drawSelection() {
        for (Map.Entry<Long, View> en : views.entrySet()) {
            View v = en.getValue();
            if (v instanceof AppIconView) {
                AppIconView a = (AppIconView) v;
                a.setSelectable(arranging);
                a.setChecked(arranging && selected.contains(a.item));
            } else {
                Item it = layout == null ? null : layout.byId(en.getKey());
                v.setAlpha(arranging && it != null && !selected.contains(it) ? 0.55f : 1f);
            }
        }
    }

    private void renderEditBar() {
        editBar.removeAllViews();
        if (arranging) {
            editBarButton(selected.size() + " selected", null, false);
            editBarButton("Page", this::moveSelectedToPage, false);
            editBarButton("Folder", this::folderFromSelected, false);
            editBarButton("Remove", () -> {
                deleteItems(new ArrayList<>(selected));
                selected.clear();
                commit();
                renderEditBar();
            }, false);
            editBarButton("Done", this::exitArrange, true);
        } else {
            editBarButton("Widgets", this::openWidgetPicker, false);
            editBarButton("Wallpaper", () -> start(new Intent(this, SettingsActivity.class).putExtra("page", "wallpaper"), null), false);
            editBarButton("Arrange", this::enterArrange, false);
            editBarButton("Settings", () -> start(new Intent(this, SettingsActivity.class), null), false);
        }
    }

    private void editBarButton(String label, Runnable action, boolean primary) {
        TextView b = Ui.mono(this, label, primary ? palette.onAccent : palette.onGlass, 11);
        b.setGravity(Gravity.CENTER);
        b.setPadding(Ui.dp(12), 0, Ui.dp(12), 0);
        if (primary) b.setBackground(Ui.pill(palette.accent));
        else b.setBackground(Ui.pill(palette.translucent ? palette.glass : palette.solid));
        if (action != null) b.setOnClickListener(v -> {
            Ui.tick(v);
            action.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp.leftMargin = lp.rightMargin = Ui.dp(4);
        editBar.addView(b, lp);
    }

    private void moveSelectedToPage() {
        if (selected.isEmpty()) {
            toast("Select something first");
            return;
        }
        Rows r = new Rows(this, palette);
        LinearLayout col = r.column();
        for (int p = 0; p <= layout.pages; p++) {
            final int page = p;
            r.button(col, p < layout.pages ? "Page " + (p + 1) : "New page", p == workspace.current(), () -> {
                Sheet.dismissIn(this);
                if (page >= layout.pages) layout.pages++;
                List<Item> move = new ArrayList<>(selected);
                for (Item i : move) {
                    List<Item> ignore = new ArrayList<>(move);
                    ignore.remove(i);
                    for (Item o : move) if (o != i && o.page == page) ignore.remove(o);
                    int[] spot = layout.findVacant(page, i.w, i.h, cols, rows, Collections.singleton(i), 0, 0);
                    if (spot == null) {
                        int[] any = layout.findAnywhere(page, i.w, i.h, cols, rows);
                        i.page = any[0];
                        i.x = any[1];
                        i.y = any[2];
                    } else {
                        i.page = page;
                        i.x = spot[0];
                        i.y = spot[1];
                    }
                }
                selected.clear();
                commit();
                workspace.snapTo(page, true);
                renderEditBar();
            });
        }
        Sheet.show(root, palette, "Move to", col);
    }

    private void folderFromSelected() {
        List<Item> apps = new ArrayList<>();
        for (Item i : selected) if (i.type == Item.APP) apps.add(i);
        if (apps.size() < 2) {
            toast("Select two or more apps");
            return;
        }
        Item first = apps.get(0);
        Item f = new Item();
        f.type = Item.FOLDER;
        f.title = folderTitle(apps.get(0).app, apps.get(1).app);
        f.copyPlacement(first);
        for (Item a : apps) {
            f.apps.add(a.app);
            layout.items.remove(a);
        }
        layout.add(f);
        selected.clear();
        commit();
        renderEditBar();
    }

    // ------------------------------------------------------------------ Widgets

    private void openWidgetPicker() {
        Sheet.show(root, palette, "Widgets", WidgetPicker.build(this, palette, this));
    }

    @Override
    public void addHelmWidget(String kind, int w, int h) {
        Sheet.dismissIn(this);
        Item i = Item.helm(kind, w, h);
        int page = workspace.current();
        int[] spot = layout.findVacant(page, w, h, cols, rows, null, 0, 0);
        if (spot == null) {
            int[] any = layout.findAnywhere(page, w, h, cols, rows);
            i.page = any[0];
            i.x = any[1];
            i.y = any[2];
        } else {
            i.page = page;
            i.x = spot[0];
            i.y = spot[1];
        }
        layout.add(i);
        commit();
        workspace.snapTo(i.page, true);
        boolean setup = HelmWidget.PHOTO.equals(kind) || HelmWidget.WORLD.equals(kind)
                || (HelmWidget.WEATHER.equals(kind) && !Weather.configured(this)) || HelmWidget.TOGGLE.equals(kind)
                || HelmWidget.COUNTDOWN.equals(kind) || HelmWidget.CONTACT.equals(kind);
        // Toggles, clocks and countdowns are set up in their options; a tap on a toggle would switch it.
        boolean options = HelmWidget.TOGGLE.equals(kind) || HelmWidget.WORLD.equals(kind) || HelmWidget.COUNTDOWN.equals(kind);
        if (setup) {
            root.postDelayed(() -> {
                View v = views.get(i.id);
                if (v instanceof HelmWidget) {
                    if (options) ((HelmWidget) v).openOptions();
                    else ((HelmWidget) v).onTap();
                }
            }, Ui.ms(350));
        }
    }

    @Override
    public void addSystemWidget(AppWidgetProviderInfo info) {
        Sheet.dismissIn(this);
        pendingInfo = info;
        pendingId = widgetHost.allocateAppWidgetId();
        boolean ok;
        try {
            ok = awm.bindAppWidgetIdIfAllowed(pendingId, info.getProfile(), info.provider, null);
        } catch (RuntimeException e) {
            ok = false;
        }
        if (ok) {
            configureOrPlace();
        } else {
            Intent i = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingId)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.getProfile());
            try {
                startActivityForResult(i, REQ_BIND);
            } catch (RuntimeException e) {
                widgetHost.deleteAppWidgetId(pendingId);
                toast("Android didn't allow that widget");
            }
        }
    }

    private void configureOrPlace() {
        AppWidgetProviderInfo info = pendingInfo;
        if (info == null) return;
        boolean needsConfig = info.configure != null
                && (info.widgetFeatures & AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) == 0;
        if (needsConfig) {
            try {
                widgetHost.startAppWidgetConfigureActivityForResult(this, pendingId, 0, REQ_CONFIG, null);
                return;
            } catch (RuntimeException ignored) {
            }
        }
        placeSystemWidget();
    }

    private void placeSystemWidget() {
        AppWidgetProviderInfo info = pendingInfo;
        if (info == null) return;
        int[] span = spansFor(info);
        Item i = new Item();
        i.type = Item.WIDGET;
        i.widgetId = pendingId;
        i.w = span[0];
        i.h = span[1];
        int page = workspace.current();
        int[] spot = layout.findVacant(page, i.w, i.h, cols, rows, null, 0, 0);
        if (spot == null) {
            int[] any = layout.findAnywhere(page, i.w, i.h, cols, rows);
            i.page = any[0];
            i.x = any[1];
            i.y = any[2];
        } else {
            i.page = page;
            i.x = spot[0];
            i.y = spot[1];
        }
        layout.add(i);
        pendingInfo = null;
        pendingId = -1;
        commit();
        workspace.snapTo(i.page, true);
    }

    private int[] spansFor(AppWidgetProviderInfo info) {
        if (Build.VERSION.SDK_INT >= 31 && info.targetCellWidth > 0 && info.targetCellHeight > 0) {
            return new int[]{Ui.clamp(info.targetCellWidth, 1, cols), Ui.clamp(info.targetCellHeight, 1, rows)};
        }
        CellLayout page = workspace.currentPage();
        int cw = page != null && page.cellW() > 1 ? page.cellW() : Ui.dp(90);
        int ch = page != null && page.cellH() > 1 ? page.cellH() : Ui.dp(90);
        return new int[]{Ui.clamp((int) Math.ceil(info.minWidth / (float) cw), 1, cols),
                Ui.clamp((int) Math.ceil(info.minHeight / (float) ch), 1, rows)};
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        switch (request) {
            case REQ_BIND:
                if (result == RESULT_OK) configureOrPlace();
                else cancelPendingWidget();
                break;
            case REQ_CONFIG:
                if (result == RESULT_OK) placeSystemWidget();
                else cancelPendingWidget();
                break;
            case REQ_IMAGE: {
                Consumer<Uri> cb = pendingImage;
                pendingImage = null;
                if (cb != null) cb.accept(result == RESULT_OK && data != null ? data.getData() : null);
                break;
            }
            case REQ_CONTACT: {
                Consumer<Uri> cb = pendingContact;
                pendingContact = null;
                if (cb != null) cb.accept(result == RESULT_OK && data != null ? data.getData() : null);
                break;
            }
            default:
                break;
        }
    }

    private void cancelPendingWidget() {
        if (pendingId >= 0) widgetHost.deleteAppWidgetId(pendingId);
        pendingId = -1;
        pendingInfo = null;
    }

    @Override
    public void save() { if (layout != null) layout.save(this); }

    @Override
    public ViewGroup sheetHost() { return root; }

    @Override
    public void needPermission(String permission, Runnable granted) {
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            granted.run();
            return;
        }
        int code = REQ_PERMISSION + (nextPermission++ % 100);
        permissionCallbacks.put(code, granted);
        requestPermissions(new String[]{permission}, code);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        Runnable r = permissionCallbacks.remove(code);
        if (r != null && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) r.run();
    }

    @Override
    public void pickContact(Consumer<Uri> picked) {
        pendingContact = picked;
        try {
            startActivityForResult(new Intent(Intent.ACTION_PICK,
                    android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI), REQ_CONTACT);
        } catch (RuntimeException e) {
            pendingContact = null;
            toast("No contacts app available");
        }
    }

    @Override
    public void pickImage(Consumer<Uri> picked) {
        pendingImage = picked;
        try {
            startActivityForResult(new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"), REQ_IMAGE);
        } catch (RuntimeException e) {
            pendingImage = null;
            toast("No photo picker available");
        }
    }

    // ------------------------------------------------------------------ Resize

    private void startResize(Item item) {
        closeResize();
        CellLayout page = workspace.page(item.page);
        if (page == null) return;
        resizeFrame = new ResizeFrame(this, palette, item, page, this);
        root.addView(resizeFrame, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void closeResize() {
        if (resizeFrame == null) return;
        root.removeView(resizeFrame);
        resizeFrame = null;
        save();
    }

    @Override
    public int[] allowed(Item item, int w, int h) {
        if (item.type != Item.HELM) return new int[]{Math.min(w, cols), Math.min(h, rows)};
        int[] best = null;
        int bestD = Integer.MAX_VALUE;
        for (int[] s : HelmWidget.sizes(item.kind)) {
            int d = Math.abs(s[0] - w) * 2 + Math.abs(s[1] - h) * 2 + (s[0] == item.w && s[1] == item.h ? 1 : 0);
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    @Override
    public int[] minimum(Item item) {
        if (item.type == Item.WIDGET) {
            AppWidgetProviderInfo info = awm.getAppWidgetInfo(item.widgetId);
            CellLayout page = workspace.page(item.page);
            if (info != null && page != null) {
                if (info.resizeMode == AppWidgetProviderInfo.RESIZE_NONE) return new int[]{item.w, item.h};
                int mw = info.minResizeWidth > 0 ? info.minResizeWidth : info.minWidth;
                int mh = info.minResizeHeight > 0 ? info.minResizeHeight : info.minHeight;
                return new int[]{Ui.clamp((int) Math.ceil(mw / (float) Math.max(1, page.cellW())), 1, cols),
                        Ui.clamp((int) Math.ceil(mh / (float) Math.max(1, page.cellH())), 1, rows)};
            }
        }
        return new int[]{1, 1};
    }

    @Override
    public void resized(Item item) {
        View v = views.get(item.id);
        if (v != null) {
            v.setLayoutParams(new CellLayout.LP(item.x, item.y, item.w, item.h));
            v.invalidate();
        }
    }

    @Override
    public void resizeClosed() { closeResize(); }

    // ------------------------------------------------------------------ Drag host

    @Override
    public Layout layout() { return layout; }

    @Override
    public Workspace workspace() { return workspace; }

    @Override
    public CellLayout dock() { return prefs.dock() ? dock : null; }

    @Override
    public boolean dockEnabled() { return prefs.dock(); }

    @Override
    public int cols() { return cols; }

    @Override
    public int rows() { return rows; }

    @Override
    public View viewFor(Item i) { return i == null ? null : views.get(i.id); }

    @Override
    public RectF removeZone() {
        if (removePill.getWidth() == 0) return null;
        // Only the pill itself (a little larger), so the top row of home stays a valid drop target.
        float[] a = Ui.toRoot(removePill, 0, 0);
        RectF r = new RectF(a[0], a[1], a[0] + removePill.getWidth(), a[1] + removePill.getHeight());
        r.inset(-Ui.dpf(28), -Ui.dpf(14));
        return r;
    }

    @Override
    public void showRemove(boolean show, boolean hot) {
        removePill.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        removePill.setBackground(Ui.pill(hot ? palette.accent : palette.glass));
        removePill.setTextColor(hot ? palette.onAccent : palette.onGlass);
    }

    @Override
    public void dragStarted(int source) {
        dismissPopup();
        closeResize();
        if (source == DragController.SRC_DRAWER) {
            if (folderPanel != null) folderPanel.closeNow();
            closeDrawer(false);
        }
        if (source == DragController.SRC_FOLDER && folderPanel != null) folderPanel.closeNow();
        if (layout != null && !editing && layout.onPage(layout.pages - 1).size() > 0 && source != DragController.SRC_HOME) {
            // Room to drop on a fresh page.
            layout.pages++;
            workspace.addView(newPage(workspace.count()));
            indicator.setCount(layout.pages);
        }
    }

    @Override
    public void dragEnded() {
        if (!editing && layout != null) {
            int before = layout.pages;
            layout.trimPages();
            if (layout.pages != before) {
                layout.save(this);
                bind();
            }
        }
        drawSelection();
    }

    @Override
    public void addPageForDrag() {
        layout.pages++;
        workspace.addView(newPage(workspace.count()));
        indicator.setCount(layout.pages);
        indicator.setVisibility(prefs.pageDots() ? View.VISIBLE : View.INVISIBLE);
    }

    @Override
    public void deleteItems(List<Item> items) {
        for (Item i : items) {
            if (i.type == Item.WIDGET && i.widgetId >= 0) widgetHost.deleteAppWidgetId(i.widgetId);
            if (i.type == Item.HELM && HelmWidget.PHOTO.equals(i.kind)) {
                //noinspection ResultOfMethodCallIgnored
                new java.io.File(getFilesDir(), "photo_" + i.id + ".png").delete();
            }
            layout.items.remove(i);
            widgetCache.remove(i.id);
            selected.remove(i);
        }
    }

    @Override
    public void removeFromFolder(Item folder, String appKey) {
        folder.apps.remove(appKey);
        if (folder.apps.isEmpty()) {
            layout.items.remove(folder);
        } else if (folder.apps.size() == 1) {
            folder.type = Item.APP;
            folder.app = folder.apps.get(0);
            folder.apps.clear();
            folder.w = folder.h = 1;
        }
    }

    @Override
    public void commit() {
        if (layout == null) return;
        layout.save(this);
        bind();
    }

    @Override
    public String folderTitle(String appA, String appB) {
        AppEntry a = repo.find(appA), b = repo.find(appB);
        if (a != null && b != null && a.category == b.category && a.category >= 0) {
            switch (a.category) {
                case android.content.pm.ApplicationInfo.CATEGORY_GAME: return "Games";
                case android.content.pm.ApplicationInfo.CATEGORY_SOCIAL: return "Social";
                case android.content.pm.ApplicationInfo.CATEGORY_PRODUCTIVITY: return "Work";
                case android.content.pm.ApplicationInfo.CATEGORY_NEWS: return "News";
                case android.content.pm.ApplicationInfo.CATEGORY_MAPS: return "Travel";
                case android.content.pm.ApplicationInfo.CATEGORY_AUDIO:
                case android.content.pm.ApplicationInfo.CATEGORY_VIDEO:
                case android.content.pm.ApplicationInfo.CATEGORY_IMAGE: return "Media";
                default: break;
            }
        }
        return "Folder";
    }

    // ------------------------------------------------------------------ Folders

    @Override
    public void openFolder(FolderIconView v) {
        if (arranging) {
            toggleSelect(v.item);
            return;
        }
        if (editing || folderPanel != null) return;
        RectF disc = v.discBounds();
        float[] a = Ui.toRoot(v, disc.left, disc.top), b = Ui.toRoot(v, disc.right, disc.bottom);
        folderPanel = new FolderPanel(this, palette, v.item, entries(v.item.apps), iconPx, new RectF(a[0], a[1], b[0], b[1]), this);
        root.addView(folderPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @Override
    public void launchFromFolder(AppEntry e, View from) {
        if (arranging || editing) return;
        launch(e, from);
    }

    @Override
    public boolean longPressInFolder(AppIconView v, Item folder) {
        if (v.entry == null) return false;
        if (folder.page == Item.DRAWER_GROUP) {
            Ui.longPress(v);
            Item moving = Item.app(v.entry.key);
            root.drag.arm(DragController.SRC_DRAWER, Collections.singletonList(moving), moving, v, null);
            AppEntry e = v.entry;
            List<AppMenu.Item> items = shortcutItems(e, v);
            items.add(action("Add to home", () -> addAppToHome(e)));
            items.add(action("Remove from group", () -> {
                DrawerGroups.removeFrom(prefs, e.key);
                if (folderPanel != null) folderPanel.closeNow();
            }));
            items.add(action("App info", () -> appInfo(e, v)));
            showMenu(v, e.label, items);
            return true;
        }
        Ui.longPress(v);
        Item moving = Item.app(v.entry.key);
        root.drag.arm(DragController.SRC_FOLDER, Collections.singletonList(moving), moving, v, folder);
        AppEntry e = v.entry;
        List<AppMenu.Item> items = shortcutItems(e, v);
        items.add(action("Remove from folder", () -> {
            Item out = Item.app(e.key);
            int[] spot = layout.findAnywhere(workspace.current(), 1, 1, cols, rows);
            out.page = spot[0];
            out.x = spot[1];
            out.y = spot[2];
            layout.add(out);
            removeFromFolder(folder, e.key);
            if (folderPanel != null) folderPanel.closeNow();
            commit();
        }));
        items.add(action("App info", () -> appInfo(e, v)));
        showMenu(v, e.label, items);
        return true;
    }

    @Override
    public void renameFolder(Item folder, String title) {
        if (folder.page == Item.DRAWER_GROUP) {
            String old = folder.title;
            folder.title = title;
            DrawerGroups.rename(prefs, old, title);
            return;
        }
        folder.title = title;
        commit();
    }

    @Override
    public void folderClosed() { folderPanel = null; }

    // ------------------------------------------------------------------ Drawer

    @Override
    public boolean longPress(AppIconView v) {
        if (v.entry == null || root.drag.busy()) return false;
        Ui.longPress(v);
        Item moving = Item.app(v.entry.key);
        root.drag.arm(DragController.SRC_DRAWER, Collections.singletonList(moving), moving, v, null);
        appMenu(v.entry, v, null);
        return true;
    }

    @Override
    public void unlockHidden(Runnable unlocked) {
        try {
            BiometricPrompt prompt = new BiometricPrompt.Builder(this)
                    .setTitle("Hidden apps")
                    .setSubtitle("Confirm it's you")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK
                            | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build();
            prompt.authenticate(new CancellationSignal(), getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) { unlocked.run(); }

                @Override
                public void onAuthenticationError(int code, CharSequence message) {
                    // No screen lock or no biometrics set up: there is nothing to protect with.
                    if (code == BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL
                            || code == BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT
                            || code == BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS
                            || code == BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE) {
                        unlocked.run();
                    }
                }
            });
        } catch (RuntimeException e) {
            unlocked.run(); // No lock screen set up: nothing to protect with.
        }
    }

    @Override
    public void dragClose(float dy) {
        if (drawerAnim != null) drawerAnim.cancel();
        setDrawerProgress(1f - dy / Math.max(1, root.getHeight()));
    }

    @Override
    public void releaseClose(float velocityY) {
        float fling = Ui.dpf(500);
        if (velocityY > fling || (velocityY > -fling && drawerProgress < 0.75f)) closeDrawer(true);
        else openDrawer(false);
    }

    @Override
    public void dragDrawer(float dy) {
        if (drawerAnim != null) drawerAnim.cancel();
        dismissPopup();
        setDrawerProgress(-dy / Math.max(1, root.getHeight()));
    }

    @Override
    public void releaseDrawer(float velocityY) {
        float fling = Ui.dpf(500);
        if (velocityY < -fling || (velocityY < fling && drawerProgress > 0.25f)) openDrawer(false);
        else closeDrawer(true);
    }

    private void openDrawer(boolean focusSearch) {
        dismissPopup();
        drawer.setVisibility(View.VISIBLE);
        animateDrawer(1f);
        if (focusSearch || prefs.drawerKeyboard()) drawer.focusSearch();
    }

    private void closeDrawer(boolean animate) {
        drawer.hideKeyboard();
        if (animate && drawerProgress > 0f) {
            animateDrawer(0f);
        } else {
            if (drawerAnim != null) drawerAnim.cancel();
            setDrawerProgress(0f);
            drawer.reset();
        }
    }

    private void animateDrawer(float target) {
        if (drawerAnim != null) drawerAnim.cancel();
        if (root.getHeight() == 0 || Ui.motionOff()) {
            setDrawerProgress(target);
            if (target == 0f) drawer.reset();
            return;
        }
        ValueAnimator a = ValueAnimator.ofFloat(drawerProgress, target);
        a.setDuration(Ui.ms((long) (200 + 160 * Math.abs(target - drawerProgress))));
        a.setInterpolator(Ui.EASE);
        a.addUpdateListener(x -> setDrawerProgress((float) x.getAnimatedValue()));
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator x) { cancelled = true; }

            @Override
            public void onAnimationEnd(android.animation.Animator x) {
                if (drawerAnim == a) drawerAnim = null;
                if (!cancelled && target == 0f) drawer.reset();
            }
        });
        drawerAnim = a;
        a.start();
    }

    private void setDrawerProgress(float p) {
        p = Ui.clamp(p, 0f, 1f);
        drawerProgress = p;
        if (p > 0f && drawer.getVisibility() != View.VISIBLE) drawer.setVisibility(View.VISIBLE);
        drawer.setTranslationY((1f - p) * root.getHeight());
        drawer.invalidate();
        float s = 1f - 0.05f * p;
        home.setScaleX(s);
        home.setScaleY(s);
        home.setAlpha(Math.max(0f, 1f - p * 1.6f));
        home.setVisibility(p >= 1f ? View.INVISIBLE : View.VISIBLE);
        if (p <= 0f) drawer.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------------ Back

    /** Closes the top-most open thing; false when home was already clear. */
    private boolean closeTop() {
        if (Sheet.dismissIn(this)) return true;
        if (popup != null) {
            dismissPopup();
            return true;
        }
        if (shade != null) {
            shade.close();
            return true;
        }
        if (resizeFrame != null) {
            closeResize();
            return true;
        }
        if (folderPanel != null) {
            folderPanel.close();
            return true;
        }
        if (arranging) {
            exitArrange();
            return true;
        }
        if (editing) {
            exitEdit();
            return true;
        }
        if (drawerProgress > 0f) {
            closeDrawer(true);
            return true;
        }
        return false;
    }

    /** Closes everything at once, without animation (Home pressed while on home). */
    private boolean closeEverything() {
        boolean any = Sheet.dismissIn(this);
        if (shade != null) {
            shade.closeNow();
            any = true;
        }
        if (popup != null) {
            dismissPopup();
            any = true;
        }
        if (resizeFrame != null) {
            closeResize();
            any = true;
        }
        if (folderPanel != null) {
            folderPanel.closeNow();
            any = true;
        }
        if (editing || arranging) {
            exitEdit();
            any = true;
        }
        if (drawerProgress > 0f) {
            closeDrawer(false);
            any = true;
        }
        return any;
    }

    /** Home is the bottom of the stack, so it always owns Back; the drawer follows a predictive back swipe. */
    private void setupBack() {
        OnBackInvokedDispatcher d = getOnBackInvokedDispatcher();
        if (Build.VERSION.SDK_INT >= 34) {
            backCallback = new OnBackAnimationCallback() {
                private boolean drawerOnly() {
                    return drawerProgress > 0f && Sheet.activeIn(HelmActivity.this) == null && popup == null && folderPanel == null;
                }

                @Override
                public void onBackProgressed(BackEvent e) {
                    if (!drawerOnly()) return;
                    if (drawerAnim != null) drawerAnim.cancel();
                    setDrawerProgress(1f - 0.12f * e.getProgress());
                }

                @Override
                public void onBackInvoked() { closeTop(); }

                @Override
                public void onBackCancelled() {
                    if (drawerOnly()) animateDrawer(1f);
                }
            };
        } else {
            backCallback = this::closeTop;
        }
        d.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
    }
}
