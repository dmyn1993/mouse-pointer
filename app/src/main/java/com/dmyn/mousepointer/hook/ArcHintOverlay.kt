package com.dmyn.mousepointer.hook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hint overlay: a thin arc anchored at the touched edge point. The user crosses the arc with the
 * finger to trigger the mouse pointer. The arc radius/span/orientation and the trigger tolerance
 * band are configurable.
 */
internal class ArcHintOverlay(private val log: (String) -> Unit) {

    private var view: ArcView? = null
    private var windowManager: WindowManager? = null

    fun show(
        context: Context,
        centerX: Float,
        centerY: Float,
        radiusPx: Float,
        spanDeg: Float,
        centerAngleDeg: Float,
        upward: Boolean,
        fromLeftEdge: Boolean,
        tolerancePx: Float,
    ) {
        hide()
        val manager = context.getSystemService(WindowManager::class.java) ?: return
        val arcView = ArcView(
            context = context,
            centerX = centerX,
            centerY = centerY,
            radiusPx = radiusPx,
            spanDeg = spanDeg,
            centerAngleDeg = centerAngleDeg,
            upward = upward,
            fromLeftEdge = fromLeftEdge,
            tolerancePx = tolerancePx,
        )
        try {
            manager.addView(
                arcView,
                WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    2027, // TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY – usable from system_server
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                ).apply { gravity = Gravity.TOP or Gravity.START },
            )
            view = arcView
            windowManager = manager
        } catch (t: Throwable) {
            log("Arc hint overlay unavailable: ${t.message}")
        }
    }

    fun hide() {
        val current = view ?: return
        try {
            windowManager?.removeViewImmediate(current)
        } catch (_: Throwable) {
        } finally {
            view = null
            windowManager = null
        }
    }

    private class ArcView(
        context: Context,
        private val centerX: Float,
        private val centerY: Float,
        private val radiusPx: Float,
        private val spanDeg: Float,
        private val centerAngleDeg: Float,
        private val upward: Boolean,
        private val fromLeftEdge: Boolean,
        private val tolerancePx: Float,
    ) : View(context) {
        private val density = context.resources.displayMetrics.density
        private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x1A4CAF50
            style = Paint.Style.STROKE
            strokeWidth = tolerancePx * 2f
            strokeCap = Paint.Cap.ROUND
        }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xB34CAF50.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2.2f * density
            strokeCap = Paint.Cap.ROUND
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCC4CAF50.toInt()
            style = Paint.Style.FILL
        }
        private val screenLocation = IntArray(2)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            getLocationOnScreen(screenLocation)
            val cx = centerX - screenLocation[0]
            val cy = centerY - screenLocation[1]

            val inwardX = if (fromLeftEdge) 1f else -1f
            val verticalSign = if (upward) -1f else 1f
            val startDeg = centerAngleDeg - spanDeg / 2f
            val endDeg = centerAngleDeg + spanDeg / 2f
            val steps = 32

            val path = Path()
            for (i in 0..steps) {
                val angle = Math.toRadians((startDeg + (endDeg - startDeg) * i / steps).toDouble())
                val dirX = inwardX * cos(angle).toFloat()
                val dirY = verticalSign * sin(angle).toFloat()
                val x = cx + dirX * radiusPx
                val y = cy + dirY * radiusPx
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, bandPaint)
            canvas.drawPath(path, linePaint)

            // Small end dots make the arc endpoints readable.
            for (index in intArrayOf(0, steps)) {
                val angleDeg = startDeg + (endDeg - startDeg) * index / steps
                val angle = Math.toRadians(angleDeg.toDouble())
                val dirX = inwardX * cos(angle).toFloat()
                val dirY = verticalSign * sin(angle).toFloat()
                canvas.drawCircle(
                    cx + dirX * radiusPx,
                    cy + dirY * radiusPx,
                    2.2f * density,
                    dotPaint,
                )
            }
        }
    }
}
