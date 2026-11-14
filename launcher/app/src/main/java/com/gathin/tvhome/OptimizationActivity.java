package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
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
import rikka.shizuku.Shizuku;

/** Opt-in shell access; one package per confirmation, private backup before every change. */
public final class OptimizationActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile IOptimizationService remote;
    private boolean binding, busy;
    private volatile boolean preferLocal;
    private volatile boolean closed;
    private LinearLayout rows;
    private SharedPreferences journal;
    private JSONArray packages = new JSONArray();
    private Shizuku.UserServiceArgs args;
    private final Shizuku.OnRequestPermissionResultListener permission = (code, result) -> {
        if (code == 50 && !closed) connect();
    };
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (closed || preferLocal) return;
            remote = IOptimizationService.Stub.asInterface(binder); refresh();
        }
        public void onServiceDisconnected(ComponentName name) {
            if (!preferLocal) remote = null;
            binding = false;
            if (!closed) render();
        }
    };
    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(LauncherLocale.wrap(base)); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        journal = getSharedPreferences("optimization_restore", MODE_PRIVATE);
        args = new Shizuku.UserServiceArgs(new ComponentName(this, OptimizationService.class))
                .daemon(false).processNameSuffix("optimizer").version(1);
        Shizuku.addRequestPermissionResultListener(permission);
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(0xFF161A23);
        rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(48), dp(24), dp(48), dp(24)); scroll.addView(rows); setContentView(scroll);
        render();
    }
    @Override protected void onResume() {
        super.onResume();
        if (remote != null) refresh(); else connect();
    }
    private void connect() {
        if (preferLocal) return;
        try {
            if (Shizuku.pingBinder() && Shizuku.getUid() == 2000
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED && !binding) {
                binding = true; Shizuku.bindUserService(args, connection);
            }
        } catch (RuntimeException e) { binding = false; }
        render();
    }
    private void authorize() {
        preferLocal = false;
        if (binding && remote == null) {
            try { Shizuku.unbindUserService(args, connection, true); } catch (RuntimeException ignored) { }
            binding = false;
        }
        try {
            if (!Shizuku.pingBinder()) { setup(); return; }
            if (Shizuku.getUid() != 2000) { message(getString(R.string.optimize_adb_only)); return; }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(50);
            else connect();
        } catch (RuntimeException e) { message(getString(R.string.optimize_access_error)); }
    }
    private void setup() {
        new AlertDialog.Builder(this).setTitle("Shizuku").setMessage(R.string.optimize_setup)
                .setPositiveButton(R.string.optimize_open_helper, (d, w) -> {
                    Intent open = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                    if (open == null) open = new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"));
                    launch(open);
                }).setNegativeButton(android.R.string.cancel, null).show();
    }
    private void localSetup() {
        new AlertDialog.Builder(this).setTitle(R.string.optimize_local_connect)
                .setMessage(R.string.optimize_local_setup)
                .setPositiveButton(R.string.optimize_local_connect, (d, w) -> run(() -> {
                    if (!"2000".equals(LocalAdbClient.run(this, "id -u")))
                        throw new IllegalStateException("ADB_USER_REQUIRED");
                    try { LocalAdbClient.run(this, "pm grant " + getPackageName()
                            + " android.permission.WRITE_SECURE_SETTINGS"); }
                    catch (Exception ignored) { /* The manual grant action remains available on restrictive firmware. */ }
                    preferLocal = true;
                    remote = new LocalOptimizationService(this);
                    packages = new JSONArray(remote.inventory());
                }))
                .setNeutralButton(R.string.optimize_open_settings, (d, w) ->
                        launch(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)))
                .setNegativeButton(android.R.string.cancel, null).show();
    }
    private void grantLocalPermission() {
        run(() -> LocalAdbClient.run(this, "pm grant " + getPackageName()
                + " android.permission.WRITE_SECURE_SETTINGS"));
    }
    private void setLocalHome() {
        run(() -> {
            try { LocalAdbClient.run(this, "cmd role add-role-holder --user 0 android.app.role.HOME "
                    + getPackageName()); } catch (Exception ignored) { /* Some TV builds lack RoleManager. */ }
            LocalAdbClient.run(this, "cmd package set-home-activity --user 0 "
                    + getPackageName() + "/.MainActivity");
            String resolved = LocalAdbClient.run(this, "cmd package resolve-activity --brief --user 0"
                    + " -a android.intent.action.MAIN -c android.intent.category.HOME");
            if (!resolved.contains(getPackageName() + "/"))
                throw new IllegalStateException("SET_DEFAULT_HOME_FAILED");
        });
    }
    private void closeLocalAdb() {
        run(() -> {
            try { LocalAdbClient.run(this, "settings put global adb_enabled 0"); }
            catch (Exception ignored) { /* adbd may close its socket before replying. */ }
            Thread.sleep(600);
            if (LocalAdbClient.portOpen()) throw new IllegalStateException("ADB_PORT_STILL_OPEN");
            preferLocal = false; remote = null; packages = new JSONArray();
        });
    }
    private void render() {
        if (closed || rows == null) return;
        rows.removeAllViews(); text(getString(R.string.optimize_title), 26);
        text(getString(R.string.optimize_help), 16);
        button(getString(preferLocal ? R.string.optimize_local_connected : R.string.optimize_local_connect), this::localSetup);
        if (preferLocal) {
            if (checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                    == PackageManager.PERMISSION_GRANTED) text(getString(R.string.optimize_local_granted), 14);
            else button(getString(R.string.optimize_local_grant), this::grantLocalPermission);
            button(getString(R.string.optimize_local_home), this::setLocalHome);
            button(getString(R.string.optimize_local_close), this::closeLocalAdb);
        } else button(getString(remote == null ? R.string.optimize_connect : R.string.optimize_connected), this::authorize);
        button(getString(R.string.optimize_cache), () -> launch(new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)));
        button(getString(R.string.optimize_default_home), () -> launch(new Intent(this, LauncherSettingsActivity.class)));
        button(getString(R.string.optimize_refresh), this::refresh);
        for (int i = 0; i < packages.length(); i++) {
            JSONObject item = packages.optJSONObject(i); if (item == null) continue;
            String pkg = item.optString("package"); int state = item.optInt("state", -1);
            String label = getString(OptimizationPolicy.launcher(pkg) ? R.string.optimize_stock_home
                    : pkg.startsWith("com.tcl.") ? R.string.optimize_tcl_extras : R.string.optimize_recommendations);
            text(label + "\n" + pkg, 17);
            if (journal.contains(pkg)) button(getString(R.string.optimize_restore), () -> confirm(pkg, true));
            else if (state == 0 || state == 1) button(getString(R.string.optimize_freeze), () -> confirm(pkg, false));
            else text(getString(R.string.optimize_disabled_before), 14);
        }
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
        b.setOnClickListener(v -> action.run()); rows.addView(b);
    }
    private void refresh() {
        if (remote == null) { localSetup(); return; }
        run(() -> packages = new JSONArray(remote.inventory()));
    }
    private void confirm(String pkg, boolean restore) {
        if (remote == null) { localSetup(); return; }
        new AlertDialog.Builder(this).setTitle(restore ? R.string.optimize_restore : R.string.optimize_freeze)
                .setMessage(pkg + "\n\n" + getString(restore ? R.string.optimize_restore_help
                        : OptimizationPolicy.launcher(pkg) ? R.string.optimize_home_warning : R.string.optimize_recommendations_warning))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> run(() -> {
                    IOptimizationService service = remote;
                    if (service == null) throw new IllegalStateException("ACCESS_LOST");
                    if (restore) {
                        int original = journal.getInt(pkg, -1);
                        service.restore(pkg, original);
                        if (!journal.edit().remove(pkg).commit()) throw new IllegalStateException("JOURNAL_WRITE_FAILED");
                    } else {
                        String backup = service.prepare(pkg);
                        JSONObject data = new JSONObject(backup);
                        File directory = new File(getFilesDir(), "optimization-backups");
                        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("BACKUP_FAILED");
                        AtomicFile file = new AtomicFile(new File(directory, pkg + ".json"));
                        FileOutputStream out = file.startWrite();
                        try { out.write(backup.getBytes(StandardCharsets.UTF_8)); file.finishWrite(out); }
                        catch (Exception e) { file.failWrite(out); throw e; }
                        if (!journal.edit().putInt(pkg, data.getInt("state")).commit()) throw new IllegalStateException("BACKUP_FAILED");
                        if (closed) return;
                        service.freeze(pkg);
                    }
                    packages = new JSONArray(service.inventory());
                })).show();
    }
    private interface Work { void execute() throws Exception; }
    private void run(Work action) {
        if (busy) return; busy = true; render();
        worker.execute(() -> {
            String error = null;
            try { action.execute(); } catch (Exception e) {
                error = e.getMessage() == null ? "OPERATION_FAILED" : e.getMessage();
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
        closed = true; Shizuku.removeRequestPermissionResultListener(permission);
        try { if (binding) Shizuku.unbindUserService(args, connection, true); } catch (RuntimeException ignored) { }
        worker.shutdown(); super.onDestroy();
    }
}
