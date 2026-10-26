package com.helm.launcher;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.SparseArray;

/**
 * A 5x7 dot-matrix face drawn as round dots, in the spirit of Nothing's Ndot. Original glyphs:
 * Nothing's fonts are proprietary, so none of their files are used.
 */
final class DotFont {
    static final int ROWS = 7;

    /** Dot diameter as a share of the pitch. Ndot's dots nearly touch. */
    private static final float DOT = 0.84f;

    private static final SparseArray<boolean[][]> GLYPHS = new SparseArray<>();

    static {
        def('0', ".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###.");
        def('1', "..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###.");
        def('2', ".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####");
        def('3', "#####", "...#.", "..#..", "...#.", "....#", "#...#", ".###.");
        def('4', "...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#.");
        def('5', "#####", "#....", "####.", "....#", "....#", "#...#", ".###.");
        def('6', "..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###.");
        def('7', "#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#...");
        def('8', ".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###.");
        def('9', ".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##..");
        def('A', ".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#");
        def('B', "####.", "#...#", "#...#", "####.", "#...#", "#...#", "####.");
        def('C', ".###.", "#...#", "#....", "#....", "#....", "#...#", ".###.");
        def('D', "####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####.");
        def('E', "#####", "#....", "#....", "####.", "#....", "#....", "#####");
        def('F', "#####", "#....", "#....", "####.", "#....", "#....", "#....");
        def('G', ".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".####");
        def('H', "#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#");
        def('I', ".###.", "..#..", "..#..", "..#..", "..#..", "..#..", ".###.");
        def('J', "..###", "...#.", "...#.", "...#.", "...#.", "#..#.", ".##..");
        def('K', "#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#");
        def('L', "#....", "#....", "#....", "#....", "#....", "#....", "#####");
        def('M', "#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#");
        def('N', "#...#", "#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#");
        def('O', ".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###.");
        def('P', "####.", "#...#", "#...#", "####.", "#....", "#....", "#....");
        def('Q', ".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#");
        def('R', "####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#");
        def('S', ".####", "#....", "#....", ".###.", "....#", "....#", "####.");
        def('T', "#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#..");
        def('U', "#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###.");
        def('V', "#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#..");
        def('W', "#...#", "#...#", "#...#", "#.#.#", "#.#.#", "#.#.#", ".#.#.");
        def('X', "#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#");
        def('Y', "#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#..");
        def('Z', "#####", "....#", "...#.", "..#..", ".#...", "#....", "#####");
        def(':', ".", ".", "#", ".", "#", ".", ".");
        def('.', ".", ".", ".", ".", ".", ".", "#");
        def('-', "...", "...", "...", "###", "...", "...", "...");
        def(' ', "...", "...", "...", "...", "...", "...", "...");
        def('%', "##...", "##..#", "...#.", "..#..", ".#...", "#..##", "...##");
        def('#', ".#.#.", ".#.#.", "#####", ".#.#.", "#####", ".#.#.", ".#.#.");
        def('?', ".###.", "#...#", "....#", "...#.", "..#..", ".....", "..#..");
        def('°', ".#.", "#.#", ".#.", "...", "...", "...", "...");
        def('\'', "#", "#", ".", ".", ".", ".", ".");
        def('/', "....#", "....#", "...#.", "..#..", ".#...", "#....", "#....");
        def('+', ".....", "..#..", "..#..", "#####", "..#..", "..#..", ".....");
        def('!', "#", "#", "#", "#", "#", ".", "#");
        def(',', "..", "..", "..", "..", "..", ".#", "#.");
    }

    private DotFont() {}

    private static void def(char c, String... rows) {
        boolean[][] g = new boolean[ROWS][rows[0].length()];
        for (int r = 0; r < ROWS; r++) {
            for (int x = 0; x < rows[r].length(); x++) g[r][x] = rows[r].charAt(x) == '#';
        }
        GLYPHS.put(c, g);
    }

    static boolean has(char c) {
        return GLYPHS.get(Character.toUpperCase(c)) != null;
    }

    static boolean[][] glyph(char c) {
        boolean[][] g = GLYPHS.get(Character.toUpperCase(c));
        return g != null ? g : GLYPHS.get('?');
    }

    static int width(char c) { return glyph(c)[0].length; }

    /** Width in dot columns, including one blank column between glyphs. */
    static int columns(CharSequence s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (i > 0) n++;
            n += width(s.charAt(i));
        }
        return n;
    }

    /**
     * Draws {@code s} with its top-left at (x, y). {@code off} (nullable) paints the unlit
     * dots of the matrix; characters in {@code accentChars} use {@code accent}.
     */
    static void draw(Canvas canvas, CharSequence s, float x, float y, float pitch,
                     Paint on, Paint off, Paint accent, String accentChars) {
        float radius = pitch * DOT / 2f;
        float half = pitch / 2f;
        int col = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (i > 0) {
                if (off != null) {
                    for (int row = 0; row < ROWS; row++) {
                        canvas.drawCircle(x + col * pitch + half, y + row * pitch + half, radius, off);
                    }
                }
                col++;
            }
            boolean[][] g = glyph(ch);
            Paint lit = accent != null && accentChars != null && accentChars.indexOf(ch) >= 0 ? accent : on;
            for (int row = 0; row < ROWS; row++) {
                for (int c = 0; c < g[row].length; c++) {
                    Paint p = g[row][c] ? lit : off;
                    if (p == null) continue;
                    canvas.drawCircle(x + (col + c) * pitch + half, y + row * pitch + half, radius, p);
                }
            }
            col += g[0].length;
        }
    }
}
