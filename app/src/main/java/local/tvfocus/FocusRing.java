package local.tvfocus;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * A thick electric-pink ring drawn over Tidal's TV app around whatever has input focus, so the remote's
 * "cursor" is easy to find. Driven by FocusService's focus, scroll and window events. Every other app is
 * left alone, and a focused view that fills most of the screen gets no ring.
 */
final class FocusRing {

    /** Pixel sizes, timings and colour for this TV: assets/profiles/, see TvProfile. */
    private final TvProfile profile;
    private final int color;
    /** After focus moves, wait this long, then check the focused view every SAMPLE_MS until it stops moving:
     *  focus zoom and list scrolling move it for a moment, and a ring placed early jumps twice. */
    private static final long SETTLE_MS = 15, SAMPLE_MS = 20;
    private static final int MAX_SAMPLES = 45;
    /** How long a newly opened screen takes to slide in; the ring waits for it. */
    private final long ENTER_QUIET_MS;
    /** A Tidal screen up at least this long was one the user was on, not one flashed while Tidal starts. */
    private final long FROM_TIDAL_MIN_MS;
    private long screenSince;
    private long enterQuietUntil;
    /** Screens where only the buttons in SHAPES get a ring (Tidal's playlists). */
    private static final String[] SHAPES_ONLY_SCREENS = {
            "com.aspiro.tidal/com.aspiro.wamp.tv.playlist.PlaylistActivity",
    };
    /** Views whose items get no ring: anything focused inside one of these (Tidal's left menu). */
    private static final String[] SKIP_CONTAINERS = {
            "com.aspiro.tidal:id/browse_headers",
            // The player's and lyrics' bar row: the pink marker shows there instead of a ring.
            "com.aspiro.tidal:id/seekBarAndTime",
    };
    private static final String PLAYER = "com.aspiro.tidal/com.aspiro.wamp.tv.nowplaying.NowPlayingActivity";
    /** Tidal's lyrics: a dialog over the player, with its own bar and previous / play / next below it. */
    private static final String LYRICS = "com.aspiro.tidal/androidx.activity.ComponentDialog";
    private static final String SEEK_BAR_ID = "com.aspiro.tidal:id/seekBar";
    private static final String PLAY_PAUSE_ID = "com.aspiro.tidal:id/playPauseButton";
    /** Where the bar starts inside the SeekBar, Tidal's marker width, and how much bigger each way the pink one
     *  is (same centre), so it still covers the white one in the frame it takes to catch up. */
    private final int THUMB_INSET, THUMB_W, THUMB_GROW;
    /** The row the remote selects: the bar with its two times. */
    private static final String SEEK_ROW_ID = "com.aspiro.tidal:id/seekBarAndTime";
    private static final long THUMB_IDLE_CHECK_MS = 16;
    /** After a key that moves focus, how long the marker stays away while Tidal's focus animation runs. */
    private final long THUMB_QUIET_MS;
    private long thumbQuietUntil;
    private static final String PLAYLIST = "com.aspiro.tidal/com.aspiro.wamp.tv.playlist.PlaylistActivity";
    /** Buttons whose ring hugs the visible shape instead of the view's box, from the profile's "shapes". */
    private final Shape[] SHAPES;
    /** The screen in front, as package/class from the last window change. */
    private String screen = "";

