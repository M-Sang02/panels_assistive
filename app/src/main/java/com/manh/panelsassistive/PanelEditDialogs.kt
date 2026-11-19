package com.manh.panelsassistive

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.WeakHashMap

internal object PanelEditDialogs {
    private val noticeHandler = Handler(Looper.getMainLooper())
    private val notices = WeakHashMap<FrameLayout, UndoNotice>()

    fun showSize(
        context: Context,
        width: Int,
        height: Int,
        maxWidth: Int,
        onApply: (Int, Int) -> Boolean
    ) {
        val dialog = Dialog(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(18), context.dp(14), context.dp(18), context.dp(16))
            background = panelBackground(context)
        }
        val header = dialogHeader(context, context.getString(R.string.edit_panel_size_title)) {
            dialog.dismiss()
        }
        root.addView(header)
        val input = EditText(context).apply {
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            hint = context.getString(R.string.panel_size_hint)
            setText("${width}x$height")
            setSelectAllOnFocus(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            background = panelBackground(context, 0x332D4052)
            setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(10))
        }
        root.addView(input, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            context.dp(50)
        ).apply {
            topMargin = context.dp(12)
        })
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, context.dp(18), 0, 0)
        }
        buttons.addView(actionButton(context, context.getString(android.R.string.cancel)) {
            dialog.dismiss()
        }, LinearLayout.LayoutParams(0, context.dp(44), 1f).apply {
            marginEnd = context.dp(8)
        })
        buttons.addView(actionButton(context, context.getString(R.string.panel_apply), emphasized = true) {
            val match = SIZE_PATTERN.matchEntire(input.text.toString().trim())
            val parsedWidth = match?.groupValues?.getOrNull(1)?.toIntOrNull()
            val parsedHeight = match?.groupValues?.getOrNull(2)?.toIntOrNull()
            if (parsedWidth == null || parsedHeight == null ||
                parsedWidth !in 1..maxWidth || parsedHeight !in 1..10
            ) {
                input.error = context.getString(R.string.panel_size_invalid, maxWidth)
                return@actionButton
            }
            if (!onApply(parsedWidth, parsedHeight)) {
                input.error = context.getString(R.string.panel_size_overlap)
                return@actionButton
            }
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(input.windowToken, 0)
            dialog.dismiss()
        }, LinearLayout.LayoutParams(0, context.dp(44), 1f))
        root.addView(buttons)
        dialog.setContentView(root)
        dialog.setCancelable(false)
        configureDialog(dialog, context, focusable = true)
        dialog.show()
        dialog.window?.setLayout(context.dp(320), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
    }

    fun showMove(
        context: Context,
        title: String,
        onMove: (Int, Int) -> Unit
    ) {
        val dialog = Dialog(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(12))
            background = panelBackground(context)
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = TextView(context).apply {
            text = title
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(context.dp(8), context.dp(10), context.dp(8), context.dp(10))
            contentDescription = context.getString(R.string.move_dialog_drag_handle)
        }
        header.addView(titleView, LinearLayout.LayoutParams(0, context.dp(42), 1f))
        header.addView(actionButton(context, "×", description = R.string.close) {
            dialog.dismiss()
        }, LinearLayout.LayoutParams(context.dp(42), context.dp(42)))
        root.addView(header)

        val directions = GridLayout(context).apply {
            columnCount = 3
            rowCount = 3
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
            setPadding(0, context.dp(4), 0, context.dp(4))
        }
        fun addDirection(label: String, description: Int, dx: Int, dy: Int, row: Int, column: Int) {
            val button = actionButton(context, label, emphasized = true, description = description) {
                onMove(dx, dy)
            }
            directions.addView(button, GridLayout.LayoutParams(
                GridLayout.spec(row),
                GridLayout.spec(column)
            ).apply {
                width = context.dp(54)
                height = context.dp(48)
                setMargins(context.dp(3), context.dp(3), context.dp(3), context.dp(3))
            })
        }
        addDirection("↑", R.string.move_item_up, 0, -1, 0, 1)
        addDirection("←", R.string.move_item_left, -1, 0, 1, 0)
        addDirection("→", R.string.move_item_right, 1, 0, 1, 2)
        addDirection("↓", R.string.move_item_down, 0, 1, 2, 1)
        directions.addView(actionButton(context, "✓", description = R.string.confirm_position) {
            dialog.dismiss()
        }, GridLayout.LayoutParams(
            GridLayout.spec(1),
            GridLayout.spec(1)
        ).apply {
            width = context.dp(54)
            height = context.dp(48)
            setMargins(context.dp(3), context.dp(3), context.dp(3), context.dp(3))
        })
        root.addView(directions, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER })
        dialog.setContentView(root)
        dialog.setCancelable(false)
        configureDialog(dialog, context, focusable = true)
        dialog.show()
        val window = dialog.window ?: return
        window.setLayout(context.dp(238), ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        positionMoveDialog(dialog, context, titleView)
    }

    fun showUndoNotice(
        container: FrameLayout,
        message: String,
        onUndo: () -> Unit,
        onExpire: () -> Unit
    ) {
        notices.remove(container)?.finish(expire = true)
        val context = container.context
        val notice = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(14), context.dp(8), context.dp(8), context.dp(8))
            background = panelBackground(context, 0xF018202C.toInt(), 18)
            elevation = context.dp(12).toFloat()
        }
        notice.addView(TextView(context).apply {
            text = message
            textSize = 13f
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        notice.addView(actionButton(context, context.getString(R.string.undo), emphasized = true) {
            notices.remove(container)
            val entry = notice.tag as? UndoNotice ?: return@actionButton
            entry.finish(expire = false)
            onUndo()
        }, LinearLayout.LayoutParams(context.dp(92), context.dp(42)))
        val entry = UndoNotice(container, notice, onExpire)
        notice.tag = entry
        notices[container] = entry
        entry.attach()
        noticeHandler.postDelayed(entry.timeout, UNDO_DURATION_MS)
    }

    fun reattachUndoNotice(container: FrameLayout) {
        notices[container]?.attach()
    }

    fun guideBackground(
        context: Context,
        columns: Int,
        cellWidthDp: Int,
        rowHeightDp: Int,
    ) = PanelGridGuidesDrawable(context, columns, cellWidthDp, rowHeightDp)

    private fun configureDialog(dialog: Dialog, context: Context, focusable: Boolean) {
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.28f)
            if (context !is Activity) {
                setType(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                })
            }
            if (!focusable) addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
    }

    private fun positionMoveDialog(dialog: Dialog, context: Context, dragHandle: View) {
        val window = dialog.window ?: return
        window.decorView.post {
            val attributes = window.attributes
            val navBarHeight = context.resources.getIdentifier(
                "navigation_bar_height",
                "dimen",
                "android"
            ).takeIf { it != 0 }?.let { context.resources.getDimensionPixelSize(it) } ?: 0
            attributes.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            attributes.y = navBarHeight + context.dp(14)
            window.attributes = attributes
        }
        var startRawX = 0f
        var startRawY = 0f
        var startLeft = 0
        var startTop = 0
        dragHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    val location = IntArray(2)
                    window.decorView.getLocationOnScreen(location)
                    startLeft = location[0]
                    startTop = location[1]
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val attributes = window.attributes
                    val width = window.decorView.width
                    val height = window.decorView.height
                    val navBarHeight = context.resources.getIdentifier(
                        "navigation_bar_height",
                        "dimen",
                        "android"
                    ).takeIf { it != 0 }?.let { context.resources.getDimensionPixelSize(it) } ?: 0
                    attributes.gravity = Gravity.TOP or Gravity.START
                    attributes.x = (startLeft + (event.rawX - startRawX).toInt())
                        .coerceIn(0, (context.resources.displayMetrics.widthPixels - width).coerceAtLeast(0))
                    attributes.y = (startTop + (event.rawY - startRawY).toInt())
                        .coerceIn(0, (context.resources.displayMetrics.heightPixels - navBarHeight - height).coerceAtLeast(0))
                    window.attributes = attributes
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun dialogHeader(context: Context, title: String, onClose: () -> Unit) =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = title
                textSize = 15f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, context.dp(42), 1f))
            addView(actionButton(context, "×", description = R.string.close, action = onClose),
                LinearLayout.LayoutParams(context.dp(42), context.dp(42)))
        }

    private fun actionButton(
        context: Context,
        label: String,
        emphasized: Boolean = false,
        description: Int? = null,
        action: () -> Unit
    ) = TextView(context).apply {
        text = label
        textSize = if (label.length < 3) 20f else 13f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        isClickable = true
        isFocusable = true
        contentDescription = description?.let(context::getString) ?: label
        background = panelBackground(
            context,
            if (emphasized) 0xBB2869A5.toInt() else 0x55364759
        )
        setOnClickListener { action() }
    }

    private fun panelBackground(
        context: Context,
        color: Int = 0xF018202C.toInt(),
        radiusDp: Int = 18
    ) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = context.dp(radiusDp).toFloat()
        setStroke(context.dp(1), 0x667EA7CC)
    }

    private fun Context.dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private class UndoNotice(
        private val container: FrameLayout,
        private val view: View,
        private val onExpire: () -> Unit
    ) {
        val timeout = Runnable { finish(expire = true) }

        fun attach() {
            if (view.parent != null) return
            val context = container.context
            container.addView(view, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                leftMargin = context.dp(12)
                rightMargin = context.dp(12)
                bottomMargin = context.dp(12)
            })
        }

        fun finish(expire: Boolean) {
            noticeHandler.removeCallbacks(timeout)
            (view.parent as? ViewGroup)?.removeView(view)
            if (notices[container] === this) notices.remove(container)
            if (expire) onExpire()
        }

        private fun Context.dp(value: Int): Int =
            (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    internal class PanelGridGuidesDrawable(
        private val context: Context,
        private val columns: Int,
        private val cellWidthDp: Int,
        private val rowHeightDp: Int
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x667DA8D3
            strokeWidth = context.dp(1).toFloat()
            style = Paint.Style.STROKE
        }

        override fun draw(canvas: Canvas) {
            val bounds = bounds
            val left = (bounds.left + context.dp(2)).toFloat()
            val top = (bounds.top + context.dp(2)).toFloat()
            val cellWidth = context.dp(cellWidthDp + 6).toFloat()
            val rowHeight = context.dp(rowHeightDp + 6).toFloat()
            val right = minOf(
                (bounds.right - context.dp(2)).toFloat(),
                left + columns.coerceIn(2, 20) * cellWidth
            )
            val bottom = (bounds.bottom - context.dp(2)).toFloat()
            var x = left
            while (x <= right) {
                canvas.drawLine(x, top, x, bottom, paint)
                x += cellWidth
            }
            var y = top
            while (y <= bottom) {
                canvas.drawLine(left, y, right, y, paint)
                y += rowHeight
            }
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        private fun Context.dp(value: Int): Int =
            (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private val SIZE_PATTERN = Regex("(\\d{1,2})\\s*[xX×]\\s*(\\d{1,2})")
    private const val UNDO_DURATION_MS = 3_000L
}
