package cz.powerdroid.shell;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Jednoduchý interpret jazyka podobného PowerShellu. */
public class Shell {

    public interface Output {
        void write(String s);
        void writeColor(String s, int color);
        void error(String s);
        void clear();
        void done();
        void exit();
    }

    public static class Args {
        public final List<String> pos = new ArrayList<>();
        public final Map<String, String> named = new HashMap<>();
        public final java.util.Set<String> switches = new java.util.HashSet<>();
        public String block; // obsah { ... }

        public String get(String name, int position) {
            String v = named.get(name.toLowerCase(Locale.ROOT));
            if (v != null) return v;
            return position >= 0 && position < pos.size() ? pos.get(position) : null;
        }
        public String get(String name, int position, String def) { String v = get(name, position); return v == null ? def : v; }
        public boolean has(String sw) { return switches.contains(sw.toLowerCase(Locale.ROOT)); }
        public String rest(int from) {
            if (from >= pos.size()) return "";
            return String.join(" ", pos.subList(from, pos.size()));
        }
    }

    public interface Handler { List<String> run(Args a, List<String> in) throws Exception; }

    public static class Cmd {
        final String name, syntax, help;
        final String[] aliases, switches;
        final Handler h;
        Cmd(String name, String[] aliases, String[] switches, String syntax, String help, Handler h) {
            this.name = name; this.aliases = aliases; this.switches = switches; this.syntax = syntax; this.help = help; this.h = h;
        }
    }

    static class Tok {
        final String text; final boolean quoted; final boolean block;
        Tok(String t, boolean q, boolean b) { text = t; quoted = q; block = b; }
    }

    final MainActivity act;
    final Output out;
    final Map<String, Cmd> cmds = new LinkedHashMap<>();
    final Map<String, String> aliases = new HashMap<>();
    final Map<String, String> vars = new HashMap<>();
    final List<String> history = new ArrayList<>();
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    volatile boolean cancelled;
    volatile Process current;
    File cwd;

    public Shell(MainActivity act, Output out) {
        this.act = act; this.out = out;
        File sd = android.os.Environment.getExternalStorageDirectory();
        cwd = (sd != null && sd.canRead()) ? sd : act.getFilesDir();
        vars.put("home", cwd.getAbsolutePath());
        vars.put("apphome", act.getFilesDir().getAbsolutePath());
        vars.put("psversion", "PowerDroid 1.0");
        vars.put("true", "True");
        vars.put("false", "False");
        vars.put("null", "");
        Cmdlets.register(this);
        File profile = new File(act.getFilesDir(), "profile.ps1");
        if (profile.exists()) execute(". \"" + profile.getAbsolutePath() + "\"");
    }

    void add(String name, String[] al, String[] sw, String syntax, String help, Handler h) {
        Cmd c = new Cmd(name, al, sw, syntax, help, h);
        cmds.put(name.toLowerCase(Locale.ROOT), c);
        for (String a : al) aliases.put(a.toLowerCase(Locale.ROOT), name.toLowerCase(Locale.ROOT));
    }

    public String prompt() {
        String p = cwd.getAbsolutePath();
        String home = vars.get("home");
        if (home != null && p.startsWith(home)) p = "~" + p.substring(home.length());
        return "PS " + p + "> ";
    }

    public void addHistory(String s) { history.add(s); }

    public void cancel() {
        cancelled = true;
        Process p = current;
        if (p != null) p.destroy();
    }

    public void shutdown() { cancel(); exec.shutdownNow(); }

    public void execute(String line) {
        exec.submit(() -> {
            cancelled = false;
            try {
                List<String> res = runScript(line, Collections.emptyList());
                print(res);
            } catch (ExitException e) {
                out.exit();
            } catch (Throwable e) {
                out.error(errMsg(e));
            } finally {
                out.done();
            }
        });
    }

    static String errMsg(Throwable e) {
        String m = e.getMessage();
        return (m == null || m.isEmpty()) ? e.getClass().getSimpleName() : m;
    }

