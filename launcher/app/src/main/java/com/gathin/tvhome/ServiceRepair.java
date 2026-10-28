package com.gathin.tvhome;

import android.Manifest;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Puts accessibility helpers back after a force-stop or TCL FreezeManager clear, and watches
 * {@link Settings.Secure#ENABLED_ACCESSIBILITY_SERVICES} so HomeKeyService stays enabled when
 * {@code restore_key_services} and WRITE_SECURE_SETTINGS are granted (Aerial-style revival, clean-room).
 */
final class ServiceRepair {
    private static final String PREFS = "accessibility_helpers";
    private static final String NOTIF_LISTENERS = "enabled_notification_listeners";
    private static final long REBIND_DELAY_MS = 1500L;

    private static final Object LOCK = new Object();
    private static ContentObserver watcher;
    private static boolean watching;
    private static long lastRebindAt;

    private ServiceRepair() {
    }

    static void connected(Context context, Class<?> service) {
        prefs(context).edit().putBoolean(service.getName(), true).apply();
    }

    static void disconnected(Context context, Class<?> service) {
        prefs(context).edit().remove(service.getName()).apply();
    }

    /** Re-enable recorded helpers and always HomeKeyService; also arm the notification listener. */
    static void restore(Context context) {
        RevivalSync.ensure(context);
        if (!hasSecureWrite(context)) return;
        ensureAccessibility(context);
        ensureNotificationListener(context);
        startWatching(context);
        rebindIfStale(context);
    }

    /** True when HomeKeyService (or any recorded helper) was missing and was written back. */
    static boolean ensureAccessibility(Context context) {
        if (!hasSecureWrite(context)) return false;
        try {
            ContentResolver cr = context.getContentResolver();
            ComponentName homeKey = new ComponentName(context, HomeKeyService.class);
            Set<String> entries = parseList(Settings.Secure.getString(cr,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
            boolean hadHome = containsComponent(entries, homeKey);
            LinkedHashSet<String> updated = new LinkedHashSet<>(entries);
            for (String service : prefs(context).getAll().keySet()) {
                updated.add(new ComponentName(context.getPackageName(), service).flattenToString());
            }
            // Always keep HomeKeyService when restore is asked; prefs may be empty right after install.
            updated.add(homeKey.flattenToString());
            boolean changed = !hadHome || updated.size() != entries.size()
                    || !updated.containsAll(entries);
            if (changed) {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        join(updated));
            }
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            return !hadHome;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Service is listed but not bound (TCL froze the process): drop it from the list, then put it
     * back after {@link #REBIND_DELAY_MS} so Android rebinds (Aerial C1254a.g).
     */
    static void rebindIfStale(Context context) {
        if (!hasSecureWrite(context)) return;
        if (HomeKeyService.isRunning()) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastRebindAt < 10_000L) return;
        try {
            ContentResolver cr = context.getContentResolver();
            ComponentName homeKey = new ComponentName(context, HomeKeyService.class);
            String flat = homeKey.flattenToString();
            Set<String> entries = parseList(Settings.Secure.getString(cr,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
            if (!containsComponent(entries, homeKey)) {
                ensureAccessibility(context);
                return;
            }
            lastRebindAt = now;
            LinkedHashSet<String> without = new LinkedHashSet<>();
            for (String e : entries) {
                ComponentName n = ComponentName.unflattenFromString(e);
                if (n == null || !flat.equals(n.flattenToString())) without.add(e);
            }
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, join(without));
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    Set<String> again = parseList(Settings.Secure.getString(cr,
                            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
                    LinkedHashSet<String> with = new LinkedHashSet<>(again);
                    with.add(flat);
                    Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                            join(with));
                    Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                } catch (RuntimeException ignored) {
                }
            }, REBIND_DELAY_MS);
        } catch (RuntimeException ignored) {
        }
    }

    /** Keep RevivalListener in {@code enabled_notification_listeners} so the system can wake us. */
    static void ensureNotificationListener(Context context) {
        if (!hasSecureWrite(context)) return;
        try {
            ContentResolver cr = context.getContentResolver();
            ComponentName listener = new ComponentName(context, RevivalListener.class);
            Set<String> entries = parseList(Settings.Secure.getString(cr, NOTIF_LISTENERS));
            if (containsComponent(entries, listener)) return;
            LinkedHashSet<String> updated = new LinkedHashSet<>(entries);
            updated.add(listener.flattenToString());
            Settings.Secure.putString(cr, NOTIF_LISTENERS, join(updated));
        } catch (RuntimeException ignored) {
        }
    }

    /** One ContentObserver per process on the accessibility-services setting. */
    static void startWatching(Context context) {
        if (!hasSecureWrite(context)) return;
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            if (watching) return;
            Handler handler = new Handler(Looper.getMainLooper());
            watcher = new ContentObserver(handler) {
                @Override
                public void onChange(boolean selfChange) {
                    onChange(selfChange, null);
                }

                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    ensureAccessibility(app);
                    rebindIfStale(app);
                }
            };
            try {
                app.getContentResolver().registerContentObserver(
                        Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                        false, watcher);
                app.getContentResolver().registerContentObserver(
                        Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
                        false, watcher);
                watching = true;
            } catch (RuntimeException ignored) {
                watcher = null;
            }
        }
    }

    static boolean hasSecureWrite(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    static Set<String> parseList(String list) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (list == null || list.isEmpty() || "null".equals(list)) return out;
        for (String entry : list.split(":")) {
            if (entry == null) continue;
            String t = entry.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    static boolean containsComponent(Set<String> entries, ComponentName want) {
        String flat = want.flattenToString();
        String shortFlat = want.flattenToShortString();
        for (String e : entries) {
            ComponentName n = ComponentName.unflattenFromString(e);
            if (n == null) continue;
            if (flat.equals(n.flattenToString()) || shortFlat.equals(n.flattenToShortString())) {
                return true;
            }
        }
        return false;
    }

    static String join(Set<String> entries) {
        StringBuilder sb = new StringBuilder();
        for (String e : entries) {
            if (sb.length() > 0) sb.append(':');
            sb.append(e);
        }
        return sb.toString();
    }

    /** Package-visible for tests: build the enabled list that would be written. */
    static List<String> plannedServices(Context context, String currentList) {
        Set<String> updated = parseList(currentList);
        for (String service : prefs(context).getAll().keySet()) {
            updated.add(new ComponentName(context.getPackageName(), service).flattenToString());
        }
        updated.add(new ComponentName(context, HomeKeyService.class).flattenToString());
        return new ArrayList<>(updated);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
