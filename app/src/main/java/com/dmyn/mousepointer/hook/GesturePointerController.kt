package com.dmyn.mousepointer.hook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.dmyn.mousepointer.MousePointerConfig

/**
 * Draws a desktop-style mouse cursor (arrow) and injects the corresponding hover / click events.
 *
 * The arrow tip is the hotspot: injected events use the tip coordinates. The finger moves the
 * pointer with a configurable gain, so small finger movements can move the cursor farther.
 */
internal class GesturePointerController(
    private val injector: EventInjector,
    private val log: (String) -> Unit,
) {
    data class Cfg(
        val gain: Float = 4f,
        val sizePx: Float = 22f,
        val colorIndex: Int = MousePointerConfig.COLOR_WHITE,
        val hoverEnabled: Boolean = true,
        val clickMode: Int = MousePointerConfig.CLICK_MOUSE,
        val vibrateOnClick: Boolean = false,
    )

    private var cfg = Cfg()
    private var view: PointerView? = null
    private var windowManager: WindowManager? = null
    private var activeContext: Context? = null
    private var fingerStartX = 0f
    private var fingerStartY = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var hoverFailureLogged = false

    fun applyConfig(density: Float, config: MousePointerConfig) {
        cfg = Cfg(
            gain = config.pointerGainTenths / 10f,
            sizePx = config.pointerSizeDp * density,
            colorIndex = config.pointerColor,
            hoverEnabled = config.hoverEnabled,
            clickMode = config.clickMode,
            vibrateOnClick = config.vibrateOnClick,
        )
        view?.applyStyle(cfg, density)
    }

    fun start(context: Context, x: Float, y: Float) {
        finish()
        activeContext = context
        fingerStartX = x
        fingerStartY = y
        targetX = x
        targetY = y
        hoverFailureLogged = false
        if (!addOverlay(context)) {
            activeContext = null
            return
        }
        updateViewPosition()
        injectHover(context, targetX, targetY)
    }

    fun moveTo(fingerX: Float, fingerY: Float) {
        targetX = fingerStartX + (fingerX - fingerStartX) * cfg.gain
        targetY = fingerStartY + (fingerY - fingerStartY) * cfg.gain
        updateViewPosition()
        injectHover(activeContext, targetX, targetY)
    }

    fun tapAndFinish(context: Context, fingerX: Float, fingerY: Float) {
        val hadPointer = view != null || activeContext != null
        if (!hadPointer) {
            return
        }
        targetX = fingerStartX + (fingerX - fingerStartX) * cfg.gain
        targetY = fingerStartY + (fingerY - fingerStartY) * cfg.gain
        updateViewPosition()
        removeOverlay()
        activeContext = null
        // Refresh hover before clicking so the element under the cursor is already hovered.
        injectHover(context, targetX, targetY)
        val clicked = if (cfg.clickMode == MousePointerConfig.CLICK_TOUCH) {
            injector.injectTouchTap(context, targetX, targetY)
        } else {
            injector.injectMouseClick(context, targetX, targetY)
        }
        log(
            "Pointer click injected=$clicked at " +
                "(${targetX.toInt()}, ${targetY.toInt()}) mode=${cfg.clickMode}",
        )
        if (!clicked) {
            log("Pointer click injection failed (mode=${cfg.clickMode})")
            val fallback = if (cfg.clickMode == MousePointerConfig.CLICK_TOUCH) {
                injector.injectMouseClick(context, targetX, targetY)
            } else {
                injector.injectTouchTap(context, targetX, targetY)
            }
            if (!fallback) {
                injector.injectHoverExit(context, targetX, targetY)
            }
        }
        if (cfg.vibrateOnClick) {
            vibrate(context)
        }
    }

    fun finish() {
        val hadPointer = view != null || activeContext != null
        val context = activeContext
        val x = targetX
        val y = targetY
        removeOverlay()
        activeContext = null
        hoverFailureLogged = false
        if (hadPointer && context != null) {
            injector.injectHoverExit(context, x, y)
        }
    }

    private fun addOverlay(context: Context): Boolean {
        val manager = context.getSystemService(WindowManager::class.java) ?: return false
        val pointerView = PointerView(context, cfg, context.resources.displayMetrics.density)
        return try {
            manager.addView(
                pointerView,
                WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    2027, // TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                ).apply { gravity = Gravity.TOP or Gravity.START },
            )
            view = pointerView
            windowManager = manager
            true
        } catch (t: Throwable) {
            log("Pointer overlay unavailable: ${t.message}")
            false
        }
    }

    private fun removeOverlay() {
        val current = view ?: return
        try {
            windowManager?.removeViewImmediate(current)
        } catch (_: Throwable) {
        } finally {
            view = null
            windowManager = null
        }
    }

    private fun updateViewPosition() {
        view?.setHotspot(targetX, targetY)
    }

    private fun injectHover(context: Context?, x: Float, y: Float) {
        val ctx = context ?: return
        if (!cfg.hoverEnabled) {
            return
        }
        if (injector.injectHoverMove(ctx, x, y)) {
            return
        }
        if (!hoverFailureLogged) {
            hoverFailureLogged = true
            log("Hover injection failed; hover feedback may be unavailable")
            injector.injectHoverExit(ctx, x, y)
        }
    }

    private fun vibrate(context: Context) {
        try {
            val vibrator = context.getSystemService(Vibrator::class.java) ?: return
            if (!vibrator.hasVibrator()) {
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(
                        18,
                        VibrationEffect.DEFAULT_AMPLITUDE,
                    ),
                )
            }
        } catch (t: Throwable) {
            log("Vibrate failed: ${t.message}")
        }
    }

    /** Arrow-shaped desktop mouse cursor, hotspot at the arrow tip. */
    private class PointerView(
        context: Context,
        cfg: Cfg,
        private val density: Float,
    ) : View(context) {
        private var currentCfg = cfg
        private var hotspotX = 0f
        private var hotspotY = 0f
        private val screenLocation = IntArray(2)

        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x40000000
            style = Paint.Style.FILL
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        private val path = Path()

        init {
            applyStyle(cfg, density)
        }

        fun setHotspot(x: Float, y: Float) {
            hotspotX = x
            hotspotY = y
            invalidate()
        }

        fun applyStyle(cfg: Cfg, density: Float) {
            currentCfg = cfg
            val style = when (cfg.colorIndex) {
                MousePointerConfig.COLOR_BLACK -> 0xFF1A1A1A.toInt() to 0xFFF5F5F5.toInt()
                MousePointerConfig.COLOR_GREEN -> 0xFF43A047.toInt() to 0xFF121212.toInt()
                MousePointerConfig.COLOR_BLUE -> 0xFF1E88E5.toInt() to 0xFFF5F5F5.toInt()
                MousePointerConfig.COLOR_ORANGE -> 0xFFFB8C00.toInt() to 0xFF121212.toInt()
                else -> 0xFFFFFFFF.toInt() to 0xFF1A1A1A.toInt()
            }
            fillPaint.color = style.first
            strokePaint.color = style.second
            strokePaint.strokeWidth = (cfg.sizePx / 12f).coerceAtLeast(1.2f * density)
            buildPath(cfg.sizePx)
            invalidate()
        }

        private fun buildPath(sizePx: Float) {
            val unit = sizePx / 16f
            path.reset()
            path.moveTo(0f, 0f)                    // tip (hotspot)
            path.lineTo(0f, 14.2f * unit)
            path.lineTo(3.4f * unit, 11.1f * unit)
            path.lineTo(5.8f * unit, 16.2f * unit)
            path.lineTo(8.6f * unit, 15.0f * unit)
            path.lineTo(6.2f * unit, 9.9f * unit)
            path.lineTo(10.6f * unit, 9.9f * unit)
            path.close()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            getLocationOnScreen(screenLocation)
            val x = hotspotX - screenLocation[0]
            val y = hotspotY - screenLocation[1]
            canvas.save()
            canvas.translate(x, y)
            canvas.translate(1.5f * density, 1.5f * density)
            canvas.drawPath(path, shadowPaint)
            canvas.restore()

            canvas.save()
            canvas.translate(x, y)
            canvas.drawPath(path, fillPaint)
            canvas.drawPath(path, strokePaint)
            canvas.restore()
        }
    }
}
