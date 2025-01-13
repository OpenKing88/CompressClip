package com.openking.compressorcilp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class CircleProgressBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 画笔
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // 进度条属性
    private var borderWidth = 20f // 边框宽度
    private var defaultColor = Color.LTGRAY // 默认颜色
    private var progressColor = Color.BLUE // 进度颜色
    private var progress = 0f // 当前进度（0-100）
    private var progressText = "0%" // 进度文本
    private var textColor = Color.BLACK // 文本颜色
    private var roundedProgress = true // 是否启用圆角

    // 圆形进度条的矩形区域
    private val rectF = RectF()

    init {
        // 初始化画笔
        initPaints()

        // 解析自定义属性
        if (attrs != null) {
            val typedArray = context.obtainStyledAttributes(attrs, R.styleable.CircleProgressBar)
            borderWidth = typedArray.getDimension(R.styleable.CircleProgressBar_borderWidth, 20f)
            defaultColor = typedArray.getColor(R.styleable.CircleProgressBar_defaultColor, Color.LTGRAY)
            progressColor = typedArray.getColor(R.styleable.CircleProgressBar_progressColor, Color.BLUE)
            textColor = typedArray.getColor(R.styleable.CircleProgressBar_textColor, Color.BLACK)
            roundedProgress = typedArray.getBoolean(R.styleable.CircleProgressBar_roundedProgress, true)
            typedArray.recycle()
        }

        // 更新画笔属性
        updatePaints()
    }

    private fun initPaints() {
        backgroundPaint.style = Paint.Style.STROKE
        backgroundPaint.strokeWidth = borderWidth
        backgroundPaint.color = defaultColor

        progressPaint.style = Paint.Style.STROKE
        progressPaint.strokeWidth = borderWidth
        progressPaint.color = progressColor
        progressPaint.strokeCap = if (roundedProgress) Paint.Cap.ROUND else Paint.Cap.BUTT

        textPaint.color = textColor
        textPaint.textAlign = Paint.Align.CENTER
    }

    private fun updatePaints() {
        backgroundPaint.strokeWidth = borderWidth
        backgroundPaint.color = defaultColor

        progressPaint.strokeWidth = borderWidth
        progressPaint.color = progressColor
        progressPaint.strokeCap = if (roundedProgress) Paint.Cap.ROUND else Paint.Cap.BUTT

        textPaint.color = textColor
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 计算圆形进度条的矩形区域
        val diameter = min(width, height) - borderWidth
        rectF.set(
            borderWidth / 2,
            borderWidth / 2,
            diameter + borderWidth / 2,
            diameter + borderWidth / 2
        )

        // 绘制背景圆环
        canvas.drawArc(rectF, 0f, 360f, false, backgroundPaint)

        // 绘制进度圆环
        val sweepAngle = 360 * (progress / 100f)
        canvas.drawArc(rectF, -90f, sweepAngle, false, progressPaint)

        // 绘制进度文本
        val textSize = diameter * 0.3f // 文本大小为直径的 30%
        textPaint.textSize = textSize

        // 计算文本位置，考虑 textPadding
        val textY = rectF.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(progressText, rectF.centerX(), textY, textPaint)
    }

    // 设置进度
    fun setProgress(progress: Float) {
        this.progress = progress.coerceIn(0f, 100f)
        this.progressText = "${progress.toInt()}%"
        invalidate() // 触发重绘
    }

    // 设置边框宽度
    fun setBorderWidth(width: Float) {
        this.borderWidth = width
        updatePaints()
        invalidate()
    }

    // 设置默认颜色
    fun setDefaultColor(color: Int) {
        this.defaultColor = color
        updatePaints()
        invalidate()
    }

    // 设置进度颜色
    fun setProgressColor(color: Int) {
        this.progressColor = color
        updatePaints()
        invalidate()
    }

    // 设置文本颜色
    fun setTextColor(color: Int) {
        this.textColor = color
        updatePaints()
        invalidate()
    }

    // 设置进度条圆角
    fun setProgressRounded(isRounded: Boolean) {
        this.roundedProgress = isRounded
        updatePaints()
        invalidate()
    }

}