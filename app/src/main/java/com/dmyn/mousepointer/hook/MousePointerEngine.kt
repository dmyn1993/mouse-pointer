package com.dmyn.mousepointer.hook

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import com.dmyn.mousepointer.MousePointerConfig
import de.robv.android.xposed.XposedBridge
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Trigger flow:
 * - A single-finger DOWN within the configured edge width starts a watch session and shows a thin
 *   arc anchored at the touched edge point. The physical touch stream is consumed and replayed as
 *   an injected touch stream, so the system back arrow / app scrolling keep working.
 * - When the finger crosses the arc (within its angular span), the replay stream is closed with
 *   ACTION_CANCEL, the hint hides and the desktop-style mouse pointer starts. Subsequent physical
 *   events are consumed; releasing the finger injects a mouse click at the cursor tip.
 * - Releasing the finger without crossing the arc lets the replayed gesture complete normally.
 *
 * Arc radius, angular span, center angle, tolerance, hint duration, pointer gain/size, click mode,
 * color, hover/vibration/debug switches are all configurable.
 */
internal object MousePointerEngine {

    private const val TAG = "MousePointer"

    /** Safety net: if UP/CANCEL is ever lost while consuming, drop the pointer session. */
    private const val SESSION_TIMEOUT_MS = 10_000L

    private const val SCROLL_HIDE_VERTICAL_DP = 20f
    private const val SCROLL_HIDE_INWARD_DP = 10f

    private enum class State { IDLE, WATCHING, AIMING }

    @Volatile
    private var config = MousePointerConfig.defaults()

    private var initialized = false
    private var appContext: Context? = null
    private var mainHandler: Handler? = null
    private var windowManager: WindowManager? = null
    private var injector: EventInjector? = null
    private var pointer: GesturePointerController? = null
    private var arcOverlay: ArcHintOverlay? = null

    private var density = 1f
    private var screenWidth = 0
    private var screenHeight = 0

    private var state = State.IDLE

    // Arc geometry for the current gesture.
    private var arcCenterX = 0f
    private var arcCenterY = 0f
    private var fromLeftEdge = true
    private var upward = true
    private var arcRadiusPx = 0f
    private var arcSpanDeg = 60f
    private var arcCenterAngleDeg = 45f
    private var arcTolerancePx = 0f
    private var arcStartTime = 0L
    private var prevRadius = 0f

    private var activeDeviceId = -1
    private var lastEventTime = 0L
    private var replayEnabled = true
    private var triggerArmed = false

    @Volatile
    private var arcToken = 0

    @Volatile
    private var arcShown = false

    fun init(context: Context, initialConfig: MousePointerConfig) {
        if (initialized) {
            applyConfig(initialConfig)
            return
        }
        appContext = context.applicationContext ?: context
        mainHandler = Handler(Looper.getMainLooper())
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        density = context.resources.displayMetrics.density

        val injector = EventInjector { message -> log(message) }
        this.injector = injector
        pointer = GesturePointerController(injector) { message -> log(message) }
        arcOverlay = ArcHintOverlay { message -> log(message) }

        updateScreenBounds()
        initialized = true
        applyConfig(initialConfig)
        log("$TAG: initialized ($screenWidth x $screenHeight, ${density}x)")
    }

    /** Applies a new configuration (from Settings.Global / config broadcast). */
    fun applyConfig(newConfig: MousePointerConfig) {
        val cfg = newConfig.clamped()
        config = cfg
        mainHandler?.post {
            pointer?.applyConfig(density, cfg)
            if (!cfg.enabled) {
                pointer?.finish()
                arcOverlay?.hide()
                arcShown = false
                if (state != State.IDLE) {
                    reset()
                }
            }
        }
        log("$TAG: config applied enabled=${cfg.enabled} edge=${cfg.edgeWidthDp}dp " +
            "arcR=${cfg.arcRadiusDp}dp span=${cfg.arcSpanDeg}° center=${cfg.arcCenterAngleDeg}° " +
            "tol=${cfg.arcToleranceDp}dp gain=${cfg.pointerGainTenths / 10f}x " +
            "click=${cfg.clickMode} color=${cfg.pointerColor}")
    }

    private fun arcRadiusPx(): Float = config.arcRadiusDp * density

