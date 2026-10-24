package local.tvfocus;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;

/**
 * Hold Left in Tidal to jump straight to its left menu. Short presses pass through untouched; the key
 * is never swallowed, so the app also sees the hold as a held Left until the jump lands.
 */
final class MenuJump {

    private static final String PACKAGE = "com.aspiro.tidal";
    private static final String MENU_ID = "com.aspiro.tidal:id/browse_headers";
    /** How long Left must be held, from the TV profile. */
    private final long holdMs;

    private final AccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable jump = this::jump;

    MenuJump(AccessibilityService service, TvProfile profile) {
        this.service = service;
        holdMs = profile.holdLeftMs;
    }

    /** Watches Left in Tidal; always returns false, so the key still reaches the app. */
    boolean onKeyEvent(KeyEvent event, String screen) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_DPAD_LEFT) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            handler.removeCallbacks(jump);
            if (screen.startsWith(PACKAGE + "/")) handler.postDelayed(jump, holdMs);
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(jump);
        }
        return false;
    }

    private void jump() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return;
        List<AccessibilityNodeInfo> menus = root.findAccessibilityNodeInfosByViewId(MENU_ID);
        root.recycle();
        for (AccessibilityNodeInfo menu : menus) {
            AccessibilityNodeInfo target = null;
            // The selected item if the list marks one, else the first.
            for (int i = 0; i < menu.getChildCount() && target == null; i++) {
                AccessibilityNodeInfo child = menu.getChild(i);
                if (child == null) continue;
                if (child.isSelected() || child.isFocused()) target = child;
                else child.recycle();
            }
            if (target == null && menu.getChildCount() > 0) target = menu.getChild(0);
            boolean done = target != null && target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            if (!done) menu.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            if (target != null) target.recycle();
            menu.recycle();
        }
    }

    void stop() {
        handler.removeCallbacks(jump);
    }
}
