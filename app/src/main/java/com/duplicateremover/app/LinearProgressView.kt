package com.duplicateremover.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class LinearProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    
    private var progress = 0
    private var max = 100
    private var strokeWidth = 8f
    
    init {
        strokeWidth = resources.displayMetrics.density * 8f
        backgroundPaint.strokeWidth = strokeWidth
        progressPaint.strokeWidth = strokeWidth
        backgroundPaint.color = Color.parseColor("#30FFFFFF")
        progressPaint.color = Color.WHITE
    }
    
    fun setProgress(value: Int) {
        progress = value.coerceIn(0, max)
        invalidate()
    }
    
    fun setMax(value: Int) {
        max = value
        invalidate()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val width = width.toFloat()
        val height = height.toFloat()
        val centerY = height / 2f
        val paddingStart = strokeWidth / 2f
        val paddingEnd = width - strokeWidth / 2f
        val availableWidth = paddingEnd - paddingStart
        
        val progressWidth = (progress.toFloat() / max) * availableWidth
        
        // Small gap size in pixels
        val gap = resources.displayMetrics.density * 6f
        
        // Draw progress (filled part)
        if (progress > 0) {
            val startX = paddingStart
            val endX = paddingStart + progressWidth - (if (progress < max) gap else 0f)
            if (endX > startX) {
                canvas.drawLine(startX, centerY, endX, centerY, progressPaint)
            }
        }
        
        // Draw background (unfilled part)
        if (progress < max) {
            val startX = paddingStart + progressWidth + (if (progress > 0) gap else 0f)
            val endX = paddingEnd
            if (endX > startX) {
                canvas.drawLine(startX, centerY, endX, centerY, backgroundPaint)
            }
        }
    }
    
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = (strokeWidth + paddingTop + paddingBottom).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }
}
