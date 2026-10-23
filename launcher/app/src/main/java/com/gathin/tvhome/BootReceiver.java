package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/**
 * After boot, re-arm HomeKeyService via {@link ServiceRepair} and bring the launcher up if the
 * accessibility service is not yet bound (Aerial BootReceiver, clean-room).
 */
public class BootReceiver extends BroadcastReceiver {
    private static final long[] BOOT_RECHECK_MS = {30_000L, 45_000L};

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
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
        if (HomeKeyService.isRunning()) return;
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
