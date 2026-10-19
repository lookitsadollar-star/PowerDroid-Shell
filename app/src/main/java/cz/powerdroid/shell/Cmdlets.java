package cz.powerdroid.shell;

import android.app.ActivityManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.os.StatFs;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Všechny vestavěné cmdlety. */
final class Cmdlets {
    private Cmdlets() {}

    private static final String[] NONE = {};
    private static TextToSpeech tts;
    private static boolean torchOn;

    static List<String> L(String... s) { List<String> r = new ArrayList<>(); Collections.addAll(r, s); return r; }
    static List<String> E() { return new ArrayList<>(); }

    static void register(Shell sh) {
        Context ctx = sh.act;

        // ===================================================== ZÁKLAD ===
        sh.add("Get-Help", new String[]{"help", "man"}, NONE, "Get-Help [příkaz]",
                "Zobrazí seznam příkazů nebo nápovědu k jednomu příkazu.", (a, in) -> {
                    String n = a.get("name", 0);
                    if (n != null) {
                        String k = n.toLowerCase(Locale.ROOT);
                        if (sh.aliases.containsKey(k)) k = sh.aliases.get(k);
                        Shell.Cmd c = sh.cmds.get(k);
                        if (c == null) throw new Exception("Příkaz '" + n + "' neexistuje.");
                        List<String> r = L("NÁZEV", "    " + c.name, "", "SYNTAXE", "    " + c.syntax, "", "POPIS", "    " + c.help);
                        if (c.aliases.length > 0) { r.add(""); r.add("ALIASY"); r.add("    " + String.join(", ", c.aliases)); }
                        return r;
                    }
                    List<String> r = E();
                    r.add("Dostupné příkazy (Get-Help <příkaz> pro detail):");
                    r.add("");
                    for (Shell.Cmd c : sh.cmds.values())
                        r.add(String.format(Locale.ROOT, "  %-22s %s", c.name, c.help.split("\\. ")[0]));
                    r.add("");
                    r.add("Neznámé příkazy se spustí v Linux shellu (ls, ps, ping, getprop ...).");
                    r.add("Pipeline: Get-App | Where-Object { $_ -match 'google' } | Sort-Object");
                    r.add("Proměnné: $x = 5; $x * 2   ·   Skripty: . ./skript.ps1");
                    return r;
                });

        sh.add("Get-Command", new String[]{"gcm"}, NONE, "Get-Command [filtr]", "Vypíše názvy příkazů.", (a, in) -> {
            String f = a.get("name", 0, "*");
            List<String> r = E();
            for (Shell.Cmd c : sh.cmds.values()) if (like(c.name, f)) r.add(c.name);
            return r;
        });

        sh.add("Get-Alias", new String[]{"alias"}, NONE, "Get-Alias", "Vypíše aliasy příkazů.", (a, in) -> {
            List<String> r = E();
            for (Shell.Cmd c : sh.cmds.values()) for (String al : c.aliases) r.add(String.format(Locale.ROOT, "%-10s -> %s", al, c.name));
            for (java.util.Map.Entry<String, String> e : sh.aliases.entrySet()) {
                boolean builtin = false;
                Shell.Cmd c = sh.cmds.get(e.getValue());
                if (c != null) for (String al : c.aliases) if (al.equalsIgnoreCase(e.getKey())) builtin = true;
                if (!builtin && c != null) r.add(String.format(Locale.ROOT, "%-10s -> %s", e.getKey(), c.name));
            }
            Collections.sort(r);
            return r;
        });

        sh.add("Set-Alias", new String[]{"sal"}, NONE, "Set-Alias <alias> <příkaz>", "Vytvoří vlastní alias.", (a, in) -> {
            String n = a.get("name", 0), v = a.get("value", 1);
            if (n == null || v == null) throw new Exception("Použití: Set-Alias <alias> <příkaz>");
            String k = v.toLowerCase(Locale.ROOT);
            if (sh.aliases.containsKey(k)) k = sh.aliases.get(k);
            if (!sh.cmds.containsKey(k)) throw new Exception("Příkaz '" + v + "' neexistuje.");
            sh.aliases.put(n.toLowerCase(Locale.ROOT), k);
            return E();
        });

        sh.add("Clear-Host", new String[]{"cls", "clear"}, NONE, "Clear-Host", "Vymaže obrazovku.", (a, in) -> { sh.out.clear(); return E(); });

        sh.add("Write-Output", new String[]{"echo", "write"}, NONE, "Write-Output <text>", "Pošle text do pipeline.", (a, in) -> {
            List<String> r = E(); r.addAll(a.pos); return r;
        });

        sh.add("Write-Host", NONE, new String[]{"NoNewline"}, "Write-Host <text> [-ForegroundColor barva]",
                "Vypíše barevný text na obrazovku.", (a, in) -> {
                    String c = a.get("foregroundcolor", -1, a.get("fore", -1, "white"));
                    sh.out.writeColor(a.rest(0) + (a.has("nonewline") ? "" : "\n"), color(c));
                    return E();
                });

        sh.add("Get-Date", new String[]{"date"}, NONE, "Get-Date [-Format 'yyyy-MM-dd HH:mm']", "Aktuální datum a čas.", (a, in) -> {
            String f = a.get("format", 0, "EEEE d. MMMM yyyy HH:mm:ss");
            return L(new SimpleDateFormat(f, new Locale("cs", "CZ")).format(new Date()));
        });

        sh.add("Get-History", new String[]{"history", "h"}, NONE, "Get-History", "Historie zadaných příkazů.", (a, in) -> {
            List<String> r = E();
            for (int i = 0; i < sh.history.size(); i++) r.add(String.format(Locale.ROOT, "%4d  %s", i + 1, sh.history.get(i)));
            return r;
        });

        sh.add("Get-Variable", new String[]{"gv"}, NONE, "Get-Variable", "Vypíše proměnné.", (a, in) -> {
            List<String> r = E();
            List<String> keys = new ArrayList<>(sh.vars.keySet());
            Collections.sort(keys);
            for (String k : keys) r.add(String.format(Locale.ROOT, "%-14s %s", "$" + k, sh.vars.get(k).replace("\n", " ⏎ ")));
            return r;
        });

        sh.add("Start-Sleep", new String[]{"sleep"}, NONE, "Start-Sleep <sekundy> [-Milliseconds n]", "Počká zadanou dobu.", (a, in) -> {
            String ms = a.get("milliseconds", -1);
            long t = ms != null ? Long.parseLong(ms) : (long) (Double.parseDouble(a.get("seconds", 0, "1")) * 1000);
            long end = System.currentTimeMillis() + t;
            while (!sh.cancelled && System.currentTimeMillis() < end) Thread.sleep(50);
            return E();
        });

        sh.add("Get-Random", NONE, NONE, "Get-Random [-Minimum a] [-Maximum b]", "Náhodné číslo, nebo náhodný prvek z pipeline.", (a, in) -> {
            Random rnd = new Random();
            if (!in.isEmpty()) return L(in.get(rnd.nextInt(in.size())));
            int min = Integer.parseInt(a.get("minimum", -1, "0"));
            int max = Integer.parseInt(a.get("maximum", 0, String.valueOf(Integer.MAX_VALUE)));
            return L(String.valueOf(min + rnd.nextInt(Math.max(1, max - min))));
        });

        sh.add("Exit", new String[]{"quit"}, NONE, "Exit", "Ukončí aplikaci.", (a, in) -> { throw new Shell.ExitException(); });

        // ===================================================== SOUBORY ===
        sh.add("Get-Location", new String[]{"pwd", "gl"}, NONE, "Get-Location", "Aktuální složka.", (a, in) -> L(sh.cwd.getAbsolutePath()));

        sh.add("Set-Location", new String[]{"cd", "sl", "chdir"}, NONE, "Set-Location <cesta>", "Změní aktuální složku. Bez parametru jde do ~.", (a, in) -> {
            String p = a.get("path", 0, "~");
            if (p.equals("-")) p = sh.vars.getOrDefault("oldpwd", "~");
            File f = sh.resolve(p);
            if (!f.isDirectory()) throw new Exception("Složka '" + p + "' neexistuje.");
            if (!f.canRead()) throw new Exception("Přístup odepřen: " + f + "  (zkuste Request-Permission Storage)");
            sh.vars.put("oldpwd", sh.cwd.getAbsolutePath());
            sh.cwd = f;
            return E();
        });

        sh.add("Get-ChildItem", new String[]{"ls", "dir", "gci", "ll"}, new String[]{"Recurse", "Name", "Force", "File", "Directory"},
                "Get-ChildItem [cesta] [-Filter *.jpg] [-Recurse] [-Name] [-File] [-Directory]",
                "Vypíše obsah složky. Bez -Force skryje skryté soubory.", (a, in) -> {
                    File d = sh.resolve(a.get("path", 0, "."));
                    String filter = a.get("filter", 1, "*");
                    if (!d.exists()) throw new Exception("Cesta '" + d + "' neexistuje.");
                    List<String> r = E();
                    boolean names = a.has("name");
                    if (d.isFile()) { r.add(names ? d.getName() : fileLine(d)); return r; }
                    if (!names) { r.add(""); r.add("    Adresář: " + d.getAbsolutePath()); r.add("");
                        r.add("Mode     LastWriteTime          Length Name"); r.add("----     -------------          ------ ----"); }
                    listDir(sh, d, d, filter, a, r, names, 0);
                    if (!names && r.size() == 5) r.add("(prázdné nebo bez oprávnění)");
                    return r;
                });

        sh.add("Get-Content", new String[]{"cat", "gc", "type"}, NONE, "Get-Content <soubor> [-Head n] [-Tail n]", "Přečte textový soubor.", (a, in) -> {
            File f = sh.resolve(req(a.get("path", 0), "Zadejte soubor."));
            List<String> r = Shell.lines(readText(f));
            String head = a.get("head", -1, a.get("totalcount", -1, a.get("first", -1)));
            String tail = a.get("tail", -1, a.get("last", -1));
            if (head != null) r = new ArrayList<>(r.subList(0, Math.min(r.size(), Integer.parseInt(head))));
            if (tail != null) r = new ArrayList<>(r.subList(Math.max(0, r.size() - Integer.parseInt(tail)), r.size()));
            return r;
        });

        Shell.Handler setContent = (a, in) -> {
            File f = sh.resolve(req(a.get("path", 0), "Zadejte soubor."));
            String v = a.get("value", 1);
            String text = v != null ? v : String.join("\n", in);
            writeText(f, text + "\n", false);
            return E();
        };
        sh.add("Set-Content", new String[]{"sc"}, NONE, "Set-Content <soubor> <text>  |  ... | Set-Content <soubor>", "Zapíše text do souboru (přepíše).", setContent);
        sh.add("Out-File", NONE, new String[]{"Append"}, "... | Out-File <soubor> [-Append]", "Uloží výstup pipeline do souboru.", (a, in) -> {
            File f = sh.resolve(req(a.get("filepath", 0), "Zadejte soubor."));
            writeText(f, String.join("\n", in) + "\n", a.has("append"));
            return E();
        });
        sh.add("Add-Content", new String[]{"ac"}, NONE, "Add-Content <soubor> <text>", "Připíše text na konec souboru.", (a, in) -> {
            File f = sh.resolve(req(a.get("path", 0), "Zadejte soubor."));
            String v = a.get("value", 1);
            writeText(f, (v != null ? v : String.join("\n", in)) + "\n", true);
            return E();
        });

        sh.add("New-Item", new String[]{"ni"}, new String[]{"Force"}, "New-Item <cesta> [-ItemType File|Directory] [-Value text]", "Vytvoří soubor nebo složku.", (a, in) -> {
            File f = sh.resolve(req(a.get("path", 0), "Zadejte cestu."));
            String type = a.get("itemtype", 1, a.get("type", -1, "file")).toLowerCase(Locale.ROOT);
            if (type.startsWith("d")) { if (!f.mkdirs() && !f.isDirectory()) throw new Exception("Nelze vytvořit složku."); }
            else {
                if (f.exists() && !a.has("force")) throw new Exception("Soubor už existuje (použijte -Force).");
                if (f.getParentFile() != null) f.getParentFile().mkdirs();
                writeText(f, a.get("value", -1, ""), false);
            }
            return L(fileLine(f));
        });
        sh.add("mkdir", new String[]{"md"}, NONE, "mkdir <složka>", "Vytvoří složku (včetně nadřazených).", (a, in) -> {
            for (String p : a.pos) { File f = sh.resolve(p); if (!f.mkdirs() && !f.isDirectory()) throw new Exception("Nelze vytvořit " + p); }
            return E();
        });

        sh.add("Remove-Item", new String[]{"rm", "del", "rmdir", "ri", "erase"}, new String[]{"Recurse", "Force"}, "Remove-Item <cesta> [-Recurse]", "Smaže soubor nebo složku.", (a, in) -> {
            List<String> paths = a.pos.isEmpty() ? in : a.pos;
            for (String p : paths) {
                File f = sh.resolve(p);
                if (!f.exists()) throw new Exception("'" + p + "' neexistuje.");
                if (f.isDirectory() && f.list() != null && f.list().length > 0 && !a.has("recurse"))
                    throw new Exception("Složka '" + p + "' není prázdná. Použijte -Recurse.");
                deleteRec(f);
            }
            return E();
        });

        sh.add("Copy-Item", new String[]{"cp", "copy", "cpi"}, new String[]{"Recurse", "Force"}, "Copy-Item <zdroj> <cíl> [-Recurse]", "Zkopíruje soubor nebo složku.", (a, in) -> {
            File s = sh.resolve(req(a.get("path", 0), "Zadejte zdroj.")), d = sh.resolve(req(a.get("destination", 1), "Zadejte cíl."));
            if (d.isDirectory()) d = new File(d, s.getName());
            if (s.isDirectory() && !a.has("recurse")) throw new Exception("Pro složky použijte -Recurse.");
            copyRec(s, d);
            return E();
        });

        sh.add("Move-Item", new String[]{"mv", "move", "mi"}, NONE, "Move-Item <zdroj> <cíl>", "Přesune soubor nebo složku.", (a, in) -> {
            File s = sh.resolve(req(a.get("path", 0), "Zadejte zdroj.")), d = sh.resolve(req(a.get("destination", 1), "Zadejte cíl."));
            if (d.isDirectory()) d = new File(d, s.getName());
            if (!s.renameTo(d)) { copyRec(s, d); deleteRec(s); }
            return E();
        });

        sh.add("Rename-Item", new String[]{"ren", "rni"}, NONE, "Rename-Item <cesta> <nový název>", "Přejmenuje soubor.", (a, in) -> {
            File s = sh.resolve(req(a.get("path", 0), "Zadejte soubor."));
            String nn = req(a.get("newname", 1), "Zadejte nový název.");
            if (!s.renameTo(new File(s.getParentFile(), nn))) throw new Exception("Přejmenování selhalo.");
            return E();
        });

        sh.add("Test-Path", NONE, NONE, "Test-Path <cesta>", "Vrátí True, pokud cesta existuje.", (a, in) ->
                L(sh.resolve(a.get("path", 0, "")).exists() ? "True" : "False"));

        sh.add("Get-Item", new String[]{"gi", "stat"}, NONE, "Get-Item <cesta>", "Podrobnosti o souboru.", (a, in) -> {
            File f = sh.resolve(req(a.get("path", 0), "Zadejte cestu."));
            if (!f.exists()) throw new Exception("'" + f + "' neexistuje.");
            return L("FullName      : " + f.getAbsolutePath(),
                    "Type          : " + (f.isDirectory() ? "Directory" : "File"),
                    "Length        : " + f.length() + " (" + human(f.length()) + ")",
                    "LastWriteTime : " + new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.ROOT).format(new Date(f.lastModified())),
                    "Readable      : " + f.canRead(), "Writable      : " + f.canWrite(), "Hidden        : " + f.isHidden());
        });

