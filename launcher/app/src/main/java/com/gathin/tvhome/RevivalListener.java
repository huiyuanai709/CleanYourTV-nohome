package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.service.notification.NotificationListenerService;

/**
 * System-bound notification listener that re-enables {@link HomeKeyService} when the process is
 * woken (Aerial RevivalListener, clean-room). Needs WRITE_SECURE_SETTINGS to rewrite settings.
 */
public class RevivalListener extends NotificationListenerService {
    private BroadcastReceiver unlockReceiver;

    @Override
    public void onListenerConnected() {
        if (userUnlocked()) {
            revive();
            return;
        }
        if (unlockReceiver != null) return;
        unlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                stopWaiting();
                revive();
            }
        };
        registerReceiver(unlockReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
        if (userUnlocked()) {
            stopWaiting();
            revive();
        }
    }

    @Override
    public void onDestroy() {
        stopWaiting();
        super.onDestroy();
    }

    private void revive() {
        ServiceRepair.restore(this);
        // If listed but not bound, force a rebind shortly after.
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> ServiceRepair.rebindIfStale(getApplicationContext()), 5000L);
    }

    private boolean userUnlocked() {
        UserManager users = getSystemService(UserManager.class);
        return users == null || users.isUserUnlocked();
    }

    private void stopWaiting() {
        if (unlockReceiver == null) return;
        try {
            unregisterReceiver(unlockReceiver);
        } catch (RuntimeException ignored) {
        }
        unlockReceiver = null;
    }
}