    private final AccessibilityService service;
    private final WindowManager windows;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float density;
    /** The usual ring thickness; the window is the target plus this all round. */
    private final int pad;
    private RingView ring;
    private boolean shown;
    private final Rect bounds = new Rect();
    /** The part of the focused view the ring goes around: its bounds, or the shape inside them. */
    private final Rect target = new Rect();
    /** Where the focused view was at the previous sample; the ring goes up once two samples agree. */
    private final Rect lastBounds = new Rect();
    /** The focused view's bounds while the ring is up; an event that leaves the focus there changes nothing. */
    private final Rect shownBounds = new Rect();
    private int samples;
    private final Runnable refresh = this::refresh;
    /** Tidal's player fades its buttons out this long after the last key, with no event and focus kept:
     *  the ring goes with them, on a timer the remote's keys restart (measured: about 5 s). */
    private final long PLAYER_IDLE_MS;
    /** When a key last reached the player, for its fade timer (which keeps running under lyrics). */
    private long lastPlayerKey;
    /** The player's buttons have faded: no ring until a key brings them back. */
    private boolean idle;
    private final Runnable goIdle = () -> {
        idle = true;
        hide();
        this.thumb.hide();
        // Hidden buttons: OK must land on play/pause (whose toggle is undone), not on whatever was last selected.
        focusPlayPause();
    };
    private final Runnable focusPlayPause = this::focusPlayPause;
    /** The player's progress marker, drawn pink over Tidal's white one. */
    private Thumb thumb;
    private final Runnable thumbTick = this::thumbTick;
    /** The marker follows on every display frame (vsync), the fastest it can be redrawn. */
    private final android.view.Choreographer.FrameCallback thumbFrame = frameTimeNanos -> thumbTick();

    FocusRing(AccessibilityService service, TvProfile profile) {
        this.service = service;
        this.profile = profile;
        windows = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        density = service.getResources().getDisplayMetrics().density;
        pad = Math.round(profile.ringDp * density);
        color = profile.color;
        ENTER_QUIET_MS = profile.enterQuietMs;
        FROM_TIDAL_MIN_MS = profile.fromTidalMinMs;
        THUMB_INSET = profile.thumbInset;
        THUMB_W = profile.thumbWidth;
        THUMB_GROW = profile.thumbGrow;
        THUMB_QUIET_MS = profile.thumbQuietMs;
        PLAYER_IDLE_MS = profile.playerIdleMs;
        UNDO_TOGGLE_MS = profile.undoToggleMs;
        PRESHOW_MS = profile.preshowMs;
        SHAPES = shapes(profile);
        thumb = new Thumb();
    }

    /** Profile screen names to Tidal screens; "any" matches every screen. */
    private static String screenOf(String name) {
        switch (name) {
            case "player": return PLAYER;
            case "lyrics": return LYRICS;
            case "playlist": return PLAYLIST;
            default: return "";
        }
    }

    private static Shape[] shapes(TvProfile profile) {
        Shape[] out = new Shape[profile.shapes.size()];
        for (int i = 0; i < out.length; i++) {
            TvProfile.ShapeSpec s = profile.shapes.get(i);
            String screen = screenOf(s.screen), id = "com.aspiro.tidal:id/" + s.id;
            int[] in = s.inset;
            switch (s.kind) {
                case "circle":
                    out[i] = new Shape(screen, id, Shape.CIRCLE, in[0], in[1], in[2], in[3], 0, s.stroke);
                    break;
                case "pill":
                    out[i] = new Shape(screen, id, Shape.PILL, in[0], in[1], in[2], in[3], 0, s.stroke);
                    break;
                case "frame":
                    int[] h = s.innerInset;
                    out[i] = Shape.frame(screen, id, in[0], in[1], in[2], in[3], s.corner, h[0], h[1], h[2], h[3], s.innerCorner);
                    break;
                default:
                    out[i] = new Shape(screen, id, Shape.ROUNDED, in[0], in[1], in[2], in[3], s.corner, s.stroke);
            }
        }
        return out;
    }

    /** The only app the ring is drawn in. */
    private static final String TIDAL_PACKAGE = "com.aspiro.tidal";

    private static boolean isTidal(CharSequence pkg) {
        return pkg != null && TIDAL_PACKAGE.contentEquals(pkg);
    }

