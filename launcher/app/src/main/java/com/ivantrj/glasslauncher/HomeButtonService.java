package com.ivantrj.glasslauncher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Fire OS protects its launcher, so it can't be disabled and stays the system
 * home. This service makes Glass the effective home screen instead:
 *  - the remote's Home button is consumed and opens Glass;
 *  - whenever the Amazon launcher comes to the front anyway (after boot, or when
 *    an app exits), Glass is brought straight back on top.
 * Event-driven only: it receives window changes from the Amazon launcher's
 * package and key events, and does nothing in between.
 *
 * DIAGNOSTIC BUILD: logs to tag "GlassDiag" which path fired, so we can tell whether
 * Fire OS delivers key events (and the Home key) to accessibility services.
 * Watch with: adb logcat -s GlassDiag
 */
public class HomeButtonService extends AccessibilityService {

    private static final String TAG = "GlassDiag";
    private static final String AMAZON_LAUNCHER = "com.amazon.tv.launcher";
    private long lastLaunch;

    @Override
    protected void onServiceConnected() {
        AccessibilityServiceInfo info = getServiceInfo();
        boolean filter = info != null
                && (info.flags & AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0;
        int caps = info == null ? -1 : info.getCapabilities();
        Log.i(TAG, "service connected; FLAG_REQUEST_FILTER_KEY_EVENTS=" + filter + " capabilities=" + caps);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        Log.i(TAG, "KEY " + KeyEvent.keyCodeToString(event.getKeyCode())
                + (event.getAction() == KeyEvent.ACTION_DOWN ? " DOWN" : " UP")
                + " source=0x" + Integer.toHexString(event.getSource())
                + " device=" + event.getDeviceId());
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) openGlass("HOME key");
        return true; // swallow both down and up so Fire OS never sees Home
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && AMAZON_LAUNCHER.contentEquals(event.getPackageName())) {
            Log.i(TAG, "Amazon launcher window: " + event.getClassName());
            openGlass("Amazon launcher window");
        }
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
