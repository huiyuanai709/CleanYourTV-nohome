package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Sends the remote's Home button to this launcher on remotes that emit KEYCODE_ALL_APPS for Home
 * (Xiaomi sticks and boxes). Android TV 9 routes that key only to the system-image launcher, so once
 * the stock launcher is disabled the button does nothing. Every other key passes through untouched.
 *
 * With stock_home_redirect set, KEYCODE_HOME is consumed (Aerial-style) so the system never opens
 * the stock desktop first; window events from that package remain a backup redirect.
 *
 * Enable over adb:
 *   settings put secure enabled_accessibility_services com.gathin.tvhome/.HomeKeyService
 *   settings put secure accessibility_enabled 1
 */
public class HomeKeyService extends AccessibilityService {

    /** Bound instance for {@link ServiceRepair#rebindIfStale}; null when not running. */
    private static volatile HomeKeyService running;

    /** restore_key_services was on when the config was read: this helper is noted for ServiceRepair. */
    private boolean repairRecord;
    /** Remote buttons that should open Settings (config.xml, settings_key_scancodes and settings_key_keycodes). */
    private int[] settingsScancodes = new int[0];
    private int[] settingsKeycodes = new int[0];
    private boolean settingsPanel;
    /** app_keys, the same maps InputKeyService reads, for setups that run only this service. */
    private Map<Integer, String> appKeys = Collections.emptyMap();
    private Map<Integer, String> appScancodes = Collections.emptyMap();
    private Map<Integer, String> holdAppKeys = Collections.emptyMap();
    private Map<Integer, String> holdAppScancodes = Collections.emptyMap();

    /** Waits for the user to unlock when Android binds this service at boot; null once the config is read. */
    private BroadcastReceiver unlockReceiver;
    /** CLOSE_SYSTEM_DIALOGS with reason=homekey (some firmwares announce Home this way). */
    private BroadcastReceiver homeDialogReceiver;
    /** The stock launcher's HOME activities (stock_home_redirect), disabled ones included. */
    private final Set<String> stockHome = new HashSet<>();
    /** Package of stock_home_redirect; TCL window events often use a non-HOME class name. */
    private String stockHomePackage = "";
    /** Stops key filtering while a game controller is connected; null unless gamepad_passthrough and key_filter, or before the unlock. */
    private GamepadPassthrough gamepads;

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Double-Home window (Aerial: 400 ms). */
    private static final long HOME_DOUBLE_MS = 400L;
    private long lastHomeDownEventTime = Long.MIN_VALUE / 2;
    private boolean homeConsumedAsDoubleOrLong;
    private final Runnable homeLongPress = () -> {
        homeConsumedAsDoubleOrLong = true;
        performGlobalAction(GLOBAL_ACTION_RECENTS);
    };

    static boolean isRunning() {
        return running != null;
    }

