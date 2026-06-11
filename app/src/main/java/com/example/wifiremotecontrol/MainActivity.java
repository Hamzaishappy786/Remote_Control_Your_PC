package com.example.wifiremotecontrol;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.wifiremotecontrol.databinding.ActivityMainBinding;
import com.example.wifiremotecontrol.databinding.LayoutDashboardBinding;
import com.example.wifiremotecontrol.databinding.LayoutKeyboardBinding;
import com.example.wifiremotecontrol.databinding.LayoutTrackpadBinding;

public class MainActivity extends AppCompatActivity {

    // ── ViewBinding ───────────────────────────────────────────────────
    private ActivityMainBinding    root;
    private LayoutDashboardBinding dashboard;
    private LayoutTrackpadBinding  trackpad;
    private LayoutKeyboardBinding  keyboard;

    // ── Networking ────────────────────────────────────────────────────
    private UdpSender udpSender;
    private String    serverIp = "";

    // ── Trackpad: gesture state machine ──────────────────────────────
    private enum TouchState { IDLE, WAITING, MOVING, DRAG_LOCKED, SCROLLING }
    private TouchState touchState = TouchState.IDLE;

    private float lastX, lastY, startX, startY;
    private long  touchDownMs;
    private float tapSlopPx;

    private static final float SENSITIVITY          = 2.2f;  // higher = faster cursor
    private static final long  TAP_TIMEOUT_MS       = 180;
    private static final long  DRAG_LOCK_MS         = 400;
    private static final float MIN_FLING_PX_S       = 200f;  // lower = fling triggers easier
    private static final float FLING_DECAY          = 0.88f; // higher = momentum lasts longer
    private static final float SCROLL_PX_PER_CLICK  = 3f;   // lower = faster scroll

    // Two-finger scroll state
    private float lastScrollY;
    private float scrollAccum; // sub-click accumulator for smooth scrolling

    // Long-press timer: fires to transition WAITING → DRAG_LOCKED
    private final Handler  longPressHandler = new Handler(Looper.getMainLooper());
    private final Runnable longPressRunnable = () -> {
        touchState = TouchState.DRAG_LOCKED;
        send("MOUSEDOWN,L");
        vibrateShort(); // haptic confirmation so user knows drag-lock is active
    };

    // Fling (momentum): decays velocity after finger lifts
    private VelocityTracker velocityTracker;
    private float flingVx, flingVy;
    private final Handler  flingHandler = new Handler(Looper.getMainLooper());
    private final Runnable flingStep = new Runnable() {
        @Override public void run() {
            if (Math.abs(flingVx) < 0.5f && Math.abs(flingVy) < 0.5f) return;
            send("MOVE," + Math.round(flingVx) + "," + Math.round(flingVy));
            flingVx *= FLING_DECAY;
            flingVy *= FLING_DECAY;
            flingHandler.postDelayed(this, 16); // ~60 fps
        }
    };

    // ── Keyboard sentinel ─────────────────────────────────────────────
    private static final String SENTINEL = "​"; // zero-width space U+200B
    private boolean kbBusy   = false;
    private boolean altHeld  = false; // sticky ALT modifier

    // ─────────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        root = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(root.getRoot());

        udpSender = new UdpSender();
        tapSlopPx = 12 * getResources().getDisplayMetrics().density;

        dashboard = LayoutDashboardBinding.inflate(getLayoutInflater());
        trackpad  = LayoutTrackpadBinding.inflate(getLayoutInflater());
        keyboard  = LayoutKeyboardBinding.inflate(getLayoutInflater());

        wireDashboard();
        wireTrackpad();
        wireKeyboard();

