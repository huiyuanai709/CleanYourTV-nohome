package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/**
 * After boot or an update of this package, re-arm HomeKeyService via {@link ServiceRepair}
 * and bring the launcher up.
 * Matches Aerial BootReceiver: when the accessibility helper is already bound, ask it to look
 * again (HomeKeyService.onBootCompleted); only start MainActivity from here if it is not.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final long[] BOOT_RECHECK_MS = {30_000L, 45_000L};

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        // Aerial: WRITE_SECURE_SETTINGS + ServiceRepair puts HomeKeyService back after boot,
        // force-stop, or an update that cleared enabled_accessibility_services (TCL MultiScreen).
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            ServiceRepair.restore(context);
            Handler handler = new Handler(Looper.getMainLooper());
            for (long delay : BOOT_RECHECK_MS) {
                handler.postDelayed(() -> ServiceRepair.restore(context.getApplicationContext()), delay);
            }
            return;
        }
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            return;
        }
        ServiceRepair.restore(context);
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) return;
        Handler handler = new Handler(Looper.getMainLooper());
        for (long delay : BOOT_RECHECK_MS) {
            handler.postDelayed(() -> ServiceRepair.restore(context.getApplicationContext()), delay);
        }
        // Aerial: if HomeGuard is already running, it takes over again at BOOT_COMPLETED
        // (bound ~20s earlier on some firmwares; probe-at-bind may have missed cyberui).
        if (HomeKeyService.onBootCompleted()) return;
        try {
            context.startActivity(new Intent(context, MainActivity.class)
                    .setAction(Intent.ACTION_MAIN)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (RuntimeException ignored) {
        }
    }
}
