package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.AtomicFile;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Opt-in shell access; one package per confirmation, private backup before every change. */
public final class OptimizationActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile LocalOptimizationService remote;
    private boolean busy;
    private volatile boolean preferLocal;
    private volatile boolean closed;
    private boolean navigationSounds;
    private LinearLayout rows;
    private SharedPreferences journal;
    private JSONArray packages = new JSONArray();
    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(LauncherLocale.wrap(base)); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        navigationSounds = Config.load(this).navigationSounds;
        if (navigationSounds) NavSound.prepare(this);
        journal = getSharedPreferences("optimization_restore", MODE_PRIVATE);
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(0xFF161A23);
        rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(48), dp(24), dp(48), dp(24)); scroll.addView(rows); setContentView(scroll);
        preferLocal = getSharedPreferences("optimization_access", MODE_PRIVATE).getBoolean("local", false);
        render();
    }
    @Override protected void onResume() {
        super.onResume();
        navigationSounds = Config.load(this).navigationSounds;
        if (navigationSounds) NavSound.prepare(this);
        if (remote != null) refresh(); else connect();
    }
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        return NavSound.dispatchActivityKey(this, event, navigationSounds, () -> super.dispatchKeyEvent(event));
    }
    private void connect() {
        if (preferLocal) {
            run(() -> {
                remote = new LocalOptimizationService(this);
                packages = new JSONArray(remote.inventory());
            });
            return;
        }
        render();
    }
    private void localSetup() {
        new AlertDialog.Builder(this).setTitle(R.string.optimize_local_connect)
                .setMessage(R.string.optimize_local_setup)
                .setPositiveButton(R.string.optimize_local_connect, (d, w) -> run(() -> {
                    if (!"2000".equals(LocalAdbClient.run(this, "id -u")))
                        throw new IllegalStateException("ADB_USER_REQUIRED");
                    preferLocal = true;
                    remote = new LocalOptimizationService(this);
                    packages = new JSONArray(remote.inventory());
                    getSharedPreferences("optimization_access", MODE_PRIVATE).edit().putBoolean("local", true).apply();
                }))
                .setNeutralButton(R.string.optimize_open_settings, (d, w) ->
                        launch(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)))
                .setNegativeButton(android.R.string.cancel, null).show();
    }
    private void closeLocalAdb() {
        run(() -> {
            try { LocalAdbClient.run(this, "settings put global adb_wifi_enabled 0; settings put global adb_enabled 0"); }
            catch (Exception ignored) { /* adbd may close its socket before replying. */ }
            Thread.sleep(600);
            if (LocalAdbClient.portOpen()) throw new IllegalStateException("ADB_PORT_STILL_OPEN");
            preferLocal = false; remote = null; packages = new JSONArray();
            getSharedPreferences("optimization_access", MODE_PRIVATE).edit().putBoolean("local", false).apply();
        });
    }
    private void render() {
        if (closed || rows == null) return;
        rows.removeAllViews(); text(getString(R.string.optimize_title), 26);
        text(getString(R.string.optimize_help), 16);
        if (busy) text(getString(R.string.optimize_working), 16);
        button(getString(preferLocal ? R.string.optimize_local_connected : R.string.optimize_local_connect), this::localSetup);
        if (preferLocal) button(getString(R.string.optimize_local_close), this::closeLocalAdb);
        button(getString(R.string.optimize_refresh), this::refresh);
        button(getString(R.string.optimize_profile), this::chooseCleanup);
        button(getString(R.string.optimize_restore_all), this::restoreAll);
        text(getString(R.string.optimize_profile_source), 14);
        if (remote != null && packages.length() == 0) text(getString(R.string.optimize_no_candidates), 16);
        text(getString(R.string.optimize_protected), 14);
        if (rows.getChildCount() > 2) rows.getChildAt(2).requestFocus();
    }
    private void text(String value, int size) {
        TextView label = new TextView(this); label.setText(value); label.setTextColor(0xFFE5E5EA);
        label.setTextSize(size); label.setPadding(dp(8), dp(12), dp(8), dp(12)); rows.addView(label);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void button(String label, Runnable action) {
        SettingsRowView b = (SettingsRowView) getLayoutInflater().inflate(R.layout.optimization_action, rows, false);
        b.setText(label); b.setValue("", true); b.setEnabled(!busy);
        b.setSoundEffectsEnabled(navigationSounds);
        b.setOnClickListener(v -> action.run()); rows.addView(b);
    }
    private void refresh() {
        if (remote == null) { localSetup(); return; }
        run(() -> packages = new JSONArray(remote.inventory()));
    }
    private void chooseCleanup() {
        if (remote == null) { localSetup(); return; }
        java.util.List<String> choices = new java.util.ArrayList<>();
        java.util.List<String> labels = new java.util.ArrayList<>();
        for (int i = 0; i < packages.length(); i++) {
            JSONObject item = packages.optJSONObject(i);
            if (item == null) continue;
            String pkg = item.optString("package");
            if (QuickSettingsActions.SYSTEM_CLEANER.equals(pkg)) continue;
            int state = item.optInt("state", -1);
            if (!OptimizationPolicy.tclCandidate(pkg) || (state != 0 && state != 1)
                    || journal.contains(pkg)) continue;
            choices.add(pkg);
            String label = pkg;
            try { label = getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0)).toString(); }
            catch (PackageManager.NameNotFoundException ignored) { }
            labels.add(label + "\n" + pkg + "\n" + getString(OptimizationPolicy.guidance(pkg)));
        }
        if (choices.isEmpty()) { message(getString(R.string.optimize_no_candidates)); return; }
        boolean[] checked = new boolean[choices.size()];
        new AlertDialog.Builder(this).setTitle(R.string.optimize_profile)
                .setMultiChoiceItems(labels.toArray(new String[0]), checked,
                        (dialog, index, value) -> checked[index] = value)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.optimize_apply_selected, (dialog, which) -> {
                    java.util.List<String> selected = new java.util.ArrayList<>();
                    for (int i = 0; i < checked.length; i++) if (checked[i]) selected.add(choices.get(i));
                    if (selected.isEmpty()) return;
                    new AlertDialog.Builder(this).setTitle(R.string.optimize_apply_selected)
                            .setMessage(getString(R.string.optimize_batch_warning) + "\n\n"
                                    + selected.stream().map(pkg -> pkg + "\n"
                                            + getString(OptimizationPolicy.guidance(pkg)))
                                            .collect(java.util.stream.Collectors.joining("\n\n")))
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(android.R.string.ok, (d, w) -> run(() -> {
                                for (String pkg : selected) {
                                    if (closed) break;
                                    changePackage(pkg, false);
                                }
                                packages = new JSONArray(remote.inventory());
                                runOnUiThread(() -> message(getString(R.string.optimize_complete)));
                            })).show();
                }).show();
    }

    private void restoreAll() {
        if (remote == null) { localSetup(); return; }
        java.util.List<String> recorded = new java.util.ArrayList<>(journal.getAll().keySet());
        if (recorded.isEmpty()) { message(getString(R.string.optimize_nothing_to_restore)); return; }
        new AlertDialog.Builder(this).setTitle(R.string.optimize_restore_all)
                .setMessage(getString(R.string.optimize_restore_help) + "\n\n"
                        + android.text.TextUtils.join("\n", recorded))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> run(() -> {
                    for (String pkg : recorded) {
                        if (closed) break;
                        changePackage(pkg, true);
                    }
                    packages = new JSONArray(remote.inventory());
                    runOnUiThread(() -> message(getString(R.string.optimize_complete)));
                })).show();
    }

    private void changePackage(String pkg, boolean restore) throws Exception {
        LocalOptimizationService service = remote;
        if (service == null) throw new IllegalStateException("ACCESS_LOST");
        if (restore) {
            service.restore(pkg, journal.getInt(pkg, -1));
            if (!journal.edit().remove(pkg).commit()) throw new IllegalStateException("JOURNAL_WRITE_FAILED");
            return;
        }
        String backup = service.prepare(pkg);
        JSONObject data = new JSONObject(backup);
        File directory = new File(getFilesDir(), "optimization-backups");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("BACKUP_FAILED");
        AtomicFile file = new AtomicFile(new File(directory, pkg + ".json"));
        FileOutputStream out = file.startWrite();
        try { out.write(backup.getBytes(StandardCharsets.UTF_8)); file.finishWrite(out); }
        catch (Exception e) { file.failWrite(out); throw e; }
        if (!journal.edit().putInt(pkg, data.getInt("state")).commit())
            throw new IllegalStateException("BACKUP_FAILED");
        if (!closed) service.freeze(pkg);
    }

    private interface Work { void execute() throws Exception; }
    private void run(Work action) {
        if (busy) return; busy = true; render();
        worker.execute(() -> {
            String error = null;
            try { action.execute(); } catch (Exception e) {
                error = e.getMessage() == null ? "OPERATION_FAILED" : e.getMessage();
                try { if (remote != null) packages = new JSONArray(remote.inventory()); }
                catch (Exception ignored) { remote = null; packages = new JSONArray(); }
            }
            String result = error;
            runOnUiThread(() -> {
                busy = false; if (closed) return; render();
                if (result != null) message(getString(R.string.optimize_failed) + "\n" + result);
            });
        });
    }
    private void launch(Intent intent) {
        try { startActivity(intent); } catch (RuntimeException e) { message(getString(R.string.not_available)); }
    }
    private void message(String value) { if (!closed) new AlertDialog.Builder(this).setMessage(value).setPositiveButton(android.R.string.ok, null).show(); }
    @Override protected void onDestroy() {
        closed = true;
        worker.shutdown(); super.onDestroy();
    }
}
