package com.manh.panelsassistive

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

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
    val TEXT_PRIMARY = 0xFFF4F6FA.toInt()
    val TEXT_SECONDARY = 0xFFA6ADB9.toInt()
    val TEXT_MUTED = 0xFF7E8896.toInt()
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
    }

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

    fun tabSurface(context: Context, selected: Boolean): Drawable =
        if (selected) {
            GradientDrawable().apply {
                setColor(ACCENT_SOFT)
                cornerRadius = dp(context, 16).toFloat()
                setStroke(dp(context, 1), Color.argb(150, 124, 190, 255))
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
        progressTintList = ColorStateList.valueOf(ACCENT)
        progressBackgroundTintList = ColorStateList.valueOf(Color.argb(40, 255, 255, 255))
        thumbTintList = ColorStateList.valueOf(ACCENT)
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
            setBackgroundColor(ACCENT)
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
        setPadding(dp(context, 16), 0, dp(context, 16), 0)
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

    fun iconTile(
        context: Context,
        iconName: String,
        sizeDp: Int = 40,
        glyphDp: Int = 22
    ): FrameLayout = FrameLayout(context).apply {
        background = GradientDrawable().apply {
            setColor(Color.argb(38, 124, 190, 255))
            cornerRadius = dp(context, 12).toFloat()
        }
        addView(
            LucideGlyphView(context, iconName, ACCENT),
            FrameLayout.LayoutParams(
                dp(context, glyphDp),
                dp(context, glyphDp),
                Gravity.CENTER
            )
        )
        layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
    }
}
