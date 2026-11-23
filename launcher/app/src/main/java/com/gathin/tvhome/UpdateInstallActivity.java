package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Receives PackageInstaller session callbacks via an Activity PendingIntent so Android 14+
 * permits both the install confirmation dialog and relaunching Home right after a self-update.
 */
public final class UpdateInstallActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        handleCallback(getIntent());
        finish();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleCallback(intent);
        finish();
    }

    private void handleCallback(Intent result) {
        if (result == null) return;
        int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            UpdateInstallReceiver.markPendingRelaunch(this, UpdateInstallReceiver.INSTALL_WINDOW_MS);
            Intent confirm = result.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                try { startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (RuntimeException ignored) {
                    UpdateInstallReceiver.clearPendingRelaunch(this);
                    Toast.makeText(this, R.string.update_failed, Toast.LENGTH_LONG).show();
                }
            }
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            UpdateInstallReceiver.clearPendingRelaunch(this);
            UpdateInstallReceiver.markLaunchAttempt();
            try {
                startActivity(UpdateInstallReceiver.buildHomeLaunchIntent(this));
            } catch (RuntimeException ignored) {
                UpdateInstallReceiver.relaunchAfterUpdate(this, null);
            }
        } else {
            UpdateInstallReceiver.clearPendingRelaunch(this);
            if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                Toast.makeText(this, R.string.update_failed, Toast.LENGTH_LONG).show();
            }
        }
    }
}
