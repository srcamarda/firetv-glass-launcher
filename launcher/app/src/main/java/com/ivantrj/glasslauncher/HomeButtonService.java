package com.ivantrj.glasslauncher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Fire OS protects its launcher, so it can't be disabled and stays the system
 * home. This service makes Glass the effective home screen instead:
 *  - holding Back (from any app) opens Glass instantly. Fire OS 7 delivers Back to
 *    accessibility services but never Home, and Back doesn't trigger Android's
 *    post-Home "app switch" lockout, so this path has no delay. A short Back press
 *    is passed on to the app unchanged (re-sent on release);
 *  - whenever the Amazon launcher comes to the front (Home, boot, an app exiting),
 *    a black overlay covers it at once and Glass is brought back on top. After Home,
 *    Android holds background activity starts for up to 5 s, so Glass can't appear
 *    sooner; the overlay hides the Amazon screen meanwhile and swallows key presses.
 *    It is removed as soon as Glass is visible, or after COVER_MAX_MS as a safety net;
 *  - the Home key itself is consumed and opens Glass, if Fire OS ever delivers it.
 *
 * Logs to tag "GlassDiag". Watch with: adb logcat -s GlassDiag
 */
public class HomeButtonService extends AccessibilityService {

    private static final String TAG = "GlassDiag";
    private static final String AMAZON_LAUNCHER = "com.amazon.tv.launcher";
    private static final String AMAZON_HOME_CLASS = "HomeActivity"; // e.g. com.amazon.tv.launcher.ui.HomeActivity_vNext
    private static final long LONG_PRESS_MS = 500;   // hold Back this long to open Glass
    private static final long COVER_MAX_MS = 6000;   // overlay never stays longer than this
    private static final long COVER_HIDE_DELAY_MS = 150; // let Glass draw its first frame

    private static volatile HomeButtonService instance;
    private static volatile boolean glassResumed;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastLaunch;
    private boolean receiverRegistered;

    private boolean backHeld;
    private boolean backLongFired;
    private final Runnable backLongPress = new Runnable() {
        @Override public void run() {
            if (!backHeld) return;
            backLongFired = true;
            openGlass("long-press BACK");
        }
    };

    private View cover;
    private final Runnable coverTimeout = new Runnable() {
        @Override public void run() {
            Log.i(TAG, "cover timeout");
            hideCover();
        }
    };
    private final Runnable hideCoverRunnable = new Runnable() {
        @Override public void run() { hideCover(); }
    };

