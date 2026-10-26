package com.helm.launcher;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/**
 * Optional. Lets home gestures lock the screen and open the notification shade or quick settings.
 * It subscribes to no events and reads nothing on screen.
 */
public final class HelmAccessibility extends AccessibilityService {
    private static HelmAccessibility instance;

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    static boolean enabled() { return instance != null; }

    static boolean act(int action) {
        return instance != null && instance.performGlobalAction(action);
    }
}