    /**
     * @return true when the physical event must be consumed (the module replays it instead).
     */
    fun handleMotionEvent(event: MotionEvent): Boolean {
        if (!initialized) {
            return false
        }
        if (!config.enabled) {
            if (state != State.IDLE) {
                reset()
            }
            return false
        }
        if (state != State.IDLE) {
            if (event.eventTime - lastEventTime > SESSION_TIMEOUT_MS) {
                reset()
            }
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                reset()
            }
            if (state != State.IDLE && event.deviceId != activeDeviceId) {
                return false
            }
        }
        lastEventTime = event.eventTime
        return when (state) {
            State.IDLE -> handleIdle(event)
            State.WATCHING -> handleWatching(event)
            State.AIMING -> handleAiming(event)
        }
    }

    private fun handleIdle(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) {
            return false
        }
        if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
            return false
        }
        if (event.pointerCount != 1) {
            return false
        }
        updateScreenBounds()
        if (screenWidth <= 0 || screenHeight <= 0) {
            return false
        }
        // Landscape auto-disable: avoids accidental triggers while playing games etc.
        if (config.disableInLandscape && screenWidth > screenHeight) {
            return false
        }
        val x = event.rawX
        val y = event.rawY
        // Four screen corners are exclusion zones, so edge presses near the corners do nothing.
        if (config.cornerExcludeEnabled) {
            val corner = config.cornerSizeDp * density
            val nearLeft = x < corner
            val nearRight = x > screenWidth - corner
            val nearTop = y < corner
            val nearBottom = y > screenHeight - corner
            if ((nearLeft || nearRight) && (nearTop || nearBottom)) {
                return false
            }
        }
        val edgePx = config.edgeWidthDp * density
        if (x > edgePx && x < screenWidth - edgePx) {
            return false
        }

        fromLeftEdge = x < screenWidth / 2f
        arcCenterX = event.rawX
        arcCenterY = event.rawY
        arcRadiusPx = arcRadiusPx()
        arcSpanDeg = config.arcSpanDeg.toFloat()
        arcCenterAngleDeg = config.arcCenterAngleDeg.toFloat()
        arcTolerancePx = config.arcToleranceDp * density
        arcStartTime = event.eventTime
        prevRadius = 0f
        // Keep the arc on screen: near the top edge it opens downwards instead of upwards.
        val verticalExtent = arcRadiusPx * sin(Math.toRadians(arcCenterAngleDeg.toDouble())).toFloat()
        val margin = 28f * density
        upward = if (event.rawY > verticalExtent + margin) {
            true
        } else {
            event.rawY < screenHeight - verticalExtent - margin
        }

        activeDeviceId = event.deviceId
        lastEventTime = event.eventTime
        replayEnabled = true
        triggerArmed = true
        state = State.WATCHING
        showArc()
        // Replay the DOWN to the app/system as an injected touch event.
        replay(event)
        return true
    }

    private fun handleWatching(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_MOVE &&
            event.pointerCount == 1 &&
            replayEnabled &&
            triggerArmed
        ) {
            val dx = event.rawX - arcCenterX
            val dy = event.rawY - arcCenterY
            val inward = if (fromLeftEdge) dx else -dx
            if (inward > 0f) {
                val vertical = if (upward) -dy else dy
                val radius = hypot(dx, dy)
                val angleDeg =
                    Math.toDegrees(atan2(vertical.toDouble(), inward.toDouble())).toFloat()
                if (angleDeg >= arcCenterAngleDeg - arcSpanDeg / 2f &&
                    angleDeg <= arcCenterAngleDeg + arcSpanDeg / 2f
                ) {
                    val before = prevRadius - arcRadiusPx
                    val after = radius - arcRadiusPx
                    val crossed = before * after <= 0f || abs(after) <= arcTolerancePx
                    if (crossed) {
                        triggerArmed = false
                        hideArc()
                        replay(event, MotionEvent.ACTION_CANCEL)
                        startPointer(event)
                        if (config.vibrateOnTrigger) {
                            vibrate()
                        }
                        return true
                    }
                }
                prevRadius = radius
            }
        }

        if (event.pointerCount != 1) {
            // Multi-touch is not supported: cancel the replayed stream and drain the rest.
            triggerArmed = false
            if (replayEnabled) {
                replay(event, MotionEvent.ACTION_CANCEL)
                replayEnabled = false
            }
            hideArc()
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                state = State.IDLE
                activeDeviceId = -1
            }
            return true
        }

        if (replayEnabled) {
            replay(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (event.eventTime - arcStartTime > config.hintDurationMs ||
                    hideHintIfIrrelevant(event)
                ) {
                    // Hint expired / the gesture is clearly not heading across the arc: disarm
                    // the trigger, but keep replaying the gesture to the app as normal.
                    triggerArmed = false
                    hideArc()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                triggerArmed = false
                hideArc()
                state = State.IDLE
                activeDeviceId = -1
            }
        }
        return true
    }

    /** @return true when the hint should be hidden (and the trigger disarmed). */
    private fun hideHintIfIrrelevant(event: MotionEvent): Boolean {
        val dx = event.rawX - arcCenterX
        val dy = event.rawY - arcCenterY
        val inward = if (fromLeftEdge) dx else -dx
        val radius = hypot(dx, dy)
        return when {
            inward < -arcRadiusPx * 0.2f -> true
            radius > arcRadiusPx * 2.0f -> true
            abs(dy) > SCROLL_HIDE_VERTICAL_DP * density &&
                inward < SCROLL_HIDE_INWARD_DP * density -> true
            else -> false
        }
    }

    private fun startPointer(event: MotionEvent) {
        val context = appContext ?: return
        triggerArmed = false
        state = State.AIMING
        val x = event.rawX
        val y = event.rawY
        log("$TAG: arc crossed at (${x.toInt()}, ${y.toInt()}), pointer started")
        mainHandler?.post {
            pointer?.start(context, x, y)
        }
    }

    private fun handleAiming(event: MotionEvent): Boolean {
        val context = appContext ?: run {
            state = State.IDLE
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val x = event.rawX
                val y = event.rawY
                mainHandler?.post {
                    pointer?.moveTo(x, y)
                }
            }
            MotionEvent.ACTION_UP -> {
                val x = event.rawX
                val y = event.rawY
                mainHandler?.post {
                    pointer?.tapAndFinish(context, x, y)
                }
                state = State.IDLE
                activeDeviceId = -1
            }
            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP -> {
                mainHandler?.post {
                    pointer?.finish()
                }
                state = State.IDLE
                activeDeviceId = -1
            }
        }
        return true
    }

    private fun replay(event: MotionEvent, action: Int? = null) {
        if (!replayEnabled) {
            return
        }
        val context = appContext ?: return
        injector?.replayTouch(context, event, action)
        if (action == MotionEvent.ACTION_CANCEL) {
            replayEnabled = false
        }
    }

    private fun showArc() {
        val context = appContext ?: return
        val token = ++arcToken
        val cx = arcCenterX
        val cy = arcCenterY
        val radius = arcRadiusPx
        val span = arcSpanDeg
        val centerAngle = arcCenterAngleDeg
        val tolerance = arcTolerancePx
        val up = upward
        val left = fromLeftEdge
        mainHandler?.post {
            if (!config.enabled) {
                return@post
            }
            arcShown = true
            arcOverlay?.show(
                context = context,
                centerX = cx,
                centerY = cy,
                radiusPx = radius,
                spanDeg = span,
                centerAngleDeg = centerAngle,
                upward = up,
                fromLeftEdge = left,
                tolerancePx = tolerance,
            )
            mainHandler?.postDelayed({
                if (token == arcToken) {
                    arcOverlay?.hide()
                    arcShown = false
                }
            }, config.hintDurationMs.toLong())
        }
    }

    private fun hideArc() {
        arcToken++
        arcShown = false
        mainHandler?.post {
            arcOverlay?.hide()
        }
    }

    private fun reset() {
        hideArc()
        replayEnabled = false
        triggerArmed = false
        if (state == State.AIMING) {
            mainHandler?.post {
                pointer?.finish()
            }
        }
        state = State.IDLE
        activeDeviceId = -1
    }

    private fun updateScreenBounds() {
        val wm = windowManager
        if (wm != null) {
            try {
                val bounds = wm.currentWindowMetrics.bounds
                screenWidth = bounds.width()
                screenHeight = bounds.height()
            } catch (t: Throwable) {
                log("$TAG: currentWindowMetrics failed: ${t.message}")
            }
        }
        if (screenWidth <= 0 || screenHeight <= 0) {
            val metrics = appContext?.resources?.displayMetrics
            if (metrics != null) {
                screenWidth = metrics.widthPixels
                screenHeight = metrics.heightPixels
            }
        }
    }

    private fun vibrate() {
        try {
            val context = appContext ?: return
            val vibrator = context.getSystemService(Vibrator::class.java) ?: return
            if (!vibrator.hasVibrator()) {
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (t: Throwable) {
            log("$TAG: vibrate failed: ${t.message}")
        }
    }

    private fun log(message: String) {
        XposedBridge.log(message)
        Log.i(TAG, message.removePrefix("$TAG: "))
    }
}