    // Sent by the window manager when Home is handled (not sent by Fire OS 7 in tests; kept for logging).
    private final BroadcastReceiver closeDialogs = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String reason = intent.getStringExtra("reason");
            Log.i(TAG, "CLOSE_SYSTEM_DIALOGS reason=" + reason);
            if ("homekey".equals(reason)) openGlass("homekey broadcast");
        }
    };

    /** Called by HomeActivity.onResume: Glass is on screen, the overlay can go. */
    static void onGlassVisible() {
        glassResumed = true;
        HomeButtonService s = instance;
        if (s == null) return;
        s.handler.removeCallbacks(s.hideCoverRunnable);
        s.handler.postDelayed(s.hideCoverRunnable, COVER_HIDE_DELAY_MS);
    }

    /** Called by HomeActivity.onPause. */
    static void onGlassPaused() {
        glassResumed = false;
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        AccessibilityServiceInfo info = getServiceInfo();
        boolean filter = info != null
                && (info.flags & AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0;
        int caps = info == null ? -1 : info.getCapabilities();
        Log.i(TAG, "service connected; FLAG_REQUEST_FILTER_KEY_EVENTS=" + filter + " capabilities=" + caps);
        if (!receiverRegistered) {
            registerReceiver(closeDialogs, new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
            receiverRegistered = true;
        }
    }

    @Override
    public void onDestroy() {
        instance = null;
        handler.removeCallbacksAndMessages(null);
        hideCover();
        if (receiverRegistered) {
            unregisterReceiver(closeDialogs);
            receiverRegistered = false;
        }
        super.onDestroy();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        int action = event.getAction();
        Log.i(TAG, "KEY " + KeyEvent.keyCodeToString(code)
                + (action == KeyEvent.ACTION_DOWN ? " DOWN" : " UP")
                + " repeat=" + event.getRepeatCount()
                + " device=" + event.getDeviceId());

        // Our own re-sent Back (and any other injected key) goes straight through.
        if (event.getDeviceId() == KeyCharacterMap.VIRTUAL_KEYBOARD) return false;

        // While the overlay hides the Amazon launcher, don't let key presses reach it.
        if (cover != null) {
            if (code == KeyEvent.KEYCODE_BACK) { backHeld = false; handler.removeCallbacks(backLongPress); }
            return true;
        }

        if (code == KeyEvent.KEYCODE_HOME) {
            if (action == KeyEvent.ACTION_UP && !event.isCanceled()) openGlass("HOME key");
            return true; // swallow both down and up so Fire OS never sees Home
        }

        if (code == KeyEvent.KEYCODE_BACK
                && GlassPrefs.get(this).getBoolean(GlassPrefs.BACK_LONG_PRESS, true)) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    backHeld = true;
                    backLongFired = false;
                    handler.removeCallbacks(backLongPress);
                    handler.postDelayed(backLongPress, LONG_PRESS_MS);
                }
                return true;
            }
            if (action == KeyEvent.ACTION_UP) {
                handler.removeCallbacks(backLongPress);
                boolean wasHeld = backHeld;
                backHeld = false;
                if (wasHeld && !backLongFired && !event.isCanceled()) {
                    Log.i(TAG, "short BACK -> passing to app");
                    performGlobalAction(GLOBAL_ACTION_BACK);
                }
                backLongFired = false;
                return true;
            }
        }
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && AMAZON_LAUNCHER.contentEquals(event.getPackageName())) {
            CharSequence cls = event.getClassName();
            // The Amazon launcher package also hosts Fire OS Settings (.ui.SettingsActivity):
            // only its home screen (.ui.HomeActivity_vNext) is redirected to Glass.
            if (cls == null || !cls.toString().contains(AMAZON_HOME_CLASS)) {
                Log.i(TAG, "Amazon launcher window ignored (not home): " + cls);
                return;
            }
            Log.i(TAG, "Amazon launcher window: " + cls);
            showCover();
            openGlass("Amazon launcher window");
        }
    }

    private void showCover() {
        handler.removeCallbacks(hideCoverRunnable);
        if (cover != null) return;
        if (glassResumed) { Log.i(TAG, "cover skipped: Glass already on screen"); return; }
        try {
            View v = new View(this);
            v.setBackgroundColor(Color.BLACK);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    PixelFormat.OPAQUE);
            getSystemService(WindowManager.class).addView(v, lp);
            cover = v;
            handler.postDelayed(coverTimeout, COVER_MAX_MS);
            Log.i(TAG, "cover shown");
        } catch (RuntimeException e) {
            Log.w(TAG, "cover failed", e);
        }
    }

    private void hideCover() {
        handler.removeCallbacks(coverTimeout);
        if (cover == null) return;
        try {
            getSystemService(WindowManager.class).removeViewImmediate(cover);
        } catch (RuntimeException e) {
            Log.w(TAG, "cover remove failed", e);
        }
        cover = null;
        Log.i(TAG, "cover hidden");
    }

    private void openGlass(String reason) {
        long now = SystemClock.uptimeMillis();
        if (now - lastLaunch < 600) {
            Log.i(TAG, "openGlass skipped (debounce) via " + reason);
            return;
        }
        lastLaunch = now;
        Log.i(TAG, "openGlass via " + reason);
        startActivity(new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    @Override public void onInterrupt() {}
}
