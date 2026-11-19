package com.manh.panelsassistive

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

class ActionGlyphView(
    context: Context,
    private val action: String,
    color: Int = Color.WHITE
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return
        canvas.save()
        canvas.translate((width - size) / 2f, (height - size) / 2f)
        canvas.scale(size / 24f, size / 24f)
        when (action) {
            ActionDispatcher.FLASHLIGHT -> {
                val flashlight = Path().apply {
                    moveTo(8f, 4f)
                    lineTo(16f, 4f)
                    lineTo(15f, 9f)
                    lineTo(14f, 10f)
                    lineTo(14f, 18f)
                    lineTo(10f, 18f)
                    lineTo(10f, 10f)
                    lineTo(9f, 9f)
                    close()
                }
                canvas.drawPath(flashlight, paint)
                canvas.drawLine(12f, 18f, 12f, 21f, paint)
                canvas.drawLine(7f, 7f, 4f, 6f, paint)
                canvas.drawLine(17f, 7f, 20f, 6f, paint)
                canvas.drawLine(7f, 10f, 4f, 11f, paint)
                canvas.drawLine(17f, 10f, 20f, 11f, paint)
            }
            ActionDispatcher.BACKGROUND_RECORDING -> {
                canvas.drawRoundRect(9f, 3f, 15f, 15f, 3f, 3f, paint)
                canvas.drawArc(6f, 6f, 18f, 18f, 0f, 180f, false, paint)
                canvas.drawLine(12f, 18f, 12f, 21f, paint)
                canvas.drawLine(9f, 21f, 15f, 21f, paint)
                canvas.drawLine(4f, 9f, 4f, 15f, paint)
                canvas.drawLine(20f, 9f, 20f, 15f, paint)
            }
            ActionDispatcher.WIDGET_POPUP -> {
                canvas.drawRoundRect(4f, 4f, 20f, 20f, 3f, 3f, paint)
                canvas.drawLine(4f, 10f, 20f, 10f, paint)
                canvas.drawLine(10f, 10f, 10f, 20f, paint)
            }
            ActionDispatcher.EXPANSION_PANEL -> {
                canvas.drawLine(6f, 6f, 6f, 18f, paint)
                canvas.drawLine(12f, 9f, 12f, 15f, paint)
                canvas.drawLine(18f, 4f, 18f, 20f, paint)
                canvas.drawCircle(6f, 9f, 2f, paint)
                canvas.drawCircle(12f, 15f, 2f, paint)
                canvas.drawCircle(18f, 8f, 2f, paint)
            }
            ActionDispatcher.BRIGHTNESS_SLIDER -> {
                canvas.drawCircle(12f, 12f, 4f, paint)
                for (index in 0 until 8) {
                    val angle = Math.PI * index / 4
                    canvas.drawLine(
                        12f + (kotlin.math.cos(angle) * 7).toFloat(),
                        12f + (kotlin.math.sin(angle) * 7).toFloat(),
                        12f + (kotlin.math.cos(angle) * 10).toFloat(),
                        12f + (kotlin.math.sin(angle) * 10).toFloat(),
                        paint
                    )
                }
            }
            ActionDispatcher.MEDIA_VOLUME -> {
                val speaker = Path().apply {
                    moveTo(4f, 9f)
                    lineTo(8f, 9f)
                    lineTo(13f, 5f)
                    lineTo(13f, 19f)
                    lineTo(8f, 15f)
                    lineTo(4f, 15f)
                    close()
                }
                canvas.drawPath(speaker, paint)
                canvas.drawArc(11f, 7f, 20f, 17f, -55f, 110f, false, paint)
            }
            ActionDispatcher.EXPANSION_PANEL -> {
                canvas.drawLine(6f, 6f, 6f, 18f, paint)
                canvas.drawLine(12f, 9f, 12f, 15f, paint)
                canvas.drawLine(18f, 4f, 18f, 20f, paint)
                canvas.drawCircle(6f, 9f, 2f, paint)
                canvas.drawCircle(12f, 15f, 2f, paint)
                canvas.drawCircle(18f, 8f, 2f, paint)
            }
            ActionDispatcher.AUTO_ROTATION -> {
                canvas.save()
                canvas.rotate(-35f, 12f, 12f)
                canvas.drawRoundRect(8f, 3f, 16f, 21f, 2f, 2f, paint)
                canvas.drawLine(10f, 6f, 14f, 6f, paint)
                canvas.drawCircle(12f, 18f, 0.8f, paint)
                canvas.restore()
                canvas.drawArc(3f, 4f, 21f, 22f, 205f, 75f, false, paint)
                canvas.drawLine(3f, 8f, 3f, 4f, paint)
                canvas.drawLine(3f, 4f, 7f, 5f, paint)
                canvas.drawArc(3f, 2f, 21f, 20f, 25f, 75f, false, paint)
                canvas.drawLine(21f, 16f, 21f, 20f, paint)
                canvas.drawLine(21f, 20f, 17f, 19f, paint)
            }
            ActionDispatcher.NOTIFICATION_PANEL -> {
                canvas.drawArc(6f, 4f, 18f, 19f, 180f, 180f, false, paint)
                canvas.drawLine(6f, 12f, 6f, 17f, paint)
                canvas.drawLine(18f, 12f, 18f, 17f, paint)
                canvas.drawLine(4f, 17f, 20f, 17f, paint)
                canvas.drawCircle(12f, 20f, 1f, paint)
            }
            ActionDispatcher.NAV_BACK -> {
                canvas.drawLine(19f, 12f, 5f, 12f, paint)
                canvas.drawLine(5f, 12f, 11f, 6f, paint)
                canvas.drawLine(5f, 12f, 11f, 18f, paint)
            }
            ActionDispatcher.NAV_HOME -> {
                val house = Path().apply {
                    moveTo(3f, 11f)
                    lineTo(12f, 4f)
                    lineTo(21f, 11f)
                    lineTo(19f, 11f)
                    lineTo(19f, 20f)
                    lineTo(5f, 20f)
                    lineTo(5f, 11f)
                    close()
                }
                canvas.drawPath(house, paint)
                canvas.drawRect(10f, 14f, 14f, 20f, paint)
            }
            ActionDispatcher.NAV_RECENTS -> {
                canvas.drawRoundRect(7f, 4f, 20f, 17f, 2f, 2f, paint)
                canvas.drawLine(4f, 8f, 4f, 19f, paint)
                canvas.drawLine(4f, 19f, 16f, 19f, paint)
            }
            ActionDispatcher.CALL_ASSISTANT -> {
                canvas.drawRoundRect(8f, 4f, 16f, 16f, 4f, 4f, paint)
                canvas.drawArc(5f, 8f, 19f, 21f, 0f, 180f, false, paint)
                canvas.drawLine(12f, 19f, 12f, 22f, paint)
                canvas.drawLine(9f, 22f, 15f, 22f, paint)
            }
            ActionDispatcher.TOGGLE_FLOAT_BALL -> {
                canvas.drawCircle(12f, 12f, 8f, paint)
                canvas.drawCircle(12f, 12f, 3f, paint)
            }
            ActionDispatcher.ACCESSIBILITY_SETTINGS -> {
                canvas.drawCircle(12f, 5f, 2.5f, paint)
                canvas.drawLine(12f, 8f, 12f, 15f, paint)
                canvas.drawLine(6f, 10f, 18f, 10f, paint)
                canvas.drawLine(12f, 15f, 8f, 21f, paint)
                canvas.drawLine(12f, 15f, 16f, 21f, paint)
            }
            ActionDispatcher.OPEN_APP -> {
                canvas.drawRoundRect(4f, 5f, 20f, 19f, 2f, 2f, paint)
                canvas.drawLine(4f, 9f, 20f, 9f, paint)
                canvas.drawCircle(7f, 7f, 0.6f, paint)
                canvas.drawCircle(10f, 7f, 0.6f, paint)
            }
            ActionDispatcher.OPEN_SELECTED_APP -> {
                canvas.drawRoundRect(4f, 4f, 20f, 20f, 3f, 3f, paint)
                canvas.drawLine(12f, 16f, 12f, 8f, paint)
                canvas.drawLine(8f, 12f, 12f, 8f, paint)
                canvas.drawLine(16f, 12f, 12f, 8f, paint)
            }
            ActionDispatcher.OPEN_SELECTED_SHORTCUT -> {
                canvas.drawLine(5f, 19f, 19f, 5f, paint)
                canvas.drawLine(11f, 5f, 19f, 5f, paint)
                canvas.drawLine(19f, 5f, 19f, 13f, paint)
                canvas.drawRoundRect(4f, 12f, 11f, 20f, 2f, 2f, paint)
            }
            else -> {
                canvas.drawCircle(12f, 12f, 8f, paint)
                canvas.drawCircle(12f, 12f, 2f, paint)
            }
        }
        canvas.restore()
    }
}
