package com.faststrap.dev

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Bar graph of recent frame times. Guide lines mark 60 fps (16.7 ms) and 30 fps (33.3 ms). */
class FrameGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var data: List<Float> = emptyList()

    var accent: Int = Color.parseColor("#D0BCFF")
        set(value) {
            field = value
            invalidate()
        }

    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3A3F45")
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9AA0A6")
        textSize = 24f
    }

    fun setData(values: List<Float>) {
        data = values
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val top = 8f
        val usable = h - top
        val maxMs = maxOf(50f, data.maxOrNull() ?: 0f)

        fun y(ms: Float): Float = h - (ms / maxMs) * usable

        for (ms in floatArrayOf(16.7f, 33.3f)) {
            val yy = y(ms)
            canvas.drawLine(0f, yy, w, yy, guide)
            canvas.drawText("${ms.toInt()} ms", 6f, yy - 6f, label)
        }
        if (data.isEmpty()) return

        val slots = 90
        val bw = w / slots
        val startX = w - data.size * bw
        data.forEachIndexed { i, ms ->
            bar.color = when {
                ms <= 16.8f -> accent
                ms <= 33.4f -> Color.parseColor("#FFCB6B")
                else -> Color.parseColor("#FF8A80")
            }
            val left = startX + i * bw
            canvas.drawRect(left + 1f, y(ms.coerceAtMost(maxMs)), left + bw - 1f, h, bar)
        }
    }
}