    /** Settle before taking Home at BOOT_COMPLETED (Aerial SETTLE_MS). */
    private static final long SETTLE_MS = 1500L;
    private static final long[] BOOT_RECHECKS_MS = {30_000L, 45_000L};
    private final Runnable bootRecheck = () -> {
        if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) takeOverIfStockHomeInFront("looking again after boot");
    };

    /**
     * Aerial HomeGuardService.onBootCompleted: when the helper is already bound at
     * BOOT_COMPLETED, look again (settle + rechecks). Returns false if not running so the
     * receiver can start MainActivity itself.
     */
    static boolean onBootCompleted() {
        final HomeKeyService s = running;
        if (s == null) return false;
        s.handler.post(() -> {
            PowerManager pm = (PowerManager) s.getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) return;
            if (s.isOurLauncherOnTop()) return;
            s.handler.removeCallbacks(s.bootSettleTakeOver);
            s.handler.postDelayed(s.bootSettleTakeOver, SETTLE_MS);
            s.scheduleBootRechecks();
        });
        return true;
    }

    private final Runnable bootSettleTakeOver = () -> takeOverIfStockHomeInFront("boot completed");

    private void scheduleBootRechecks() {
        handler.removeCallbacks(bootRecheck);
        for (long delay : BOOT_RECHECKS_MS) {
            handler.postDelayed(bootRecheck, delay);
        }
    }

    /**
     * Bring this launcher up when cyberui (or an unknown front right after boot) is showing.
     * Leave alone when another app or HDMI input owns the screen (Aerial takeOverIfStockHomeInFront).
     */
    private void takeOverIfStockHomeInFront(String reason) {
        if (stockHomePackage.isEmpty()) {
            openHome(true);
            return;
        }
        if (isOurLauncherOnTop()) return;
        if (isStockHomeOnTop()) {
            openHome(true);
            return;
        }
        // No active window yet: treat like Aerial's null lastWindow and take over.
        try {
            if (getRootInActiveWindow() == null) openHome(true);
        } catch (RuntimeException ignored) {
            openHome(true);
        }
    }

    private boolean isOurLauncherOnTop() {
        try {
            android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            CharSequence pkg = root.getPackageName();
            root.recycle();
            return pkg != null && getPackageName().equals(pkg.toString());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        running = this;
        settingsScancodes = getResources().getIntArray(R.array.settings_key_scancodes);
        // The service is directBootAware: Android 11 skips a service that is not while the user is still
        // locked at boot, writes accessibility_enabled=0 and, on TCL G03 firmware, never binds it after the
        // unlock. Config.load reads credential-protected storage, so wait for the unlock before reading it.
        UserManager users = getSystemService(UserManager.class);
        if (users.isUserUnlocked()) {
            loadConfig();
            return;
        }
        unlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                stopWaitingForUnlock();
                loadConfig();
            }
        };
        registerReceiver(unlockReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
        // The unlock may have happened between the check and the registration.
        if (users.isUserUnlocked()) {
            stopWaitingForUnlock();
            loadConfig();
        }
    }

    private void stopWaitingForUnlock() {
        if (unlockReceiver == null) return;
        unregisterReceiver(unlockReceiver);
        unlockReceiver = null;
    }

    @Override
    public void onDestroy() {
        stopWaitingForUnlock();
        unregisterHomeDialogReceiver();
        if (gamepads != null) gamepads.stop();
        if (running == this) running = null;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void loadConfig() {
        // Keycodes and the panel choice can also come from a theme; theme.sh restarts this service.
        Config config = Config.load(this);
        // restore_key_services: note that this helper runs, so Home can put it back after a force stop. Here, not
        // in onServiceConnected: the note is in credential-protected storage too, readable only after the unlock.
        repairRecord = config.restoreKeyServices;
        if (repairRecord) {
            ServiceRepair.connected(this, getClass());
            ServiceRepair.restore(this);
        }
        settingsKeycodes = config.settingsKeycodes;
        settingsPanel = config.settingsKeyPanel;
        appKeys = RemoteKeys.parseAppKeys(config.appKeys);
        appScancodes = RemoteKeys.parseAppScancodes(config.appKeys);
        RemoteKeys.applyKeyFilter(this, config.keyFilter);
        stockHome.clear();
        stockHomePackage = config.stockHomeRedirect == null ? "" : config.stockHomeRedirect.trim();
        if (!stockHomePackage.isEmpty()) {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(stockHomePackage);
            for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, PackageManager.MATCH_DISABLED_COMPONENTS)) {
                stockHome.add(ri.activityInfo.name);
            }
            registerHomeDialogReceiver();
        }
        // Ensure window events stay on even if a theme reload cleared them (XML already requests them).
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null && !stockHomePackage.isEmpty()) {
            info.eventTypes |= AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            info.feedbackType |= AccessibilityServiceInfo.FEEDBACK_GENERIC;
            setServiceInfo(info);
        }
        if (!stockHomePackage.isEmpty()) {
            probeAtBoot();
            // Aerial: rechecks at 30s/45s catch cyberui coming up after boot ads / late windows.
            if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) scheduleBootRechecks();
        }
        holdAppKeys = RemoteKeys.parseHoldAppKeys(config.appKeys);
        holdAppScancodes = RemoteKeys.parseHoldAppScancodes(config.appKeys);
        // gamepad_passthrough, off by default. Only with key_filter on: off, the service never filters keys.
        if (config.gamepadPassthrough && config.keyFilter && gamepads == null) {
            gamepads = new GamepadPassthrough(this);
            gamepads.start();
        }
    }

    private void registerHomeDialogReceiver() {
        if (homeDialogReceiver != null) return;
        homeDialogReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())) return;
                if (!"homekey".equals(intent.getStringExtra("reason"))) return;
                // Firmware announced Home without a filtered key event; bring our launcher up.
                openHome(true);
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(homeDialogReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(homeDialogReceiver, filter);
        }
    }

    private void unregisterHomeDialogReceiver() {
        if (homeDialogReceiver == null) return;
        try {
            unregisterReceiver(homeDialogReceiver);
        } catch (RuntimeException ignored) {
        }
        homeDialogReceiver = null;
    }

    private boolean isSettingsKey(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) return true;
        for (int code : settingsScancodes) if (event.getScanCode() == code) return true;
        for (int code : settingsKeycodes) if (event.getKeyCode() == code) return true;
        return false;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        // A scancode entry is more specific than any keycode, so it goes first.
        if (RemoteKeys.handle(this, event, appScancodes.get(event.getScanCode()),
                holdAppScancodes.get(event.getScanCode()))) return true;
        // The stock launcher handled the remote's Settings button; with it disabled the button does nothing.
        if (isSettingsKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                Intent open = settingsPanel ? new Intent(this, QuickSettingsActivity.class)
                        : SystemSettings.intent(this, Settings.ACTION_SETTINGS);
                try {
                    startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException | SecurityException unavailable) {
                    Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        }
        if (RemoteKeys.handle(this, event, appKeys.get(event.getKeyCode()),
                holdAppKeys.get(event.getKeyCode()))) return true;
        // Aerial-style: consume KEYCODE_HOME so the system never opens cyberui first.
        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME && !stockHomePackage.isEmpty()) {
            return handleHomeKey(event);
        }
        if (event.getKeyCode() != KeyEvent.KEYCODE_ALL_APPS) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            openHome(true);
        }
        return true;
    }

    /**
     * Consume Home: short press opens this launcher immediately; double within 400 ms or long press
     * opens Recents. Returning true stops TCL from launching cyberui on the same press.
     */
    private boolean handleHomeKey(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            homeConsumedAsDoubleOrLong = false;
            handler.removeCallbacks(homeLongPress);
            if (event.getEventTime() - lastHomeDownEventTime < HOME_DOUBLE_MS) {
                homeConsumedAsDoubleOrLong = true;
                lastHomeDownEventTime = Long.MIN_VALUE / 2;
                performGlobalAction(GLOBAL_ACTION_RECENTS);
            } else {
                handler.postDelayed(homeLongPress, ViewConfiguration.getLongPressTimeout());
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(homeLongPress);
            if (!event.isCanceled() && !homeConsumedAsDoubleOrLong) {
                lastHomeDownEventTime = event.getEventTime();
                openHome(true);
            }
        }
        return true;
    }

    /** Unbound by Android: the user turned the helper off, so Home doesn't put it back (ServiceRepair). */
    @Override
    public boolean onUnbind(Intent intent) {
        if (repairRecord) ServiceRepair.disconnected(this, getClass());
        if (running == this) running = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // stock_home_redirect: the firmware turned the stock launcher back on and its home screen came up.
        // TCL cyberui often reports a non-HOME class in the window event; match package too (Aerial-style).
        if (stockHomePackage.isEmpty()) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return;
        if (isStockHomeEvent(event)) {
            openHome(true);
            return;
        }
        // WINDOWS_CHANGED may omit packageName; peek the active window.
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED && isStockHomeOnTop()) openHome(true);
    }

    private boolean isStockHomeEvent(AccessibilityEvent event) {
        CharSequence pkg = event.getPackageName();
        if (pkg != null && stockHomePackage.equals(pkg.toString())) return true;
        CharSequence cls = event.getClassName();
        return cls != null && stockHome.contains(cls.toString());
    }

    /** True when cyberui (or another stock_home_redirect activity) owns the active window. */
    private boolean isStockHomeOnTop() {
        try {
            android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            CharSequence pkg = root.getPackageName();
            root.recycle();
            return pkg != null && stockHomePackage.equals(pkg.toString());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** How long after boot the first bind still counts as "at boot" for probeAtBoot. */
    static final long BOOT_WINDOW_MS = 3 * 60 * 1000L;

    /** stock_home_redirect at boot: the stock home is often on screen before Android binds this service, so no
     *  window event for it arrives (#1116). Once per boot, open ProbeActivity: when it closes, whatever was in
     *  front comes back and sends a window event, and onAccessibilityEvent covers it only if it is the stock home.
     *  An HDMI input or an app that the TV resumed at boot is left alone. */
    private void probeAtBoot() {
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        SharedPreferences prefs = getSharedPreferences("boot", MODE_PRIVATE);
        if (!firstBindAfterBoot(boot, prefs.getInt("probe_boot", -1), SystemClock.elapsedRealtime())) return;
        prefs.edit().putInt("probe_boot", boot).apply();
        try {
            startActivity(new Intent(this, ProbeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
    }

    static boolean firstBindAfterBoot(int boot, int lastBoot, long uptimeMs) {
        return boot >= 0 && boot != lastBoot && uptimeMs < BOOT_WINDOW_MS;
    }

    static boolean isShortPress(long downTime, long upTime, long longPressTimeout) {
        return upTime - downTime < longPressTimeout;
    }

    /** Soft debounce only for non-forced opens; cyberui / Home key always force. */
    private static final long OPEN_HOME_DEBOUNCE_MS = 120;
    private long lastOpenHomeAt;

    /** Open this launcher without category.HOME (TCL blocks sideloaded HOME apps). */
    private void openHome() {
        openHome(false);
    }

    private void openHome(boolean force) {
        long now = SystemClock.uptimeMillis();
        if (!force && now - lastOpenHomeAt < OPEN_HOME_DEBOUNCE_MS) return;
        // Even forced opens: collapse a burst of identical cyberui window events in the same frame.
        if (force && now - lastOpenHomeAt < 40) return;
        lastOpenHomeAt = now;
        Intent home = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        home.putExtra("home_press", true);
        try {
            startActivity(home);
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
    }

    @Override
    public void onInterrupt() {
    }
}
