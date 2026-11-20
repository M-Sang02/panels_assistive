package com.manh.panelsassistive

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import androidx.core.content.res.ResourcesCompat

/** Lucide icon-font renderer, using the same icon font catalog shipped by XGesture. */
open class LucideGlyphView(
    context: Context,
    iconName: String,
    color: Int = Color.WHITE
) : View(context) {
    private var glyph = LucideGlyphs.glyph(context, iconName)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = LucideGlyphs.typeface(context)
        textAlign = Paint.Align.CENTER
        this.color = color
        style = Paint.Style.FILL
    }

    fun setIconColor(color: Int) { paint.color = color; invalidate() }
    fun setIconName(name: String) { glyph = LucideGlyphs.glyph(context, name); invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (glyph.isEmpty()) return
        val side = minOf(width, height).toFloat()
        paint.textSize = side * 0.82f
        val metrics = paint.fontMetrics
        val baseline = height / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(glyph, width / 2f, baseline, paint)
    }
}

internal object LucideGlyphs {
    @Volatile private var typefaceCache: Typeface? = null
    @Volatile private var mapCache: Map<String, Int>? = null

    fun typeface(context: Context): Typeface = typefaceCache ?: synchronized(this) {
        typefaceCache ?: (ResourcesCompat.getFont(context, R.font.lucide) ?: Typeface.DEFAULT)
            .also { typefaceCache = it }
    }

    fun glyph(context: Context, name: String): String {
        val map = mapCache ?: synchronized(this) {
            mapCache ?: runCatching {
                context.applicationContext.assets.open("lucide_icons.tsv").bufferedReader().useLines { lines ->
                    lines.mapNotNull { line ->
                        val parts = line.split('\t')
                        if (parts.size != 2) null else parts[0] to parts[1].toIntOrNull()
                    }.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
                }
            }.getOrDefault(emptyMap()).also { mapCache = it }
        }
        return map[name]?.let { String(Character.toChars(it)) }.orEmpty()
    }
}
