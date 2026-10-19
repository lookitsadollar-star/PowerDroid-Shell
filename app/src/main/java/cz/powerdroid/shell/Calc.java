package cz.powerdroid.shell;

/** Malý vyhodnocovač aritmetických výrazů: + - * / % ( ) a jednotky KB/MB/GB. */
final class Calc {
    private final String s; private int p;
    private Calc(String s) { this.s = s; }

    static Double tryEval(String expr) {
        String e = expr.trim();
        if (e.isEmpty() || !e.matches("[0-9.()+\\-*/%\\sKMGTBkmgtb]+")) return null;
        if (!e.matches(".*[0-9].*")) return null;
        try {
            Calc c = new Calc(e);
            double v = c.expr();
            c.ws();
            return c.p == c.s.length() ? v : null;
        } catch (Exception ex) { return null; }
    }

    private void ws() { while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++; }
    private boolean eat(char c) { ws(); if (p < s.length() && s.charAt(p) == c) { p++; return true; } return false; }

    private double expr() {
        double v = term();
        while (true) { if (eat('+')) v += term(); else if (eat('-')) v -= term(); else return v; }
    }
    private double term() {
        double v = factor();
        while (true) {
            if (eat('*')) v *= factor(); else if (eat('/')) v /= factor(); else if (eat('%')) v %= factor(); else return v;
        }
    }
    private double factor() {
        if (eat('-')) return -factor();
        if (eat('+')) return factor();
        if (eat('(')) { double v = expr(); if (!eat(')')) throw new IllegalStateException(); return v; }
        ws();
        int st = p;
        while (p < s.length() && (Character.isDigit(s.charAt(p)) || s.charAt(p) == '.')) p++;
        if (st == p) throw new IllegalStateException();
        double v = Double.parseDouble(s.substring(st, p));
        if (p + 1 < s.length() && Character.toUpperCase(s.charAt(p + 1)) == 'B') {
            char u = Character.toUpperCase(s.charAt(p));
            double mul = u == 'K' ? 1024d : u == 'M' ? 1048576d : u == 'G' ? 1073741824d : u == 'T' ? 1099511627776d : 0;
            if (mul > 0) { v *= mul; p += 2; }
        }
        return v;
    }
}
