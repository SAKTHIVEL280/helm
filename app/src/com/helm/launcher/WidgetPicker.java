package com.helm.launcher;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.pm.LauncherApps;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;

/** The widget drawer: Helm's widgets with live previews, then every other app's widgets. */
final class WidgetPicker {
    interface Host extends HelmWidget.Host {
        void addHelmWidget(String kind, int w, int h);

        void addSystemWidget(AppWidgetProviderInfo info);
    }

    private WidgetPicker() {}

    static View build(Context c, Palette p, Host host) {
        Rows r = new Rows(c, p);
        LinearLayout col = r.column();
        LinearLayout helmList = r.column();
        LinearLayout appList = r.column();
        appList.setVisibility(View.GONE);
        Segmented tabs = new Segmented(c, p, new String[]{"Helm", "Apps"}, 0, i -> {
            helmList.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
            appList.setVisibility(i == 1 ? View.VISIBLE : View.GONE);
            if (i == 1 && appList.getChildCount() == 0) fillApps(c, p, r, appList, host);
        });
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.bottomMargin = Ui.dp(14);
        col.addView(tabs, tl);
        col.addView(helmList);
        col.addView(appList);

        for (String kind : HelmWidget.KINDS) helmList.addView(helmRow(c, p, r, kind, host));
        return col;
    }

    private static View helmRow(Context c, Palette p, Rows r, String kind, Host host) {
        LinearLayout card = r.column();
        card.setBackground(Ui.rounded(p.solid, 22));
        card.setPadding(Ui.dp(16), Ui.dp(16), Ui.dp(16), Ui.dp(14));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.bottomMargin = Ui.dp(12);
        card.setLayoutParams(cl);

        // A live preview on a slice of the wallpaper.
        int[] size = HelmWidget.sizes(kind)[0];
        int cell = Ui.dp(64);
        FrameLayout stage = new FrameLayout(c) {
            @Override
            protected void dispatchDraw(android.graphics.Canvas canvas) {
                android.graphics.Bitmap wall = WallpaperStore.get(getContext()).frost();
                android.graphics.RectF rr = new android.graphics.RectF(0, 0, getWidth(), getHeight());
                android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
                if (wall != null) {
                    android.graphics.BitmapShader sh = new android.graphics.BitmapShader(wall,
                            android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
                    android.graphics.Matrix m = new android.graphics.Matrix();
                    float s = Math.max(getWidth() / (float) wall.getWidth(), getHeight() / (float) wall.getHeight());
                    m.setScale(s, s);
                    sh.setLocalMatrix(m);
                    fill.setShader(sh);
                } else {
                    fill.setColor(p.dark ? 0xFF000000 : 0xFFE6E6E6);
                }
                canvas.drawRoundRect(rr, Ui.dpf(16), Ui.dpf(16), fill);
                super.dispatchDraw(canvas);
            }
        };
        stage.setWillNotDraw(false);
        Item preview = Item.helm(kind, size[0], size[1]);
        HelmWidget w = HelmWidget.create(c, preview, p, host);
        w.setOnClickListener(null);
        w.setClickable(false);
        stage.addView(w, new FrameLayout.LayoutParams(cell * size[0], cell * size[1], Gravity.CENTER));
        card.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cell * Math.min(2, size[1]) + Ui.dp(28)));

        LinearLayout text = r.column();
        text.setPadding(Ui.dp(4), Ui.dp(14), 0, 0);
        text.addView(Ui.text(c, HelmWidget.title(kind), p.onSolid, 17, Ui.SANS_MEDIUM));
        TextView blurb = Ui.text(c, HelmWidget.blurb(kind), p.onSolid2, 13, Ui.SANS);
        blurb.setPadding(0, Ui.dp(4), 0, 0);
        text.addView(blurb);
        card.addView(text);

        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(c);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        for (int[] s : HelmWidget.sizes(kind)) {
            TextView chip = Ui.mono(c, "+ " + s[0] + "×" + s[1], p.onSolid, 12);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(Ui.dp(14), 0, Ui.dp(14), 0);
            chip.setBackground(Ui.pill(p.solidHigh));
            chip.setOnClickListener(v -> {
                Ui.confirm(v);
                host.addHelmWidget(kind, s[0], s[1]);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(36));
            lp.rightMargin = Ui.dp(8);
            chips.addView(chip, lp);
        }
        hs.addView(chips);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.topMargin = Ui.dp(12);
        card.addView(hs, hl);
        return card;
    }

