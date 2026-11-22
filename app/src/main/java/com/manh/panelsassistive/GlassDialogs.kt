package com.manh.panelsassistive

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * One glass-styled chooser for every list in the app: apps, actions, icon
 * packs, add-item menus. A dark sheet with a light rim, a title, an optional
 * search field, iconned rows with a check mark on the current value and a
 * single accent "Huỷ" button — the same language as the settings pages.
 */
object GlassDialogs {
    class Item(
        val id: String,
        val label: String,
        val caption: String? = null,
        val glyph: String? = null,
        val actionId: String? = null,
        val iconPackage: String? = null,
        val selected: Boolean = false
    )

    class Sheet internal constructor(
        private val dialog: Dialog,
        val content: LinearLayout
    ) {
        /** [windowType] is only set for service overlay sheets, never for activities. */
        fun show(windowType: Int? = null) {
            if (windowType != null) dialog.window?.setType(windowType)
            dialog.show()
            val metrics = dialog.context.resources.displayMetrics
            val width = minOf((metrics.widthPixels * 0.92f).toInt(), dp(dialog.context, 560))
            dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        fun dismiss() = dialog.dismiss()
    }

    /** Opens an empty sheet; callers append their own controls to [Sheet.content]. */
    fun sheet(context: Context, title: String, subtitle: String? = null): Sheet =
        build(context, title, subtitle, Dialog(context))

    /** Sheet hosted by a floating-panel overlay window instead of an activity. */
    fun sheetOverlay(context: Context, title: String, subtitle: String? = null): Sheet =
        build(context, title, subtitle, Dialog(context, R.style.GlassOverlayDialog))

    private fun build(
        activity: Context,
        title: String,
        subtitle: String?,
        dialog: Dialog
    ): Sheet {
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val params = attributes
            params.dimAmount = 0.6f
            attributes = params
            setGravity(Gravity.CENTER)
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = sheetBackground(activity)
            setPadding(dp(activity, 18), dp(activity, 16), dp(activity, 18), dp(8))
        }
        root.addView(TextView(activity).apply {
            text = title
            textSize = 18f
            setTextColor(GlassUi.TEXT_PRIMARY)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            includeFontPadding = false
        })
        if (subtitle != null) {
            root.addView(TextView(activity).apply {
                text = subtitle
                textSize = 12f
                setTextColor(GlassUi.TEXT_SECONDARY)
                includeFontPadding = false
                setPadding(0, dp(activity, 5), 0, 0)
            })
        }
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(activity, 8) })
        root.addView(TextView(activity).apply {
            text = "Huỷ"
            textSize = 14f
            setTextColor(GlassUi.ACCENT)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            includeFontPadding = false
            isClickable = true
            isFocusable = true
            setPadding(dp(activity, 4), dp(activity, 14), dp(activity, 4), dp(activity, 8))
            setOnClickListener { dialog.dismiss() }
        })
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(true)
        return Sheet(dialog, content)
    }

    /** Searchable single-select list with icons and a check mark. */
    fun show(
        activity: Activity,
        title: String,
        items: List<Item>,
        subtitle: String? = null,
        searchable: Boolean = items.size >= 6,
        onPick: (Item) -> Unit
    ) {
        val sheet = sheet(activity, title, subtitle)
        populate(activity, sheet, items, searchable, onPick)
        sheet.show()
    }

    /** The same chooser inside a floating-panel overlay window. */
    fun showOverlay(
        context: Context,
        title: String,
        items: List<Item>,
        subtitle: String? = null,
        windowType: Int,
        searchable: Boolean = items.size >= 6,
        onPick: (Item) -> Unit
    ) {
        val sheet = sheetOverlay(context, title, subtitle)
        populate(context, sheet, items, searchable, onPick)
        sheet.show(windowType)
    }

    private fun populate(
        activity: Context,
        sheet: Sheet,
        items: List<Item>,
        searchable: Boolean,
        onPick: (Item) -> Unit
    ) {
        val search = if (searchable) addSearch(activity, sheet.content) else null
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroller = MaxHeightScrollView(activity, maxListHeight(activity))
        scroller.addView(list, ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        sheet.content.addView(scroller, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        val empty = TextView(activity).apply {
            text = "Không có kết quả"
            textSize = 13f
            setTextColor(GlassUi.TEXT_MUTED)
            includeFontPadding = false
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(activity, 18), 0, dp(activity, 18))
        }
        sheet.content.addView(empty)

        val rows = mutableListOf<Pair<View, String>>()
        items.forEach { item ->
            val row = row(activity, item) {
                sheet.dismiss()
                onPick(item)
            }
            rows.add(row to (item.label + " " + (item.caption ?: "")).lowercase())
            list.addView(row)
        }
        if (search != null) {
            fun filter() {
                val query = search.text.toString().trim().lowercase()
                var visible = 0
                rows.forEach { (view, haystack) ->
                    val matches = query.isEmpty() || haystack.contains(query)
                    view.visibility = if (matches) View.VISIBLE else View.GONE
                    if (matches) visible++
                }
                empty.visibility = if (visible == 0) View.VISIBLE else View.GONE
            }
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = filter()
            })
        }
    }

    /** App icon for a package, or null when the app is gone. */
    fun appIcon(context: Context, packageName: String?): Drawable? {
        if (packageName.isNullOrEmpty()) return null
        return runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
    }

    private fun row(activity: Context, item: Item, onClick: () -> Unit): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(activity, 52)
            setPadding(dp(activity, 10), dp(activity, 7), dp(activity, 10), dp(activity, 7))
            isClickable = true
            isFocusable = true
            if (item.selected) background = GlassUi.fieldBackground(activity, 14)
            setOnClickListener { onClick() }
            addView(icon(activity, item), LinearLayout.LayoutParams(
                dp(activity, 30),
                dp(activity, 30)
            ))
            val column = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(activity, 12), 0, dp(activity, 8), 0)
            }
            column.addView(TextView(activity).apply {
                text = item.label
                textSize = 15f
                setTextColor(if (item.selected) GlassUi.ACCENT else GlassUi.TEXT_PRIMARY)
                includeFontPadding = false
            })
            if (item.caption != null) {
                column.addView(TextView(activity).apply {
                    text = item.caption
                    textSize = 11f
                    setTextColor(GlassUi.TEXT_MUTED)
                    includeFontPadding = false
                    setPadding(0, dp(activity, 2), 0, 0)
                })
            }
            addView(column, LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ))
            if (item.selected) {
                addView(
                    LucideGlyphView(activity, "check", GlassUi.ACCENT),
                    LinearLayout.LayoutParams(dp(activity, 18), dp(activity, 18))
                )
            }
        }

    private fun icon(activity: Context, item: Item): View {
        if (item.actionId != null) return ActionGlyphView(activity, item.actionId, GlassUi.ACCENT)
        if (item.iconPackage != null) {
            val drawable = appIcon(activity, item.iconPackage)
            if (drawable != null) {
                return ImageView(activity).apply {
                    setImageDrawable(drawable)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
            }
            return LucideGlyphView(activity, "smartphone", GlassUi.TEXT_SECONDARY)
        }
        return LucideGlyphView(activity, item.glyph ?: "circle-dot", GlassUi.ACCENT)
    }

    /** Search bar for custom list content inside a [sheet]. */
    fun searchField(activity: Context, parent: LinearLayout): EditText =
        addSearch(activity, parent)

    private fun addSearch(activity: Context, parent: LinearLayout): EditText {
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GlassUi.fieldBackground(activity, 14)
            setPadding(dp(activity, 12), 0, dp(activity, 12), 0)
        }
        bar.addView(
            LucideGlyphView(activity, "search", GlassUi.TEXT_MUTED),
            LinearLayout.LayoutParams(dp(activity, 18), dp(activity, 18))
        )
        val input = EditText(activity).apply {
            background = null
            hint = "Tìm kiếm"
            setHintTextColor(GlassUi.TEXT_MUTED)
            setTextColor(GlassUi.TEXT_PRIMARY)
            textSize = 14f
            setSingleLine()
            setPadding(dp(activity, 8), 0, 0, 0)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        bar.addView(input, LinearLayout.LayoutParams(
            0,
            dp(activity, 46),
            1f
        ))
        parent.addView(bar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(activity, 8) })
        return input
    }

    private fun maxListHeight(activity: Context): Int =
        minOf((activity.resources.displayMetrics.heightPixels * 0.46f).toInt(), dp(activity, 400))

    private fun sheetBackground(context: Context): Drawable {
        val corner = dp(context, 24).toFloat()
        val fill = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xF2162537.toInt(), 0xF20A1421.toInt())
        ).apply { cornerRadius = corner }
        val rim = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = corner
            setStroke(dp(context, 1).coerceAtLeast(1), Color.argb(52, 255, 255, 255))
        }
        return LayerDrawable(arrayOf(fill, rim))
    }

    private fun dp(context: Context, value: Int): Int = GlassUi.dp(context, value)

    private class MaxHeightScrollView(context: Context, private val maxHeight: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(
                widthMeasureSpec,
                View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST)
            )
        }
    }
}
