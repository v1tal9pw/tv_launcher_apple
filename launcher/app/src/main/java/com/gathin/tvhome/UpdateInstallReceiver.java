package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Starts Android's confirmation screen when PackageInstaller requests user action. */
public final class UpdateInstallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent result) {
        int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = result.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                try { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (RuntimeException ignored) {
                    Toast.makeText(context, R.string.update_failed, Toast.LENGTH_LONG).show();
                }
            }
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(context, R.string.update_failed, Toast.LENGTH_LONG).show();
        }
    }
}