    private static final java.util.concurrent.ExecutorService LOADER = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final java.util.concurrent.ExecutorService PREVIEWS = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());

    /** Labels and sorting happen off the UI thread; previews load row by row after. Big lists no longer freeze. */
    private static void fillApps(Context c, Palette p, Rows r, LinearLayout list, Host host) {
        TextView loading = Ui.mono(c, "LOADING", p.onSolid3, 11);
        list.addView(loading);
        Context app = c.getApplicationContext();
        LOADER.execute(() -> {
            List<Object[]> rows = new ArrayList<>(); // {info, appName, label}
            try {
                AppWidgetManager awm = AppWidgetManager.getInstance(app);
                LauncherApps la = app.getSystemService(LauncherApps.class);
                java.util.Map<String, String> names = new java.util.HashMap<>();
                for (UserHandle u : la.getProfiles()) {
                    List<AppWidgetProviderInfo> found = awm.getInstalledProvidersForProfile(u);
                    if (found == null) continue;
                    for (AppWidgetProviderInfo info : found) {
                        String pkg = info.provider.getPackageName();
                        String name = names.get(pkg);
                        if (name == null) {
                            name = appName(app, info);
                            names.put(pkg, name);
                        }
                        rows.add(new Object[]{info, name, info.loadLabel(app.getPackageManager())});
                    }
                }
                Collator col = Collator.getInstance();
                rows.sort((x, y) -> {
                    int k = col.compare((String) x[1], (String) y[1]);
                    return k != 0 ? k : col.compare((String) x[2], (String) y[2]);
                });
            } catch (RuntimeException ignored) {
                // Show whatever was gathered.
            }
            MAIN.post(() -> {
                if (!list.isAttachedToWindow()) return;
                list.removeView(loading);
                build(c, p, r, list, host, rows);
            });
        });
    }

    private static void build(Context c, Palette p, Rows r, LinearLayout list, Host host, List<Object[]> all) {
        if (all.isEmpty()) {
            list.addView(Ui.text(c, "No widgets found.", p.onSolid2, 15, Ui.SANS));
            return;
        }
        String last = null;
        LinearLayout card = null;
        int dpi = c.getResources().getDisplayMetrics().densityDpi;
        for (Object[] o : all) {
            AppWidgetProviderInfo info = (AppWidgetProviderInfo) o[0];
            String app = (String) o[1];
            if (!app.equals(last)) {
                r.section(list, app);
                card = r.card(list);
                last = app;
            }
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(14), Ui.dp(12), Ui.dp(18), Ui.dp(12));
            ImageView img = new ImageView(c);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            row.addView(img, new LinearLayout.LayoutParams(Ui.dp(84), Ui.dp(64)));
            PREVIEWS.execute(() -> {
                if (!list.isAttachedToWindow()) return; // The picker closed: skip the rest.
                Drawable d = null;
                try {
                    d = info.loadPreviewImage(c, dpi);
                    if (d == null) d = info.loadIcon(c, dpi);
                } catch (RuntimeException ignored) {
                }
                Drawable done = d;
                MAIN.post(() -> img.setImageDrawable(done));
            });
            String spans = info.targetCellWidth > 0 ? info.targetCellWidth + "×" + info.targetCellHeight : "";
            View labels = r.labels((String) o[2], spans.isEmpty() ? null : spans);
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            ll.leftMargin = Ui.dp(14);
            row.addView(labels, ll);
            row.addView(Ui.text(c, "+", p.onSolid2, 22, Ui.SANS));
            row.setOnClickListener(v -> {
                Ui.confirm(v);
                host.addSystemWidget(info);
            });
            card.addView(row);
        }
    }

    private static String appName(Context c, AppWidgetProviderInfo info) {
        try {
            return String.valueOf(c.getPackageManager().getApplicationLabel(
                    c.getPackageManager().getApplicationInfo(info.provider.getPackageName(), 0)));
        } catch (Exception e) {
            return info.provider.getPackageName();
        }
    }
}
