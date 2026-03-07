package com.duplicateremover.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class SegmentedCircularProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 30f
        color = Color.parseColor("#33FFFFFF")
        strokeCap = Paint.Cap.ROUND
    }

    private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 30f
        strokeCap = Paint.Cap.ROUND
    }

    private val rectF = RectF()
    private val segments = mutableListOf<Segment>()

    data class Segment(val percentage: Float, val color: Int)

    fun setSegments(newSegments: List<Segment>) {
        segments.clear()
        segments.addAll(newSegments)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val padding = 40f
        rectF.set(padding, padding, width - padding, height - padding)

        // Draw background circle
        canvas.drawArc(rectF, 0f, 360f, false, backgroundPaint)

        var startAngle = -90f
        val gap = 4f // Gap between segments

        segments.forEach { segment ->
            segmentPaint.color = segment.color
            val sweepAngle = (segment.percentage * 3.6f) - gap
            if (sweepAngle > 0) {
                canvas.drawArc(rectF, startAngle + (gap / 2), sweepAngle, false, segmentPaint)
            }
            startAngle += segment.percentage * 3.6f
        }
    }
}
