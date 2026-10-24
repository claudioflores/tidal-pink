package local.tidalpink;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * The accessibility service: hands focus, scroll and window events to FocusRing, and every remote key to
 * FocusRing (Tidal's player timer and OK handling) and MenuJump (Hold Left in Tidal). It never swallows a key.
 */
public class FocusService extends AccessibilityService {

    private FocusRing focusRing;
    private MenuJump menuJump;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        TvProfile profile = TvProfile.load(this);
        if (focusRing == null) focusRing = new FocusRing(this, profile);
        if (menuJump == null) menuJump = new MenuJump(this, profile);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (focusRing == null) return false;
        focusRing.beforeOk(event);
        if (event.getAction() == KeyEvent.ACTION_DOWN) focusRing.onKey(event.getKeyCode());
        if (menuJump != null) menuJump.onKeyEvent(event, focusRing.screen());
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (focusRing != null) focusRing.onEvent(event);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (focusRing != null) focusRing.stop();
        if (menuJump != null) menuJump.stop();
        super.onDestroy();
    }
}
