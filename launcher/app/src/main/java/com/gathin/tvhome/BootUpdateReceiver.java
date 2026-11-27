package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Runs a background release check whenever the TV finishes booting. */
public final class BootUpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)) return;
        PendingResult pending = goAsync();
        ReleaseUpdater.checkOnPowerOn(context, null, pending::finish);
    }
}
