package com.helm.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** All apps, over frosted wallpaper: search, categories, suggestions, A–Z, a scrubber. */
final class DrawerView extends FrameLayout {
    interface Host {
        void launch(AppEntry e, View from);

        boolean longPress(AppIconView v);

        void dragClose(float dy);

        void releaseClose(float velocityY);

        void webSearch(String query);

        void openIntent(Intent i);

        void unlockHidden(Runnable unlocked);

        void copy(String text);

        void openGroup(FolderIconView v);

        boolean groupLongPress(FolderIconView v);
    }

    private static final int BAR_HEIGHT = 52;
    private static final String ALL = "All", HIDDEN = "Hidden", SUGGESTED = "Suggested";
    private static final String[][] SETTINGS = {
            {"Wi-Fi", Settings.ACTION_WIFI_SETTINGS, "wifi wi-fi wireless internet network"},
            {"Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS, "bluetooth pair headphones earbuds"},
            {"Display", Settings.ACTION_DISPLAY_SETTINGS, "display brightness screen refresh rate dark mode"},
            {"Battery", Intent.ACTION_POWER_USAGE_SUMMARY, "battery power charge"},
            {"Sound", Settings.ACTION_SOUND_SETTINGS, "sound volume ringtone vibration"},
            {"Notifications", "android.settings.NOTIFICATION_SETTINGS", "notifications alerts"},
            {"Apps", Settings.ACTION_APPLICATION_SETTINGS, "apps applications"},
            {"Storage", Settings.ACTION_INTERNAL_STORAGE_SETTINGS, "storage space memory"},
            {"Location", Settings.ACTION_LOCATION_SOURCE_SETTINGS, "location gps"},
            {"Security", Settings.ACTION_SECURITY_SETTINGS, "security lock screen fingerprint face password"},
            {"Mobile data", Settings.ACTION_DATA_USAGE_SETTINGS, "data usage mobile cellular sim"},
            {"Hotspot", "android.settings.TETHER_SETTINGS", "hotspot tethering"},
            {"Date & time", Settings.ACTION_DATE_SETTINGS, "date time clock timezone"},
            {"Language", Settings.ACTION_LOCALE_SETTINGS, "language keyboard"},
            {"Accessibility", Settings.ACTION_ACCESSIBILITY_SETTINGS, "accessibility"},
            {"About phone", Settings.ACTION_DEVICE_INFO_SETTINGS, "about phone model version"},
            {"Default apps", Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, "default apps launcher home browser"},
    };

    private final Host host;
    private final Palette p;
    private final Prefs prefs;
    private final int columns, iconPx;
    private final boolean list;
    private final int slop;
    private final Material.Frost frost = new Material.Frost();
    private final List<Row> rows = new ArrayList<>();
    private final Adapter adapter = new Adapter();

    private LinearLayout bar;
    private EditText field;
    private TextView clear;
    private HorizontalScrollView chipScroll;
    private LinearLayout chips;
    private ListView listView;
    private AlphabetScrubber scrubber;
    private FrameLayout bubble;
    private TextView bubbleLetter;

    private List<AppEntry> apps = Collections.emptyList();
    private String query = "";
    private String category = ALL;
    private boolean hiddenUnlocked;
    private VelocityTracker velocity;
    private float downX, downY;
    private boolean closing;

    DrawerView(Context c, Palette palette, Host host) {
        super(c);
        this.host = host;
        this.p = palette;
        this.prefs = Prefs.get(c);
        this.columns = prefs.drawerColumns();
        this.list = prefs.drawerLayout() == Prefs.LAYOUT_LIST;
        this.iconPx = Ui.dp(list ? 40 : (columns >= 5 ? 46 : 54));
        this.slop = ViewConfiguration.get(c).getScaledTouchSlop();
        setWillNotDraw(false);

        buildSearchBar(c);
        buildChips(c);

        listView = new ListView(c);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setSelector(new ColorDrawable(0));
        listView.setVerticalScrollBarEnabled(false);
        listView.setClipToPadding(false);
        listView.setCacheColorHint(0);
        listView.setAdapter(adapter);
        listView.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int state) {
                if (state == SCROLL_STATE_TOUCH_SCROLL) hideKeyboard();
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {}
        });
        addView(listView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        scrubber = new AlphabetScrubber(c, p, new AlphabetScrubber.Listener() {
            @Override
            public void onLetter(char letter, float y) {
                bubbleLetter.setText(String.valueOf(letter));
                bubble.setVisibility(VISIBLE);
                bubble.setTranslationY(scrubber.getTop() + y - Ui.dp(34));
                int i = headerIndex(letter);
                if (i >= 0) listView.setSelectionFromTop(i, 0);
            }

            @Override
            public void onRelease() { bubble.setVisibility(GONE); }
        });
        addView(scrubber, new LayoutParams(Ui.dp(28), LayoutParams.MATCH_PARENT, Gravity.END));

        bubble = new FrameLayout(c);
        bubble.setBackground(Ui.pill(p.accent));
        bubble.setVisibility(GONE);
        bubbleLetter = Ui.text(c, "", p.onAccent, 30, Ui.headline(c));
        bubble.addView(bubbleLetter, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        LayoutParams bl = new LayoutParams(Ui.dp(68), Ui.dp(68), Gravity.TOP | Gravity.END);
        bl.rightMargin = Ui.dp(44);
        addView(bubble, bl);

        applyInsets(0, 0);
    }

    private int ink(int level) {
        int m = p.translucent ? Material.TRANSLUCENT : Material.OPAQUE;
        return p.fg(m, level);
    }

    @Override
    protected void onDraw(Canvas c) {
        int bg = prefs.drawerBg();
        if (bg == Prefs.DRAWER_BG_CLEAR) c.drawColor(Palette.alpha(p.dark ? 0xFF000000 : 0xFFFFFFFF, p.dark ? 0.45f : 0.55f));
        else if (p.translucent && bg == Prefs.DRAWER_BG_FROST) Material.screen(c, this, frost, Palette.alpha(p.dark ? 0xFF0C0C0E : 0xFFF4F4F2, 0.72f), p);
        else c.drawColor(p.bg);
    }

    private void buildSearchBar(Context c) {
        bar = new LinearLayout(c) {
            private final Material.Frost f = new Material.Frost();

            @Override
            protected void dispatchDraw(Canvas canvas) {
                android.graphics.RectF r = new android.graphics.RectF(0, 0, getWidth(), getHeight());
                Material.roundRect(canvas, this, f, r, getHeight() / 2f, p.translucent ? Material.TRANSLUCENT : Material.OPAQUE, p);
                super.dispatchDraw(canvas);
            }
        };
        bar.setWillNotDraw(false);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(20), 0, Ui.dp(10), 0);

        field = new EditText(c);
        field.setBackground(null);
        field.setHint("Search apps, settings, the web");
        field.setHintTextColor(ink(2));
        field.setTextColor(ink(1));
        field.setTextSize(16);
        field.setTypeface(Ui.SANS);
        field.setSingleLine(true);
        field.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setPadding(0, 0, Ui.dp(8), 0);
        bar.addView(field, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        clear = Ui.mono(c, "Clear", ink(2), 11);
        clear.setPadding(Ui.dp(10), Ui.dp(10), Ui.dp(10), Ui.dp(10));
        clear.setVisibility(GONE);
        clear.setOnClickListener(v -> field.setText(""));
        bar.addView(clear);

        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(BAR_HEIGHT), Gravity.TOP);
        lp.leftMargin = lp.rightMargin = Ui.dp(16);
        addView(bar, lp);

        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) {}

