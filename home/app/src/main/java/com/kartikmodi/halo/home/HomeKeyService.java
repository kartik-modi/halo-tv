package com.kartikmodi.halo.home;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.Collections;
import java.util.Map;

/**
 * Sends the remote's Home button to this launcher on remotes that emit KEYCODE_ALL_APPS for Home
 * (Xiaomi sticks and boxes). Android TV 9 routes that key only to the system-image launcher, so once
 * the stock launcher is disabled the button does nothing. Every other key passes through untouched.
 *
 * Enable over adb:
 *   settings put secure enabled_accessibility_services com.kartikmodi.halo.home/.HomeKeyService
 *   settings put secure accessibility_enabled 1
 */
public class HomeKeyService extends AccessibilityService {

    /** Remote buttons that should open Settings (config.xml, settings_key_scancodes and settings_key_keycodes). */
    private int[] settingsScancodes = new int[0];
    private int[] settingsKeycodes = new int[0];
    private boolean settingsPanel;
    /** app_keys, the same map InputKeyService reads, for setups that run only this service. */
    private Map<Integer, String> appKeys = Collections.emptyMap();

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        // Bound by the system for as long as it is enabled, so the phone remote stays reachable.
        RemoteServer.ensureStarted(this);
        settingsScancodes = getResources().getIntArray(R.array.settings_key_scancodes);
        // Keycodes and the panel choice can also come from a theme; a theme push restarts this service.
        Config config = Config.load(this);
        settingsKeycodes = config.settingsKeycodes;
        settingsPanel = config.settingsKeyPanel;
        appKeys = RemoteKeys.parseAppKeys(config.appKeys);
    }

    private boolean isSettingsKey(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) return true;
        for (int code : settingsScancodes) if (event.getScanCode() == code) return true;
        for (int code : settingsKeycodes) if (event.getKeyCode() == code) return true;
        return false;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        // The stock launcher handled the remote's Settings button; with it disabled the button does nothing.
        if (isSettingsKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                Intent open = settingsPanel ? new Intent(this, QuickSettingsActivity.class)
                        : new Intent(Settings.ACTION_SETTINGS);
                startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            return true;
        }
        String app = appKeys.get(event.getKeyCode());
        if (app != null) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) RemoteKeys.launch(this, app);
            return true;
        }
        if (event.getKeyCode() != KeyEvent.KEYCODE_ALL_APPS) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            Intent home = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setPackage(getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(home);
        }
        return true;
    }

    /** Fire OS keeps Home and its Settings screens in this package, so it can never be disabled. */
    static final String FIRE_OS_LAUNCHER = "com.amazon.tv.launcher";
    /** Fire OS's Settings grid, also in Amazon's launcher; it can be started directly. */
    static final String FIRE_OS_SETTINGS = "com.amazon.tv.launcher.ui.MainSettingsActivity";
    /** How long Amazon's home may sit in front on its way to Settings before it counts as Home. */
    private static final long SETTINGS_HOP_MS = 1200;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable openHome = this::openHome;
    private CharSequence lastPackage;
    /** A redirect is waiting to see whether Settings follows Amazon's home. */
    private boolean hopPending;

    /**
     * Fire OS never hands Home to another app, and its remote's Home button never reaches an
     * accessibility service, so bring this launcher up whenever Amazon's home screen comes to the
     * front: the Home button, start-up, Back out of Settings. Amazon's Settings screens live in the
     * same app, and a Settings request coming from outside it passes through Amazon's home first; in
     * that case wait briefly and stand down when Settings follows. Other TVs never match.
     */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();
        if (pkg == null || cls == null) return;
        // Alexa's listening screen: the remote's mic button is held. The sun or moon on the Ambient
        // screen listens along.
        if ("com.amazon.vizzini".contentEquals(pkg)) AmbientActivity.alexaListening(true);
        if ("com.amazon.vizzini".contentEquals(pkg) || "com.amazon.aria".contentEquals(pkg)) alexaAt = System.currentTimeMillis();
        boolean amazonHome = FIRE_OS_LAUNCHER.contentEquals(pkg) && cls.toString().contains("HomeActivity");
        if (!amazonHome) {
            // Anything else in front, Settings included, cancels a pending redirect. Bare FrameLayout
            // events are transient window frames, not a screen the person landed on.
            if (!"android.widget.FrameLayout".contentEquals(cls)) {
                handler.removeCallbacks(openHome);
                hopPending = false;
            }
            lastPackage = pkg;
            return;
        }
        if (hopPending) return;   // Amazon's home often reports itself twice; keep waiting
        // A Settings request from another app (this launcher's ACTION_SETTINGS, the remote's quick
        // settings panel) shows the settings app's frame, then Amazon's home, then Settings.
        boolean maybeSettingsHop = lastPackage != null && (lastPackage.toString().startsWith("com.amazon.tv.settings")
                || lastPackage.toString().startsWith("com.amazon.tv.quicksettings"));
        lastPackage = pkg;
        if (maybeSettingsHop) {
            hopPending = true;
            handler.postDelayed(openHome, SETTINGS_HOP_MS);
        } else {
            openHome();
        }
    }

    private long alexaAt;

    private void openHome() {
        hopPending = false;
        // Alexa covers the Ambient screen with its own card, then sends the TV to Amazon's home when it
        // is done. If that is what happened, go back to the Ambient screen as it was, music and all.
        long paused = AmbientActivity.pausedAt;
        long now = System.currentTimeMillis();
        boolean alexaJustNow = now - alexaAt < 60000 || now - JarvisLink.lastAlexaCommandAt < 60000;
        // Jarvis just opened TONIGHT. with a pick: Alexa's trip to Amazon's home shouldn't bury it.
        if (now - JarvisLink.lastTonightAt < 60000) {
            Intent t = getPackageManager().getLeanbackLaunchIntentForPackage(MainActivity.TONIGHT_PKG);
            if (t != null) {
                startActivity(t.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("keep", true));
                return;
            }
        }
        if (AmbientActivity.alive() && paused > 0 && alexaJustNow && now - paused < 120000) {
            startActivity(new Intent(this, AmbientActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            return;
        }
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }

    @Override
    public void onInterrupt() {
    }
}
