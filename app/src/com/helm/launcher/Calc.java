package com.helm.launcher;

/** A small calculator for the search bar: + − × ÷ % ^, brackets, decimals. */
final class Calc {
    private final String s;
    private int pos;

    private Calc(String s) { this.s = s; }

    /** The value, or null when the text isn't a calculation (a lone number doesn't count). */
    static Double eval(String input) {
        String t = input.replace('×', '*').replace('x', '*').replace('÷', '/').replace(',', '.').replace(" ", "");
        if (t.isEmpty() || !t.matches("[0-9.+\\-*/%^()]+") || !t.matches(".*[0-9].*[+\\-*/%^].*|.*[+\\-*/%^].*[0-9].*")) return null;
        try {
            Calc c = new Calc(t);
            double v = c.expr();
            if (c.pos != t.length() || Double.isNaN(v) || Double.isInfinite(v)) return null;
            return v;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String format(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.valueOf((long) v);
        String r = String.format(java.util.Locale.US, "%.8f", v).replaceAll("0+$", "");
        return r.endsWith(".") ? r.substring(0, r.length() - 1) : r;
    }

    private double expr() {
        double v = term();
        while (pos < s.length()) {
            char ch = s.charAt(pos);
            if (ch == '+') { pos++; v += term(); }
            else if (ch == '-') { pos++; v -= term(); }
            else break;
        }
        return v;
    }

    private double term() {
        double v = factor();
        while (pos < s.length()) {
            char ch = s.charAt(pos);
            if (ch == '*') { pos++; v *= factor(); }
            else if (ch == '/') { pos++; v /= factor(); }
            else if (ch == '%') { pos++; v = pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '(') ? v % factor() : v / 100.0; }
            else break;
        }
        return v;
    }

    private double factor() {
        double base = unary();
        if (pos < s.length() && s.charAt(pos) == '^') {
            pos++;
            return Math.pow(base, factor());
        }
        return base;
    }

    private double unary() {
        if (pos < s.length() && s.charAt(pos) == '-') {
            pos++;
            return -unary();
        }
        if (pos < s.length() && s.charAt(pos) == '+') {
            pos++;
            return unary();
        }
        if (pos < s.length() && s.charAt(pos) == '(') {
            pos++;
            double v = expr();
            if (pos >= s.length() || s.charAt(pos) != ')') throw new IllegalStateException();
            pos++;
            return v;
        }
        int start = pos;
        while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) pos++;
        if (start == pos) throw new IllegalStateException();
        return Double.parseDouble(s.substring(start, pos));
    }
}