        sh.add("Get-FileHash", NONE, NONE, "Get-FileHash <soubor> [-Algorithm SHA256|SHA1|MD5]", "Spočítá hash souboru.", (a, in) -> {
            File f = sh.resolve(req(a.get("path", 0), "Zadejte soubor."));
            String alg = a.get("algorithm", 1, "SHA256").toUpperCase(Locale.ROOT).replace("SHA", "SHA-");
            if (alg.equals("SHA--1")) alg = "SHA-1";
            MessageDigest md = MessageDigest.getInstance(alg.equals("MD5") ? "MD5" : alg);
            try (InputStream is = new FileInputStream(f)) {
                byte[] buf = new byte[65536]; int n;
                while ((n = is.read(buf)) > 0) { if (sh.cancelled) return E(); md.update(buf, 0, n); }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02X", b));
            return L(alg + "  " + sb + "  " + f.getName());
        });

        // ===================================================== PIPELINE ===
        sh.add("Where-Object", new String[]{"where", "?"}, NONE, "... | Where-Object { $_ -match 'text' }",
                "Filtruje řádky. Operátory: -match -notmatch -like -notlike -eq -ne -gt -lt -ge -le -contains, -and, -or.", (a, in) -> {
                    String cond = a.block;
                    if (cond == null) {
                        if (a.pos.size() >= 1 && !a.named.isEmpty()) {
                            java.util.Map.Entry<String, String> e = a.named.entrySet().iterator().next();
                            cond = "$_ -" + e.getKey() + " '" + e.getValue() + "'";
                        } else throw new Exception("Použití: Where-Object { $_ -match 'text' }");
                    }
                    List<String> r = E();
                    for (String line : in) if (Cond.eval(sh, cond, line)) r.add(line);
                    return r;
                });

        sh.add("ForEach-Object", new String[]{"foreach", "%"}, NONE, "... | ForEach-Object { příkaz s $_ }",
                "Pro každý řádek spustí blok; $_ je aktuální řádek.", (a, in) -> {
                    String blk = req(a.block, "Použití: ForEach-Object { ... $_ ... }");
                    List<String> r = E();
                    String old = sh.vars.get("_");
                    try {
                        for (String line : in) {
                            if (sh.cancelled) break;
                            sh.vars.put("_", line);
                            r.addAll(sh.runScript(blk, Collections.emptyList()));
                        }
                    } finally { if (old == null) sh.vars.remove("_"); else sh.vars.put("_", old); }
                    return r;
                });

        sh.add("Select-String", new String[]{"sls", "grep"}, new String[]{"CaseSensitive", "NotMatch", "SimpleMatch"},
                "... | Select-String <vzor> [-Path soubor] [-NotMatch]", "Hledá text pomocí regulárního výrazu.", (a, in) -> {
                    String pat = req(a.get("pattern", 0), "Zadejte vzor.");
                    List<String> src = in;
                    String path = a.get("path", 1);
                    if (path != null) src = Shell.lines(readText(sh.resolve(path)));
                    int flags = a.has("casesensitive") ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
                    Pattern p = Pattern.compile(a.has("simplematch") ? Pattern.quote(pat) : pat, flags);
                    List<String> r = E();
                    for (String l : src) if (p.matcher(l).find() != a.has("notmatch")) r.add(l);
                    return r;
                });

        sh.add("Sort-Object", new String[]{"sort"}, new String[]{"Descending", "Unique"}, "... | Sort-Object [-Descending] [-Unique]", "Seřadí řádky (čísla numericky).", (a, in) -> {
            List<String> r = new ArrayList<>(a.has("unique") ? new LinkedHashSet<>(in) : in);
            r.sort((x, y) -> {
                Double dx = Shell.num(x), dy = Shell.num(y);
                if (dx != null && dy != null) return Double.compare(dx, dy);
                return x.compareToIgnoreCase(y);
            });
            if (a.has("descending")) Collections.reverse(r);
            return r;
        });

        sh.add("Select-Object", new String[]{"select"}, new String[]{"Unique"}, "... | Select-Object [-First n] [-Last n] [-Skip n] [-Unique]", "Vybere část řádků.", (a, in) -> {
            List<String> r = new ArrayList<>(a.has("unique") ? new LinkedHashSet<>(in) : in);
            int skip = Integer.parseInt(a.get("skip", -1, "0"));
            r = new ArrayList<>(r.subList(Math.min(skip, r.size()), r.size()));
            String first = a.get("first", -1), last = a.get("last", -1);
            if (first != null) r = new ArrayList<>(r.subList(0, Math.min(r.size(), Integer.parseInt(first))));
            if (last != null) r = new ArrayList<>(r.subList(Math.max(0, r.size() - Integer.parseInt(last)), r.size()));
            return r;
        });

        sh.add("Get-Unique", new String[]{"gu", "uniq"}, NONE, "... | Get-Unique", "Odstraní duplicitní řádky.", (a, in) -> new ArrayList<>(new LinkedHashSet<>(in)));

        sh.add("Measure-Object", new String[]{"measure", "wc"}, NONE, "... | Measure-Object", "Spočítá řádky, slova, znaky (a součet čísel).", (a, in) -> {
            long words = 0, chars = 0; double sum = 0; int nums = 0;
            for (String l : in) {
                chars += l.length();
                String t = l.trim(); if (!t.isEmpty()) words += t.split("\\s+").length;
                Double d = Shell.num(l); if (d != null) { sum += d; nums++; }
            }
            List<String> r = L("Count      : " + in.size(), "Words      : " + words, "Characters : " + chars);
            if (nums > 0) { r.add("Sum        : " + Shell.fmt(sum)); r.add("Average    : " + Shell.fmt(sum / nums)); }
            return r;
        });

        sh.add("Out-Host", new String[]{"oh"}, NONE, "... | Out-Host", "Vypíše výstup na obrazovku.", (a, in) -> in);

        // ===================================================== ZAŘÍZENÍ ===
        sh.add("Get-DeviceInfo", new String[]{"sysinfo", "neofetch"}, NONE, "Get-DeviceInfo", "Informace o telefonu a systému.", (a, in) -> {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo(); am.getMemoryInfo(mi);
            android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            return L("Výrobce        : " + Build.MANUFACTURER,
                    "Model          : " + Build.MODEL + " (" + Build.DEVICE + ")",
                    "Značka         : " + Build.BRAND,
                    "Android        : " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")",
                    "Bezp. záplata  : " + (Build.VERSION.SDK_INT >= 23 ? Build.VERSION.SECURITY_PATCH : "?"),
                    "Build          : " + Build.DISPLAY,
                    "Hardware       : " + Build.HARDWARE + (Build.VERSION.SDK_INT >= 31 ? " / " + Build.SOC_MODEL : ""),
                    "CPU ABI        : " + String.join(", ", Build.SUPPORTED_ABIS),
                    "Jádra CPU      : " + Runtime.getRuntime().availableProcessors(),
                    "RAM            : " + human(mi.availMem) + " volno z " + human(mi.totalMem),
                    "Displej        : " + dm.widthPixels + "x" + dm.heightPixels + " @ " + dm.densityDpi + " dpi",
                    "Jádro          : " + System.getProperty("os.version"),
                    "Doba běhu      : " + uptime());
        });

        sh.add("Get-Battery", new String[]{"battery"}, NONE, "Get-Battery", "Stav baterie.", (a, in) -> {
            Intent b = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) throw new Exception("Informace o baterii nejsou k dispozici.");
            int lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), sc = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int st = b.getIntExtra(BatteryManager.EXTRA_STATUS, 1), hl = b.getIntExtra(BatteryManager.EXTRA_HEALTH, 1);
            int pl = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            String[] sts = {"?", "Neznámý", "Nabíjí se", "Vybíjí se", "Nenabíjí", "Nabito"};
            String[] hls = {"?", "Neznámý", "Dobrý", "Přehřátá", "Mrtvá", "Přepětí", "Chyba", "Studená"};
            String plug = pl == BatteryManager.BATTERY_PLUGGED_AC ? "AC" : pl == BatteryManager.BATTERY_PLUGGED_USB ? "USB"
                    : pl == BatteryManager.BATTERY_PLUGGED_WIRELESS ? "Bezdrátově" : "Nepřipojeno";
            BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
            int cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            return L("Úroveň    : " + (lvl * 100 / Math.max(sc, 1)) + " %",
                    "Stav      : " + sts[Math.min(Math.max(st, 0), 5)],
                    "Zdraví    : " + hls[Math.min(Math.max(hl, 0), 7)],
                    "Napájení  : " + plug,
                    "Teplota   : " + String.format(Locale.ROOT, "%.1f °C", b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0),
                    "Napětí    : " + b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) + " mV",
                    "Proud     : " + (cur == Integer.MIN_VALUE ? "?" : (cur / 1000) + " mA"),
                    "Technologie: " + b.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY));
        });

        sh.add("Get-Storage", new String[]{"df", "Get-PSDrive"}, NONE, "Get-Storage", "Volné a obsazené místo.", (a, in) -> {
            List<String> r = L(String.format(Locale.ROOT, "%-10s %10s %10s %10s %5s", "Úložiště", "Celkem", "Použito", "Volno", "%"));
            File[] roots = {Environment.getDataDirectory(), Environment.getExternalStorageDirectory()};
            String[] names = {"Interní", "Sdílené"};
            for (int i = 0; i < roots.length; i++) {
                StatFs s = new StatFs(roots[i].getPath());
                long t = s.getTotalBytes(), f = s.getAvailableBytes();
                r.add(String.format(Locale.ROOT, "%-10s %10s %10s %10s %4d%%", names[i], human(t), human(t - f), human(f), t == 0 ? 0 : (t - f) * 100 / t));
            }
            File[] ext = ctx.getExternalFilesDirs(null);
            for (int i = 1; i < ext.length; i++) if (ext[i] != null) {
                StatFs s = new StatFs(ext[i].getPath()); long t = s.getTotalBytes(), f = s.getAvailableBytes();
                r.add(String.format(Locale.ROOT, "%-10s %10s %10s %10s %4d%%", "SD karta", human(t), human(t - f), human(f), t == 0 ? 0 : (t - f) * 100 / t));
            }
            return r;
        });

        sh.add("Get-Memory", new String[]{"free"}, NONE, "Get-Memory", "Využití paměti RAM.", (a, in) -> {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo(); am.getMemoryInfo(mi);
            Runtime rt = Runtime.getRuntime();
            return L("Celkem RAM   : " + human(mi.totalMem), "Volno        : " + human(mi.availMem),
                    "Použito      : " + human(mi.totalMem - mi.availMem) + " (" + ((mi.totalMem - mi.availMem) * 100 / mi.totalMem) + " %)",
                    "Málo paměti  : " + (mi.lowMemory ? "Ano" : "Ne"),
                    "Aplikace JVM : " + human(rt.totalMemory() - rt.freeMemory()) + " / " + human(rt.maxMemory()));
        });

        sh.add("Get-Network", new String[]{"ipconfig", "Get-NetIPAddress"}, NONE, "Get-Network", "Síťové připojení, IP adresy a DNS.", (a, in) -> {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            List<String> r = E();
            Network act = cm.getActiveNetwork();
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                LinkProperties lp = cm.getLinkProperties(n);
                if (nc == null || lp == null) continue;
                String type = nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi"
                        : nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "Mobilní data"
                        : nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? "VPN"
                        : nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "Ethernet" : "Jiné";
                r.add(type + " (" + lp.getInterfaceName() + ")" + (n.equals(act) ? "  [aktivní]" : ""));
                for (LinkAddress la : lp.getLinkAddresses()) r.add("   IP      : " + la);
                for (InetAddress d : lp.getDnsServers()) r.add("   DNS     : " + d.getHostAddress());
                r.add("   Rychlost: ↓" + nc.getLinkDownstreamBandwidthKbps() / 1000 + " / ↑" + nc.getLinkUpstreamBandwidthKbps() / 1000 + " Mbit/s");
                r.add("   Internet: " + (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? "Ano" : "Ne")
                        + "   Účtované: " + (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ? "Ne" : "Ano"));
                r.add("");
            }
            if (r.isEmpty()) r.add("Žádné síťové připojení.");
            return r;
        });

        sh.add("Get-Sensor", new String[]{"sensors"}, NONE, "Get-Sensor", "Seznam senzorů v telefonu.", (a, in) -> {
            SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
            List<String> r = E();
            for (Sensor s : sm.getSensorList(Sensor.TYPE_ALL)) r.add(s.getName() + "  (" + s.getVendor() + ")");
            return r;
        });

        sh.add("Get-Uptime", new String[]{"uptime"}, NONE, "Get-Uptime", "Doba od posledního spuštění.", (a, in) -> L(uptime()));

        // ===================================================== OVLÁDÁNÍ ===
        sh.add("Set-Flashlight", new String[]{"torch", "flashlight", "svitilna"}, NONE, "Set-Flashlight [On|Off|Toggle]", "Zapne nebo vypne svítilnu.", (a, in) -> {
            CameraManager cam = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            String id = null;
            for (String c : cam.getCameraIdList()) {
                Boolean f = cam.getCameraCharacteristics(c).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (f != null && f) { id = c; break; }
            }
            if (id == null) throw new Exception("Telefon nemá blesk.");
            String m = a.get("state", 0, "toggle").toLowerCase(Locale.ROOT);
            boolean on = m.equals("on") || m.equals("1") || m.equals("zapnout") || (m.equals("toggle") && !torchOn);
            cam.setTorchMode(id, on);
            torchOn = on;
            return L("Svítilna: " + (on ? "ZAPNUTO" : "VYPNUTO"));
        });

        sh.add("Get-Volume", NONE, NONE, "Get-Volume", "Zobrazí hlasitosti všech kanálů.", (a, in) -> {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            List<String> r = E();
            String[] n = {"Media", "Ring", "Notification", "Alarm", "Call", "System"};
            for (String s : n) {
                int st = stream(s);
                int v = am.getStreamVolume(st), mx = am.getStreamMaxVolume(st);
                r.add(String.format(Locale.ROOT, "%-13s %3d %%  (%d/%d)", s, v * 100 / Math.max(mx, 1), v, mx));
            }
            String[] modes = {"Silent", "Vibrate", "Normal"};
            r.add("Režim zvonění: " + modes[am.getRingerMode()]);
            return r;
        });

        sh.add("Set-Volume", new String[]{"volume", "vol"}, new String[]{"Mute", "Unmute"}, "Set-Volume <0-100> [-Stream Media|Ring|Alarm|Notification|Call] [-Mute]",
                "Nastaví hlasitost v procentech.", (a, in) -> {
                    AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                    String sn = a.get("stream", 1, "Media");
                    int st = stream(sn);
                    int mx = am.getStreamMaxVolume(st);
                    if (a.has("mute")) { am.adjustStreamVolume(st, AudioManager.ADJUST_MUTE, 0); return L(sn + ": ztlumeno"); }
                    if (a.has("unmute")) { am.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, 0); return L(sn + ": zvuk obnoven"); }
                    String lv = req(a.get("level", 0), "Zadejte hlasitost 0-100.");
                    int pct = Math.max(0, Math.min(100, (int) Double.parseDouble(lv)));
                    try { am.setStreamVolume(st, Math.round(pct * mx / 100f), AudioManager.FLAG_SHOW_UI); }
                    catch (SecurityException e) { throw new Exception("Nelze měnit hlasitost v režimu Nerušit. Spusťte Request-Permission DoNotDisturb"); }
                    return L(sn + ": " + pct + " %");
                });

        sh.add("Set-Ringer", new String[]{"ringer"}, NONE, "Set-Ringer Normal|Vibrate|Silent", "Režim zvonění.", (a, in) -> {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            String m = req(a.get("mode", 0), "Zadejte Normal, Vibrate nebo Silent.").toLowerCase(Locale.ROOT);
            int mode = m.startsWith("s") ? AudioManager.RINGER_MODE_SILENT : m.startsWith("v") ? AudioManager.RINGER_MODE_VIBRATE : AudioManager.RINGER_MODE_NORMAL;
            try { am.setRingerMode(mode); }
            catch (SecurityException e) { throw new Exception("Chybí oprávnění Nerušit. Spusťte Request-Permission DoNotDisturb"); }
            return L("Režim zvonění: " + m);
        });

        sh.add("Set-Brightness", new String[]{"brightness"}, new String[]{"Auto"}, "Set-Brightness <0-100> | -Auto", "Nastaví jas displeje.", (a, in) -> {
            if (a.has("auto")) {
                if (!Settings.System.canWrite(ctx)) throw new Exception("Chybí oprávnění. Spusťte Request-Permission Settings");
                Settings.System.putInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
                return L("Jas: automatický");
            }
            int pct = Math.max(0, Math.min(100, (int) Double.parseDouble(req(a.get("level", 0), "Zadejte jas 0-100."))));
            if (Settings.System.canWrite(ctx)) {
                Settings.System.putInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                Settings.System.putInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, Math.round(pct * 2.55f));
                return L("Jas systému: " + pct + " %");
            }
            sh.act.runOnUiThread(() -> {
                WindowManager.LayoutParams lp = sh.act.getWindow().getAttributes();
                lp.screenBrightness = Math.max(0.01f, pct / 100f);
                sh.act.getWindow().setAttributes(lp);
            });
            return L("Jas této aplikace: " + pct + " %  (pro systémový jas: Request-Permission Settings)");
        });

        sh.add("Invoke-Vibrate", new String[]{"vibrate"}, NONE, "Invoke-Vibrate [ms]", "Zavibruje telefonem.", (a, in) -> {
            long ms = Long.parseLong(a.get("milliseconds", 0, "300"));
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) throw new Exception("Vibrace nejsou k dispozici.");
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            return E();
        });

        sh.add("Out-Speech", new String[]{"say", "speak"}, NONE, "Out-Speech <text> [-Language cs]", "Přečte text nahlas (syntéza řeči).", (a, in) -> {
            String text = a.pos.isEmpty() ? String.join(". ", in) : a.rest(0);
            if (text.isEmpty()) throw new Exception("Zadejte text.");
            String lang = a.get("language", -1, "cs");
            if (tts == null) {
                CountDownLatch l = new CountDownLatch(1);
                final int[] status = {-1};
                tts = new TextToSpeech(ctx.getApplicationContext(), s -> { status[0] = s; l.countDown(); });
                l.await(10, TimeUnit.SECONDS);
                if (status[0] != TextToSpeech.SUCCESS) { tts = null; throw new Exception("Syntéza řeči není dostupná."); }
            }
            tts.setLanguage(Locale.forLanguageTag(lang));
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, "pd" + System.nanoTime());
            return E();
        });

        sh.add("Set-Clipboard", new String[]{"scb"}, NONE, "Set-Clipboard <text>  |  ... | Set-Clipboard", "Zkopíruje text do schránky.", (a, in) -> {
            String t = a.pos.isEmpty() ? String.join("\n", in) : a.rest(0);
            CountDownLatch l = new CountDownLatch(1);
            sh.act.runOnUiThread(() -> {
                ((ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("PowerDroid", t));
                l.countDown();
            });
            l.await(3, TimeUnit.SECONDS);
            return E();
        });

        sh.add("Get-Clipboard", new String[]{"gcb"}, NONE, "Get-Clipboard", "Obsah schránky.", (a, in) -> {
            final String[] res = {""};
            CountDownLatch l = new CountDownLatch(1);
            sh.act.runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData cd = cm.getPrimaryClip();
                if (cd != null && cd.getItemCount() > 0) res[0] = String.valueOf(cd.getItemAt(0).coerceToText(ctx));
                l.countDown();
            });
            l.await(3, TimeUnit.SECONDS);
            return Shell.lines(res[0]);
        });

        sh.add("Show-Notification", new String[]{"notify"}, NONE, "Show-Notification <text> [-Title nadpis]", "Zobrazí systémové oznámení.", (a, in) -> {
            if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                sh.act.runOnUiThread(() -> sh.act.requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 2));
                throw new Exception("Povolte oznámení v dialogu a spusťte příkaz znovu.");
            }
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("pd", "PowerDroid", NotificationManager.IMPORTANCE_HIGH));
            android.app.Notification n = new android.app.Notification.Builder(ctx, "pd")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(a.get("title", -1, "PowerDroid"))
                    .setContentText(a.pos.isEmpty() ? String.join(" ", in) : a.rest(0))
                    .setAutoCancel(true).build();
            nm.notify((int) (System.currentTimeMillis() % 100000), n);
            return E();
        });

        sh.add("Show-Toast", new String[]{"toast"}, NONE, "Show-Toast <text>", "Krátká bublina na obrazovce.", (a, in) -> {
            String t = a.pos.isEmpty() ? String.join(" ", in) : a.rest(0);
            sh.act.runOnUiThread(() -> Toast.makeText(ctx, t, Toast.LENGTH_SHORT).show());
            return E();
        });

        sh.add("Invoke-MediaKey", new String[]{"media"}, NONE, "Invoke-MediaKey Play|Pause|Next|Previous|Stop",
                "Ovládá přehrávání hudby/videa v jiných aplikacích.", (a, in) -> {
                    String k = req(a.get("key", 0), "Zadejte Play, Pause, Next, Previous nebo Stop.").toLowerCase(Locale.ROOT);
                    int code = k.startsWith("n") ? KeyEvent.KEYCODE_MEDIA_NEXT : k.startsWith("pr") ? KeyEvent.KEYCODE_MEDIA_PREVIOUS
                            : k.startsWith("s") ? KeyEvent.KEYCODE_MEDIA_STOP : k.equals("pause") ? KeyEvent.KEYCODE_MEDIA_PAUSE
                            : k.equals("play") ? KeyEvent.KEYCODE_MEDIA_PLAY : KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
                    AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                    am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
                    am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
                    return E();
                });

        // ===================================================== APLIKACE ===
        sh.add("Get-App", new String[]{"apps", "Get-Package"}, new String[]{"All", "System"}, "Get-App [filtr] [-All] [-System]",
                "Seznam nainstalovaných aplikací (název a balíček).", (a, in) -> {
                    PackageManager pm = ctx.getPackageManager();
                    String f = a.get("name", 0, "").toLowerCase(Locale.ROOT);
                    List<String> r = E();
                    if (a.has("all") || a.has("system")) {
                        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                            boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                            if (a.has("system") && !sys) continue;
                            String lbl = String.valueOf(pm.getApplicationLabel(ai));
                            if (f.isEmpty() || lbl.toLowerCase(Locale.ROOT).contains(f) || ai.packageName.contains(f))
                                r.add(String.format(Locale.ROOT, "%-28s %s", lbl, ai.packageName));
                        }
                    } else {
                        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                        LinkedHashSet<String> seen = new LinkedHashSet<>();
                        for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
                            String pkg = ri.activityInfo.packageName;
                            if (!seen.add(pkg)) continue;
                            String lbl = String.valueOf(ri.loadLabel(pm));
                            if (f.isEmpty() || lbl.toLowerCase(Locale.ROOT).contains(f) || pkg.contains(f))
                                r.add(String.format(Locale.ROOT, "%-28s %s", lbl, pkg));
                        }
                    }
                    r.sort(String::compareToIgnoreCase);
                    return r;
                });

        sh.add("Start-App", new String[]{"open", "launch", "run"}, NONE, "Start-App <název nebo balíček>", "Spustí aplikaci (např. Start-App kamera).", (a, in) -> {
            String q = a.pos.isEmpty() ? (in.isEmpty() ? null : in.get(0).trim()) : a.rest(0);
            q = req(q, "Zadejte název aplikace.");
            if (q.contains(" ") && q.matches(".*\\s[a-z][\\w]*(\\.[\\w]+)+$")) q = q.substring(q.lastIndexOf(' ') + 1);
            String pkg = findPackage(ctx, q);
            if (pkg == null) throw new Exception("Aplikace '" + q + "' nenalezena. Zkuste Get-App " + q);
            Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) throw new Exception("Aplikaci " + pkg + " nelze spustit.");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return L("Spouštím " + pkg);
        });

        sh.add("Get-AppInfo", new String[]{"appinfo"}, NONE, "Get-AppInfo <název nebo balíček>", "Verze, velikost a datum instalace aplikace.", (a, in) -> {
            String q = req(a.pos.isEmpty() ? null : a.rest(0), "Zadejte aplikaci.");
            String pkg = findPackage(ctx, q);
            if (pkg == null) throw new Exception("Aplikace nenalezena.");
            PackageManager pm = ctx.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS);
            SimpleDateFormat df = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ROOT);
            File apk = new File(pi.applicationInfo.sourceDir);
            List<String> r = L("Název      : " + pm.getApplicationLabel(pi.applicationInfo),
                    "Balíček    : " + pkg, "Verze      : " + pi.versionName + " (" + (Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode) + ")",
                    "Target SDK : " + pi.applicationInfo.targetSdkVersion,
                    "Instalace  : " + df.format(new Date(pi.firstInstallTime)), "Aktualizace: " + df.format(new Date(pi.lastUpdateTime)),
                    "APK        : " + apk + " (" + human(apk.length()) + ")",
                    "Systémová  : " + ((pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0 ? "Ano" : "Ne"));
            if (pi.requestedPermissions != null) r.add("Oprávnění  : " + pi.requestedPermissions.length);
            return r;
        });

        sh.add("Open-AppSettings", new String[]{"appsettings"}, NONE, "Open-AppSettings <aplikace>", "Otevře systémové nastavení aplikace (vynutit zastavení, oprávnění, data).", (a, in) -> {
            String pkg = findPackage(ctx, req(a.pos.isEmpty() ? null : a.rest(0), "Zadejte aplikaci."));
            if (pkg == null) throw new Exception("Aplikace nenalezena.");
            ctx.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return E();
        });

        sh.add("Uninstall-App", new String[]{"uninstall"}, NONE, "Uninstall-App <aplikace>", "Spustí odinstalaci aplikace (s potvrzením).", (a, in) -> {
            String pkg = findPackage(ctx, req(a.pos.isEmpty() ? null : a.rest(0), "Zadejte aplikaci."));
            if (pkg == null) throw new Exception("Aplikace nenalezena.");
            ctx.startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return L("Potvrďte odinstalaci " + pkg);
        });

        // ===================================================== SYSTÉM ===
        sh.add("Start-Process", new String[]{"start", "saps"}, NONE, "Start-Process <url | tel:číslo | mailto:... | soubor | aplikace>",
                "Otevře odkaz, soubor, telefonní číslo nebo aplikaci.", (a, in) -> {
                    String t = req(a.get("filepath", 0), "Zadejte cíl.");
                    Intent i;
                    if (t.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) i = new Intent(Intent.ACTION_VIEW, Uri.parse(t));
                    else if (t.matches("^[\\w.-]+\\.[a-z]{2,}(/.*)?$") && !sh.resolve(t).exists()) i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://" + t));
                    else if (sh.resolve(t).exists()) {
                        File f = sh.resolve(t);
                        throw new Exception("Otevírání souborů: použijte Start-App se správcem souborů. Soubor: " + f);
                    } else {
                        String pkg = findPackage(ctx, t);
                        if (pkg == null) throw new Exception("Nevím, jak otevřít '" + t + "'.");
                        i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
                    }
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(i);
                    return E();
                });

        sh.add("Open-Settings", new String[]{"settings"}, NONE, "Open-Settings [wifi|bluetooth|display|sound|battery|apps|location|developer|storage|nfc|date|language|security|airplane|notifications|data]",
                "Otevře zvolenou stránku systémového nastavení.", (a, in) -> {
                    String p = a.get("page", 0, "").toLowerCase(Locale.ROOT);
                    String action;
                    switch (p) {
                        case "wifi": action = Settings.ACTION_WIFI_SETTINGS; break;
                        case "bluetooth": case "bt": action = Settings.ACTION_BLUETOOTH_SETTINGS; break;
                        case "display": case "displej": action = Settings.ACTION_DISPLAY_SETTINGS; break;
                        case "sound": case "zvuk": action = Settings.ACTION_SOUND_SETTINGS; break;
                        case "battery": case "baterie": action = Intent.ACTION_POWER_USAGE_SUMMARY; break;
                        case "apps": case "aplikace": action = Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS; break;
                        case "location": case "gps": action = Settings.ACTION_LOCATION_SOURCE_SETTINGS; break;
                        case "developer": case "dev": action = Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS; break;
                        case "storage": action = Settings.ACTION_INTERNAL_STORAGE_SETTINGS; break;
                        case "nfc": action = Settings.ACTION_NFC_SETTINGS; break;
                        case "date": action = Settings.ACTION_DATE_SETTINGS; break;
                        case "language": action = Settings.ACTION_LOCALE_SETTINGS; break;
                        case "security": action = Settings.ACTION_SECURITY_SETTINGS; break;
                        case "airplane": action = Settings.ACTION_AIRPLANE_MODE_SETTINGS; break;
                        case "notifications": action = Settings.ACTION_APP_NOTIFICATION_SETTINGS; break;
                        case "data": action = Settings.ACTION_DATA_ROAMING_SETTINGS; break;
                        case "hotspot": action = Settings.ACTION_WIRELESS_SETTINGS; break;
                        default: action = Settings.ACTION_SETTINGS;
                    }
                    Intent i = new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (action.equals(Settings.ACTION_APP_NOTIFICATION_SETTINGS)) i.putExtra(Settings.EXTRA_APP_PACKAGE, ctx.getPackageName());
                    ctx.startActivity(i);
                    return E();
                });

        sh.add("Request-Permission", new String[]{"permit"}, NONE, "Request-Permission Storage|Settings|Notifications|DoNotDisturb",
                "Požádá o oprávnění potřebná pro některé příkazy.", (a, in) -> {
                    String p = req(a.get("name", 0), "Zadejte Storage, Settings, Notifications nebo DoNotDisturb.").toLowerCase(Locale.ROOT);
                    if (p.startsWith("stor")) sh.act.runOnUiThread(sh.act::requestStoragePermission);
                    else if (p.startsWith("set")) ctx.startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    else if (p.startsWith("not")) sh.act.runOnUiThread(() -> sh.act.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2));
                    else if (p.startsWith("do") || p.startsWith("dnd")) ctx.startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    else throw new Exception("Neznámé oprávnění.");
                    return L("Otevírám žádost o oprávnění...");
                });

        sh.add("Invoke-Shell", new String[]{"sh", "bash", "cmd"}, NONE, "Invoke-Shell <linuxový příkaz>", "Spustí příkaz v Linux shellu Androidu (toybox).", (a, in) ->
                runProcess(sh, a.rest(0), in, false));

        sh.add("Get-Process", new String[]{"ps", "gps"}, NONE, "Get-Process", "Běžící procesy (viditelné pro aplikaci).", (a, in) -> runProcess(sh, "ps -A -o PID,USER,RSS,NAME 2>/dev/null || ps", in, false));

        sh.add("Get-SystemProperty", new String[]{"getprop"}, NONE, "Get-SystemProperty [klíč]", "Čte systémové vlastnosti (getprop).", (a, in) ->
                runProcess(sh, "getprop " + a.get("name", 0, ""), in, false));

        sh.add("Test-Connection", new String[]{"ping"}, NONE, "Test-Connection <host> [-Count 4]", "Ping na server.", (a, in) ->
                runProcess(sh, "ping -c " + a.get("count", 1, "4") + " " + req(a.get("computername", 0), "Zadejte host."), in, true));

        sh.add("Resolve-DnsName", new String[]{"nslookup"}, NONE, "Resolve-DnsName <doména>", "Přeloží doménu na IP adresy.", (a, in) -> {
            List<String> r = E();
            for (InetAddress ia : InetAddress.getAllByName(req(a.get("name", 0), "Zadejte doménu."))) r.add(ia.getHostAddress());
            return r;
        });

        sh.add("Invoke-WebRequest", new String[]{"iwr", "curl", "wget", "Invoke-RestMethod", "irm"}, NONE,
                "Invoke-WebRequest <url> [-OutFile soubor] [-Method GET|POST] [-Body text]", "Stáhne obsah z internetu.", (a, in) -> {
                    String u = req(a.get("uri", 0), "Zadejte URL.");
                    if (!u.matches("^https?://.*")) u = "https://" + u;
                    HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                    c.setConnectTimeout(15000); c.setReadTimeout(30000);
                    c.setRequestProperty("User-Agent", "PowerDroid/1.0");
                    String method = a.get("method", -1, "GET").toUpperCase(Locale.ROOT);
                    c.setRequestMethod(method);
                    String body = a.get("body", -1);
                    if (body != null) {
                        c.setDoOutput(true);
                        c.setRequestProperty("Content-Type", a.get("contenttype", -1, body.trim().startsWith("{") ? "application/json" : "text/plain"));
                        try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
                    }
                    int code = c.getResponseCode();
                    InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    String outFile = a.get("outfile", 1);
                    if (outFile != null) {
                        File f = sh.resolve(outFile);
                        long total = 0;
                        try (InputStream s = is; OutputStream os = new FileOutputStream(f)) {
                            byte[] buf = new byte[65536]; int n;
                            while ((n = s.read(buf)) > 0) { if (sh.cancelled) break; os.write(buf, 0, n); total += n; }
                        }
                        return L("StatusCode : " + code, "Uloženo    : " + f + " (" + human(total) + ")");
                    }
                    String text = is == null ? "" : readStream(is);
                    List<String> r = E();
                    if (code >= 300) r.add("StatusCode : " + code);
                    r.addAll(Shell.lines(text.length() > 100_000 ? text.substring(0, 100_000) + "\n... (zkráceno)" : text));
                    return r;
                });

        sh.add("Invoke-Script", new String[]{"source"}, NONE, "Invoke-Script <soubor.ps1>  (nebo:  . ./soubor.ps1)", "Spustí skript ze souboru.", (a, in) ->
                sh.runScript(readText(sh.resolve(req(a.get("path", 0), "Zadejte skript."))), in));

        sh.add("Edit-Profile", new String[]{"profile"}, NONE, "Edit-Profile [text]",
                "Zobrazí nebo doplní profil – skript spouštěný při startu ($apphome/profile.ps1).", (a, in) -> {
                    File f = new File(ctx.getFilesDir(), "profile.ps1");
                    if (!a.pos.isEmpty()) { writeText(f, a.rest(0) + "\n", true); return L("Přidáno do profilu."); }
                    return f.exists() ? Shell.lines(readText(f)) : L("Profil je prázdný. Příklad: Edit-Profile \"Set-Alias t Set-Flashlight\"");
                });
    }

    // ================================================================ POMOCNÉ ===

    static String req(String v, String msg) throws Exception { if (v == null || v.isEmpty()) throw new Exception(msg); return v; }

    static boolean like(String s, String pattern) {
        String re = "(?i)" + Pattern.quote(pattern).replace("*", "\\E.*\\Q").replace("?", "\\E.\\Q");
        return s.matches(re);
    }

    static int stream(String s) {
        switch (s.toLowerCase(Locale.ROOT)) {
            case "ring": case "ringtone": return AudioManager.STREAM_RING;
            case "notification": case "notify": return AudioManager.STREAM_NOTIFICATION;
            case "alarm": return AudioManager.STREAM_ALARM;
            case "call": case "voice": return AudioManager.STREAM_VOICE_CALL;
            case "system": return AudioManager.STREAM_SYSTEM;
            default: return AudioManager.STREAM_MUSIC;
        }
    }

    static int color(String c) {
        switch (c.toLowerCase(Locale.ROOT)) {
            case "red": return Color.rgb(231, 72, 86);
            case "darkred": return Color.rgb(197, 15, 31);
            case "green": return Color.rgb(22, 198, 12);
            case "darkgreen": return Color.rgb(19, 161, 14);
            case "yellow": return Color.rgb(249, 241, 165);
            case "darkyellow": return Color.rgb(193, 156, 0);
            case "blue": return Color.rgb(59, 120, 255);
            case "cyan": return Color.rgb(97, 214, 214);
            case "magenta": return Color.rgb(180, 0, 158);
            case "gray": case "grey": return Color.rgb(204, 204, 204);
            case "darkgray": return Color.rgb(118, 118, 118);
            case "black": return Color.BLACK;
            default: try { return Color.parseColor(c); } catch (Exception e) { return Color.WHITE; }
        }
    }

    static String findPackage(Context ctx, String q) {
        PackageManager pm = ctx.getPackageManager();
        try { pm.getPackageInfo(q, 0); return q; } catch (Exception ignored) {}
        String lq = q.toLowerCase(Locale.ROOT);
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        String partial = null;
        for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
            String lbl = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.ROOT);
            if (lbl.equals(lq)) return ri.activityInfo.packageName;
            if (partial == null && (lbl.contains(lq) || ri.activityInfo.packageName.toLowerCase(Locale.ROOT).contains(lq)))
                partial = ri.activityInfo.packageName;
        }
        return partial;
    }

    static String human(long b) {
        if (b < 1024) return b + " B";
        String[] u = {"KB", "MB", "GB", "TB"};
        double v = b; int i = -1;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return String.format(Locale.ROOT, "%.1f %s", v, u[i]);
    }

    static String uptime() {
        long s = SystemClock.elapsedRealtime() / 1000;
        return String.format(Locale.ROOT, "%d d %02d:%02d:%02d", s / 86400, (s / 3600) % 24, (s / 60) % 60, s % 60);
    }

    static String fileLine(File f) {
        String mode = (f.isDirectory() ? "d" : "-") + (f.canRead() ? "r" : "-") + (f.canWrite() ? "w" : "-") + (f.isHidden() ? "h" : "-") + "-";
        String date = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ROOT).format(new Date(f.lastModified()));
        String len = f.isDirectory() ? "" : human(f.length());
        return String.format(Locale.ROOT, "%-8s %-18s %10s %s", mode, date, len, f.getName() + (f.isDirectory() ? "/" : ""));
    }

    static void listDir(Shell sh, File base, File d, String filter, Shell.Args a, List<String> r, boolean names, int depth) {
        File[] fs = d.listFiles();
        if (fs == null) return;
        java.util.Arrays.sort(fs, (x, y) -> x.isDirectory() != y.isDirectory() ? (x.isDirectory() ? -1 : 1) : x.getName().compareToIgnoreCase(y.getName()));
        for (File f : fs) {
            if (sh.cancelled || r.size() > 20000) return;
            if (f.isHidden() && !a.has("force")) continue;
            boolean ok = like(f.getName(), filter) && !(a.has("file") && f.isDirectory()) && !(a.has("directory") && f.isFile());
            if (ok) {
                String rel = base.toURI().relativize(f.toURI()).getPath();
                r.add(names ? rel : (depth > 0 ? fileLine(f).replace(f.getName(), rel) : fileLine(f)));
            }
            if (a.has("recurse") && f.isDirectory() && depth < 30) listDir(sh, base, f, filter, a, r, names, depth + 1);
        }
    }

    static String readText(File f) throws Exception {
        if (!f.exists()) throw new Exception("Soubor '" + f + "' neexistuje.");
        if (f.isDirectory()) throw new Exception("'" + f + "' je složka.");
        try (InputStream is = new FileInputStream(f)) { return readStream(is); }
    }

    static String readStream(InputStream is) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384]; int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toString("UTF-8");
    }

    static void writeText(File f, String s, boolean append) throws Exception {
        try (OutputStream os = new FileOutputStream(f, append)) { os.write(s.getBytes(StandardCharsets.UTF_8)); }
    }

    static void deleteRec(File f) throws Exception {
        if (f.isDirectory()) { File[] c = f.listFiles(); if (c != null) for (File x : c) deleteRec(x); }
        if (!f.delete()) throw new Exception("Nelze smazat " + f);
    }

    static void copyRec(File s, File d) throws Exception {
        if (s.isDirectory()) {
            d.mkdirs();
            File[] c = s.listFiles(); if (c != null) for (File x : c) copyRec(x, new File(d, x.getName()));
            return;
        }
        try (InputStream is = new FileInputStream(s); OutputStream os = new FileOutputStream(d)) {
            byte[] buf = new byte[65536]; int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    /** Spustí příkaz přes /system/bin/sh; při stream=true vypisuje průběžně. */
    static List<String> runProcess(Shell sh, String cmd, List<String> in, boolean stream) throws Exception {
        if (cmd.trim().isEmpty()) throw new Exception("Zadejte příkaz.");
        ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", cmd);
        pb.directory(sh.cwd.canRead() ? sh.cwd : sh.act.getFilesDir());
        pb.redirectErrorStream(true);
        pb.environment().put("HOME", sh.vars.get("home"));
        Process p = pb.start();
        sh.current = p;
        try {
            if (in != null && !in.isEmpty()) {
                try (OutputStream os = p.getOutputStream()) { os.write((String.join("\n", in) + "\n").getBytes(StandardCharsets.UTF_8)); }
                catch (Exception ignored) {}
            } else p.getOutputStream().close();
            List<String> r = E();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (sh.cancelled) break;
                    if (stream) sh.out.write(line + "\n"); else r.add(line);
                }
            }
            int code = p.waitFor();
            sh.vars.put("lastexitcode", String.valueOf(code));
            if (code == 127 && r.size() <= 1 && !stream) {
                String first = cmd.trim().split("\\s+")[0];
                throw new Exception("Příkaz '" + first + "' nebyl rozpoznán jako cmdlet ani program. Zkuste Get-Help.");
            }
            return r;
        } finally {
            p.destroy();
            sh.current = null;
        }
    }

    // ================================================================ PODMÍNKY ===
    static final class Cond {
        private static final Pattern OP = Pattern.compile("\\s+-(not)?(match|like|eq|ne|gt|lt|ge|le|contains)\\s+", Pattern.CASE_INSENSITIVE);

        static boolean eval(Shell sh, String cond, String item) {
            String old = sh.vars.get("_");
            sh.vars.put("_", item);
            try {
                for (String orPart : cond.split("(?i)\\s+-or\\s+")) {
                    boolean all = true;
                    for (String andPart : orPart.split("(?i)\\s+-and\\s+")) if (!one(sh, andPart.trim(), item)) { all = false; break; }
                    if (all) return true;
                }
                return false;
            } finally { if (old == null) sh.vars.remove("_"); else sh.vars.put("_", old); }
        }

        private static String val(Shell sh, String s) {
            s = s.trim();
            if (s.length() >= 2 && (s.startsWith("'") && s.endsWith("'"))) return s.substring(1, s.length() - 1);
            if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\""))) return sh.expand(s.substring(1, s.length() - 1), false);
            String e = sh.expand(s, false);
            Double d = Calc.tryEval(e);
            return d != null && !e.matches("-?\\d+") ? Shell.fmt(d) : e;
        }

        private static boolean one(Shell sh, String c, String item) {
            if (c.startsWith("(") && c.endsWith(")")) c = c.substring(1, c.length() - 1).trim();
            boolean negate = false;
            if (c.toLowerCase(Locale.ROOT).startsWith("-not ")) { negate = true; c = c.substring(5).trim(); }
            else if (c.startsWith("!")) { negate = true; c = c.substring(1).trim(); }
            Matcher m = OP.matcher(c);
            if (!m.find()) {
                String v = val(sh, c);
                boolean truthy = !(v.isEmpty() || v.equals("0") || v.equalsIgnoreCase("false"));
                return negate != truthy;
            }
            String l = val(sh, c.substring(0, m.start())), r = val(sh, c.substring(m.end()));
            boolean not = m.group(1) != null;
            String op = m.group(2).toLowerCase(Locale.ROOT);
            boolean res;
            Double dl = Shell.num(l), dr = Shell.num(r);
            switch (op) {
                case "match": res = Pattern.compile(r, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(l).find(); break;
                case "like": res = like(l, r); break;
                case "contains": res = l.toLowerCase(Locale.ROOT).contains(r.toLowerCase(Locale.ROOT)); break;
                case "eq": res = dl != null && dr != null ? dl.doubleValue() == dr : l.equalsIgnoreCase(r); break;
                case "ne": res = dl != null && dr != null ? dl.doubleValue() != dr : !l.equalsIgnoreCase(r); break;
                case "gt": res = dl != null && dr != null ? dl > dr : l.compareToIgnoreCase(r) > 0; break;
                case "lt": res = dl != null && dr != null ? dl < dr : l.compareToIgnoreCase(r) < 0; break;
                case "ge": res = dl != null && dr != null ? dl >= dr : l.compareToIgnoreCase(r) >= 0; break;
                case "le": res = dl != null && dr != null ? dl <= dr : l.compareToIgnoreCase(r) <= 0; break;
                default: res = false;
            }
            return negate != (not != res);
        }
    }
}
