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
        if (pack.back == null && pack.mask == null) return original
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
                back = findFirst(resources, packageName, "iconback"),
                mask = findFirst(resources, packageName, "iconmask"),
                upon = findFirst(resources, packageName, "iconupon")
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

    private fun findFirst(resources: Resources, packageName: String, base: String): String? {
        for (index in 1..6) {
            val name = if (index == 1) base else base + index
            if (resolveId(resources, packageName, name) != 0) return name
        }
        return null
    }

    private fun resolveId(resources: Resources, packageName: String, name: String): Int {
        var id = resources.getIdentifier(name, "drawable", packageName)
        if (id == 0) id = resources.getIdentifier(name, "mipmap", packageName)
        return id
    }

    private fun bitmap(context: Context, pack: PackData, name: String, sizePx: Int): Bitmap? {
        if (name.isEmpty()) return null
        val key = pack.packageName + "/" + name + "/" + sizePx
        bitmapCache.get(key)?.let { return it }
        val result = try {
            val id = resolveId(pack.resources, pack.packageName, name)
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
        // No invented background: packs that ship no iconback stay fully transparent,
        // so panel icons never get a rim they did not ask for.
        pack.back?.let { name ->
            bitmap(context, pack, name, sizePx)?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        }
        val icon = drawableBitmap(original, sizePx)
        val mask = pack.mask?.let { bitmap(context, pack, it, sizePx) }
        if (mask != null) {
            // The pack mask already carries its own safe margin, so the icon is
            // drawn over the full canvas and only the mask decides the shape.
            val layer = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val layerCanvas = Canvas(layer)
            layerCanvas.drawBitmap(icon, 0f, 0f, null)
            val clip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(maskMode(mask))
            }
            layerCanvas.drawBitmap(mask, 0f, 0f, clip)
            canvas.drawBitmap(layer, 0f, 0f, null)
        } else {
            val inset = (sizePx * 0.17f).toInt()
            canvas.drawBitmap(
                icon, null,
                RectF(inset.toFloat(), inset.toFloat(), (sizePx - inset).toFloat(), (sizePx - inset).toFloat()),
                null
            )
        }
        pack.upon?.let { name ->
            bitmap(context, pack, name, sizePx)?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        }
        return out
    }

    /**
     * ADW-style masks come in two conventions: "black frame" (opaque outside,
     * transparent inside) must erase with DST_OUT, while "white shape" (transparent
     * outside, opaque inside) must keep with DST_IN. Pick from the alpha of the
     * corners versus the center so both pack families render the same.
     */
    private fun maskMode(mask: Bitmap): PorterDuff.Mode {
        val edge = mask.getPixel(1.coerceAtMost(mask.width - 1), 1.coerceAtMost(mask.height - 1))
        val center = mask.getPixel(mask.width / 2, mask.height / 2)
        return if (Color.alpha(edge) > Color.alpha(center)) {
            PorterDuff.Mode.DST_OUT
        } else {
            PorterDuff.Mode.DST_IN
        }
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
