package com.manh.panelsassistive

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * ADW/Nova icon packs: appfilter.xml (component -> drawable), drawable.xml
 * (name -> drawable) and the iconback/iconmask/iconupon mask set.
 */
object IconPacks {
    data class Pack(val packageName: String, val label: String)

    private val THEME_ACTIONS = listOf(
        "org.adw.launcher.THEMES",
        "com.novalauncher.THEME",
        "com.gau.go.launcherex.theme",
        "com.anddoes.launcher.THEME",
        "com.teslacoilsw.launcher.THEME",
        "com.smartlauncher.THEME"
    )

    private class PackData(
        val packageName: String,
        val resources: Resources,
        val appFilter: Map<String, String>,
        val packageIcons: Map<String, String>,
        val drawables: List<Pair<String, String>>,
        val back: String?,
        val mask: String?,
        val upon: String?
    )

    private val packCache = HashMap<String, PackData?>()
    private val bitmapCache = LruCache<String, Bitmap>(64)

    fun installedPacks(context: Context): List<Pack> {
        val seen = LinkedHashMap<String, Pack>()
        THEME_ACTIONS.forEach { action ->
            val found = try {
                context.packageManager.queryIntentActivities(Intent(action), 0)
            } catch (_: Exception) {
                emptyList()
            }
            found.forEach { resolved ->
                val info = resolved.activityInfo ?: return@forEach
                if (info.packageName == context.packageName || seen.containsKey(info.packageName)) return@forEach
                val label = try {
                    info.applicationInfo?.loadLabel(context.packageManager)?.toString()
                } catch (_: Exception) {
                    null
                }
                seen[info.packageName] = Pack(info.packageName, label ?: info.packageName)
            }
        }
        return seen.values.sortedBy { it.label.lowercase() }
    }

    fun packIconEntries(context: Context, packageName: String): List<Pair<String, String>> =
        packData(context, packageName)?.drawables.orEmpty()

    fun overrideKey(type: String, value: String): String = "$type|$value"

    /** Final icon for a panel item: user override > icon pack entry > mask composite. */
    fun resolve(
        context: Context,
        type: String,
        value: String,
        original: Drawable?,
        sizePx: Int = 128
    ): Drawable? {
        val override = AppPrefs.iconOverride(context, overrideKey(type, value))
        if (override != null) {
            decodeOverride(context, override, sizePx)?.let {
                return BitmapDrawable(context.resources, it)
            }
        }
        if (type != PanelItemType.APP || !AppPrefs.iconPackEnabled(context)) return original
        val pack = packData(context, AppPrefs.iconPackPackage(context)) ?: return original
        val component = ComponentName.unflattenFromString(value)
        val name = component?.let { lookupAppFilter(pack, it) }
        if (name != null) {
            bitmap(context, pack, name, sizePx)?.let { return BitmapDrawable(context.resources, it) }
        }
        if (!AppPrefs.iconMaskEnabled(context) || original == null) return original
        return BitmapDrawable(context.resources, masked(context, pack, original, sizePx))
    }

    /** Icon from a specific pack, used by the picker previews and stored overrides. */
    fun packIconBitmap(context: Context, packageName: String, drawableName: String, sizePx: Int): Bitmap? =
        packData(context, packageName)?.let { bitmap(context, it, drawableName, sizePx) }

