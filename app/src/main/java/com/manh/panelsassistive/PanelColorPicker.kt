package com.manh.panelsassistive

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale

/**
 * Shared HSV picker. Uses the same glass sheet as the other choosers, with a
 * rainbow hue bar whose thumb takes the current colour, a live preview and a
 * hex field so a colour can also be typed by hand.
 */
object PanelColorPicker {
    fun show(activity: Activity, initial: Int, onColor: (Int) -> Unit) =
        show(activity, initial, "Màu nền tùy chỉnh", onColor)

    fun show(activity: Activity, initial: Int, title: String, onColor: (Int) -> Unit) {
        val sheet = GlassDialogs.sheet(
            activity,
            title,
            "Kéo trên bảng, kéo thanh sắc độ hoặc nhập mã màu bằng tay."
        )
        PickerUi(activity, initial, onColor).build(sheet.content)
        sheet.show()
    }

    private class PickerUi(
        private val activity: Activity,
        initial: Int,
        private val onColor: (Int) -> Unit
    ) {
        private var alpha = Color.alpha(initial)
        private val hsv = FloatArray(3).also { Color.colorToHSV(initial, it) }
        private var syncing = false

        private val plane = ColorPlane(activity, hsv[0]) { h, s, v ->
            hsv[0] = h
            hsv[1] = s
            hsv[2] = v
            push(syncPlane = false, syncHex = true)
        }

        private val preview = FrameLayout(activity)

        private val hexInput = EditText(activity).apply {
            background = null
            hint = "#RRGGBB"
            setHintTextColor(GlassUi.TEXT_MUTED)
            setTextColor(GlassUi.TEXT_PRIMARY)
            textSize = 15f
            letterSpacing = 0.06f
            setSingleLine()
            setPadding(dp(12), 0, dp(12), 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }

        private val hueBar = SeekBar(activity).apply {
            max = 359
            progress = hsv[0].toInt().coerceIn(0, 359)
            splitTrack = false
            minimumHeight = dp(40)
            progressDrawable = RainbowDrawable(dp(8))
        }

        init {
            plane.setHsv(hsv[0], hsv[1], hsv[2])
            hueBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    hsv[0] = progress.toFloat()
                    plane.setHue(hsv[0])
                    push(syncPlane = false, syncHex = true)
                }

                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
            hexInput.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (syncing) return
                    val parsed = parseHex(s?.toString().orEmpty()) ?: return
                    alpha = Color.alpha(parsed)
                    Color.colorToHSV(parsed, hsv)
                    push(syncPlane = true, syncHex = false)
                }
            })
            push(syncPlane = false, syncHex = true, emit = false)
        }

        fun build(content: LinearLayout) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(preview, LinearLayout.LayoutParams(dp(46), dp(46)))
            row.addView(LinearLayout(activity).apply {
                background = GlassUi.fieldBackground(activity, 14)
                addView(hexInput, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(46)
                ))
            }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(12) })
            content.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))

            val frame = FrameLayout(activity)
            frame.addView(plane, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            frame.clipToOutline = true
            frame.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(16).toFloat())
                }
            }
            content.addView(frame, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(200)
            ).apply { topMargin = dp(14) })

            content.addView(caption("Sắc độ"), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) })
            content.addView(hueBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(40)
            ))
            content.addView(caption(
                "Mã màu nhận #RRGGBB hoặc #AARRGGBB. Thay đổi được áp dụng ngay."
            ), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) })
        }

        private fun caption(text: String): TextView = TextView(activity).apply {
            this.text = text
            textSize = 11f
            setTextColor(GlassUi.TEXT_MUTED)
            includeFontPadding = false
        }

        private fun push(syncPlane: Boolean, syncHex: Boolean, emit: Boolean = true) {
            val color = (Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], hsv[2])) and 0x00FFFFFF) or
                (alpha shl 24)
            preview.background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(color)
                setStroke(dp(1), Color.argb(96, 255, 255, 255))
            }
            hueBar.thumbTintList = ColorStateList.valueOf(
                Color.HSVToColor(floatArrayOf(hsv[0], 0.9f, 1f))
            )
            if (syncPlane) {
                plane.setHsv(hsv[0], hsv[1], hsv[2])
                hueBar.progress = hsv[0].toInt().coerceIn(0, 359)
            }
            if (syncHex) {
                syncing = true
                hexInput.setText(hexText(color))
                hexInput.setSelection(hexInput.text.length)
                syncing = false
            }
            if (emit) onColor(color)
        }

        private fun hexText(color: Int): String =
            if (Color.alpha(color) == 255) {
                String.format(Locale.US, "#%06X", color and 0x00FFFFFF)
            } else {
                String.format(Locale.US, "#%08X", color)
            }

        private fun parseHex(text: String): Int? {
            val cleaned = text.trim().removePrefix("#")
            if (cleaned.length != 6 && cleaned.length != 8) return null
            if (!cleaned.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
            val value = cleaned.toLongOrNull(16) ?: return null
            return if (cleaned.length == 6) (0xFF000000L or value).toInt() else value.toInt()
        }

        private fun dp(value: Int): Int = GlassUi.dp(activity, value)
    }

    /** Hue bar track: a static rainbow, independent of the current value. */
    private class RainbowDrawable(private val heightPx: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val colors = intArrayOf(
            0xFFFF0000.toInt(),
            0xFFFFFF00.toInt(),
            0xFF00FF00.toInt(),
            0xFF00FFFF.toInt(),
            0xFF0000FF.toInt(),
            0xFFFF00FF.toInt(),
            0xFFFF0000.toInt()
        )

        override fun draw(canvas: Canvas) {
            if (bounds.width() <= 0 || bounds.height() <= 0) return
            rect.set(bounds)
            paint.shader = LinearGradient(
                rect.left, 0f, rect.right, 0f,
                colors, null, Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicHeight(): Int = heightPx
    }

    private class ColorPlane(
        context: Context,
        initialHue: Float,
        private val onChanged: (Float, Float, Float) -> Unit
    ) : View(context) {
        private var hue = initialHue
        private var saturation = 1f
        private var value = 1f
        private val paint = Paint()
        private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        fun setHue(newHue: Float) {
            hue = newHue
            invalidate()
        }

        fun setHsv(h: Float, s: Float, v: Float) {
            hue = h
            saturation = s
            value = v
            invalidate()
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
                onChanged(hue, saturation, value)
                return true
            }
            return super.onTouchEvent(event)
        }

        private fun dp(value: Int): Int =
            (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)
    }
}
