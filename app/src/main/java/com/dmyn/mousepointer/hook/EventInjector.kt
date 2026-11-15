package com.dmyn.mousepointer.hook

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent

/**
 * Injects synthetic events into the input dispatcher.
 *
 * - The physical touch stream is consumed by the module while a gesture is being evaluated and
 *   replayed to the app/system as a consistent injected touch stream (so the system back gesture
 *   and normal scrolling still see the gesture).
 * - When the finger crosses the hint arc, the replay stream is closed with ACTION_CANCEL and the
 *   pointer starts injecting mouse hover/click events.
 */
internal class EventInjector(
    private val log: (String) -> Unit,
) {
    private var injectMethod: java.lang.reflect.Method? = null
    private var setActionButtonMethod: java.lang.reflect.Method? = null

    /** Replays one physical touch event as an injected touch event (deviceId -> virtual). */
    fun replayTouch(context: Context, sourceEvent: MotionEvent, action: Int? = null): Boolean {
        try {
            val properties = arrayOf(MotionEvent.PointerProperties())
            sourceEvent.getPointerProperties(0, properties[0])
            val coords = arrayOf(MotionEvent.PointerCoords())
            sourceEvent.getPointerCoords(0, coords[0])
            val event = MotionEvent.obtain(
                sourceEvent.downTime,
                sourceEvent.eventTime,
                action ?: sourceEvent.actionMasked,
                1,
                properties,
                coords,
                sourceEvent.metaState,
                0,
                sourceEvent.xPrecision,
                sourceEvent.yPrecision,
                0, // deviceId -> injected VIRTUAL_KEYBOARD_ID
                0, // edgeFlags
                InputDevice.SOURCE_TOUCHSCREEN,
                0, // flags
            )
            return try {
                injectRaw(context, event)
            } finally {
                event.recycle()
            }
        } catch (t: Throwable) {
            log("Touch replay failed: ${t.message}")
            return false
        }
    }

    /** Touch tap at (x, y), used when the click mode is set to touch. */
    fun injectTouchTap(context: Context, x: Float, y: Float): Boolean {
        val downTime = SystemClock.uptimeMillis()
        val down = obtainTouchEvent(MotionEvent.ACTION_DOWN, x, y, downTime, downTime)
        val up = obtainTouchEvent(
            MotionEvent.ACTION_UP,
            x,
            y,
            downTime,
            SystemClock.uptimeMillis(),
        )
        return try {
            val downOk = injectRaw(context, down)
            val upOk = injectRaw(context, up)
            if (downOk && !upOk) {
                injectTouchCancel(context, x, y, downTime)
            }
            downOk && upOk
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    /**
     * Injects a mouse hover move. Built with source = SOURCE_MOUSE and toolType = TOOL_TYPE_MOUSE
     * so InputDispatcher performs the normal mouse hit-test and synthesises HOVER_ENTER/EXIT.
     */
    fun injectHoverMove(context: Context, x: Float, y: Float): Boolean {
        return try {
            val event = obtainMouseEvent(
                action = MotionEvent.ACTION_HOVER_MOVE,
                x = x,
                y = y,
                buttonState = 0,
                downTime = SystemClock.uptimeMillis(),
                eventTime = SystemClock.uptimeMillis(),
            )
            try {
                injectRaw(context, event)
            } finally {
                event.recycle()
            }
        } catch (t: Throwable) {
            log("Hover injection failed: ${t.message}")
            false
        }
    }

    /** Exits the hover state so no element stays highlighted after the pointer disappears. */
    fun injectHoverExit(context: Context, x: Float, y: Float): Boolean {
        return try {
            val event = obtainMouseEvent(
                action = MotionEvent.ACTION_HOVER_EXIT,
                x = x,
                y = y,
                buttonState = 0,
                downTime = SystemClock.uptimeMillis(),
                eventTime = SystemClock.uptimeMillis(),
            )
            try {
                injectRaw(context, event)
            } finally {
                event.recycle()
            }
        } catch (t: Throwable) {
            log("Hover exit injection failed: ${t.message}")
            false
        }
    }

    /**
     * Injects a mouse click using the event sequence produced by a real mouse:
     * ACTION_DOWN -> ACTION_BUTTON_PRESS -> ACTION_BUTTON_RELEASE -> ACTION_UP.
     *
     * Normal Android views react to ACTION_DOWN/UP; Chromium/WebView (Chrome, Via, ...) ignore
     * those when toolType is TOOL_TYPE_MOUSE and react to ACTION_BUTTON_PRESS/RELEASE instead.
     */
    fun injectMouseClick(
        context: Context,
        x: Float,
        y: Float,
        buttonState: Int = MotionEvent.BUTTON_PRIMARY,
    ): Boolean {
        val downTime = SystemClock.uptimeMillis()
        try {
            val down = obtainMouseEvent(
                action = MotionEvent.ACTION_DOWN,
                x = x,
                y = y,
                buttonState = buttonState,
                downTime = downTime,
                eventTime = SystemClock.uptimeMillis(),
            )
            val downOk = try {
                injectRaw(context, down)
            } finally {
                down.recycle()
            }
            if (!downOk) {
                return false
            }

            val press = obtainMouseEvent(
                action = MotionEvent.ACTION_BUTTON_PRESS,
                x = x,
                y = y,
                buttonState = buttonState,
                downTime = downTime,
                eventTime = SystemClock.uptimeMillis(),
            )
            val pressOk = try {
                setActionButton(press, buttonState)
                injectRaw(context, press)
            } finally {
                press.recycle()
            }
            if (!pressOk) {
                injectMouseCancel(context, x, y, downTime)
                return false
            }

            val release = obtainMouseEvent(
                action = MotionEvent.ACTION_BUTTON_RELEASE,
                x = x,
                y = y,
                buttonState = 0,
                downTime = downTime,
                eventTime = SystemClock.uptimeMillis(),
            )
            val releaseOk = try {
                setActionButton(release, buttonState)
                injectRaw(context, release)
            } finally {
                release.recycle()
            }
            if (!releaseOk) {
                injectMouseCancel(context, x, y, downTime)
                return false
            }

            val up = obtainMouseEvent(
                action = MotionEvent.ACTION_UP,
                x = x,
                y = y,
                buttonState = 0,
                downTime = downTime,
                eventTime = SystemClock.uptimeMillis(),
            )
            val upOk = try {
                injectRaw(context, up)
            } finally {
                up.recycle()
            }
            if (!upOk) {
                injectMouseCancel(context, x, y, downTime)
            }
            return upOk
        } catch (t: Throwable) {
            log("Mouse click injection failed: ${t.message}")
            return false
        }
    }

    private fun injectMouseCancel(context: Context, x: Float, y: Float, downTime: Long) {
        try {
            val event = obtainMouseEvent(
                action = MotionEvent.ACTION_CANCEL,
                x = x,
                y = y,
                buttonState = 0,
                downTime = downTime,
                eventTime = SystemClock.uptimeMillis(),
            )
            try {
                injectRaw(context, event)
            } finally {
                event.recycle()
            }
        } catch (t: Throwable) {
            log("Mouse cancel injection failed: ${t.message}")
        }
    }

    private fun injectTouchCancel(context: Context, x: Float, y: Float, downTime: Long) {
        try {
            val event = obtainTouchEvent(
                MotionEvent.ACTION_CANCEL,
                x,
                y,
                downTime,
                SystemClock.uptimeMillis(),
            )
            try {
                injectRaw(context, event)
            } finally {
                event.recycle()
            }
        } catch (t: Throwable) {
            log("Touch cancel injection failed: ${t.message}")
        }
    }

    private fun obtainTouchEvent(
        action: Int,
        x: Float,
        y: Float,
        downTime: Long,
        eventTime: Long,
    ): MotionEvent {
        val properties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = if (action == MotionEvent.ACTION_DOWN) 1f else 0f
            size = 1f
        })
        return MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            1,
            properties,
            coords,
            0, // metaState
            0, // buttonState
            0f, // xPrecision
            0f, // yPrecision
            0, // deviceId (injected -> VIRTUAL_KEYBOARD_ID)
            0, // edgeFlags
            InputDevice.SOURCE_TOUCHSCREEN,
            0, // flags
        )
    }

    private fun obtainMouseEvent(
        action: Int,
        x: Float,
        y: Float,
        buttonState: Int,
        downTime: Long,
        eventTime: Long,
    ): MotionEvent {
        val properties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = if (action == MotionEvent.ACTION_DOWN) 1f else 0f
            size = 1f
        })
        return MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            1,
            properties,
            coords,
            0, // metaState
            buttonState,
            0f, // xPrecision
            0f, // yPrecision
            0, // deviceId (injected -> VIRTUAL_KEYBOARD_ID)
            0, // edgeFlags
            InputDevice.SOURCE_MOUSE,
            0, // flags
        )
    }

    /** Sets the hidden MotionEvent actionButton field (needed by Chromium's mouse handling). */
    private fun setActionButton(event: MotionEvent, button: Int) {
        try {
            if (setActionButtonMethod == null) {
                setActionButtonMethod = MotionEvent::class.java.getMethod(
                    "setActionButton",
                    Int::class.javaPrimitiveType,
                )
            }
            setActionButtonMethod?.invoke(event, button)
        } catch (t: Throwable) {
            log("setActionButton failed: ${t.message}")
        }
    }

    private fun injectRaw(context: Context, event: MotionEvent): Boolean {
        return try {
            val inputManager =
                context.getSystemService(Context.INPUT_SERVICE) as? android.hardware.input.InputManager
                    ?: return false
            if (injectMethod == null) {
                injectMethod = inputManager.javaClass.getMethod(
                    "injectInputEvent",
                    android.view.InputEvent::class.java,
                    Int::class.javaPrimitiveType,
                )
            }
            (injectMethod?.invoke(inputManager, event, 0) as? Boolean) ?: false
        } catch (e: Exception) {
            log("Injection failed: ${e.message}")
            false
        }
    }
}
