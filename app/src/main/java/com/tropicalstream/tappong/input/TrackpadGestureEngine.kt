package com.tropicalstream.tappong.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.max

/**
 * Self-contained gesture engine for the X3 Pro temple trackpads (ported verbatim
 * from WanderQuest, on-device-tuned):
 *
 *  - RIGHT temple pad = `cyttsp5_mt`. A LIGHT tap is a touch DOWN/UP MotionEvent.
 *    A FIRM click arrives as a hardware KEY (KEYCODE_BUTTON_A / DPAD_CENTER) — and
 *    one firm click can appear on BOTH paths (gotcha #11), so taps are deduped.
 *  - LEFT temple pad = `cyttsp6_mt` = the system volume pad. Ignored for nav.
 *  - Match input devices by NAME; device ids shuffle across reboots.
 *
 * Feed from the Activity's dispatchTouchEvent / dispatchKeyEvent / dispatchGenericMotionEvent.
 */
class TrackpadGestureEngine {

    companion object {
        const val SHORT_TAP_MAX_MS = 300L
        const val DOUBLE_TAP_WINDOW_MS = 300L
        const val LONG_TAP_MIN_MS = 550L
        const val SYSTEM_HOLD_MS = 450L
        const val KEY_TAP_MAX_MS = 400L
        const val ECHO_MIN_GAP_MS = 40L
        const val CROSS_SOURCE_DEDUP_MS = 250L
        const val GENERIC_SCROLL_SCALE = 22f
        private const val TAP_MOVE_TOLERANCE_MIN_PX = 18f
        private const val TAP_MOVE_TOLERANCE_RATIO = 0.04f
        private const val SWIPE_MIN_PX = 26f
        const val LEFT_ARM_DEVICE = "cyttsp6"
        private const val SRC_TOUCH = 0
        private const val SRC_KEY = 1
    }

