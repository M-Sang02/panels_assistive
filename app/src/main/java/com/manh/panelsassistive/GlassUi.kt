package com.manh.panelsassistive

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Shared faux-glass design language for settings surfaces, modelled on the
 * XGesture capsule mockups: quiet navy page, translucent cards with a 1dp
 * light rim, pill controls and a single blue accent. Everything is static
 * drawing (no runtime blur), so it stays cheap and predictable.
 */
object GlassUi {
    var ACCENT = 0xFF7CBEFF.toInt()
        private set
    var ACCENT_STRONG = 0xFF4E96E8.toInt()
        private set
    var ACCENT_SOFT = Color.argb(58, 124, 190, 255)
        private set
    var TEXT_PRIMARY = 0xFFF4F6FA.toInt()
        private set
    var TEXT_SECONDARY = 0xFFA6ADB9.toInt()
        private set
    var TEXT_MUTED = 0xFF7E8896.toInt()
        private set
    var SLIDER_COLOR = ACCENT
        private set
    var ICON_COLOR = ACCENT
        private set
    var PAGE_COLOR = 0xFF091422.toInt()
        private set

    private var PAGE_TOP = 0xFF091422.toInt()
    private var PAGE_MID = 0xFF0D1E30.toInt()
    private var PAGE_BOTTOM = 0xFF07101D.toInt()
    private val CARD_FILL_TOP = Color.argb(32, 224, 236, 255)
    private val CARD_FILL_BOTTOM = Color.argb(13, 224, 236, 255)
    private val CARD_RIM = Color.argb(36, 255, 255, 255)
    private val FIELD_FILL = Color.argb(24, 224, 236, 255)
    private val DIVIDER_COLOR = Color.argb(26, 255, 255, 255)

    /** Re-syncs the shared palette. Called on app start and after a theme change. */
    fun applyTheme(context: Context) {
        val theme = AppThemes.current(context)
        ACCENT = theme.accent
        ACCENT_STRONG = theme.accentStrong
        ACCENT_SOFT = (theme.accent and 0x00FFFFFF) or 0x3A000000
        PAGE_COLOR = theme.pageTop
        PAGE_TOP = theme.pageTop
        PAGE_MID = theme.pageMid
        PAGE_BOTTOM = theme.pageBottom
        val text = AppPrefs.themeTextColor(context)
        if (text != 0) {
            TEXT_PRIMARY = text
            TEXT_SECONDARY = withAlpha(text, 0.68f)
            TEXT_MUTED = withAlpha(text, 0.46f)
        } else {
            TEXT_PRIMARY = 0xFFF4F6FA.toInt()
            TEXT_SECONDARY = 0xFFA6ADB9.toInt()
            TEXT_MUTED = 0xFF7E8896.toInt()
        }
        SLIDER_COLOR = AppPrefs.themeSliderColor(context).takeIf { it != 0 } ?: theme.accent
        ICON_COLOR = AppPrefs.themeIconColor(context).takeIf { it != 0 } ?: theme.accent
    }

    internal fun withAlpha(color: Int, alpha: Float): Int = Color.argb(
        (alpha.coerceIn(0f, 1f) * 255).toInt(),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun pageBackground(): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(PAGE_TOP, PAGE_MID, PAGE_BOTTOM)
        )

