package com.helm.launcher;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The home screen: pages of freely placed items plus the dock. Saved as JSON in app storage. */
final class Layout {
    int pages = 1;
    final List<Item> items = new ArrayList<>();
    private long nextId = 1;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    long newId() { return nextId++; }

    Item add(Item i) {
        if (i.id == 0) i.id = newId();
        items.add(i);
        return i;
    }

    Item byId(long id) {
        for (Item i : items) if (i.id == id) return i;
        return null;
    }

    List<Item> onPage(int page) {
        List<Item> out = new ArrayList<>();
        for (Item i : items) if (i.page == page) out.add(i);
        return out;
    }

    Item at(int page, int x, int y, Collection<Item> ignore) {
        for (Item i : items) {
            if (i.page != page || (ignore != null && ignore.contains(i))) continue;
            if (x >= i.x && x < i.x + i.w && y >= i.y && y < i.y + i.h) return i;
        }
        return null;
    }

    boolean fits(int page, int x, int y, int w, int h, int cols, int rows, Collection<Item> ignore) {
        if (x < 0 || y < 0 || x + w > cols || y + h > rows) return false;
        for (Item i : items) {
            if (i.page != page || (ignore != null && ignore.contains(i))) continue;
            if (x < i.x + i.w && i.x < x + w && y < i.y + i.h && i.y < y + h) return false;
        }
        return true;
    }

    /** Nearest free w×h spot on {@code page} to (px, py), or null. */
    int[] findVacant(int page, int w, int h, int cols, int rows, Collection<Item> ignore, int px, int py) {
        int[] best = null;
        long bestD = Long.MAX_VALUE;
        for (int y = 0; y + h <= rows; y++) {
            for (int x = 0; x + w <= cols; x++) {
                if (!fits(page, x, y, w, h, cols, rows, ignore)) continue;
                long d = (long) (x - px) * (x - px) + (long) (y - py) * (y - py) * 2;
                if (d < bestD) {
                    bestD = d;
                    best = new int[]{x, y};
                }
            }
        }
        return best;
    }

    /** First free spot from page {@code start} on; adds a page when all are full. Returns {page, x, y}. */
    int[] findAnywhere(int start, int w, int h, int cols, int rows) {
        for (int p = Math.max(0, start); p < pages; p++) {
            int[] v = findVacant(p, w, h, cols, rows, null, 0, 0);
            if (v != null) return new int[]{p, v[0], v[1]};
        }
        for (int p = 0; p < Math.max(0, start); p++) {
            int[] v = findVacant(p, w, h, cols, rows, null, 0, 0);
            if (v != null) return new int[]{p, v[0], v[1]};
        }
        pages++;
        return new int[]{pages - 1, 0, 0};
    }

    /** After a grid size change: shrink what's too big, move what no longer fits. */
    void normalize(int cols, int rows, int dockCols) {
        List<Item> misplaced = new ArrayList<>();
        List<Item> sorted = new ArrayList<>(items);
        Collections.sort(sorted, (a, b) -> a.page != b.page ? Integer.compare(a.page, b.page)
                : a.y != b.y ? Integer.compare(a.y, b.y) : Integer.compare(a.x, b.x));
        items.clear();
        for (Item i : sorted) {
            if (i.inDock()) {
                if (i.x < dockCols && fits(Item.DOCK, i.x, 0, 1, 1, dockCols, 1, null)) items.add(i);
                else misplaced.add(i);
                continue;
            }
            i.w = Math.min(i.w, cols);
            i.h = Math.min(i.h, rows);
            if (fits(i.page, i.x, i.y, i.w, i.h, cols, rows, null)) items.add(i);
            else misplaced.add(i);
        }
        for (Item i : misplaced) {
            if (i.inDock()) {
                i.w = i.h = 1;
            }
            int[] spot = findAnywhere(0, i.w, i.h, cols, rows);
            i.page = spot[0];
            i.x = spot[1];
            i.y = spot[2];
            items.add(i);
        }
        int maxPage = 0;
        for (Item i : items) maxPage = Math.max(maxPage, i.page);
        pages = Math.max(1, Math.max(pages, maxPage + 1));
    }

    /** Drops empty trailing pages (keeps at least one). */
    void trimPages() {
        while (pages > 1 && onPage(pages - 1).isEmpty()) pages--;
    }

    /** Removes an empty page in the middle and shifts later pages down. */
    void removePage(int page) {
        if (pages <= 1 || !onPage(page).isEmpty()) return;
        for (Item i : items) if (i.page > page) i.page--;
        pages--;
    }

    // Persistence

    JSONObject toJson() throws org.json.JSONException {
        JSONObject o = new JSONObject();
        o.put("version", 2).put("pages", pages).put("nextId", nextId);
        JSONArray a = new JSONArray();
        for (Item i : items) a.put(i.toJson());
        o.put("items", a);
        return o;
    }

    static Layout fromJson(JSONObject o) throws org.json.JSONException {
        Layout l = new Layout();
        l.pages = Math.max(1, o.optInt("pages", 1));
        l.nextId = Math.max(1, o.optLong("nextId", 1));
        JSONArray a = o.optJSONArray("items");
        if (a != null) {
            for (int k = 0; k < a.length(); k++) {
                Item i = Item.fromJson(a.getJSONObject(k));
                l.items.add(i);
                l.nextId = Math.max(l.nextId, i.id + 1);
            }
        }
        return l;
    }

    static Layout load(Context c) {
        File f = new File(c.getFilesDir(), "layout.json");
        if (!f.exists()) return null;
        try {
            return fromJson(new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }

    /** Writes off the main thread; the JSON is captured now so later edits can't race the write. */
    void save(Context c) {
        final String json;
        try {
            json = toJson().toString();
        } catch (Exception e) {
            return;
        }
        final File f = new File(c.getFilesDir(), "layout.json");
        final File tmp = new File(c.getFilesDir(), "layout.json.tmp");
        IO.execute(() -> {
            try {
                Files.write(tmp.toPath(), json.getBytes(StandardCharsets.UTF_8));
                if (!tmp.renameTo(f)) Files.write(f.toPath(), json.getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
        });
    }
}