    void print(List<String> res) {
        if (res == null || res.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (String s : res) sb.append(s).append('\n');
        out.write(sb.toString());
    }

    static class ExitException extends RuntimeException {}

    /** Spustí více příkazů oddělených ; nebo novým řádkem. */
    List<String> runScript(String script, List<String> input) throws Exception {
        List<String> all = new ArrayList<>();
        for (String stmt : split(script, new char[]{';', '\n'})) {
            if (cancelled) break;
            String s = stripComment(stmt).trim();
            if (s.isEmpty()) continue;
            List<String> r = runStatement(s, input);
            if (r != null) all.addAll(r);
        }
        return all;
    }

    private static final Pattern ASSIGN = Pattern.compile("^\\$([A-Za-z_][A-Za-z0-9_]*)\\s*(\\+?=)\\s*(.*)$", Pattern.DOTALL);

    List<String> runStatement(String s, List<String> input) throws Exception {
        Matcher m = ASSIGN.matcher(s);
        if (m.matches() && !m.group(3).startsWith("=")) {
            String name = m.group(1).toLowerCase(Locale.ROOT);
            String rhs = m.group(3).trim();
            String val = String.join("\n", evaluate(rhs, input));
            if (m.group(2).equals("+=")) {
                String old = vars.getOrDefault(name, "");
                Double a = num(old), b = num(val);
                val = (a != null && b != null) ? fmt(a + b) : old + val;
            }
            vars.put(name, val);
            return Collections.emptyList();
        }
        return evaluate(s, input);
    }

    /** Vyhodnotí výraz: pipeline příkazů, řetězec, proměnnou nebo aritmetiku. */
    List<String> evaluate(String s, List<String> input) throws Exception {
        List<String> stages = split(s, new char[]{'|'});
        List<String> data = input;
        for (int i = 0; i < stages.size(); i++) {
            if (cancelled) return Collections.emptyList();
            data = runStage(stages.get(i).trim(), data, i == 0);
        }
        return data;
    }

    private List<String> runStage(String stage, List<String> in, boolean first) throws Exception {
        if (stage.isEmpty()) return in;
        List<Tok> toks = tokenize(stage);
        if (toks.isEmpty()) return in;
        Tok t0 = toks.get(0);

        // Literály a výrazy
        if (toks.size() == 1 && t0.quoted) return lines(t0.text);
        if (!t0.quoted && !t0.block) {
            String raw = t0.text;
            if (raw.startsWith("$") || raw.startsWith("(") || raw.matches("^-?[0-9].*")) {
                String expanded = expand(stage.trim(), true);
                Double n = Calc.tryEval(expanded);
                if (n != null) return lines(fmt(n));
                if (toks.size() == 1) return lines(expand(raw, false));
                return lines(expanded);
            }
        }

        String name = t0.text;
        boolean dotSource = false;
        if ((name.equals("&") || name.equals(".")) && toks.size() > 1) {
            dotSource = true; toks = toks.subList(1, toks.size()); name = toks.get(0).text;
        }
        String key = name.toLowerCase(Locale.ROOT);
        if (aliases.containsKey(key)) key = aliases.get(key);
        Cmd c = cmds.get(key);

        // skript .ps1
        if (c == null && (dotSource || key.endsWith(".ps1"))) {
            File f = resolve(expand(name, false));
            if (f.exists()) return runScript(Cmdlets.readText(f), in);
        }
        if (c == null) {
            // neznámý příkaz -> Linux shell (toybox)
            return Cmdlets.runProcess(this, stage, in, false);
        }
        Args a = parseArgs(toks.subList(1, toks.size()), c);
        return c.h.run(a, in == null ? Collections.emptyList() : in);
    }

    Args parseArgs(List<Tok> toks, Cmd c) {
        Args a = new Args();
        java.util.Set<String> sw = new java.util.HashSet<>();
        for (String s : c.switches) sw.add(s.toLowerCase(Locale.ROOT));
        for (int i = 0; i < toks.size(); i++) {
            Tok t = toks.get(i);
            if (t.block) { a.block = t.text; continue; }
            String x = t.text;
            if (!t.quoted && x.length() > 1 && x.charAt(0) == '-' && !Character.isDigit(x.charAt(1))) {
                String n = x.substring(1).toLowerCase(Locale.ROOT);
                if (n.endsWith(":")) n = n.substring(0, n.length() - 1);
                if (sw.contains(n)) { a.switches.add(n); continue; }
                if (i + 1 < toks.size()) {
                    Tok v = toks.get(++i);
                    if (v.block) a.block = v.text;
                    a.named.put(n, v.quoted ? v.text : expand(v.text, false));
                } else a.switches.add(n);
                continue;
            }
            a.pos.add(t.quoted ? t.text : expand(x, false));
        }
        return a;
    }

    /** Rozdělí text podle oddělovačů mimo uvozovky a závorky. */
    static List<String> split(String s, char[] seps) {
        List<String> r = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0; char q = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (q != 0) { cur.append(ch); if (ch == q) q = 0; else if (ch == '`' && i + 1 < s.length()) cur.append(s.charAt(++i)); continue; }
            if (ch == '"' || ch == '\'') { q = ch; cur.append(ch); continue; }
            if (ch == '{' || ch == '(') depth++;
            if (ch == '}' || ch == ')') depth--;
            boolean sep = false;
            for (char c : seps) if (c == ch) sep = true;
            if (sep && depth <= 0) { r.add(cur.toString()); cur.setLength(0); } else cur.append(ch);
        }
        r.add(cur.toString());
        return r;
    }

