package com.helm.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Drag and drop on home. A long-press arms a drag; moving past touch slop lifts the item (or a
 * selected group). Drop on a free cell to place, on an app to make a folder, on a folder to add,
 * at the screen edge to change page, or on Remove at the top.
 */
final class DragController {
    interface Host {
        Layout layout();

        Workspace workspace();

        CellLayout dock();

        boolean dockEnabled();

        int cols();

        int rows();

        View viewFor(Item i);

        RectF removeZone();

        void showRemove(boolean show, boolean hot);

        void dragStarted(int source);

        void dragEnded();

        void addPageForDrag();

        void deleteItems(List<Item> items);

        void removeFromFolder(Item folder, String appKey);

        void commit();

        String folderTitle(String appA, String appB);
    }

    static final int SRC_HOME = 0, SRC_DRAWER = 1, SRC_FOLDER = 2;
    private static final int M_NONE = 0, M_PLACE = 1, M_FOLDER = 2, M_REMOVE = 3;
    private static final float LIFT = 1.08f;

    private final HomeRoot root;
    private final int slop;
    private Host host;

    // Armed (long-pressed, not yet moved)
    private boolean armed, active;
    private int source;
    private final List<Item> group = new ArrayList<>();
    private Item grabbed, fromFolder;
    private View grabbedView;
    private float armX, armY;

    // Active
    private Bitmap shadow;
    private float shadowScale = 1f, grabX, grabY, x, y;
    private final Paint shadowPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int mode = M_NONE;
    private boolean toDock, cancelled;
    private int targetPage, targetX, targetY;
    private Item folderCandidate;
    private long folderSince, edgeSince;
    private final Runnable pulse = new Runnable() {
        @Override
        public void run() {
            if (!active) return;
            update();
            root.postDelayed(this, 120);
        }
    };

    DragController(HomeRoot root) {
        this.root = root;
        slop = ViewConfiguration.get(root.getContext()).getScaledTouchSlop();
        badgeText.setTextAlign(Paint.Align.CENTER);
        badgeText.setTypeface(Ui.MONO_MEDIUM);
        badgeText.setTextSize(Ui.sp(12));
    }

    void setHost(Host h) { host = h; }

    boolean armed() { return armed; }

    boolean active() { return active; }

    boolean busy() { return armed || active; }

    /** Called from a long-press. The drag begins only if the finger then moves. */
    void arm(int src, List<Item> items, Item grab, View view, Item folder) {
        if (host == null || active) return;
        armed = true;
        source = src;
        group.clear();
        group.addAll(items);
        grabbed = grab;
        grabbedView = view;
        fromFolder = folder;
        armX = root.lastX;
        armY = root.lastY;
    }

    void disarm() {
        if (!active) armed = false;
    }

    boolean shouldStart(float px, float py) {
        return Math.hypot(px - armX, py - armY) > slop * 1.5f;
    }

    void begin(float px, float py) {
        armed = false;
        active = true;
        View v = grabbedView;
        if (v instanceof AppIconView) ((AppIconView) v).settle();
        int w = Math.max(1, v.getWidth()), h = Math.max(1, v.getHeight());
        shadow = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(shadow);
        v.draw(c);
        float[] o = Ui.toRoot(v, 0, 0);
        float[] o2 = Ui.toRoot(v, w, h);
        shadowScale = Math.max(0.2f, (o2[0] - o[0]) / w);
        grabX = armX - o[0];
        grabY = armY - o[1];
        x = px;
        y = py;
        if (source == SRC_HOME) {
            for (Item i : group) {
                View iv = host.viewFor(i);
                if (iv != null) iv.setAlpha(0f);
            }
        }
        host.workspace().setLocked(true);
        host.dragStarted(source);
        Ui.tick(root);
        root.post(pulse);
        update();
        root.invalidate();
    }