            @Override public void onTextChanged(CharSequence s, int a, int b, int d) {}

            @Override
            public void afterTextChanged(Editable s) { setQuery(s.toString()); }
        });
        field.setOnEditorActionListener((v, id, ev) -> {
            boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN;
            if (id == EditorInfo.IME_ACTION_GO || enter) {
                launchTop();
                return true;
            }
            return false;
        });
    }

    private void buildChips(Context c) {
        chipScroll = new HorizontalScrollView(c);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setClipToPadding(false);
        chipScroll.setPadding(Ui.dp(16), 0, Ui.dp(16), 0);
        chips = new LinearLayout(c);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chipScroll.addView(chips);
        addView(chipScroll, new LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(36), Gravity.TOP));
        chipScroll.setVisibility(prefs.categories() ? VISIBLE : GONE);
    }

    private static String categoryOf(AppEntry e) {
        switch (e.category) {
            case ApplicationInfo.CATEGORY_GAME: return "Games";
            case ApplicationInfo.CATEGORY_AUDIO:
            case ApplicationInfo.CATEGORY_VIDEO:
            case ApplicationInfo.CATEGORY_IMAGE: return "Media";
            case ApplicationInfo.CATEGORY_SOCIAL: return "Social";
            case ApplicationInfo.CATEGORY_NEWS: return "News";
            case ApplicationInfo.CATEGORY_MAPS: return "Travel";
            case ApplicationInfo.CATEGORY_PRODUCTIVITY: return "Work";
            default: return "Other";
        }
    }

    private void rebuildChips() {
        chips.removeAllViews();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String k : new String[]{"Social", "Media", "Work", "Games", "Travel", "News", "Other"}) counts.put(k, 0);
        for (AppEntry e : apps) counts.put(categoryOf(e), counts.get(categoryOf(e)) + 1);
        List<String> shown = new ArrayList<>();
        shown.add(ALL);
        for (Map.Entry<String, Integer> en : counts.entrySet()) if (en.getValue() > 0) shown.add(en.getKey());
        if (!AppRepository.get(getContext()).hiddenApps().isEmpty()) shown.add(HIDDEN);
        if (!shown.contains(category)) category = ALL;
        for (String name : shown) {
            boolean on = name.equals(category);
            TextView t = Ui.mono(getContext(), name, on ? (p.dark ? 0xFF000000 : 0xFFFFFFFF) : ink(1), 11);
            t.setGravity(Gravity.CENTER);
            t.setPadding(Ui.dp(14), 0, Ui.dp(14), 0);
            t.setBackground(on ? Ui.pill(ink(1)) : Ui.outline(0, Palette.alpha(ink(1), 0.25f), 999));
            t.setOnClickListener(v -> {
                if (HIDDEN.equals(name) && !hiddenUnlocked && prefs.hiddenLock()) {
                    host.unlockHidden(() -> {
                        hiddenUnlocked = true;
                        pickCategory(name, v);
                    });
                    return;
                }
                pickCategory(name, v);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(32));
            lp.rightMargin = Ui.dp(8);
            lp.gravity = Gravity.CENTER_VERTICAL;
            chips.addView(t, lp);
        }
    }

    private void pickCategory(String name, View v) {
        category = name;
        Ui.tick(v);
        rebuildChips();
        rebuild();
        listView.setSelection(0);
    }

    void applyInsets(int top, int bottom) {
        LayoutParams bl = (LayoutParams) bar.getLayoutParams();
        bl.topMargin = top + Ui.dp(12);
        bar.setLayoutParams(bl);
        int y = top + Ui.dp(12 + BAR_HEIGHT + 10);
        LayoutParams cl = (LayoutParams) chipScroll.getLayoutParams();
        cl.topMargin = y;
        chipScroll.setLayoutParams(cl);
        int listTop = y + (prefs.categories() ? Ui.dp(42) : 0);
        LayoutParams ll = (LayoutParams) listView.getLayoutParams();
        ll.topMargin = listTop;
        listView.setLayoutParams(ll);
        listView.setPadding(Ui.dp(6), 0, Ui.dp(24), bottom + Ui.dp(16));
        LayoutParams sl = (LayoutParams) scrubber.getLayoutParams();
        sl.topMargin = listTop + Ui.dp(8);
        sl.bottomMargin = bottom + Ui.dp(16);
        scrubber.setLayoutParams(sl);
    }

    void setApps(List<AppEntry> all) {
        apps = all;
        if (prefs.categories()) rebuildChips();
        rebuild();
    }

    void focusSearch() {
        field.requestFocus();
        field.post(() -> {
            InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
            if (imm != null) imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    void hideKeyboard() {
        InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
        field.clearFocus();
    }

    void reset() {
        hideKeyboard();
        if (field.length() > 0) field.setText("");
        hiddenUnlocked = false;
        if (!ALL.equals(category)) {
            category = ALL;
            if (prefs.categories()) rebuildChips();
            rebuild();
        }
        listView.setSelection(0);
    }

    private void setQuery(String raw) {
        String q = AppEntry.normalize(raw.trim());
        clear.setVisibility(raw.isEmpty() ? GONE : VISIBLE);
        if (q.equals(query)) return;
        query = q;
        chipScroll.setVisibility(prefs.categories() && q.isEmpty() ? VISIBLE : (prefs.categories() ? INVISIBLE : GONE));
        rebuild();
        listView.setSelection(0);
    }

    private List<AppEntry> pool() {
        AppRepository repo = AppRepository.get(getContext());
        if (HIDDEN.equals(category)) return repo.hiddenApps();
        java.util.Set<String> hidden = prefs.hidden();
        java.util.Set<String> grouped = new java.util.HashSet<>();
        if (ALL.equals(category) && prefs.groupsHideMembers()) {
            for (DrawerGroups.Group g : DrawerGroups.load(prefs)) grouped.addAll(g.apps);
        }
        List<AppEntry> out = new ArrayList<>();
        for (AppEntry e : apps) {
            if (hidden.contains(e.key) || grouped.contains(e.key)) continue;
            if (!ALL.equals(category) && !category.equals(categoryOf(e))) continue;
            out.add(e);
        }
        return out;
    }

    private void rebuild() {
        rows.clear();
        List<Character> letters = new ArrayList<>();
        AppRepository repo = AppRepository.get(getContext());
        if (query.isEmpty()) {
            List<AppEntry> pool = pool();
            if (ALL.equals(category) && prefs.suggestions()) {
                List<AppEntry> sug = repo.suggestions(list ? 4 : columns);
                if (!sug.isEmpty()) {
                    rows.add(Row.header(SUGGESTED));
                    addApps(sug);
                }
            }
            if (ALL.equals(category)) addGroups();
            if (prefs.drawerSort() == Prefs.SORT_USAGE) {
                pool.sort((a, b) -> Integer.compare(repo.launches(b.key), repo.launches(a.key)));
                rows.add(Row.header("Most used"));
                addApps(pool);
            } else {
                List<AppEntry> chunk = new ArrayList<>();
                char current = 0;
                for (AppEntry e : pool) {
                    if (e.section != current) {
                        addApps(chunk);
                        chunk = new ArrayList<>();
                        current = e.section;
                        rows.add(Row.letter(current));
                        letters.add(current);
                    }
                    chunk.add(e);
                }
                addApps(chunk);
            }
        } else {
            String raw = field.getText().toString().trim();
            if (prefs.calculator()) {
                Double v = Calc.eval(raw);
                if (v != null) rows.add(Row.calc(raw, Calc.format(v)));
            }
            List<AppEntry> hits = search(query);
            if (!hits.isEmpty()) {
                rows.add(Row.header("Apps"));
                addApps(hits);
            }
            if (prefs.settingsResults()) {
                boolean first = true;
                for (String[] s : SETTINGS) {
                    if (AppEntry.normalize(s[0]).startsWith(query) || s[2].contains(query)) {
                        if (first) rows.add(Row.header("Settings"));
                        first = false;
                        rows.add(Row.setting(s[0], s[1]));
                    }
                }
            }
            rows.add(Row.web(raw));
        }
        scrubber.setLetters(letters);
        scrubber.setVisibility(letters.size() > 4 ? VISIBLE : GONE);
        adapter.notifyDataSetChanged();
    }

    /** Drawer groups as folder tiles, a row at a time, above the A–Z list. */
    private void addGroups() {
        List<DrawerGroups.Group> groups = DrawerGroups.load(prefs);
        java.util.Set<String> hidden = prefs.hidden();
        List<Item> tiles = new ArrayList<>();
        AppRepository repo = AppRepository.get(getContext());
        for (DrawerGroups.Group g : groups) {
            Item it = new Item();
            it.type = Item.FOLDER;
            it.page = Item.DRAWER_GROUP;
            it.title = g.name;
            for (String k : g.apps) if (!hidden.contains(k) && repo.find(k) != null) it.apps.add(k);
            if (!it.apps.isEmpty()) tiles.add(it);
        }
        if (tiles.isEmpty()) return;
        rows.add(Row.header("Groups"));
        int n = list ? Math.max(4, columns) : columns;
        for (int i = 0; i < tiles.size(); i += n) {
            rows.add(Row.groups(new ArrayList<>(tiles.subList(i, Math.min(tiles.size(), i + n)))));
        }
    }

    private void addApps(List<AppEntry> entries) {
        if (list) {
            for (AppEntry e : entries) rows.add(Row.apps(Collections.singletonList(e)));
            return;
        }
        for (int i = 0; i < entries.size(); i += columns) {
            rows.add(Row.apps(new ArrayList<>(entries.subList(i, Math.min(entries.size(), i + columns)))));
        }
    }

    private List<AppEntry> search(String q) {
        List<List<AppEntry>> buckets = new ArrayList<>();
        for (int i = 0; i < 4; i++) buckets.add(new ArrayList<>());
        java.util.Set<String> hidden = prefs.hidden();
        for (AppEntry e : apps) {
            if (hidden.contains(e.key) && !hiddenUnlocked) continue;
            int s = score(e.search, q);
            if (s >= 0) buckets.get(s).add(e);
        }
        List<AppEntry> out = new ArrayList<>();
        for (List<AppEntry> b : buckets) out.addAll(b);
        return out;
    }

    /** 0 prefix, 1 word start, 2 anywhere, 3 initials ("gm" for Google Maps); -1 no match. */
    private static int score(String text, String q) {
        if (text.startsWith(q)) return 0;
        if (text.contains(" " + q)) return 1;
        if (text.contains(q)) return 2;
        StringBuilder initials = new StringBuilder();
        boolean start = true;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                if (start) initials.append(ch);
                start = false;
            } else {
                start = true;
            }
        }
        return initials.length() > 1 && initials.toString().startsWith(q) ? 3 : -1;
    }

    private void launchTop() {
        for (Row r : rows) {
            if (r.type == Row.APPS && !r.apps.isEmpty()) {
                host.launch(r.apps.get(0), field);
                return;
            }
        }
        String q = field.getText().toString().trim();
        if (!q.isEmpty()) host.webSearch(q);
    }

    private int headerIndex(char letter) {
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (r.type == Row.LETTER && r.letter == letter) return i;
        }
        return -1;
    }

    private boolean listAtTop() {
        if (listView.getChildCount() == 0) return true;
        return listView.getFirstVisiblePosition() == 0 && listView.getChildAt(0).getTop() >= listView.getPaddingTop();
    }

    // Pull down from the top of the list to close.

    private void track(MotionEvent ev) {
        if (velocity == null) velocity = VelocityTracker.obtain();
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) velocity.clear();
        // Screen coordinates: the drawer moves under the finger while closing, so local ones would feed back.
        MotionEvent raw = MotionEvent.obtain(ev);
        raw.setLocation(ev.getRawX(), ev.getRawY());
        velocity.addMovement(raw);
        raw.recycle();
    }

    private boolean shouldClose(MotionEvent ev) {
        float dy = ev.getRawY() - downY, dx = ev.getRawX() - downX;
        return dy > slop && dy > Math.abs(dx) * 1.2f && listAtTop();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        track(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getRawX();
                downY = ev.getRawY();
                closing = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!closing && shouldClose(ev)) {
                    closing = true;
                    downY = ev.getRawY(); // Start from here so the drawer doesn't jump by the touch slop.
                    hideKeyboard();
                    return true;
                }
                break;
            default:
                break;
        }
        return closing;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        track(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getRawX();
                downY = ev.getRawY();
                closing = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!closing && shouldClose(ev)) {
                    closing = true;
                    downY = ev.getRawY();
                    hideKeyboard();
                }
                if (closing) host.dragClose(Math.max(0f, ev.getRawY() - downY));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (closing) {
                    velocity.computeCurrentVelocity(1000);
                    host.releaseClose(velocity.getYVelocity());
                }
                closing = false;
                return true;
            default:
                return true;
        }
    }

    private static final class Row {
        static final int LETTER = 0, HEADER = 1, APPS = 2, CALC = 3, SETTING = 4, WEB = 5, GROUPS = 6;
        int type;
        char letter;
        List<AppEntry> apps;
        List<Item> groups;
        String text, extra;

        static Row letter(char c) { Row r = new Row(); r.type = LETTER; r.letter = c; return r; }

        static Row header(String s) { Row r = new Row(); r.type = HEADER; r.text = s; return r; }

        static Row apps(List<AppEntry> a) { Row r = new Row(); r.type = APPS; r.apps = a; return r; }

        static Row calc(String expr, String value) { Row r = new Row(); r.type = CALC; r.text = expr; r.extra = value; return r; }

        static Row setting(String name, String action) { Row r = new Row(); r.type = SETTING; r.text = name; r.extra = action; return r; }

        static Row groups(List<Item> g) { Row r = new Row(); r.type = GROUPS; r.groups = g; return r; }

        static Row web(String q) { Row r = new Row(); r.type = WEB; r.text = q; return r; }
    }

    private final class AppRow extends LinearLayout {
        final AppIconView[] cells;

        AppRow(Context c) {
            super(c);
            setOrientation(HORIZONTAL);
            setPadding(0, Ui.dp(4), 0, Ui.dp(4));
            int n = list ? 1 : columns;
            cells = new AppIconView[n];
            for (int i = 0; i < n; i++) {
                AppIconView v = new AppIconView(c);
                v.setOnClickListener(x -> host.launch(((AppIconView) x).entry, x));
                v.setOnLongClickListener(x -> host.longPress((AppIconView) x));
                addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                cells[i] = v;
            }
        }

        void bind(List<AppEntry> entries) {
            for (int i = 0; i < cells.length; i++) {
                if (i < entries.size()) {
                    cells[i].setVisibility(VISIBLE);
                    cells[i].bind(entries.get(i), iconPx, true, ink(1), Ui.SANS, p);
                } else {
                    cells[i].setVisibility(INVISIBLE);
                }
            }
        }
    }

    /** A row of drawer-group folder tiles. */
    private final class GroupRow extends LinearLayout {
        final FolderIconView[] cells;

        GroupRow(Context c, int n) {
            super(c);
            setOrientation(HORIZONTAL);
            setPadding(0, Ui.dp(4), 0, Ui.dp(4));
            cells = new FolderIconView[n];
            android.text.TextPaint tp = new android.text.TextPaint();
            tp.setTextSize(Ui.sp(prefs.labelSize()));
            Paint.FontMetricsInt fm = tp.getFontMetricsInt();
            int h = iconPx + Ui.dp(16) + fm.descent - fm.ascent;
            FolderIconView.Host fh = new FolderIconView.Host() {
                @Override
                public void openFolder(FolderIconView v) { host.openGroup(v); }

                @Override
                public void launchFromFolder(AppEntry e, View from) { host.launch(e, from); }
            };
            for (int i = 0; i < n; i++) {
                FolderIconView v = new FolderIconView(c, fh);
                v.setOnLongClickListener(x -> host.groupLongPress((FolderIconView) x));
                addView(v, new LinearLayout.LayoutParams(0, h, 1f));
                cells[i] = v;
            }
        }

        void bind(List<Item> groups) {
            AppRepository repo = AppRepository.get(getContext());
            for (int i = 0; i < cells.length; i++) {
                if (i < groups.size()) {
                    Item g = groups.get(i);
                    List<AppEntry> entries = new ArrayList<>();
                    for (String k : g.apps) {
                        AppEntry e = repo.find(k);
                        if (e != null) entries.add(e);
                    }
                    cells[i].setVisibility(VISIBLE);
                    cells[i].bind(g, entries, iconPx, true, ink(1), p);
                } else {
                    cells[i].setVisibility(INVISIBLE);
                }
            }
        }
    }

    /** List layout: icon, then the name, left-aligned. */
    private final class ListRow extends LinearLayout {
        final AppIconView icon;
        final TextView name;
        AppEntry entry;

        ListRow(Context c) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(Ui.dp(14), Ui.dp(6), Ui.dp(14), Ui.dp(6));
            icon = new AppIconView(c);
            addView(icon, new LinearLayout.LayoutParams(Ui.dp(52), Ui.dp(52)));
            name = Ui.text(c, "", ink(1), 16, Ui.SANS);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            nl.leftMargin = Ui.dp(14);
            addView(name, nl);
            setOnClickListener(v -> host.launch(entry, icon));
            setOnLongClickListener(v -> host.longPress(icon));
            setHapticFeedbackEnabled(false);
        }

        void bind(AppEntry e) {
            entry = e;
            icon.bind(e, iconPx, false, ink(1), Ui.SANS, p);
            name.setText(e.label);
        }
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }

        @Override public Object getItem(int i) { return rows.get(i); }

        @Override public long getItemId(int i) { return i; }

        @Override public int getViewTypeCount() { return 7; }

        @Override public int getItemViewType(int i) { return rows.get(i).type; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            Row r = rows.get(i);
            Context c = parent.getContext();
            switch (r.type) {
                case Row.LETTER:
                case Row.HEADER: {
                    TextView v = convert instanceof TextView ? (TextView) convert : header(c);
                    v.setText(r.type == Row.LETTER ? String.valueOf(r.letter) : r.text.toUpperCase(Locale.getDefault()));
                    return v;
                }
                case Row.APPS: {
                    if (list) {
                        ListRow v = convert instanceof ListRow ? (ListRow) convert : new ListRow(c);
                        v.bind(r.apps.get(0));
                        return v;
                    }
                    AppRow v = convert instanceof AppRow ? (AppRow) convert : new AppRow(c);
                    v.bind(r.apps);
                    return v;
                }
                case Row.GROUPS: {
                    int n = list ? Math.max(4, columns) : columns;
                    GroupRow v = convert instanceof GroupRow ? (GroupRow) convert : new GroupRow(c, n);
                    v.bind(r.groups);
                    return v;
                }
                case Row.CALC: {
                    FrameLayout v = convert instanceof FrameLayout ? (FrameLayout) convert : pillRow(c);
                    TextView t = (TextView) v.getChildAt(0);
                    t.setText("= " + r.extra);
                    t.setTypeface(Ui.headline(c));
                    t.setTextSize(24);
                    t.setOnClickListener(x -> {
                        host.copy(r.extra);
                        Ui.confirm(x);
                    });
                    return v;
                }
                case Row.SETTING: {
                    FrameLayout v = convert instanceof FrameLayout ? (FrameLayout) convert : pillRow(c);
                    TextView t = (TextView) v.getChildAt(0);
                    t.setText(r.text);
                    t.setTypeface(Ui.SANS);
                    t.setTextSize(15);
                    t.setOnClickListener(x -> host.openIntent(new Intent(r.extra)));
                    return v;
                }
                default: {
                    FrameLayout v = convert instanceof FrameLayout ? (FrameLayout) convert : pillRow(c);
                    TextView t = (TextView) v.getChildAt(0);
                    String engine = new String[]{"Google", "DuckDuckGo", "Bing", "Brave"}[Ui.clamp(prefs.engine(), 0, 3)];
                    t.setText("Search " + engine + " for “" + r.text + "”");
                    t.setTypeface(Ui.SANS);
                    t.setTextSize(15);
                    t.setOnClickListener(x -> host.webSearch(r.text));
                    return v;
                }
            }
        }

        private TextView header(Context c) {
            TextView v = Ui.mono(c, "", ink(2), 11);
            v.setLetterSpacing(0.14f);
            v.setPadding(Ui.dp(18), Ui.dp(18), 0, Ui.dp(6));
            return v;
        }

        private FrameLayout pillRow(Context c) {
            FrameLayout f = new FrameLayout(c);
            f.setPadding(Ui.dp(10), Ui.dp(4), Ui.dp(10), Ui.dp(4));
            TextView t = Ui.text(c, "", ink(1), 15, Ui.SANS);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setBackground(Ui.pill(Palette.alpha(ink(1), 0.08f)));
            t.setPadding(Ui.dp(20), 0, Ui.dp(20), 0);
            f.addView(t, new LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(52)));
            return f;
        }
    }
}
