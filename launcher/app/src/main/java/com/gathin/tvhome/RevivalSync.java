package com.gathin.tvhome;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentResolver;
import android.content.Context;
import android.os.UserManager;
import android.util.Log;

/**
 * Aerial RevivalSync setup: register a dummy account so settings writes wake {@link RevivalSyncService}.
 */
final class RevivalSync {
    static final String TAG = "CleanYourTVRevive";
    static final String ACCOUNT_TYPE = "com.gathin.tvhome.revival";
    static final String AUTHORITY = "settings";

    private RevivalSync() {
    }

    static void ensure(Context context) {
        if (!isUserUnlocked(context)) return;
        try {
            AccountManager accounts = AccountManager.get(context);
            Account[] existing = accounts.getAccountsByType(ACCOUNT_TYPE);
            Account account;
            if (existing.length > 0) {
                account = existing[0];
            } else {
                account = new Account("CleanYourTV", ACCOUNT_TYPE);
                if (!accounts.addAccountExplicitly(account, null, null)) return;
                Log.i(TAG, "added sync account for accessibility revival");
            }
            if (ContentResolver.getIsSyncable(account, AUTHORITY) <= 0) {
                ContentResolver.setIsSyncable(account, AUTHORITY, 1);
            }
            if (!ContentResolver.getSyncAutomatically(account, AUTHORITY)) {
                ContentResolver.setSyncAutomatically(account, AUTHORITY, true);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "could not set up sync revival", e);
        }
    }

    static boolean isUserUnlocked(Context context) {
        UserManager users = context.getSystemService(UserManager.class);
        return users == null || users.isUserUnlocked();
    }
}