        showDashboard();
    }

    // ══════════════════════════ Dashboard ════════════════════════════

    private void wireDashboard() {
        dashboard.btnTrackpad.setOnClickListener(v -> {
            if (validateAndStoreIp()) showTrackpadScreen();
        });
        dashboard.btnKeyboard.setOnClickListener(v -> {
            if (validateAndStoreIp()) showKeyboardScreen();
        });
    }

    private boolean validateAndStoreIp() {
        String ip = dashboard.editIpAddress.getText().toString().trim();
        if (ip.isEmpty()) {
            Toast.makeText(this, "Enter the laptop IP address first", Toast.LENGTH_SHORT).show();
            return false;
        }
        serverIp = ip;
        udpSender.setHost(ip); // resolve DNS now so first packet has zero lookup delay
        return true;
    }

    // ══════════════════════════ Trackpad ═════════════════════════════
    //
    //  State machine:
    //
    //  IDLE ──finger down──> WAITING
    //  WAITING ──moved > slop──> MOVING        (cancel long-press timer)
    //  WAITING ──held 400 ms──> DRAG_LOCKED    (MOUSEDOWN sent, vibrate)
    //  MOVING  ──finger up──> IDLE             (tap→CLICK,L  |  fast lift→fling)
    //  DRAG_LOCKED ──finger up──> IDLE         (MOUSEUP sent)

    private void wireTrackpad() {
        trackpad.touchSurface.setOnTouchListener((v, event) -> {
            // Feed every event into the velocity tracker
            if (velocityTracker != null) velocityTracker.addMovement(event);

            switch (event.getActionMasked()) {

                // ── Second finger touches → enter two-finger scroll mode ──────────
                case MotionEvent.ACTION_POINTER_DOWN: {
                    if (event.getPointerCount() == 2) {
                        longPressHandler.removeCallbacks(longPressRunnable);
                        flingHandler.removeCallbacks(flingStep);
                        if (touchState == TouchState.DRAG_LOCKED) send("MOUSEUP,L");
                        if (velocityTracker != null) { velocityTracker.recycle(); velocityTracker = null; }
                        touchState  = TouchState.SCROLLING;
                        lastScrollY = avgY(event);
                        scrollAccum = 0;
                    }
                    break;
                }

                // ── One finger lifts while scrolling → back to idle ───────────────
                case MotionEvent.ACTION_POINTER_UP: {
                    if (touchState == TouchState.SCROLLING) {
                        touchState  = TouchState.IDLE;
                        scrollAccum = 0;
                    }
                    break;
                }

                case MotionEvent.ACTION_DOWN: {
                    // Cancel any fling in progress
                    flingHandler.removeCallbacks(flingStep);

                    // Fresh velocity tracker
                    if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
                    else velocityTracker.clear();
                    velocityTracker.addMovement(event);

                    lastX = startX = event.getX();
                    lastY = startY = event.getY();
                    touchDownMs = System.currentTimeMillis();
                    touchState  = TouchState.WAITING;

                    // Arm the drag-lock timer
                    longPressHandler.postDelayed(longPressRunnable, DRAG_LOCK_MS);
                    break;
                }

                case MotionEvent.ACTION_MOVE: {
                    if (touchState == TouchState.SCROLLING) {
                        // Two-finger scroll: use average Y of both fingers
                        float curY = avgY(event);
                        scrollAccum += lastScrollY - curY; // finger up = positive accum = scroll up
                        int clicks = (int) (scrollAccum / SCROLL_PX_PER_CLICK);
                        if (clicks != 0) {
                            // Cap to ±2 per packet — avoids big jumps on fast swipes
                            int capped = Math.max(-2, Math.min(2, clicks));
                            send("SCROLL," + capped);
                            scrollAccum -= capped * SCROLL_PX_PER_CLICK;
                        }
                        lastScrollY = curY;
                        break;
                    }

                    if (touchState == TouchState.WAITING) {
                        float dist = (float) Math.hypot(
                                event.getX() - startX, event.getY() - startY);
                        if (dist > tapSlopPx) {
                            longPressHandler.removeCallbacks(longPressRunnable);
                            touchState = TouchState.MOVING;
                        }
                    }

                    if (touchState == TouchState.MOVING || touchState == TouchState.DRAG_LOCKED) {
                        float dx = (event.getX() - lastX) * SENSITIVITY;
                        float dy = (event.getY() - lastY) * SENSITIVITY;
                        if (Math.abs(dx) >= 1f || Math.abs(dy) >= 1f) {
                            send("MOVE," + Math.round(dx) + "," + Math.round(dy));
                            lastX = event.getX();
                            lastY = event.getY();
                        }
                    }
                    break;
                }

                case MotionEvent.ACTION_UP: {
                    longPressHandler.removeCallbacks(longPressRunnable);

                    if (touchState == TouchState.DRAG_LOCKED) {
                        // Release the held mouse button
                        send("MOUSEUP,L");

                    } else if (touchState == TouchState.WAITING) {
                        // Finger never moved — treat as a tap click
                        long elapsed = System.currentTimeMillis() - touchDownMs;
                        if (elapsed < TAP_TIMEOUT_MS) send("CLICK,L");

                    } else if (touchState == TouchState.MOVING && velocityTracker != null) {
                        // Check for fling: fast lift-off continues mouse movement
                        velocityTracker.computeCurrentVelocity(1000);
                        float vx = velocityTracker.getXVelocity();
                        float vy = velocityTracker.getYVelocity();
                        if (Math.abs(vx) > MIN_FLING_PX_S || Math.abs(vy) > MIN_FLING_PX_S) {
                            // Convert pixels/sec → pixels/frame at ~60 fps, apply sensitivity
                            flingVx = (vx / 60f) * SENSITIVITY;
                            flingVy = (vy / 60f) * SENSITIVITY;
                            flingHandler.post(flingStep);
                        }
                    }

                    if (velocityTracker != null) {
                        velocityTracker.recycle();
                        velocityTracker = null;
                    }
                    touchState = TouchState.IDLE;
                    break;
                }

                case MotionEvent.ACTION_CANCEL: {
                    longPressHandler.removeCallbacks(longPressRunnable);
                    flingHandler.removeCallbacks(flingStep);
                    if (touchState == TouchState.DRAG_LOCKED) send("MOUSEUP,L");
                    if (velocityTracker != null) { velocityTracker.recycle(); velocityTracker = null; }
                    touchState  = TouchState.IDLE;
                    scrollAccum = 0;
                    break;
                }
            }
            return true;
        });

        trackpad.btnLeftClick.setOnClickListener(v  -> send("CLICK,L"));
        trackpad.btnRightClick.setOnClickListener(v -> send("CLICK,R"));
        trackpad.btnBackTrackpad.setOnClickListener(v -> {
            // Clean up any active gesture before leaving
            longPressHandler.removeCallbacks(longPressRunnable);
            flingHandler.removeCallbacks(flingStep);
            if (touchState == TouchState.DRAG_LOCKED) send("MOUSEUP,L");
            touchState = TouchState.IDLE;
            showDashboard();
        });
    }

    // ══════════════════════════ Keyboard ═════════════════════════════

    private void wireKeyboard() {
        keyboard.btnEnter.setOnClickListener(v      -> sendKey("ENTER"));
        keyboard.btnBackspace.setOnClickListener(v  -> sendKey("BACKSPACE"));
        keyboard.btnEscape.setOnClickListener(v     -> sendKey("ESCAPE"));
        keyboard.btnTab.setOnClickListener(v        -> sendKey("TAB"));
        keyboard.btnArrowUp.setOnClickListener(v    -> sendKey("ARROW_UP"));
        keyboard.btnArrowDown.setOnClickListener(v  -> sendKey("ARROW_DOWN"));
        keyboard.btnArrowLeft.setOnClickListener(v  -> sendKey("ARROW_LEFT"));
        keyboard.btnArrowRight.setOnClickListener(v -> sendKey("ARROW_RIGHT"));

        // ALT sticky modifier: first tap arms it (button turns blue), second tap disarms
        keyboard.btnAlt.setOnClickListener(v -> {
            altHeld = !altHeld;
            updateAltButton();
        });

        // WIN+D is always a direct hotkey (no modifier needed)
        keyboard.btnWinD.setOnClickListener(v -> send("HOTKEY,win,d"));

        keyboard.btnSpace.setOnClickListener(v -> sendKey("SPACE"));

        keyboard.btnBackKeyboard.setOnClickListener(v -> {
            altHeld = false;
            updateAltButton();
            hideSoftKeyboard();
            showDashboard();
        });

        keyboard.editKeyInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c)    {}

            @Override
            public void afterTextChanged(Editable s) {
                if (kbBusy) return;
                String text = s.toString();
                if (text.length() > SENTINEL.length()) {
                    String added = text.substring(SENTINEL.length());
                    for (char c : added.toCharArray()) {
                        if (c != '​') {
                            if (altHeld) {
                                send("HOTKEY,alt," + c);
                                // keep altHeld active — user must tap ALT again to release
                            } else {
                                send("KEY," + c);
                            }
                        }
                    }
                } else if (text.length() < SENTINEL.length() || !text.contains(SENTINEL)) {
                    sendKey("BACKSPACE");
                }
                resetSentinel();
            }
        });
    }

    /**
     * Sends KEY,<name> normally, or HOTKEY,alt,<name> when ALT is held.
     * ALT stays held after the press — tap ALT again to release it.
     */
    private void sendKey(String keyName) {
        if (altHeld) {
            send("HOTKEY,alt," + keyName.toLowerCase());
        } else {
            send("KEY," + keyName);
        }
    }

    /** Reflects current altHeld state on the ALT button (blue = held, dark = idle). */
    private void updateAltButton() {
        int colorRes = altHeld ? R.color.primary : R.color.btn_special;
        keyboard.btnAlt.setBackgroundTintList(
                ColorStateList.valueOf(getResources().getColor(colorRes, null)));
        keyboard.btnAlt.setText(altHeld ? "ALT ●" : "ALT");
    }

    private void resetSentinel() {
        kbBusy = true;
        keyboard.editKeyInput.setText(SENTINEL);
        keyboard.editKeyInput.setSelection(SENTINEL.length());
        kbBusy = false;
    }

    // ══════════════════════════ Navigation ═══════════════════════════

    private void swap(View view) {
        root.container.removeAllViews();
        root.container.addView(view);
    }

    private void showDashboard()      { swap(dashboard.getRoot()); }
    private void showTrackpadScreen() { swap(trackpad.getRoot()); }

    private void showKeyboardScreen() {
        swap(keyboard.getRoot());
        keyboard.editKeyInput.post(() -> {
            resetSentinel();
            keyboard.editKeyInput.requestFocus();
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null)
                imm.showSoftInput(keyboard.editKeyInput, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void hideSoftKeyboard() {
        View focus = getCurrentFocus();
        if (focus != null) {
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        }
    }

    // ══════════════════════════ Helpers ══════════════════════════════

    private void send(String msg) { udpSender.send(serverIp, msg); }

    /** Average Y coordinate across all active pointers (for two-finger scroll). */
    private float avgY(MotionEvent event) {
        float sum = 0;
        for (int i = 0; i < event.getPointerCount(); i++) sum += event.getY(i);
        return sum / event.getPointerCount();
    }

    @SuppressWarnings("deprecation")
    private void vibrateShort() {
        Vibrator vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vib == null || !vib.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vib.vibrate(25);
        }
    }

    // ══════════════════════════ Lifecycle ════════════════════════════

    @Override
    public void onBackPressed() {
        View current = root.container.getChildCount() > 0
                ? root.container.getChildAt(0) : null;
        if (current != null && current != dashboard.getRoot()) {
            if (current == keyboard.getRoot()) {
                altHeld = false;
                updateAltButton();
                hideSoftKeyboard();
            }
            if (current == trackpad.getRoot()) {
                longPressHandler.removeCallbacks(longPressRunnable);
                flingHandler.removeCallbacks(flingStep);
                if (touchState == TouchState.DRAG_LOCKED) send("MOUSEUP,L");
                touchState = TouchState.IDLE;
            }
            showDashboard();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        longPressHandler.removeCallbacksAndMessages(null);
        flingHandler.removeCallbacksAndMessages(null);
        udpSender.shutdown();
    }
}