    fun glassSurface(context: Context, radiusDp: Int = 20): Drawable {
        val inset = dp(context, 1).coerceAtLeast(1)
        val corner = dp(context, radiusDp).toFloat()
        val rim = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = corner
            setStroke(inset, CARD_RIM)
        }
        val fill = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(CARD_FILL_TOP, CARD_FILL_BOTTOM)
        ).apply {
            cornerRadius = (corner - inset).coerceAtLeast(0f)
        }
        return LayerDrawable(arrayOf(rim, fill)).apply {
            setLayerInset(1, inset, inset, inset, inset)
        }
    }

    fun fieldBackground(context: Context, radiusDp: Int = 12): Drawable =
        GradientDrawable().apply {
            setColor(FIELD_FILL)
            cornerRadius = dp(context, radiusDp).toFloat()
            setStroke(dp(context, 1).coerceAtLeast(1), Color.argb(26, 255, 255, 255))
        }

    fun applyPanelBorder(context: Context, drawable: GradientDrawable) {
        applyBorder(
            context,
            drawable,
            AppPrefs.panelBorderEnabled(context),
            AppPrefs.panelBorderAlpha(context),
            AppPrefs.panelBorderWidth(context)
        )
    }

    /** The widget popup keeps its own rim so it can differ from the other panels. */
    fun applyWidgetPanelBorder(context: Context, drawable: GradientDrawable) {
        applyBorder(
            context,
            drawable,
            AppPrefs.widgetPanelBorderEnabled(context),
            AppPrefs.widgetPanelBorderAlpha(context),
            AppPrefs.widgetPanelBorderWidth(context)
        )
    }

    private fun applyBorder(
        context: Context,
        drawable: GradientDrawable,
        enabled: Boolean,
        alpha: Int,
        widthDp: Float
    ) {
        if (!enabled) return
        val widthPx = (widthDp * context.resources.displayMetrics.density)
            .roundToInt()
            .coerceAtLeast(1)
        drawable.setStroke(widthPx, Color.argb(alpha, 225, 238, 250))
    }

    fun tabSurface(context: Context, selected: Boolean): Drawable =
        if (selected) {
            GradientDrawable().apply {
                setColor(ACCENT_SOFT)
                cornerRadius = dp(context, 16).toFloat()
                setStroke(dp(context, 1), (ACCENT and 0x00FFFFFF) or 0x96000000.toInt())
            }
        } else {
            fieldBackground(context, 16)
        }

    fun divider(context: Context): View = View(context).apply {
        setBackgroundColor(DIVIDER_COLOR)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(context, 1).coerceAtLeast(1)
        ).apply {
            topMargin = dp(context, 10)
            bottomMargin = dp(context, 10)
        }
    }

    fun glassCard(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val inset = dp(context, 16)
        setPadding(inset, inset, inset, inset)
        background = glassSurface(context)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(context, 12) }
    }

    fun pageHeader(context: Context, title: CharSequence, subtitle: CharSequence? = null): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = context.getString(R.string.brand_name)
                textSize = 11f
                setTextColor(ACCENT)
                setTypeface(typeface, Typeface.BOLD)
                letterSpacing = 0.18f
                includeFontPadding = false
            })
            addView(TextView(context).apply {
                text = title
                textSize = 24f
                setTextColor(TEXT_PRIMARY)
                setTypeface(typeface, Typeface.BOLD)
                includeFontPadding = false
                setPadding(0, dp(context, 6), 0, if (subtitle == null) dp(context, 7) else dp(context, 4))
            })
            if (subtitle != null) {
                addView(TextView(context).apply {
                    text = subtitle
                    textSize = 13f
                    setTextColor(TEXT_SECONDARY)
                    includeFontPadding = false
                    setPadding(0, 0, 0, dp(context, 7))
                })
            }
        }

    fun backBar(context: Context, onBack: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = "Quay lại"
            setPadding(0, dp(context, 2), 0, dp(context, 10))
            addView(LucideGlyphView(context, "chevron-left", TEXT_SECONDARY), LinearLayout.LayoutParams(
                dp(context, 18), dp(context, 18)
            ))
            addView(TextView(context).apply {
                text = "Quay lại"
                textSize = 13f
                setTextColor(TEXT_SECONDARY)
                includeFontPadding = false
                setPadding(dp(context, 4), 0, 0, 0)
            })
            setOnClickListener { onBack() }
        }

    fun sectionHeader(context: Context, text: CharSequence): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 11f
            setTextColor(TEXT_MUTED)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.14f
            includeFontPadding = false
            gravity = Gravity.START
            setPadding(0, dp(context, 26), 0, dp(context, 8))
        }

    fun cardTitle(context: Context, text: CharSequence): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 16f
            setTextColor(TEXT_PRIMARY)
            setTypeface(typeface, Typeface.BOLD)
            includeFontPadding = false
            gravity = Gravity.START
        }

    fun bodyText(
        context: Context,
        text: CharSequence,
        sizeSp: Float = 14f,
        color: Int = TEXT_SECONDARY,
        bold: Boolean = false
    ): TextView = TextView(context).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        includeFontPadding = false
        gravity = Gravity.START
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    fun switch(context: Context, checked: Boolean, onChanged: (Boolean) -> Unit): Switch =
        Switch(context).apply {
            isChecked = checked
            setShowText(false)
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.WHITE, Color.rgb(201, 211, 224))
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.argb(200, 78, 150, 232), Color.argb(60, 255, 255, 255))
            )
            setOnCheckedChangeListener { _, enabled -> onChanged(enabled) }
        }

    fun slider(context: Context): SeekBar = SeekBar(context).apply {
        splitTrack = false
        progressTintList = ColorStateList.valueOf(SLIDER_COLOR)
        progressBackgroundTintList = ColorStateList.valueOf(Color.argb(40, 255, 255, 255))
        thumbTintList = ColorStateList.valueOf(SLIDER_COLOR)
    }

    /** Slider with a centre notch marking the neutral position. */
    fun notchedSlider(context: Context): Pair<FrameLayout, SeekBar> {
        val bar = slider(context)
        val container = FrameLayout(context)
        container.addView(bar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dp(context, 48)
        ))
        container.addView(View(context).apply {
            setBackgroundColor(SLIDER_COLOR)
            alpha = 0.75f
            isClickable = false
        }, FrameLayout.LayoutParams(dp(context, 2), dp(context, 12), Gravity.CENTER))
        return container to bar
    }

    fun actionButton(
        context: Context,
        label: CharSequence,
        prominent: Boolean = false,
        onAction: () -> Unit
    ): TextView = TextView(context).apply {
        text = label
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10))
        minimumHeight = dp(context, 46)
        background = if (prominent) {
            GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(ACCENT_STRONG, ACCENT)
            ).apply {
                cornerRadius = dp(context, 14).toFloat()
            }
        } else {
            glassSurface(context, 14)
        }
        setTextColor(if (prominent) Color.rgb(6, 18, 31) else TEXT_PRIMARY)
        isClickable = true
        isFocusable = true
        setOnClickListener { onAction() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(context, 8) }
    }

    /**
     * Two equally wide action buttons on one row, for pairs such as
     * "Xuất cài đặt / Nhập cài đặt". Each still uses the shared 46dp height.
     */
    fun actionRow(
        context: Context,
        firstLabel: CharSequence,
        secondLabel: CharSequence,
        prominentFirst: Boolean = false,
        prominentSecond: Boolean = false,
        onFirst: () -> Unit,
        onSecond: () -> Unit
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(actionButton(context, firstLabel, prominentFirst, onFirst).apply {
            maxLines = 2
            gravity = Gravity.CENTER
            setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
            layoutParams = LinearLayout.LayoutParams(0, dp(context, 56), 1f).apply {
                topMargin = dp(context, 8)
                marginEnd = dp(context, 4)
            }
        })
        addView(actionButton(context, secondLabel, prominentSecond, onSecond).apply {
            maxLines = 2
            gravity = Gravity.CENTER
            setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
            layoutParams = LinearLayout.LayoutParams(0, dp(context, 56), 1f).apply {
                topMargin = dp(context, 8)
                marginStart = dp(context, 4)
            }
        })
    }

    /** Circular colour chip shared by every colour section in the app. */
    fun paletteSwatch(context: Context, color: Int, selected: Boolean): FrameLayout =
        FrameLayout(context).apply {
            paintSwatch(context, this, color, selected)
            contentDescription = "Chọn màu"
            isClickable = true
            isFocusable = true
        }

    fun paintSwatch(context: Context, swatch: View, color: Int, selected: Boolean) {
        swatch.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(
                dp(context, if (selected) 3 else 1),
                if (selected) TEXT_PRIMARY else Color.argb(90, 255, 255, 255)
            )
        }
    }

    /** Small palette tile that opens the shared HSV picker. */
    fun paletteButton(
        context: Context,
        initial: Int,
        title: String,
        onPick: (Int) -> Unit
    ): FrameLayout = FrameLayout(context).apply {
        background = fieldBackground(context, 12)
        contentDescription = "Bảng màu tùy chỉnh"
        isClickable = true
        isFocusable = true
        addView(
            LucideGlyphView(context, "palette", ICON_COLOR),
            FrameLayout.LayoutParams(dp(context, 18), dp(context, 18), Gravity.CENTER)
        )
        setOnClickListener {
            val activity = context as? Activity ?: return@setOnClickListener
            PanelColorPicker.show(activity, initial, title, onPick)
        }
    }

    /**
     * One unified colour strip: 32dp swatches followed by the palette button,
     * horizontally scrollable. Every colour section uses this exact row.
     */
    fun paletteRow(
        context: Context,
        palette: IntArray,
        selected: Int,
        title: String,
        chips: MutableList<Pair<Int, View>>? = null,
        initial: Int = selected,
        onPick: (Int) -> Unit
    ): HorizontalScrollView = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 4), dp(context, 4), dp(context, 4))
            palette.forEach { color ->
                val swatch = paletteSwatch(context, color, color == selected).apply {
                    setOnClickListener { onPick(color) }
                }
                chips?.add(color to swatch)
                addView(swatch, LinearLayout.LayoutParams(dp(context, 32), dp(context, 32)).apply {
                    marginEnd = dp(context, 7)
                })
            }
            addView(paletteButton(context, initial, title, onPick),
                LinearLayout.LayoutParams(dp(context, 36), dp(context, 36)))
        }
        addView(row)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(context, 6) }
    }

    fun iconTile(
        context: Context,
        iconName: String,
        sizeDp: Int = 40,
        glyphDp: Int = 22
    ): FrameLayout = FrameLayout(context).apply {
        background = GradientDrawable().apply {
            setColor(withAlpha(ICON_COLOR, 0.15f))
            cornerRadius = dp(context, 12).toFloat()
        }
        addView(
            LucideGlyphView(context, iconName, ICON_COLOR),
            FrameLayout.LayoutParams(
                dp(context, glyphDp),
                dp(context, glyphDp),
                Gravity.CENTER
            )
        )
        layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
    }

    /**
     * Float-ball row icon: same tinted tile as [iconTile], with a halo-ring
     * glyph that echoes the ball's default artwork.
     */
    fun floatBallTile(context: Context, sizeDp: Int = 40, glyphDp: Int = 22): FrameLayout =
        FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.argb(38, 124, 190, 255))
                cornerRadius = dp(context, 12).toFloat()
            }
            addView(
                HaloGlyphView(context),
                FrameLayout.LayoutParams(
                    dp(context, glyphDp),
                    dp(context, glyphDp),
                    Gravity.CENTER
                )
            )
            layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
        }

    /** Distinct launcher-shortcut icon: accent tile plus the action's Lucide glyph. */
    fun shortcutIcon(context: Context, actionId: String, sizePx: Int = 192): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val plate = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(ACCENT_STRONG, ACCENT)
        ).apply {
            cornerRadius = sizePx * 0.24f
            setBounds(0, 0, sizePx, sizePx)
        }
        plate.draw(canvas)
        val glyph = LucideGlyphs.glyph(context, ActionGlyphView.iconFor(actionId))
        if (glyph.isNotEmpty()) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = LucideGlyphs.typeface(context)
                textAlign = Paint.Align.CENTER
                textSize = sizePx * 0.54f
                color = Color.WHITE
            }
            val metrics = paint.fontMetrics
            canvas.drawText(
                glyph,
                sizePx / 2f,
                sizePx / 2f - (metrics.ascent + metrics.descent) / 2f,
                paint
            )
        }
        return bitmap
    }

    /** Ring with a soft outer halo, drawn on a 24-unit grid like the Lucide set. */
    private class HaloGlyphView(context: Context) : View(context) {
        private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.6f
            color = withAlpha(ICON_COLOR, 0.22f)
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.9f
            strokeCap = Paint.Cap.ROUND
            color = ICON_COLOR
        }
        private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = ICON_COLOR
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val scale = minOf(width, height) / 24f
            if (scale <= 0f) return
            canvas.save()
            canvas.translate((width - scale * 24f) / 2f, (height - scale * 24f) / 2f)
            canvas.scale(scale, scale)
            canvas.drawCircle(12f, 12f, 8.7f, haloPaint)
            canvas.drawCircle(12f, 12f, 7.3f, ringPaint)
            canvas.drawCircle(12f, 4.7f, 1.1f, glintPaint)
            canvas.drawCircle(18.4f, 15.6f, 1.0f, glintPaint)
            canvas.restore()
        }
    }
}
