package com.gathin.tvhome;

import android.accounts.AbstractAccountAuthenticator;
import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

/** Dummy account authenticator for {@link RevivalSync}. */
public class RevivalAccountService extends Service {
    private Authenticator authenticator;

    @Override
    public IBinder onBind(Intent intent) {
        if (authenticator == null) authenticator = new Authenticator(this);
        return authenticator.getIBinder();
    }

    private static final class Authenticator extends AbstractAccountAuthenticator {
        Authenticator(Context context) {
            super(context);
        }

        @Override
        public Bundle editProperties(AccountAuthenticatorResponse response, String accountType) {
            return null;
        }

        @Override
        public Bundle addAccount(AccountAuthenticatorResponse response, String accountType,
                String authTokenType, String[] requiredFeatures, Bundle options) {
            return null;
        }

        @Override
        public Bundle confirmCredentials(AccountAuthenticatorResponse response, Account account,
                Bundle options) {
            return null;
        }

        @Override
        public Bundle getAuthToken(AccountAuthenticatorResponse response, Account account,
                String authTokenType, Bundle options) {
            return null;
        }

        @Override
        public String getAuthTokenLabel(String authTokenType) {
            return null;
        }

        @Override
        public Bundle updateCredentials(AccountAuthenticatorResponse response, Account account,
                String authTokenType, Bundle options) {
            return null;
        }

        @Override
        public Bundle hasFeatures(AccountAuthenticatorResponse response, Account account,
                String[] features) {
            Bundle b = new Bundle();
            b.putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false);
            return b;
        }
    }
}
