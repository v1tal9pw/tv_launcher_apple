package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;

/** Downloads a verified Release APK and hands installation to Android for user confirmation. */
public final class UpdateActivity extends Activity {
    private static final int UNKNOWN_SOURCE_REQUEST = 41;
    private File downloaded;
    private AlertDialog progress;

    static Intent intent(Activity from, ReleaseUpdater.Release release) {
        return new Intent(from, UpdateActivity.class)
                .putExtra("url", release.url).putExtra("sha256", release.sha256)
                .putExtra("code", release.code);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        progress = new AlertDialog.Builder(this).setMessage(R.string.update_downloading).create();
        progress.setCancelable(false);
        progress.show();
        String address = getIntent().getStringExtra("url");
        String expected = getIntent().getStringExtra("sha256");
        int code = getIntent().getIntExtra("code", -1);
        new Thread(() -> {
            File file = null;
            try {
                file = download(address, expected, code);
                File ready = file;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    downloaded = ready;
                    progress.dismiss();
                    requestInstall();
                });
            } catch (Exception error) {
                new File(getCacheDir(), "release-update.apk").delete();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    progress.dismiss();
                    Toast.makeText(this, R.string.update_failed, Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        }, "tvhome-update").start();
    }

    private File download(String address, String expected, int code) throws Exception {
        if (address == null || !address.startsWith("https://github.com/" + ReleaseUpdater.REPOSITORY
                + "/releases/download/") || expected == null || !expected.matches("[0-9a-f]{64}")
                || code < 1) throw new SecurityException("Invalid release");
        URL url = new URL(address);
        File target = new File(getCacheDir(), "release-update.apk");
        for (int redirect = 0; redirect < 5; redirect++) {
            String host = url.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equals(url.getProtocol()) || !(host.equals("github.com")
                    || host.endsWith(".githubusercontent.com"))) throw new SecurityException("Invalid download host");
            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(8000); connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent", "TvHome-Updater");
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new java.io.IOException("Missing redirect");
                    url = new URL(url, location);
                    continue;
                }
                if (status != 200) throw new java.io.IOException("Release unavailable");
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                int total = 0;
                try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(target)) {
                    byte[] bytes = new byte[8192]; int count;
                    while ((count = in.read(bytes)) != -1) {
                        total += count;
                        if (total > 5 * 1024 * 1024) throw new java.io.IOException("APK too large");
                        sha.update(bytes, 0, count);
                        out.write(bytes, 0, count);
                    }
                }
                StringBuilder actual = new StringBuilder();
                for (byte b : sha.digest()) actual.append(String.format(Locale.ROOT, "%02x", b & 0xff));
                if (total == 0 || !expected.equals(actual.toString())) throw new SecurityException("APK digest mismatch");
                PackageInfo archive = getPackageManager().getPackageArchiveInfo(target.getAbsolutePath(), 0);
                if (archive == null || !getPackageName().equals(archive.packageName) || archive.versionCode != code)
                    throw new SecurityException("Wrong APK package or version");
                return target;
            } finally { connection.disconnect(); }
        }
        throw new java.io.IOException("Too many redirects");
    }

    private void requestInstall() {
        if (!getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, R.string.update_allow_installs, Toast.LENGTH_LONG).show();
            try {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())), UNKNOWN_SOURCE_REQUEST);
            } catch (Exception unavailable) { finish(); }
            return;
        }
        install();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == UNKNOWN_SOURCE_REQUEST) {
            if (getPackageManager().canRequestPackageInstalls()) install();
            else finish();
        }
    }

    private void install() {
        File apk = downloaded;
        if (apk == null || !apk.isFile()) { finish(); return; }
        downloaded = null;
        new Thread(() -> {
            try {
                PackageInstaller installer = getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(getPackageName());
                params.setSize(apk.length());
                int id = installer.createSession(params);
                try (PackageInstaller.Session session = installer.openSession(id)) {
                    try (InputStream in = new FileInputStream(apk);
                         OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                        byte[] bytes = new byte[8192]; int count;
                        while ((count = in.read(bytes)) != -1) out.write(bytes, 0, count);
                        session.fsync(out);
                    }
                    Intent callback = new Intent(this, UpdateInstallActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    PendingIntent pending = PendingIntent.getActivity(this, id, callback,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                    UpdateInstallReceiver.markPendingRelaunch(this, UpdateInstallReceiver.INSTALL_WINDOW_MS);
                    session.commit(pending.getIntentSender());
                }
                runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) finish(); });
            } catch (Exception error) {
                UpdateInstallReceiver.clearPendingRelaunch(this);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, R.string.update_failed, Toast.LENGTH_LONG).show();
                    finish();
                });
            } finally {
                apk.delete();
            }
        }, "tvhome-install").start();
    }

    @Override protected void onDestroy() {
        if (progress != null && progress.isShowing()) progress.dismiss();
        progress = null;
        super.onDestroy();
    }
}
