package com.newsreader.wear

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A circular slider for volume control on Wear OS.
 * Draws an arc track + filled arc progress, with a draggable thumb.
 * Touch anywhere on the ring or drag to change the value.
 */
class CircularVolumeSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Colors
    private val trackColor = Color.parseColor("#2A2A2A")
    private val progressColor = Color.parseColor("#E07A3A")
    private val thumbColor = Color.WHITE

    // Paints
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = trackColor
        style = Paint.Style.STROKE
        strokeWidth = 16f
        strokeCap = Paint.Cap.ROUND
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = progressColor
        style = Paint.Style.STROKE
        strokeWidth = 16f
        strokeCap = Paint.Cap.ROUND
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = thumbColor
        style = Paint.Style.FILL
        setShadowLayer(4f, 0f, 2f, Color.parseColor("#40000000"))
    }

    private val arcRect = RectF()
    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f

    // Value
    var max: Int = 15
        set(value) { field = value; invalidate() }

    var progress: Int = 7
        set(value) {
            val clamped = value.coerceIn(0, max)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    /** Called when user drags to a new volume level */
    var onVolumeChanged: ((Int) -> Unit)? = null

    // Start angle: bottom (6 o'clock) with a gap
    // Arc sweeps from 150° (roughly 7 o'clock) to 30° (roughly 5 o'clock) = 240° sweep
    private val startAngle = 150f
    private val sweepAngle = 240f
    private val thumbRadius = 12f

    private var isDragging = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        val strokeHalf = trackPaint.strokeWidth / 2f + thumbRadius
        radius = min(w, h) / 2f - strokeHalf - 4f
        arcRect.set(
            centerX - radius, centerY - radius,
            centerX + radius, centerY + radius
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw track (full arc)
        canvas.drawArc(arcRect, startAngle, sweepAngle, false, trackPaint)

        // Draw progress arc
        val progressSweep = if (max > 0) (progress.toFloat() / max) * sweepAngle else 0f
        if (progressSweep > 0f) {
            canvas.drawArc(arcRect, startAngle, progressSweep, false, progressPaint)
        }

        // Draw thumb at progress position
        val thumbAngle = Math.toRadians((startAngle + progressSweep).toDouble())
        val thumbX = centerX + radius * cos(thumbAngle).toFloat()
        val thumbY = centerY + radius * sin(thumbAngle).toFloat()
        canvas.drawCircle(thumbX, thumbY, thumbRadius, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                updateFromTouch(event.x, event.y)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    updateFromTouch(event.x, event.y)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateFromTouch(x: Float, y: Float) {
        // Calculate angle from center (0° = 3 o'clock, clockwise positive)
        val dx = x - centerX
        val dy = y - centerY
        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (angle < 0) angle += 360f

        // Convert to progress within our arc range
        var relativeAngle = angle - startAngle
        if (relativeAngle < 0) relativeAngle += 360f

        // Clamp to arc range
        if (relativeAngle > sweepAngle + 30f) {
            // In the gap area near the bottom — snap to 0 or max
            relativeAngle = if (relativeAngle > sweepAngle + 60f) 0f else sweepAngle
        }
        relativeAngle = relativeAngle.coerceIn(0f, sweepAngle)

        val newProgress = ((relativeAngle / sweepAngle) * max).toInt().coerceIn(0, max)
        if (newProgress != progress) {
            progress = newProgress
            onVolumeChanged?.invoke(newProgress)
        }
    }
}
