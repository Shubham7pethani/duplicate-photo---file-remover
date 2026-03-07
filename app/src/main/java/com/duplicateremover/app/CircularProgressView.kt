package com.duplicateremover.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class CircularProgressView @JvmOverloads constructor(
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

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    
    private val rectF = RectF()
    private var strokeWidth = 8f
    private var progress = 0
    private var max = 100
    
    private var progressColor = Color.parseColor("#0066FF")
    private var backgroundColor = Color.parseColor("#E0E0E0")
    private var isFilled = false
    private var fillColor = Color.parseColor("#150066FF")
    private var useTrackStyle = false

    init {
        strokeWidth = resources.displayMetrics.density * 8f // Corrected from 10f to 8f as per instruction
        
        attrs?.let {
            val a = context.obtainStyledAttributes(it, R.styleable.CircularProgressView)
            progressColor = a.getColor(R.styleable.CircularProgressView_cpv_progressColor, progressColor)
            backgroundColor = a.getColor(R.styleable.CircularProgressView_cpv_backgroundColor, backgroundColor)
            isFilled = a.getBoolean(R.styleable.CircularProgressView_cpv_fillEnabled, isFilled)
            fillColor = a.getColor(R.styleable.CircularProgressView_cpv_fillColor, fillColor)
            strokeWidth = a.getDimension(R.styleable.CircularProgressView_cpv_strokeWidth, strokeWidth)
            useTrackStyle = a.getBoolean(R.styleable.CircularProgressView_cpv_useTrackStyle, false)
            progress = a.getInt(R.styleable.CircularProgressView_cpv_progress, 0)
            max = a.getInt(R.styleable.CircularProgressView_cpv_max, 100)
            a.recycle()
        }

        backgroundPaint.strokeWidth = strokeWidth
        progressPaint.strokeWidth = strokeWidth
        updateColors()
    }

    private fun updateColors() {
        backgroundPaint.color = backgroundColor
        progressPaint.color = progressColor
        fillPaint.color = fillColor
    }

    fun setColors(progress: Int, background: Int) {
        progressColor = progress
        backgroundColor = background
        updateColors()
        invalidate()
    }

    fun setProgressColor(color: Int) {
        progressColor = color
        progressPaint.color = progressColor
        invalidate()
    }

    fun setProgressBackgroundColor(color: Int) {
        backgroundColor = color
        backgroundPaint.color = backgroundColor
        invalidate()
    }

    fun setFillEnabled(enabled: Boolean, color: Int = -1) {
        isFilled = enabled
        if (color != -1) {
            fillColor = color
            fillPaint.color = fillColor
        }
        invalidate()
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
        val diameter = min(width, height) - strokeWidth
        val radius = diameter / 2f
        val centerX = width / 2f
        val centerY = height / 2f
        
        rectF.set(
            centerX - radius,
            centerY - radius,
            centerX + radius,
            centerY + radius
        )

        if (isFilled) {
            canvas.drawCircle(centerX, centerY, radius, fillPaint)
        }
        
        if (useTrackStyle) {
            // "Elite" Home/Dashboard Style: Continuous track
            canvas.drawArc(rectF, 0f, 360f, false, backgroundPaint)
            if (progress > 0) {
                val progressSweep = (progress.toFloat() / max) * 360f
                canvas.drawArc(rectF, -90f, progressSweep, false, progressPaint)
            }
        } else {
            // "Premium" Scan Style: Gapped segments
            val totalSweep = 360f
            val progressSweep = (progress.toFloat() / max) * totalSweep
            val gapAngle = 4f
            
            // Draw background arc
            if (progress < max) {
                val backgroundStart = -90f + progressSweep + gapAngle
                val backgroundSweep = totalSweep - progressSweep - (2 * gapAngle)
                if (backgroundSweep > 0) {
                    canvas.drawArc(rectF, backgroundStart, backgroundSweep, false, backgroundPaint)
                }
            }
            
            // Draw progress arc
            if (progress > 0) {
                val progressStart = -90f + gapAngle
                val actualProgressSweep = progressSweep - (2 * gapAngle)
                if (actualProgressSweep > 0) {
                    canvas.drawArc(rectF, progressStart, actualProgressSweep, false, progressPaint)
                }
            }
        }
    }
    
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = min(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec)
        )
        setMeasuredDimension(size, size)
    }
}