    fun imageOverridePath(context: Context, key: String): File {
        val dir = File(context.filesDir, "icons")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, key.hashCode().toString() + ".png")
    }

    private fun lookupAppFilter(pack: PackData, component: ComponentName): String? {
        val flattened = component.flattenToString()
        pack.appFilter[flattened]?.let { return it }
        pack.appFilter[component.flattenToShortString()]?.let { return it }
        return pack.packageIcons[component.packageName]
    }

    @Synchronized
    private fun packData(context: Context, packageName: String): PackData? {
        if (packageName.isEmpty()) return null
        if (packCache.containsKey(packageName)) return packCache[packageName]
        val data = try {
            val resources = context.packageManager.getResourcesForApplication(packageName)
            val appFilter = HashMap<String, String>()
            val packageIcons = HashMap<String, String>()
            parseItems(resources, "appfilter.xml") { values ->
                val component = values["component"] ?: return@parseItems
                val drawable = values["drawable"] ?: return@parseItems
                if (component.isEmpty() || drawable.isEmpty()) return@parseItems
                val normalized = normalizeComponent(component)
                appFilter[normalized] = drawable
                normalized.substringBefore('/').takeIf { it.isNotEmpty() }?.let {
                    if (!packageIcons.containsKey(it)) packageIcons[it] = drawable
                }
            }
            val drawables = ArrayList<Pair<String, String>>()
            parseItems(resources, "drawable.xml") { values ->
                val drawable = values["drawable"] ?: return@parseItems
                val name = values["name"] ?: drawable
                if (drawable.isNotEmpty()) drawables.add(name to drawable)
            }
            PackData(
                packageName = packageName,
                resources = resources,
                appFilter = appFilter,
                packageIcons = packageIcons,
                drawables = drawables,
                back = findFirst(resources, "iconback"),
                mask = findFirst(resources, "iconmask"),
                upon = findFirst(resources, "iconupon")
            )
        } catch (_: Exception) {
            null
        }
        packCache[packageName] = data
        return data
    }

    private inline fun parseItems(
        resources: Resources,
        asset: String,
        onItem: (Map<String, String>) -> Unit
    ) {
        try {
            resources.assets.open(asset).use { stream ->
                val parser = android.util.Xml.newPullParser()
                parser.setInput(stream, null)
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && parser.name == "item") {
                        val values = HashMap<String, String>()
                        for (index in 0 until parser.attributeCount) {
                            values[parser.getAttributeName(index)] = parser.getAttributeValue(index).orEmpty()
                        }
                        onItem(values)
                    }
                    event = parser.next()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun normalizeComponent(raw: String): String =
        raw.trim()
            .removePrefix("ComponentInfo")
            .removePrefix("{")
            .removeSuffix("}")
            .trim()

    private fun findFirst(resources: Resources, base: String): String? {
        for (index in 1..6) {
            val name = if (index == 1) base else base + index
            if (resolveId(resources, name) != 0) return name
        }
        return null
    }

    private fun resolveId(resources: Resources, name: String): Int {
        var id = resources.getIdentifier(name, "drawable", resources.packageName)
        if (id == 0) id = resources.getIdentifier(name, "mipmap", resources.packageName)
        return id
    }

    private fun bitmap(context: Context, pack: PackData, name: String, sizePx: Int): Bitmap? {
        if (name.isEmpty()) return null
        val key = pack.packageName + "/" + name + "/" + sizePx
        bitmapCache.get(key)?.let { return it }
        val result = try {
            val id = resolveId(pack.resources, name)
            if (id == 0) null else drawableBitmap(pack.resources.getDrawable(id, null), sizePx)
        } catch (_: Exception) {
            null
        }
        if (result != null) bitmapCache.put(key, result)
        return result
    }

    private fun drawableBitmap(drawable: Drawable, sizePx: Int): Bitmap {
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(Canvas(out))
        return out
    }

    private fun masked(context: Context, pack: PackData, original: Drawable, sizePx: Int): Bitmap {
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val back = pack.back?.let { bitmap(context, pack, it, sizePx) }
        if (back != null) {
            canvas.drawBitmap(back, 0f, 0f, null)
        } else {
            drawFallbackBack(canvas, sizePx)
        }
        val inset = (sizePx * 0.17f).toInt()
        val target = RectF(inset.toFloat(), inset.toFloat(), (sizePx - inset).toFloat(), (sizePx - inset).toFloat())
        val icon = drawableBitmap(original, sizePx)
        val mask = pack.mask?.let { bitmap(context, pack, it, sizePx) }
        if (mask != null) {
            val layer = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val layerCanvas = Canvas(layer)
            layerCanvas.drawBitmap(icon, null, target, null)
            val clip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            }
            layerCanvas.drawBitmap(mask, null, target, clip)
            canvas.drawBitmap(layer, 0f, 0f, null)
        } else {
            canvas.drawBitmap(icon, null, target, null)
        }
        pack.upon?.let { name ->
            bitmap(context, pack, name, sizePx)?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        }
        return out
    }

    private fun drawFallbackBack(canvas: Canvas, sizePx: Int) {
        val rect = RectF(sizePx * 0.03f, sizePx * 0.03f, sizePx * 0.97f, sizePx * 0.97f)
        val radius = sizePx * 0.24f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E8EDF4") }
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = sizePx * 0.02f
        paint.color = Color.argb(150, 255, 255, 255)
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    private fun decodeOverride(context: Context, value: String, sizePx: Int): Bitmap? = try {
        when {
            value.startsWith("IMG|") -> {
                val path = value.removePrefix("IMG|")
                BitmapFactory.decodeFile(path)?.let { square(it, sizePx) }
            }
            value.startsWith("PACK|") -> {
                val parts = value.split('|', limit = 3)
                if (parts.size < 3) null else packIconBitmap(context, parts[1], parts[2], sizePx)
            }
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    private fun square(source: Bitmap, sizePx: Int): Bitmap {
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val scale = maxOf(sizePx.toFloat() / source.width, sizePx.toFloat() / source.height)
        val width = source.width * scale
        val height = source.height * scale
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
        canvas.drawBitmap(source, null, RectF(
            (sizePx - width) / 2f,
            (sizePx - height) / 2f,
            (sizePx + width) / 2f,
            (sizePx + height) / 2f
        ), paint)
        return out
    }
}
