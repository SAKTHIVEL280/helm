package com.helm.launcher;

import android.graphics.Bitmap;

/** Three passes of a box blur approximate a Gaussian. Run on small (downscaled) bitmaps. */
final class Blur {
    private Blur() {}

    static Bitmap boxBlur(Bitmap src, int radius) {
        Bitmap b = src.copy(Bitmap.Config.ARGB_8888, true);
        if (radius < 1) return b;
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        int[] tmp = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            horizontal(px, tmp, w, h, radius);
            vertical(tmp, px, w, h, radius);
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
        return b;
    }

    private static void horizontal(int[] in, int[] out, int w, int h, int r) {
        int span = r * 2 + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int a = 0, rr = 0, g = 0, bb = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[row + Ui.clamp(i, 0, w - 1)];
                a += c >>> 24; rr += (c >> 16) & 0xFF; g += (c >> 8) & 0xFF; bb += c & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                out[row + x] = ((a / span) << 24) | ((rr / span) << 16) | ((g / span) << 8) | (bb / span);
                int add = in[row + Math.min(w - 1, x + r + 1)];
                int sub = in[row + Math.max(0, x - r)];
                a += (add >>> 24) - (sub >>> 24);
                rr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                g += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                bb += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    private static void vertical(int[] in, int[] out, int w, int h, int r) {
        int span = r * 2 + 1;
        for (int x = 0; x < w; x++) {
            int a = 0, rr = 0, g = 0, bb = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[Ui.clamp(i, 0, h - 1) * w + x];
                a += c >>> 24; rr += (c >> 16) & 0xFF; g += (c >> 8) & 0xFF; bb += c & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = ((a / span) << 24) | ((rr / span) << 16) | ((g / span) << 8) | (bb / span);
                int add = in[Math.min(h - 1, y + r + 1) * w + x];
                int sub = in[Math.max(0, y - r) * w + x];
                a += (add >>> 24) - (sub >>> 24);
                rr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                g += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                bb += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }
}
