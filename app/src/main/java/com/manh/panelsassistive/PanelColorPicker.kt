package com.manh.panelsassistive

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar

/** Shared HSV picker used by panel background settings. */
object PanelColorPicker {
    fun show(activity: Activity, initial: Int, onColor: (Int) -> Unit) =
        show(activity, initial, "Màu nền tùy chỉnh", onColor)

    fun show(activity: Activity, initial: Int, title: String, onColor: (Int) -> Unit) {
        val hsv = FloatArray(3)
        Color.colorToHSV(initial, hsv)
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(GlassUi.dp(activity, 18), GlassUi.dp(activity, 12), GlassUi.dp(activity, 18), GlassUi.dp(activity, 12))
        }
        val plane = ColorPlane(activity, hsv[0], hsv[1], hsv[2], onColor)
        container.addView(plane, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            GlassUi.dp(activity, 220)
        ))
        val hue = GlassUi.slider(activity).apply {
            max = 359
            progress = hsv[0].toInt().coerceIn(0, 359)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) plane.setHue(progress.toFloat())
                }

                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        container.addView(hue, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            GlassUi.dp(activity, 44)
        ).apply { topMargin = GlassUi.dp(activity, 8) })
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private class ColorPlane(
        context: android.content.Context,
        private var hue: Float,
        private var saturation: Float,
        private var value: Float,
        private val onColor: (Int) -> Unit
    ) : View(context) {
        private val paint = Paint()
        private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        fun setHue(value: Float) {
            hue = value
            invalidate()
            emitColor()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val step = dp(6).coerceAtLeast(4)
            var y = 0
            while (y < height) {
                val yFraction = if (height > 0) y.toFloat() / height else 0f
                var x = 0
                while (x < width) {
                    val xFraction = if (width > 0) x.toFloat() / width else 0f
                    val valueFraction = 1f - yFraction
                    paint.color = Color.HSVToColor(floatArrayOf(hue, xFraction, valueFraction))
                    canvas.drawRect(
                        x.toFloat(),
                        y.toFloat(),
                        (x + step).toFloat().coerceAtMost(width.toFloat()),
                        (y + step).toFloat().coerceAtMost(height.toFloat()),
                        paint
                    )
                    x += step
                }
                y += step
            }
            canvas.drawCircle(saturation * width, (1f - value) * height, dp(7).toFloat(), marker)
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN ||
                event.actionMasked == android.view.MotionEvent.ACTION_MOVE
            ) {
                saturation = (event.x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
                value = (1f - event.y / height.coerceAtLeast(1)).coerceIn(0f, 1f)
                invalidate()
                emitColor()
                return true
            }
            return super.onTouchEvent(event)
        }

        private fun emitColor() {
            onColor(Color.HSVToColor(floatArrayOf(hue, saturation, value)))
        }

        private fun dp(value: Int): Int =
            (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)
    }
}