    static String stripComment(String s) {
        char q = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (q != 0) { if (ch == q) q = 0; continue; }
            if (ch == '"' || ch == '\'') q = ch;
            else if (ch == '#' && (i == 0 || Character.isWhitespace(s.charAt(i - 1)))) return s.substring(0, i);
        }
        return s;
    }

    List<Tok> tokenize(String s) {
        List<Tok> r = new ArrayList<>();
        int i = 0, n = s.length();
        while (i < n) {
            char ch = s.charAt(i);
            if (Character.isWhitespace(ch)) { i++; continue; }
            if (ch == '"' || ch == '\'') {
                StringBuilder sb = new StringBuilder();
                char q = ch; i++;
                while (i < n && s.charAt(i) != q) {
                    char c = s.charAt(i);
                    if (c == '`' && q == '"' && i + 1 < n) {
                        char e = s.charAt(++i);
                        sb.append(e == 'n' ? '\n' : e == 't' ? '\t' : e);
                    } else sb.append(c);
                    i++;
                }
                i++;
                r.add(new Tok(q == '"' ? expand(sb.toString(), false) : sb.toString(), true, false));
                continue;
            }
            if (ch == '{') {
                int depth = 0, st = i;
                while (i < n) {
                    char c = s.charAt(i);
                    if (c == '{') depth++;
                    else if (c == '}') { depth--; if (depth == 0) break; }
                    i++;
                }
                r.add(new Tok(s.substring(st + 1, Math.min(i, n)).trim(), false, true));
                i++;
                continue;
            }
            StringBuilder sb = new StringBuilder();
            int depth = 0;
            while (i < n && (depth > 0 || !Character.isWhitespace(s.charAt(i)))) {
                char c = s.charAt(i);
                if (c == '(') depth++; else if (c == ')') depth--;
                sb.append(c); i++;
            }
            r.add(new Tok(sb.toString(), false, false));
        }
        return r;
    }

    private static final Pattern VAR = Pattern.compile("\\$\\{([^}]+)\\}|\\$([A-Za-z_][A-Za-z0-9_]*|_)");
    private static final Pattern SUBEXPR = Pattern.compile("\\$\\(([^()]*(?:\\([^()]*\\)[^()]*)*)\\)");

    /** Nahradí $promenna a $(vyraz) hodnotami. */
    String expand(String s, boolean forMath) {
        if (s.indexOf('$') < 0) return s;
        Matcher sm = SUBEXPR.matcher(s);
        StringBuffer sb0 = new StringBuffer();
        while (sm.find()) {
            String v;
            try { v = String.join(" ", evaluate(sm.group(1), Collections.emptyList())); } catch (Exception e) { v = ""; }
            sm.appendReplacement(sb0, Matcher.quoteReplacement(v));
        }
        sm.appendTail(sb0);
        Matcher m = VAR.matcher(sb0.toString());
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String name = (m.group(1) != null ? m.group(1) : m.group(2)).toLowerCase(Locale.ROOT);
            String v;
            if (name.startsWith("env:")) v = System.getenv(name.substring(4).toUpperCase(Locale.ROOT));
            else if (name.equals("pwd")) v = cwd.getAbsolutePath();
            else v = vars.get(name);
            if (v == null) v = forMath ? "0" : "";
            m.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    File resolve(String p) {
        if (p == null || p.isEmpty()) return cwd;
        if (p.equals("~")) return new File(vars.get("home"));
        if (p.startsWith("~/")) return new File(vars.get("home"), p.substring(2));
        File f = new File(p);
        if (!f.isAbsolute()) f = new File(cwd, p);
        try { return f.getCanonicalFile(); } catch (Exception e) { return f.getAbsoluteFile(); }
    }

    public List<String> complete(String prefix, boolean command) {
        TreeSet<String> r = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        String lp = prefix.toLowerCase(Locale.ROOT);
        if (command) {
            for (Cmd c : cmds.values()) if (c.name.toLowerCase(Locale.ROOT).startsWith(lp)) r.add(c.name);
        }
        if (prefix.startsWith("$")) {
            for (String v : vars.keySet()) if (("$" + v).startsWith(lp)) r.add("$" + v);
        }
        if (!command || r.isEmpty()) {
            int sl = prefix.lastIndexOf('/');
            String dirPart = sl >= 0 ? prefix.substring(0, sl + 1) : "";
            String namePart = (sl >= 0 ? prefix.substring(sl + 1) : prefix).toLowerCase(Locale.ROOT);
            File dir = resolve(dirPart.isEmpty() ? "." : dirPart);
            String[] list = dir.list();
            if (list != null) for (String f : list) if (f.toLowerCase(Locale.ROOT).startsWith(namePart)) {
                String full = dirPart + f + (new File(dir, f).isDirectory() ? "/" : "");
                r.add(full.contains(" ") ? "\"" + full + "\"" : full);
            }
        }
        return new ArrayList<>(r);
    }

    static List<String> lines(String s) {
        List<String> r = new ArrayList<>();
        if (s == null) return r;
        Collections.addAll(r, s.split("\n", -1));
        if (!r.isEmpty() && r.get(r.size() - 1).isEmpty() && r.size() > 1) r.remove(r.size() - 1);
        return r;
    }

    static Double num(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return null; }
    }

    static String fmt(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        return String.valueOf(d);
    }
}
