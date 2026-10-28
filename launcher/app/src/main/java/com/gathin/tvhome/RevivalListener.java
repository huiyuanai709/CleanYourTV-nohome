package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;

/**
 * System-bound notification listener that re-enables {@link HomeKeyService} when the process is
 * woken (Aerial RevivalListener). Also watches accessibility settings and polls: QQ Music / TCL
 * clear the enabled list while FreezeManager freezes the UI process.
 */
public class RevivalListener extends NotificationListenerService {
    private static final long POLL_MS = 8_000L;

    private BroadcastReceiver unlockReceiver;
    private ContentObserver watcher;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            ServiceRepair.ensureAccessibility(RevivalListener.this);
            ServiceRepair.ensureNotificationListener(RevivalListener.this);
            handler.postDelayed(this, POLL_MS);
        }
    };

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
        handler.removeCallbacks(poll);
        if (watcher != null) {
            try {
                getContentResolver().unregisterContentObserver(watcher);
            } catch (RuntimeException ignored) {
            }
            watcher = null;
        }
        super.onDestroy();
    }

    private void revive() {
        ServiceRepair.restore(this);
        startWatchingSettings();
        handler.removeCallbacks(poll);
        handler.postDelayed(poll, POLL_MS);
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> ServiceRepair.rebindIfStale(getApplicationContext()), 5000L);
    }

    private void startWatchingSettings() {
        if (watcher != null) return;
        if (!ServiceRepair.hasSecureWrite(this)) return;
        watcher = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                onChange(selfChange, null);
            }

            @Override
            public void onChange(boolean selfChange, Uri uri) {
                ServiceRepair.ensureAccessibility(getApplicationContext());
            }
        };
        try {
            getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                    false, watcher);
            getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
                    false, watcher);
        } catch (RuntimeException e) {
            watcher = null;
        }
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
