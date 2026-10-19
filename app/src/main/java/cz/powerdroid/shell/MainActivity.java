package cz.powerdroid.shell;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Terminálové UI ve stylu PowerShellu. */
public class MainActivity extends Activity implements Shell.Output {

    static final int PS_BG = Color.rgb(1, 36, 86);
    private TextView out;
    private ScrollView scroll;
    private EditText input;
    private TextView promptView;
    private Shell shell;
    private final List<String> history = new ArrayList<>();
    private int histPos = 0;
    private final SpannableStringBuilder buffer = new SpannableStringBuilder();
    private float fontSize = 13f;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(PS_BG);
        getWindow().setNavigationBarColor(PS_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PS_BG);
        root.setFitsSystemWindows(true);

        scroll = new ScrollView(this);
        out = new TextView(this);
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextColor(Color.rgb(238, 237, 240));
        out.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize);
        out.setTextIsSelectable(true);
        out.setPadding(dp(8), dp(8), dp(8), dp(8));
        scroll.addView(out);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(8), 0, dp(4), 0);
        promptView = new TextView(this);
        promptView.setTypeface(Typeface.MONOSPACE);
        promptView.setTextColor(Color.rgb(255, 255, 0));
        promptView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize);
        line.addView(promptView);
        input = new EditText(this);
        input.setTypeface(Typeface.MONOSPACE);
        input.setTextColor(Color.WHITE);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_SEND || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && ev.getAction() == KeyEvent.ACTION_DOWN)) { submit(); return true; }
            return false;
        });
        input.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_UP) { histUp(); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) { histDown(); return true; }
            if (code == KeyEvent.KEYCODE_TAB) { complete(); return true; }
            return false;
        });
        line.addView(input, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(line);

        // Lišta s extra klávesami
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setBackgroundColor(Color.rgb(0, 24, 60));
        LinearLayout keys = new LinearLayout(this);
        String[] k = {"TAB", "↑", "↓", "Ctrl+C", "|", "$", "-", "\"", "{", "}", "~", "/", "cls", "A+", "A-"};
        for (String s : k) {
            Button btn = new Button(this, null, android.R.attr.borderlessButtonStyle);
            btn.setText(s);
            btn.setAllCaps(false);
            btn.setTextColor(Color.WHITE);
            btn.setMinWidth(0); btn.setMinimumWidth(dp(44));
            btn.setPadding(dp(10), 0, dp(10), 0);
            btn.setOnClickListener(v -> extraKey(s));
            keys.addView(btn, new LinearLayout.LayoutParams(-2, dp(42)));
        }
        hs.addView(keys);
        root.addView(hs);
        setContentView(root);

        shell = new Shell(this, this);
        printBanner();
        updatePrompt();
        input.requestFocus();
    }

    private void printBanner() {
        print("PowerDroid Shell 1.0 – PowerShell pro ovládání Androidu\n", Color.rgb(97, 214, 214));
        print("Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") · "
                + Build.MANUFACTURER + " " + Build.MODEL + "\n", Color.LTGRAY);
        print("Napište Get-Help pro seznam příkazů, Get-Help <příkaz> pro detail.\n\n", Color.LTGRAY);
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            print("Tip: pro plný přístup k souborům spusťte Request-Permission Storage\n\n", Color.rgb(249, 241, 165));
        }
    }

    private void extraKey(String s) {
        switch (s) {
            case "TAB": complete(); break;
            case "↑": histUp(); break;
            case "↓": histDown(); break;
            case "Ctrl+C": shell.cancel(); print("^C\n", Color.rgb(231, 72, 86)); break;
            case "cls": clear(); break;
            case "A+": setFont(fontSize + 1); break;
            case "A-": setFont(Math.max(8, fontSize - 1)); break;
            default:
                int st = Math.max(input.getSelectionStart(), 0);
                input.getText().insert(st, s);
        }
    }

    private void setFont(float f) {
        fontSize = f;
        out.setTextSize(TypedValue.COMPLEX_UNIT_SP, f);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, f);
        promptView.setTextSize(TypedValue.COMPLEX_UNIT_SP, f);
    }

    private void complete() {
        String t = input.getText().toString();
        int sp = t.lastIndexOf(' ');
        String prefix = t.substring(sp + 1);
        List<String> m = shell.complete(prefix, sp < 0);
        if (m.isEmpty()) return;
        if (m.size() == 1) {
            input.setText(t.substring(0, sp + 1) + m.get(0));
        } else {
            String common = m.get(0);
            for (String s : m) while (!s.toLowerCase().startsWith(common.toLowerCase())) common = common.substring(0, common.length() - 1);
            if (common.length() > prefix.length()) input.setText(t.substring(0, sp + 1) + common);
            print(String.join("   ", m) + "\n", Color.LTGRAY);
        }
        input.setSelection(input.getText().length());
    }

    private void histUp() {
        if (history.isEmpty()) return;
        histPos = Math.max(0, histPos - 1);
        input.setText(history.get(histPos));
        input.setSelection(input.getText().length());
    }

    private void histDown() {
        if (history.isEmpty()) return;
        histPos = Math.min(history.size(), histPos + 1);
        input.setText(histPos == history.size() ? "" : history.get(histPos));
        input.setSelection(input.getText().length());
    }

    private void submit() {
        String cmd = input.getText().toString();
        input.setText("");
        print(shell.prompt(), Color.rgb(255, 255, 0));
        print(cmd + "\n", Color.WHITE);
        if (!cmd.trim().isEmpty()) {
            if (history.isEmpty() || !history.get(history.size() - 1).equals(cmd)) history.add(cmd);
            shell.addHistory(cmd);
        }
        histPos = history.size();
        shell.execute(cmd);
    }

    List<String> getHistory() { return history; }

    private void print(CharSequence s, int color) {
        int st = buffer.length();
        buffer.append(s);
        buffer.setSpan(new ForegroundColorSpan(color), st, buffer.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (buffer.length() > 200_000) buffer.delete(0, buffer.length() - 150_000);
        out.setText(buffer);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    // ---- Shell.Output ----
    @Override public void write(String s) { runOnUiThread(() -> print(s, Color.rgb(238, 237, 240))); }
    @Override public void writeColor(String s, int c) { runOnUiThread(() -> print(s, c)); }
    @Override public void error(String s) { runOnUiThread(() -> print(s + "\n", Color.rgb(231, 72, 86))); }
    @Override public void clear() { runOnUiThread(() -> { buffer.clear(); out.setText(""); }); }
    @Override public void done() { runOnUiThread(this::updatePrompt); }
    @Override public void exit() { runOnUiThread(this::finishAndRemoveTask); }

    private void updatePrompt() { promptView.setText(shell.prompt()); }

    void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override protected void onDestroy() { shell.shutdown(); super.onDestroy(); }
}