    var onTap: (() -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null
    var onTripleTap: (() -> Unit)? = null
    var onLongTap: (() -> Unit)? = null
    var onSwipeVertical: ((direction: Int) -> Unit)? = null   // -1 up, +1 down
    var onSwipeHorizontal: ((direction: Int) -> Unit)? = null // -1 left, +1 right
    /** Continuous right-pad drag deltas (raw pad px per MOVE event) — the
     *  paddle-control channel TapPong adds to the shared engine. Fires on
     *  every ACTION_MOVE alongside (not instead of) tap/swipe detection. */
    var onDrag: ((dx: Float, dy: Float) -> Unit)? = null
    var onLeftTap: (() -> Unit)? = null
    var debugSink: ((String) -> Unit)? = null

    private var leftDownMs = 0L
    private var leftStartX = 0f
    private var leftStartY = 0f
    private var leftPeakMove = 0f
    private var leftTracking = false

    private val handler = Handler(Looper.getMainLooper())

    private var lastTapMs = 0L
    private var lastTapSource = -1
    private var tapStreak = 0
    private val resolveTaps = Runnable {
        val streak = tapStreak
        tapStreak = 0
        when {
            streak >= 3 -> onTripleTap?.invoke()
            streak == 2 -> onDoubleTap?.invoke()
            streak == 1 -> onTap?.invoke()
        }
    }

    private fun registerTap(source: Int) {
        val now = SystemClock.uptimeMillis()
        val gap = now - lastTapMs
        if (lastTapMs > 0L) {
            if (source != lastTapSource && gap < CROSS_SOURCE_DEDUP_MS) return
            if (source == lastTapSource && gap < ECHO_MIN_GAP_MS) return
        }
        lastTapMs = now
        lastTapSource = source
        tapStreak += 1
        handler.removeCallbacks(resolveTaps)
        if (tapStreak >= 3) resolveTaps.run()
        else handler.postDelayed(resolveTaps, DOUBLE_TAP_WINDOW_MS)
    }

    private var keyDownMs = 0L
    private var keyTracking = false
    private var keyHeld = false

    private var touchDownMs = 0L
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchLastX = 0f
    private var touchLastY = 0f
    private var touchAccumX = 0f
    private var touchAccumY = 0f
    private var touchTracking = false
    private var touchMovedTooFar = false
    private var touchLongFired = false
    private var swipeFiredForGesture = false
    private var screenW = 640
    private var screenH = 480
    private val touchLongCheck = Runnable {
        if (touchTracking && !touchMovedTooFar && !swipeFiredForGesture) {
            touchLongFired = true
            onLongTap?.invoke()
        }
    }

    fun setScreenSize(width: Int, height: Int) {
        if (width > 0) screenW = width
        if (height > 0) screenH = height
    }

    fun isLeftArmDevice(deviceId: Int): Boolean {
        val name = InputDevice.getDevice(deviceId)?.name ?: return false
        return name.contains(LEFT_ARM_DEVICE, ignoreCase = true)
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        if (isLeftArmDevice(event.deviceId)) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    leftDownMs = SystemClock.uptimeMillis()
                    leftStartX = event.x
                    leftStartY = event.y
                    leftPeakMove = 0f
                    leftTracking = true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (leftTracking) {
                        leftPeakMove = max(
                            leftPeakMove,
                            max(abs(event.x - leftStartX), abs(event.y - leftStartY))
                        )
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (leftTracking) {
                        leftTracking = false
                        val held = SystemClock.uptimeMillis() - leftDownMs
                        if (held <= 300L && leftPeakMove <= 30f) onLeftTap?.invoke()
                    }
                }
                MotionEvent.ACTION_CANCEL -> leftTracking = false
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownMs = SystemClock.uptimeMillis()
                touchStartX = event.x
                touchStartY = event.y
                touchLastX = event.x
                touchLastY = event.y
                touchAccumX = 0f
                touchAccumY = 0f
                touchTracking = true
                touchMovedTooFar = false
                touchLongFired = false
                swipeFiredForGesture = false
                handler.postDelayed(touchLongCheck, LONG_TAP_MIN_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                if (touchTracking) {
                    onDrag?.invoke(event.x - touchLastX, event.y - touchLastY)
                    touchLastX = event.x
                    touchLastY = event.y
                    touchAccumX = event.x - touchStartX
                    touchAccumY = event.y - touchStartY
                    val tol = max(TAP_MOVE_TOLERANCE_MIN_PX, TAP_MOVE_TOLERANCE_RATIO * minOf(screenW, screenH))
                    if (abs(touchAccumX) > tol || abs(touchAccumY) > tol) {
                        touchMovedTooFar = true
                        handler.removeCallbacks(touchLongCheck)
                    }
                    maybeFireSwipe()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(touchLongCheck)
                if (touchTracking) {
                    touchTracking = false
                    val held = SystemClock.uptimeMillis() - touchDownMs
                    val isTap = event.actionMasked == MotionEvent.ACTION_UP &&
                        !touchMovedTooFar && !swipeFiredForGesture &&
                        !touchLongFired && held <= SHORT_TAP_MAX_MS
                    if (isTap) registerTap(SRC_TOUCH)
                }
            }
        }
        return true
    }

    private fun maybeFireSwipe() {
        if (swipeFiredForGesture) return
        val minSwipe = max(SWIPE_MIN_PX, 0.06f * minOf(screenW, screenH))
        val ax = abs(touchAccumX)
        val ay = abs(touchAccumY)
        if (ay >= minSwipe && ay > ax * 1.3f) {
            swipeFiredForGesture = true
            handler.removeCallbacks(touchLongCheck)
            onSwipeVertical?.invoke(if (touchAccumY > 0) 1 else -1)
        } else if (ax >= minSwipe * 1.6f && ax > ay * 1.3f) {
            swipeFiredForGesture = true
            handler.removeCallbacks(touchLongCheck)
            onSwipeHorizontal?.invoke(if (touchAccumX > 0) 1 else -1)
        }
    }

    fun onKeyEvent(event: KeyEvent): Boolean {
        val isTapKey = event.keyCode == KeyEvent.KEYCODE_BUTTON_A ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode == KeyEvent.KEYCODE_ENTER
        if (!isTapKey) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    keyDownMs = SystemClock.uptimeMillis()
                    keyTracking = true
                    keyHeld = event.isLongPress
                } else keyHeld = true
                // Let the RayNeo launcher observe the full long-key sequence.
                return false
            }
            KeyEvent.ACTION_UP -> {
                if (!keyTracking) return false
                keyTracking = false
                val held = SystemClock.uptimeMillis() - keyDownMs
                if (keyHeld || event.isCanceled ||
                    maxOf(held, event.eventTime - event.downTime) >= SYSTEM_HOLD_MS
                ) {
                    keyHeld = false
                    return false
                }
                keyHeld = false
                if (held < KEY_TAP_MAX_MS) registerTap(SRC_KEY)
                return true
            }
        }
        return true
    }

    fun onGenericMotion(event: MotionEvent): Boolean {
        val src = event.source
        val pointerClass = (src and InputDevice.SOURCE_CLASS_POINTER) != 0 ||
            (src and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
            (src and InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD
        if (!pointerClass) return false
        if (event.actionMasked != MotionEvent.ACTION_SCROLL) return false
        if (isLeftArmDevice(event.deviceId)) return true
        if (touchTracking) return true
        var dx = event.getAxisValue(MotionEvent.AXIS_HSCROLL) * GENERIC_SCROLL_SCALE
        var dy = event.getAxisValue(MotionEvent.AXIS_VSCROLL) * GENERIC_SCROLL_SCALE
        if (dx == 0f && dy == 0f) {
            dx = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X) * GENERIC_SCROLL_SCALE
            dy = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y) * GENERIC_SCROLL_SCALE
        }
        if (abs(dy) >= SWIPE_MIN_PX && abs(dy) > abs(dx)) {
            onSwipeVertical?.invoke(if (dy > 0) -1 else 1)
            return true
        }
        if (abs(dx) >= SWIPE_MIN_PX && abs(dx) > abs(dy)) {
            onSwipeHorizontal?.invoke(if (dx > 0) 1 else -1)
            return true
        }
        return true
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
    }
}