    void onTouch(MotionEvent ev) {
        x = ev.getX();
        y = ev.getY();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                update();
                root.invalidate();
                break;
            case MotionEvent.ACTION_UP:
                drop();
                break;
            case MotionEvent.ACTION_CANCEL:
                // Interrupted (a system gesture): put everything back as it was.
                mode = M_NONE;
                cancelled = true;
                drop();
                break;
            default:
                break;
        }
    }

    private boolean dockable() {
        for (Item i : group) if (i.w != 1 || i.h != 1 || i.isWidget()) return false;
        return true;
    }

    private void clearPreviews() {
        Workspace ws = host.workspace();
        for (int i = 0; i < ws.count(); i++) ws.page(i).clearPreview();
        if (host.dock() != null) host.dock().clearPreview();
    }

    private void update() {
        clearPreviews();
        Layout layout = host.layout();
        boolean single = group.size() == 1;
        boolean canFolder = single && grabbed.type == Item.APP;
        RectF remove = host.removeZone();
        if (source != SRC_DRAWER && remove != null && remove.contains(x, y)) {
            mode = M_REMOVE;
            host.showRemove(true, true);
            return;
        }
        host.showRemove(source != SRC_DRAWER, false);

        CellLayout dock = host.dock();
        if (host.dockEnabled() && dock != null && dockable()) {
            float[] dockTop = Ui.toRoot(dock, 0, 0);
            if (y >= dockTop[1]) {
                toDock = true;
                float[] l = Ui.toLocal(dock, x, y);
                int col = dock.cellAt(l[0], l[1])[0];
                Item occ = layout.at(Item.DOCK, col, 0, group);
                if (occ != null && canFolder && (occ.type == Item.APP || occ.type == Item.FOLDER)) {
                    if (dwellFolder(occ)) {
                        dock.setPreview(occ.x, 0, 1, 1, CellLayout.PREVIEW_FOLDER);
                        return;
                    }
                }
                int[] spot = occ == null ? new int[]{col, 0}
                        : layout.findVacant(Item.DOCK, 1, 1, dock.cols, 1, group, col, 0);
                if (spot == null || group.size() > dockFree(layout, dock.cols)) {
                    mode = M_NONE;
                    return;
                }
                mode = M_PLACE;
                targetPage = Item.DOCK;
                targetX = spot[0];
                targetY = 0;
                dock.setPreview(targetX, 0, 1, 1, CellLayout.PREVIEW_PLACE);
                return;
            }
        }
        toDock = false;
        folderCandidate = null;

        Workspace ws = host.workspace();
        float edge = Ui.dpf(28);
        long now = SystemClock.uptimeMillis();
        if (x < edge || x > root.getWidth() - edge) {
            if (edgeSince == 0) edgeSince = now;
            else if (now - edgeSince > 600) {
                edgeSince = now;
                int next = ws.current() + (x < edge ? -1 : 1);
                if (next >= ws.count() && !layout.onPage(ws.current()).isEmpty()) host.addPageForDrag();
                if (next >= 0 && next < ws.count()) ws.snapTo(next, true);
            }
        } else {
            edgeSince = 0;
        }

        CellLayout page = ws.currentPage();
        if (page == null) {
            mode = M_NONE;
            return;
        }
        int[] origin;
        if (grabbed.w == 1 && grabbed.h == 1) {
            float[] l = Ui.toLocal(page, x, y);
            origin = page.cellAt(l[0], l[1]);
            Item occ = layout.at(page.page, origin[0], origin[1], group);
            if (occ != null && canFolder && (occ.type == Item.APP || (occ.type == Item.FOLDER && occ.w == 1))) {
                // A big (2×2) icon is a drop target across its whole area, not just its top-left cell.
                Rect r = page.cellRect(occ.x, occ.y, occ.w, occ.h);
                float dx = l[0] - r.exactCenterX(), dy = l[1] - r.exactCenterY();
                if (Math.hypot(dx, dy) < page.cellW() * 0.34f * occ.w && dwellFolder(occ)) {
                    page.setPreview(occ.x, occ.y, occ.w, occ.h, CellLayout.PREVIEW_FOLDER);
                    return;
                }
            }
        } else {
            float[] l = Ui.toLocal(page, x - grabX, y - grabY);
            origin = page.originFor(l[0], l[1], grabbed.w, grabbed.h);
        }
        folderSince = 0;
        int cols = host.cols(), rows = host.rows();
        int[] spot = layout.fits(page.page, origin[0], origin[1], grabbed.w, grabbed.h, cols, rows, group)
                ? origin : layout.findVacant(page.page, grabbed.w, grabbed.h, cols, rows, group, origin[0], origin[1]);
        if (spot == null) {
            mode = M_NONE;
            return;
        }
        mode = M_PLACE;
        targetPage = page.page;
        targetX = spot[0];
        targetY = spot[1];
        page.setPreview(targetX, targetY, grabbed.w, grabbed.h, CellLayout.PREVIEW_PLACE);
    }

    private int dockFree(Layout layout, int cols) {
        int free = 0;
        for (int c = 0; c < cols; c++) if (layout.at(Item.DOCK, c, 0, group) == null) free++;
        return free;
    }

    /** True once the finger has rested on {@code occ} long enough to mean "make a folder". */
    private boolean dwellFolder(Item occ) {
        long now = SystemClock.uptimeMillis();
        if (folderCandidate != occ) {
            folderCandidate = occ;
            folderSince = now;
            return false;
        }
        if (now - folderSince >= 260) {
            if (mode != M_FOLDER) Ui.tick(root);
            mode = M_FOLDER;
            return true;
        }
        return false;
    }

    private void drop() {
        root.removeCallbacks(pulse);
        clearPreviews();
        host.showRemove(false, false);
        Layout layout = host.layout();
        int cols = host.cols(), rows = host.rows();
        boolean changed = true;
        switch (mode) {
            case M_REMOVE:
                if (source == SRC_HOME) host.deleteItems(new ArrayList<>(group));
                else if (source == SRC_FOLDER && fromFolder != null) host.removeFromFolder(fromFolder, grabbed.app);
                Ui.confirm(root);
                break;
            case M_FOLDER: {
                Item occ = folderCandidate;
                // Back onto its own folder, or onto its own icon: nothing to do.
                if (occ == null || grabbed.app == null || occ == fromFolder
                        || (occ.type == Item.APP && grabbed.app.equals(occ.app))) {
                    changed = false;
                    break;
                }
                if (occ.type == Item.APP) {
                    Item f = new Item();
                    f.type = Item.FOLDER;
                    f.title = host.folderTitle(occ.app, grabbed.app);
                    f.apps.add(occ.app);
                    if (!grabbed.app.equals(occ.app)) f.apps.add(grabbed.app);
                    f.copyPlacement(occ);
                    layout.items.remove(occ);
                    layout.add(f);
                } else if (!occ.apps.contains(grabbed.app)) {
                    occ.apps.add(grabbed.app);
                }
                if (source == SRC_HOME) layout.items.remove(grabbed);
                if (source == SRC_FOLDER && fromFolder != null) host.removeFromFolder(fromFolder, grabbed.app);
                Ui.confirm(root);
                break;
            }
            case M_PLACE: {
                if (source != SRC_HOME) layout.add(grabbed);
                grabbed.page = targetPage;
                grabbed.x = targetX;
                grabbed.y = targetY;
                Set<Item> placed = new HashSet<>();
                placed.add(grabbed);
                for (Item o : group) {
                    if (o == grabbed) continue;
                    List<Item> ignore = new ArrayList<>(group);
                    ignore.removeAll(placed);
                    int[] spot = toDock
                            ? layout.findVacant(Item.DOCK, 1, 1, host.dock().cols, 1, ignore, targetX, 0)
                            : layout.findVacant(targetPage, o.w, o.h, cols, rows, ignore, targetX, targetY);
                    if (spot != null) {
                        o.page = targetPage;
                        o.x = spot[0];
                        o.y = spot[1];
                    } else {
                        int[] any = layout.findAnywhere(Math.max(0, targetPage), o.w, o.h, cols, rows);
                        o.page = any[0];
                        o.x = any[1];
                        o.y = any[2];
                    }
                    placed.add(o);
                }
                if (source == SRC_FOLDER && fromFolder != null) host.removeFromFolder(fromFolder, grabbed.app);
                break;
            }
            default:
                if (source == SRC_HOME || cancelled) {
                    changed = false;
                } else {
                    // A new item dropped nowhere useful still lands somewhere sensible.
                    int[] any = layout.findAnywhere(host.workspace().current(), grabbed.w, grabbed.h, cols, rows);
                    grabbed.page = any[0];
                    grabbed.x = any[1];
                    grabbed.y = any[2];
                    layout.add(grabbed);
                    if (source == SRC_FOLDER && fromFolder != null) host.removeFromFolder(fromFolder, grabbed.app);
                }
                break;
        }
        final float sx = x - grabX, sy = y - grabY;
        final Item landed = grabbed;
        final boolean animate = mode == M_PLACE || !changed;
        active = false;
        shadow = null;
        host.workspace().setLocked(false);
        if (changed) host.commit();
        for (Item i : group) {
            View iv = host.viewFor(i);
            if (iv != null) iv.setAlpha(1f);
        }
        if (animate && !Ui.motionOff()) {
            root.post(() -> {
                View v = host.viewFor(landed);
                if (v == null) return;
                float[] at = Ui.toRoot(v, 0, 0);
                float k = Math.max(0.01f, shadowScale);
                v.setTranslationX((sx - at[0]) / k);
                v.setTranslationY((sy - at[1]) / k);
                v.setScaleX(LIFT);
                v.setScaleY(LIFT);
                v.animate().translationX(0).translationY(0).scaleX(1f).scaleY(1f)
                        .setDuration(Ui.ms(260)).setInterpolator(Ui.EASE).start();
            });
        }
        group.clear();
        folderCandidate = null;
        mode = M_NONE;
        cancelled = false;
        host.dragEnded();
        root.invalidate();
    }

    void draw(Canvas c) {
        if (!active || shadow == null) return;
        float w = shadow.getWidth() * shadowScale, h = shadow.getHeight() * shadowScale;
        float left = x - grabX, top = y - grabY;
        c.save();
        c.translate(left, top);
        c.scale(shadowScale * LIFT, shadowScale * LIFT, shadow.getWidth() / 2f, shadow.getHeight() / 2f);
        shadowPaint.setAlpha(mode == M_REMOVE ? 120 : 240);
        if (group.size() > 1) {
            // A stack: two offset ghosts behind the grabbed item.
            shadowPaint.setAlpha(70);
            c.drawBitmap(shadow, Ui.dpf(10), Ui.dpf(8), shadowPaint);
            shadowPaint.setAlpha(140);
            c.drawBitmap(shadow, Ui.dpf(5), Ui.dpf(4), shadowPaint);
            shadowPaint.setAlpha(240);
        }
        c.drawBitmap(shadow, 0, 0, shadowPaint);
        c.restore();
        if (group.size() > 1) {
            float bx = left + w * LIFT - Ui.dpf(6), by = top + Ui.dpf(6);
            badge.setColor(Palette.RED);
            c.drawCircle(bx, by, Ui.dpf(12), badge);
            badgeText.setColor(0xFFFFFFFF);
            c.drawText(String.valueOf(group.size()), bx, by + Ui.sp(4), badgeText);
        }
    }
}
