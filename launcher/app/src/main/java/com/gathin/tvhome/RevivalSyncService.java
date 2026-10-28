package com.gathin.tvhome;

import android.accounts.Account;
import android.app.Service;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.SyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * Sync adapter: SettingsProvider syncs after accessibility is cleared; we put HomeKeyService back.
 */
public class RevivalSyncService extends Service {
    private Adapter adapter;

    @Override
    public IBinder onBind(Intent intent) {
        if (adapter == null) adapter = new Adapter(getApplicationContext());
        return adapter.getSyncAdapterBinder();
    }

    private static final class Adapter extends AbstractThreadedSyncAdapter {
        Adapter(Context context) {
            super(context, false);
        }

        @Override
        public void onPerformSync(Account account, Bundle extras, String authority,
                ContentProviderClient provider, SyncResult result) {
            Context context = getContext();
            if (!RevivalSync.isUserUnlocked(context)) return;
            boolean restored = ServiceRepair.ensureAccessibility(context);
            if (HomeKeyService.isRunning()) {
                if (restored) Log.i(RevivalSync.TAG, "restored accessibility list while HomeKeyService running");
                return;
            }
            if (!restored && !ServiceRepair.hasSecureWrite(context)) return;
            // ensureAccessibility returns false when already listed; still rebind if not running.
            ServiceRepair.ensureAccessibility(context);
            Log.i(RevivalSync.TAG, "revived by the sync framework");
            ServiceRepair.startWatching(context);
            RevivalSync.ensure(context);
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> ServiceRepair.rebindIfStale(context.getApplicationContext()), 5_000L);
        }
    }
}
