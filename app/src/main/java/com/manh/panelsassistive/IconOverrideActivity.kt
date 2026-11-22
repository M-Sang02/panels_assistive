package com.manh.panelsassistive

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.FileOutputStream

class IconOverrideActivity : Activity() {
    private data class Entry(val type: String, val value: String, val label: String)

    private var tab = PanelItemType.APP
    private var entries: MutableList<Entry> = mutableListOf()
    private var adapter: IconAdapter? = null
    private var pendingKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = GlassUi.PAGE_COLOR
        window.navigationBarColor = GlassUi.PAGE_COLOR
        render()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMAGE || resultCode != RESULT_OK) return
        val key = pendingKey ?: return
        val uri = data?.data ?: return
        saveImageOverride(key, uri)
    }

    private fun render() {
        entries = buildEntries(tab)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(10))
            background = GlassUi.pageBackground()
        }
        root.addView(GlassUi.backBar(this) { finish() })
        root.addView(GlassUi.pageHeader(
            this,
            "Icon riêng",
            "Thay icon từng app hoặc lối tắt bằng ảnh trong máy hay icon từ gói."
        ))
        root.addView(tabRow())
        val search = EditText(this).apply {
            hint = "Tìm theo tên"
            setSingleLine()
            textSize = 14f
            setTextColor(GlassUi.TEXT_PRIMARY)
            setHintTextColor(GlassUi.TEXT_MUTED)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GlassUi.glassSurface(this@IconOverrideActivity, 14)
        }
        root.addView(search, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })
        val listView = ListView(this).apply {
            divider = null
            dividerHeight = dp(6)
            clipToPadding = false
            setPadding(0, dp(8), 0, dp(8))
        }
        val listAdapter = IconAdapter()
        adapter = listAdapter
        listView.adapter = listAdapter
        listView.setOnItemClickListener { _, _, position, _ ->
            showOptions(listAdapter.visible()[position])
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                listAdapter.filter(s?.toString().orEmpty())
            }
        })
        root.addView(listView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
    }

    private fun tabRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val appTab = tabButton("App", tab == PanelItemType.APP) {
            tab = PanelItemType.APP
            render()
        }
        val shortcutTab = tabButton("Lối tắt", tab == PanelItemType.SHORTCUT) {
            tab = PanelItemType.SHORTCUT
            render()
        }
        addView(appTab, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(4) })
        addView(shortcutTab, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(4) })
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(4) }
    }

    private fun tabButton(text: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(if (selected) GlassUi.ACCENT else GlassUi.TEXT_SECONDARY)
            background = GlassUi.glassSurface(this@IconOverrideActivity, 14)
            alpha = if (selected) 1f else 0.6f
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun buildEntries(type: String): MutableList<Entry> {
        val map = LinkedHashMap<String, Entry>()
        if (type == PanelItemType.APP) {
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            packageManager.queryIntentActivities(launcher, 0).forEach { info ->
                val activityInfo = info.activityInfo ?: return@forEach
                val value = ComponentName(activityInfo.packageName, activityInfo.name).flattenToString()
                if (!map.containsKey(value)) {
                    map[value] = Entry(PanelItemType.APP, value, info.loadLabel(packageManager).toString())
                }
            }
        } else {
            listOf(false, true).forEach { landscape ->
                AppPrefs.panelPages(this, landscape).forEach { page ->
                    page.items.forEach { item -> addPanelEntry(map, item.type, item.value, item.label) }
                }
            }
            AppPrefs.expansionShortcuts(this).forEach { shortcut ->
                if (shortcut != null) addPanelEntry(map, shortcut.type, shortcut.value, shortcut.label)
            }
        }
        return map.values.sortedBy { it.label.lowercase() }.toMutableList()
    }

    private fun addPanelEntry(
        map: MutableMap<String, Entry>,
        type: String,
        value: String,
        label: String
    ) {
        if (type != PanelItemType.APP && type != PanelItemType.SHORTCUT) return
        val key = IconPacks.overrideKey(type, value)
        if (!map.containsKey(key)) map[key] = Entry(type, value, label)
    }

    private fun showOptions(entry: Entry) {
        val key = IconPacks.overrideKey(entry.type, entry.value)
        val items = listOf(
            GlassDialogs.Item(OPTION_IMAGE, "Chọn ảnh từ máy", glyph = "image"),
            GlassDialogs.Item(OPTION_PACK, "Chọn icon từ gói", glyph = "layout-grid"),
            GlassDialogs.Item(OPTION_RESET, "Đặt lại icon mặc định", glyph = "undo-2")
        )
        GlassDialogs.show(this, entry.label, items) { item ->
            when (item.id) {
                OPTION_IMAGE -> pickImage(key)
                OPTION_PACK -> pickPackIcon(key)
                else -> {
                    AppPrefs.setIconOverride(this, key, null)
                    refreshList()
                }
            }
        }
    }

    private fun pickImage(key: String) {
        pendingKey = key
        try {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("image/*"),
                REQUEST_IMAGE
            )
        } catch (_: Exception) {
            Toast.makeText(this, "Không mở được trình chọn ảnh", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveImageOverride(key: String, uri: Uri) {
        try {
            val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            if (bitmap == null) {
                Toast.makeText(this, "Không đọc được ảnh", Toast.LENGTH_SHORT).show()
                return
            }
            val file = IconPacks.imageOverridePath(this, key)
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            AppPrefs.setIconOverride(this, key, "IMG|" + file.absolutePath)
            refreshList()
        } catch (_: Exception) {
            Toast.makeText(this, "Không lưu được ảnh", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pickPackIcon(key: String) {
        val current = AppPrefs.iconPackPackage(this)
        if (current.isNotEmpty()) {
            showPackIconList(current, key)
            return
        }
        val packs = IconPacks.installedPacks(this)
        if (packs.isEmpty()) {
            Toast.makeText(this, "Không tìm thấy gói icon nào được cài", Toast.LENGTH_SHORT).show()
            return
        }
        val items = packs.map { pack ->
            GlassDialogs.Item(
                id = pack.packageName,
                label = pack.label,
                caption = pack.packageName,
                iconPackage = pack.packageName
            )
        }
        GlassDialogs.show(this, "Chọn gói icon", items, "Gói icon chứa biểu tượng bạn muốn dùng") { item ->
            AppPrefs.setIconPackPackage(this, item.id)
            showPackIconList(item.id, key)
        }
    }

    private fun showPackIconList(packageName: String, key: String) {
        val all = IconPacks.packIconEntries(this, packageName)
        if (all.isEmpty()) {
            Toast.makeText(this, "Gói này không có danh sách icon", Toast.LENGTH_SHORT).show()
            return
        }
        val sheet = GlassDialogs.sheet(this, "Chọn icon", "Gói $packageName")
        val search = GlassDialogs.searchField(this, sheet.content)
        val listView = ListView(this).apply {
            setBackgroundColor(0)
            divider = null
            dividerHeight = 0
            clipToPadding = false
        }
        sheet.content.addView(listView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(360)
        ).apply { topMargin = dp(6) })
        val rows = ArrayList(all.filter { it.first.contains(search.text, ignoreCase = true) })
        val packAdapter = PackIconAdapter(packageName, rows) { name ->
            AppPrefs.setIconOverride(this, key, "PACK|$packageName|$name")
            sheet.dismiss()
            refreshList()
        }
        listView.adapter = packAdapter
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                rows.clear()
                val query = s?.toString().orEmpty()
                rows.addAll(all.filter { it.first.contains(query, ignoreCase = true) })
                packAdapter.notifyDataSetChanged()
            }
        })
        sheet.show()
    }

    private fun refreshList() {
        entries = buildEntries(tab)
        adapter?.setEntries(entries)
    }

    private fun originalIcon(entry: Entry): android.graphics.drawable.Drawable? = try {
        when (entry.type) {
            PanelItemType.APP -> ComponentName.unflattenFromString(entry.value)
                ?.let { packageManager.getActivityIcon(it) }
            else -> Intent.parseUri(entry.value, 0)
                .resolveActivity(packageManager)
                ?.let { packageManager.getActivityIcon(it) }
        }
    } catch (_: Exception) {
        null
    }

    private fun sourceLabel(entry: Entry): String {
        val override = AppPrefs.iconOverride(this, IconPacks.overrideKey(entry.type, entry.value))
        return when {
            override == null ->
                if (entry.type == PanelItemType.APP && AppPrefs.iconPackEnabled(this)) "Gói icon" else "Mặc định"
            override.startsWith("IMG|") -> "Ảnh riêng"
            else -> "Icon từ gói"
        }
    }

    private inner class IconAdapter : BaseAdapter() {
        private var shown: List<Entry> = entries
        private val icons = HashMap<String, Bitmap?>()

        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int): Long = shown[position].value.hashCode().toLong()
        fun visible(): List<Entry> = shown

        fun setEntries(list: MutableList<Entry>) {
            shown = list
            icons.clear()
            notifyDataSetChanged()
        }

        fun filter(query: String) {
            shown = if (query.isBlank()) entries else entries.filter {
                it.label.contains(query, ignoreCase = true)
            }
            icons.clear()
            notifyDataSetChanged()
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val entry = shown[position]
            val row = convertView as? LinearLayout ?: buildRow()
            val key = IconPacks.overrideKey(entry.type, entry.value)
            val iconView = row.getChildAt(0) as ImageView
            if (icons.size > 256) icons.clear()
            val bitmap = icons.getOrPut(key) {
                val original = originalIcon(entry) ?: packageManager.defaultActivityIcon
                IconPacks.resolve(
                    this@IconOverrideActivity,
                    entry.type,
                    entry.value,
                    original,
                    dp(40)
                )?.let { drawable ->
                    val out = Bitmap.createBitmap(dp(40), dp(40), Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(out)
                    drawable.setBounds(0, 0, dp(40), dp(40))
                    drawable.draw(canvas)
                    out
                }
            }
            if (bitmap != null) iconView.setImageBitmap(bitmap)
            else iconView.setImageDrawable(packageManager.defaultActivityIcon)
            val textColumn = row.getChildAt(1) as LinearLayout
            (textColumn.getChildAt(0) as TextView).text = entry.label
            (textColumn.getChildAt(1) as TextView).text = sourceLabel(entry)
            row.setOnClickListener { showOptions(entry) }
            row.contentDescription = entry.label
            return row
        }

        private fun buildRow(): LinearLayout = LinearLayout(this@IconOverrideActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(10), dp(8))
            background = GlassUi.glassSurface(this@IconOverrideActivity, 14)
            addView(ImageView(this@IconOverrideActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                scaleType = ImageView.ScaleType.FIT_CENTER
            })
            addView(LinearLayout(this@IconOverrideActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
                addView(TextView(this@IconOverrideActivity).apply {
                    textSize = 14f
                    setTextColor(GlassUi.TEXT_PRIMARY)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                addView(TextView(this@IconOverrideActivity).apply {
                    textSize = 11f
                    setTextColor(GlassUi.TEXT_MUTED)
                    setPadding(0, dp(2), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                LucideGlyphView(this@IconOverrideActivity, "chevron-right", GlassUi.TEXT_MUTED),
                LinearLayout.LayoutParams(dp(16), dp(16))
            )
            isClickable = true
            isFocusable = true
        }
    }

    private inner class PackIconAdapter(
        private val packageName: String,
        private val items: List<Pair<String, String>>,
        private val onPick: (String) -> Unit
    ) : BaseAdapter() {
        private val icons = HashMap<String, Bitmap?>()

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = items[position].second.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = convertView as? LinearLayout ?: LinearLayout(this@IconOverrideActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(6), dp(6), dp(6))
                addView(ImageView(this@IconOverrideActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                })
                addView(TextView(this@IconOverrideActivity).apply {
                    textSize = 14f
                    setTextColor(GlassUi.TEXT_PRIMARY)
                    setPadding(dp(12), 0, 0, 0)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            val (label, drawableName) = items[position]
            val image = row.getChildAt(0) as ImageView
            if (icons.size > 96) icons.clear()
            val icon = icons.getOrPut(drawableName) {
                IconPacks.packIconBitmap(this@IconOverrideActivity, packageName, drawableName, dp(40))
            }
            if (icon != null) image.setImageBitmap(icon)
            else image.setImageDrawable(packageManager.defaultActivityIcon)
            (row.getChildAt(1) as TextView).text = label
            row.setOnClickListener { onPick(drawableName) }
            return row
        }
    }

    private fun dp(value: Int): Int = GlassUi.dp(this, value)

    private companion object {
        const val REQUEST_IMAGE = 7401
        const val OPTION_IMAGE = "image"
        const val OPTION_PACK = "pack"
        const val OPTION_RESET = "reset"
    }
}