    void onEvent(AccessibilityEvent event) {
        CharSequence pkg = event.getPackageName();
        // Adding the ring's own overlay window reports a window change under this package: ignore it.
        if (pkg != null && service.getPackageName().contentEquals(pkg)) return;
        if (!isTidal(pkg)) {
            // Another app: no ring. Window changes still count, so the screen state is right on the way back.
            if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_FOCUSED) hide();
            if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        }
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            boolean fromLyrics = LYRICS.equals(screen);
            // Opened from another Tidal screen (the left menu's Now Playing), the player shows its buttons. Launching
            // Tidal flashes another of its screens first; that one was up only briefly, so it does not count.
            long now = android.os.SystemClock.uptimeMillis();
            boolean fromTidal = !fromLyrics && screen.startsWith("com.aspiro.tidal/") && now - screenSince > FROM_TIDAL_MIN_MS;
            screen = pkg + "/" + event.getClassName();
            screenSince = now;
            // A new screen slides in: no ring until it has stopped, so the ring never rides along with it.
            enterQuietUntil = android.os.SystemClock.uptimeMillis() + ENTER_QUIET_MS;
            handler.removeCallbacks(goIdle);
            stopThumbTicks();
            thumb.hide();
            // The player can open with its buttons already faded (Tidal resumed on it): no ring or marker
            // until a key shows them. Back from lyrics, Tidal's own timer kept running from the last key in the
            // player: the buttons are still up only if that was less than PLAYER_IDLE_MS ago.
            long left = fromTidal ? PLAYER_IDLE_MS : lastPlayerKey + PLAYER_IDLE_MS - android.os.SystemClock.uptimeMillis();
            idle = PLAYER.equals(screen) && !((fromLyrics || fromTidal) && left > 0);
            if (PLAYER.equals(screen) && !idle) handler.postDelayed(goIdle, left);
            // Back from lyrics Tidal selects the Lyrics button again: with the buttons hidden, put it on play/pause.
            if (PLAYER.equals(screen) && fromLyrics && idle) handler.postDelayed(focusPlayPause, 100);
            if (barScreen() && !idle) handler.post(thumbTick);
        }
        // Something may have moved: sample the focus, and move the ring once, when it has settled. The ring stays
        // up meanwhile; apps that scroll the list under a fixed focus (Tidal) then never see it move at all.
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) hide();
        lastBounds.setEmpty();
        samples = 0;
        handler.removeCallbacks(refresh);
        handler.postDelayed(refresh, SETTLE_MS);
    }

    private void refresh() {
        AccessibilityNodeInfo node = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (node == null) {
            hide();
            return;
        }
        CharSequence pkg = node.getPackageName();
        if (!isTidal(pkg)) {
            hide();
        } else {
            node.getBoundsInScreen(bounds);
            if (shown && bounds.equals(shownBounds)) {
                // Still where the ring is: leave it up.
            } else if (bounds.equals(lastBounds) || ++samples >= MAX_SAMPLES) {
                place(node);
            } else {
                // A different size means another row of tiles: take the ring down until it settles there.
                if (shown && (bounds.width() != shownBounds.width() || bounds.height() != shownBounds.height())) hide();
                lastBounds.set(bounds);
                handler.postDelayed(refresh, SAMPLE_MS);
            }
        }
        node.recycle();
    }

    /**
     * OK in Tidal's player with its buttons hidden shows them and also toggles play/pause. The helper cannot make
     * Tidal show them any other way (it ignores taps, and the helper cannot send keys), so OK goes through and
     * play/pause is clicked right back: OK on a hidden player only shows the buttons.
     */
    private final long UNDO_TOGGLE_MS;
    private final Runnable undoToggle = this::undoToggle;

    private void focusPlayPause() {
        if (!PLAYER.equals(screen)) return;
        AccessibilityNodeInfo focus = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focus == null || PLAY_PAUSE_ID.equals(focus.getViewIdResourceName())) return;
        AccessibilityNodeInfo root = focus;
        for (AccessibilityNodeInfo up = root.getParent(); up != null; up = up.getParent()) root = up;
        java.util.List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(PLAY_PAUSE_ID);
        if (!found.isEmpty()) found.get(0).performAction(AccessibilityNodeInfo.ACTION_FOCUS);
    }

    private void undoToggle() {
        AccessibilityNodeInfo focus = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focus == null) return;
        AccessibilityNodeInfo root = focus;
        for (AccessibilityNodeInfo up = root.getParent(); up != null; up = up.getParent()) root = up;
        java.util.List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(PLAY_PAUSE_ID);
        if (!found.isEmpty()) found.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    /** OK went down on a hidden player; the undo waits for its release, which is when Tidal toggles. */
    private boolean undoOnRelease;

    /** Called for OK before onKey; never swallows the key. */
    void beforeOk(android.view.KeyEvent event) {
        int code = event.getKeyCode();
        if (code != android.view.KeyEvent.KEYCODE_DPAD_CENTER && code != android.view.KeyEvent.KEYCODE_ENTER) return;
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                // Only an OK that lands on play/pause toggled playback; anything else is left alone.
                AccessibilityNodeInfo focus = PLAYER.equals(screen) && idle
                        ? service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) : null;
                undoOnRelease = focus != null && PLAY_PAUSE_ID.equals(focus.getViewIdResourceName());
            }
        } else if (undoOnRelease) {
            undoOnRelease = false;
            handler.postDelayed(undoToggle, UNDO_TOGGLE_MS);
        }
    }

    /** Every remote key, from FocusService: in the player a key brings the buttons back and restarts their timer. */
    void onKey(int keyCode) {
        if (!barScreen()) return;
        // Back with the buttons up hides them at once in Tidal's player: the ring and marker go with them.
        if (PLAYER.equals(screen) && keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            handler.removeCallbacks(goIdle);
            stopThumbTicks();
            goIdle.run();
            return;
        }
        // Up, Down, OK or Back leave the bar (or open something): the marker goes at once, not after Tidal's
        // focus animation has dragged it along, and comes back only if the bar is still selected after it.
        if (keyCode != android.view.KeyEvent.KEYCODE_DPAD_LEFT && keyCode != android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                && thumb.isUp()) {
            thumb.hide();
            thumbQuietUntil = android.os.SystemClock.uptimeMillis() + THUMB_QUIET_MS;
        }
        // Onto the bar from the buttons next to it (Down in the player, Up in lyrics): put the marker up now, before
        // Tidal has moved focus and drawn its white one; the next ticks take it down if focus did not land there.
        if (!idle) preshowThumb(keyCode);
        handler.removeCallbacks(goIdle);
        if (PLAYER.equals(screen)) {
            handler.postDelayed(goIdle, PLAYER_IDLE_MS);
            lastPlayerKey = android.os.SystemClock.uptimeMillis();
        }
        // Move the marker just after the key, not on the next tick.
        stopThumbTicks();
        handler.post(thumbTick);
        if (idle) {
            idle = false;
            lastBounds.setEmpty();
            samples = 0;
            handler.removeCallbacks(refresh);
            handler.postDelayed(refresh, SETTLE_MS);
            stopThumbTicks();
            handler.post(thumbTick);
        }
    }

    /** While the player is up and its bar selected: put the pink marker where the song is, once per display frame. */
    private void thumbTick() {
        stopThumbTicks();
        if (!barScreen() || idle) {
            thumb.hide();
            return;
        }
        AccessibilityNodeInfo focus = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        AccessibilityNodeInfo seek = null;
        // Pink only while the bar itself is selected; with a button selected Tidal's white marker stays.
        boolean selected = focus != null && SEEK_ROW_ID.equals(focus.getViewIdResourceName())
                && android.os.SystemClock.uptimeMillis() >= thumbQuietUntil;
        if (selected) {
            // The SeekBar is a direct child of the selected row: cheaper than searching the window every frame.
            for (int i = 0; i < focus.getChildCount() && seek == null; i++) {
                AccessibilityNodeInfo child = focus.getChild(i);
                if (child != null && SEEK_BAR_ID.equals(child.getViewIdResourceName())) seek = child;
            }
            // A child can come from the node cache with an old position: fetch its current state.
            if (seek != null && !seek.refresh()) seek = null;
        }
        AccessibilityNodeInfo.RangeInfo range = seek != null ? seek.getRangeInfo() : null;
        if (range != null && range.getMax() > range.getMin() && seek.isVisibleToUser()) {
            Rect bar = new Rect();
            seek.getBoundsInScreen(bar);
            float fraction = (range.getCurrent() - range.getMin()) / (range.getMax() - range.getMin());
            thumb.place(bar, Math.max(0f, Math.min(1f, fraction)));
        } else if (android.os.SystemClock.uptimeMillis() >= preshowUntil) {
            thumb.hide();
        }
        // Every frame while the bar is selected; otherwise just check now and then whether it has been.
        if (selected) android.view.Choreographer.getInstance().postFrameCallback(thumbFrame);
        else handler.postDelayed(thumbTick, Math.min(THUMB_IDLE_CHECK_MS,
                Math.max(16, thumbQuietUntil - android.os.SystemClock.uptimeMillis())));
    }

    /** The player and lyrics: the screens with a bar the pink marker follows. */
    private boolean barScreen() {
        return PLAYER.equals(screen) || LYRICS.equals(screen);
    }

    /** Lyrics: the buttons whose Up lands on the bar. */
    private static final String[] BELOW_BAR_IDS = {
            "com.aspiro.tidal:id/previous", "com.aspiro.tidal:id/play", "com.aspiro.tidal:id/next",
    };
    /** Player: the buttons whose Down lands on the bar. */
    private static final String[] ABOVE_BAR_IDS = {
            "com.aspiro.tidal:id/shuffleButton", "com.aspiro.tidal:id/hiFiButton", "com.aspiro.tidal:id/previousButton",
            "com.aspiro.tidal:id/playPauseButton", "com.aspiro.tidal:id/nextButton", "com.aspiro.tidal:id/favoriteButton",
            "com.aspiro.tidal:id/lyricsButton",
    };
    /** How long a marker put up ahead of the focus may wait for the bar to be selected. */
    private final long PRESHOW_MS;
    private long preshowUntil;

    private void preshowThumb(int keyCode) {
        String[] next;
        if (PLAYER.equals(screen) && keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) next = ABOVE_BAR_IDS;
        else if (LYRICS.equals(screen) && keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) next = BELOW_BAR_IDS;
        else return;
        AccessibilityNodeInfo focus = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focus == null) return;
        boolean beside = false;
        String id = focus.getViewIdResourceName();
        for (String button : next) if (button.equals(id)) beside = true;
        if (!beside) return;
        AccessibilityNodeInfo root = focus;
        for (AccessibilityNodeInfo up = root.getParent(); up != null; up = up.getParent()) root = up;
        java.util.List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(SEEK_BAR_ID);
        if (found.isEmpty()) return;
        AccessibilityNodeInfo seek = found.get(0);
        AccessibilityNodeInfo.RangeInfo range = seek.getRangeInfo();
        if (range == null || range.getMax() <= range.getMin()) return;
        Rect bar = new Rect();
        seek.getBoundsInScreen(bar);
        thumb.place(bar, Math.max(0f, Math.min(1f, (range.getCurrent() - range.getMin()) / (range.getMax() - range.getMin()))));
        preshowUntil = android.os.SystemClock.uptimeMillis() + PRESHOW_MS;
        thumbQuietUntil = 0;
    }

    private void stopThumbTicks() {
        handler.removeCallbacks(thumbTick);
        android.view.Choreographer.getInstance().removeFrameCallback(thumbFrame);
    }

    private Shape shapeFor(String id) {
        if (id == null) return null;
        for (Shape shape : SHAPES) {
            if (shape.id.equals(id) && (shape.screen.isEmpty() || shape.screen.equals(screen))) return shape;
        }
        return null;
    }

    private void place(AccessibilityNodeInfo node) {
        if (idle) {
            hide();
            return;
        }
        long quiet = enterQuietUntil - android.os.SystemClock.uptimeMillis();
        if (quiet > 0) {
            hide();
            lastBounds.setEmpty();
            samples = 0;
            handler.removeCallbacks(refresh);
            handler.postDelayed(refresh, quiet);
            return;
        }
        Shape shape = shapeFor(node.getViewIdResourceName());
        if (shape == null) {
            for (String only : SHAPES_ONLY_SCREENS) {
                if (only.equals(screen)) {
                    hide();
                    return;
                }
            }
        }
        if (inSkippedContainer(node)) {
            hide();
            return;
        }
        node.getBoundsInScreen(bounds);
        int sw = service.getResources().getDisplayMetrics().widthPixels;
        int sh = service.getResources().getDisplayMetrics().heightPixels;
        if (bounds.isEmpty() || (long) bounds.width() * bounds.height() > 0.6 * sw * sh) {
            hide();
            return;
        }
        target.set(bounds);
        if (shape != null) {
            target.set(bounds.left + shape.left, bounds.top + shape.top, bounds.right - shape.right, bounds.bottom - shape.bottom);
            if (shape.kind == Shape.CIRCLE) {
                // A circle: the largest square around the middle of the shape.
                int d = Math.min(target.width(), target.height());
                int cx = target.centerX(), cy = target.centerY();
                target.set(cx - d / 2, cy - d / 2, cx - d / 2 + d, cy - d / 2 + d);
            }
        }
        if (ring == null) ring = new RingView(service);
        int pad = shape != null && shape.kind == Shape.FRAME ? 0 : shape != null && shape.stroke > 0 ? shape.stroke : this.pad;
        ring.setShape(shape, pad);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                target.width() + 2 * pad, target.height() + 2 * pad,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = target.left - pad;
        lp.y = target.top - pad;
        try {
            if (shown) windows.updateViewLayout(ring, lp);
            else windows.addView(ring, lp);
            shown = true;
            shownBounds.set(bounds);
        } catch (RuntimeException e) {
            shown = false;
        }
    }

    /** The focused view or one of its nearest ancestors is listed in SKIP_CONTAINERS. The view itself counts:
     *  Tidal focuses the whole menu for a moment while it slides open. */
    private static boolean inSkippedContainer(AccessibilityNodeInfo node) {
        String own = node.getViewIdResourceName();
        for (String skip : SKIP_CONTAINERS) if (skip.equals(own)) return true;
        AccessibilityNodeInfo current = node.getParent();
        for (int depth = 0; current != null && depth < 5; depth++) {
            String id = current.getViewIdResourceName();
            for (String skip : SKIP_CONTAINERS) {
                if (skip.equals(id)) {
                    current.recycle();
                    return true;
                }
            }
            AccessibilityNodeInfo up = current.getParent();
            current.recycle();
            current = up;
        }
        if (current != null) current.recycle();
        return false;
    }

    /** The screen in front, as package/class from the last window change. */
    String screen() {
        return screen;
    }

    void hide() {
        if (!shown) return;
        try {
            windows.removeView(ring);
        } catch (RuntimeException ignored) {
        }
        shown = false;
    }

    void stop() {
        handler.removeCallbacks(refresh);
        handler.removeCallbacks(goIdle);
        stopThumbTicks();
        thumb.hide();
        hide();
    }

    /**
     * A pink pill over Tidal's white progress marker. Measured on the 75QM751G: the bar runs from 32 px inside
     * each end of the SeekBar's bounds, and the marker is 12 x 28 px, the SeekBar's full height, centred on it.
     */
    private final class Thumb {
        /** One window over the whole bar; the marker moves inside it by redrawing, which is quicker than moving a
         *  window, so it keeps up with Tidal's own marker. */
        private final View view = new View(service) {
            private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

            {
                fill.setColor(color);
            }

            @Override
            protected void onDraw(Canvas canvas) {
                float w = THUMB_W + THUMB_GROW, r = w / 2f;
                canvas.drawRoundRect(markX, 0, markX + w, getHeight(), r, r, fill);
            }
        };
        private boolean up;
        private final Rect at = new Rect();
        private float markX = -1;

        void place(Rect bar, float fraction) {
            if (!up || !bar.equals(at)) {
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(bar.width() + THUMB_GROW, bar.height() + THUMB_GROW,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.START;
                lp.x = bar.left - THUMB_GROW / 2;
                lp.y = bar.top - THUMB_GROW / 2;
                try {
                    if (up) windows.updateViewLayout(view, lp);
                    else windows.addView(view, lp);
                    up = true;
                    at.set(bar);
                } catch (RuntimeException e) {
                    up = false;
                    return;
                }
            }
            // Window x 0 is THUMB_GROW / 2 left of the bar, so the wider marker keeps the same centre.
            float x = Math.round(THUMB_INSET + fraction * (bar.width() - 2 * THUMB_INSET) - THUMB_W / 2f);
            if (x != markX) {
                markX = x;
                view.invalidate();
            }
        }

        boolean isUp() {
            return up;
        }

        void hide() {
            if (!up) return;
            try {
                windows.removeView(view);
            } catch (RuntimeException ignored) {
            }
            up = false;
        }
    }

    /** The visible shape of a button inside its view: insets from each edge, in pixels, and its outline. */
    private static final class Shape {
        /** FRAME: the area between an outer and an inner rounded rect is filled, exactly covering a focus frame. */
        static final int ROUNDED = 0, CIRCLE = 1, PILL = 2, FRAME = 3;
        final String screen, id;
        /** Ring thickness in pixels for this shape; 0 keeps the usual thickness. */
        final int kind, left, top, right, bottom, corner, stroke;
        /** FRAME only: the hole, as insets from the view's bounds, and its corner radius. */
        int innerLeft, innerTop, innerRight, innerBottom, innerCorner;

        static Shape frame(String screen, String id, int left, int top, int right, int bottom, int corner,
                int innerLeft, int innerTop, int innerRight, int innerBottom, int innerCorner) {
            Shape shape = new Shape(screen, id, FRAME, left, top, right, bottom, corner);
            shape.innerLeft = innerLeft;
            shape.innerTop = innerTop;
            shape.innerRight = innerRight;
            shape.innerBottom = innerBottom;
            shape.innerCorner = innerCorner;
            return shape;
        }

        Shape(String screen, String id, int kind, int left, int top, int right, int bottom, int corner) {
            this(screen, id, kind, left, top, right, bottom, corner, 0);
        }

        Shape(String screen, String id, int kind, int left, int top, int right, int bottom, int corner, int stroke) {
            this.stroke = stroke;
            this.screen = screen;
            this.id = id;
            this.kind = kind;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.corner = corner;
        }
    }

    private final class RingView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Shape shape;

        RingView(Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(pad);
            paint.setColor(color);
        }

        void setShape(Shape shape, int stroke) {
            if (this.shape == shape && paint.getStrokeWidth() == stroke) return;
            this.shape = shape;
            paint.setStrokeWidth(stroke);
            paint.setStyle(shape != null && shape.kind == Shape.FRAME ? Paint.Style.FILL : Paint.Style.STROKE);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            // The window is the shape plus one stroke width all round, so the stroke's inner edge sits on the
            // shape's edge: no gap between the ring and the button.
            float half = paint.getStrokeWidth() / 2;
            float w = getWidth(), h = getHeight();
            if (shape != null && shape.kind == Shape.FRAME) {
                // The window is the outer rect; the hole sits at the inner insets, measured from the view's bounds.
                android.graphics.Path frame = new android.graphics.Path();
                frame.setFillType(android.graphics.Path.FillType.EVEN_ODD);
                frame.addRoundRect(0, 0, w, h, shape.corner, shape.corner, android.graphics.Path.Direction.CW);
                float l = shape.innerLeft - shape.left, t = shape.innerTop - shape.top;
                float r = w - (shape.innerRight - shape.right), b = h - (shape.innerBottom - shape.bottom);
                frame.addRoundRect(l, t, r, b, shape.innerCorner, shape.innerCorner, android.graphics.Path.Direction.CW);
                canvas.drawPath(frame, paint);
                return;
            }
            if (shape != null && shape.kind == Shape.CIRCLE) {
                canvas.drawCircle(w / 2f, h / 2f, Math.min(w, h) / 2f - half, paint);
                return;
            }
            float r;
            if (shape != null && shape.kind == Shape.PILL) r = h / 2f - half;
            else if (shape != null) r = shape.corner + paint.getStrokeWidth() - half;
            else r = profile.cornerDp * density;
            canvas.drawRoundRect(half, half, w - half, h - half, r, r, paint);
        }
    }
}
