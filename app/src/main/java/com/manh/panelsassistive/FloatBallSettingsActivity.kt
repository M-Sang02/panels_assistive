package com.manh.panelsassistive

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class FloatBallSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = GlassUi.PAGE_COLOR
        window.navigationBarColor = GlassUi.PAGE_COLOR
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(28))
            background = GlassUi.pageBackground()
        }
        root.addView(GlassUi.backBar(this) { finish() })
        root.addView(GlassUi.pageHeader(
            this,
            getString(R.string.float_ball_title),
            "Biểu tượng, kích thước, chạy ngầm và ẩn cạnh."
        ))
        root.addView(iconCard())
        root.addView(displayCard())
        root.addView(behaviorCard())
        root.addView(edgeHideCard())
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun iconCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(cardTitle("Biểu tượng"))
        addView(hint(currentIconSummary()))
        addView(GlassUi.actionButton(this@FloatBallSettingsActivity, getString(R.string.float_ball_static_image)) {
            pickStaticImage()
        })
        addView(hint(getString(R.string.float_ball_static_image_hint)))
        addView(GlassUi.actionButton(this@FloatBallSettingsActivity, getString(R.string.float_ball_gif_image)) {
            pickGifImage()
        })
        val cropButton = GlassUi.actionButton(
            this@FloatBallSettingsActivity,
            getString(R.string.crop_float_ball_icon)
        ) { openCrop(AppPrefs.floatBallIconMode(this@FloatBallSettingsActivity)) }
        cropButton.visibility = if (
            (AppPrefs.floatBallIconMode(this@FloatBallSettingsActivity) == AppPrefs.FloatBallIconMode.GIF &&
                AppPrefs.floatBallGifUri(this@FloatBallSettingsActivity) != null) ||
            (AppPrefs.floatBallIconMode(this@FloatBallSettingsActivity) == AppPrefs.FloatBallIconMode.STATIC &&
                AppPrefs.floatBallStaticUris(this@FloatBallSettingsActivity).isNotEmpty())
        ) View.VISIBLE else View.GONE
        addView(cropButton)
        addView(hint(getString(R.string.float_ball_gif_hint)))
        addView(GlassUi.actionButton(
            this@FloatBallSettingsActivity,
            getString(R.string.reset_float_ball_icon)
        ) {
            GlassDialogs.confirm(
                this@FloatBallSettingsActivity,
                getString(R.string.reset_float_ball_icon),
                getString(R.string.reset_float_ball_icon_confirmation)
            ) {
                AppPrefs.clearFloatBallIcon(this@FloatBallSettingsActivity)
                updateFloatBall()
                render()
            }
        })
        val gifControls = gifAnimationControls().apply {
            visibility = if (
                AppPrefs.floatBallIconMode(this@FloatBallSettingsActivity) == AppPrefs.FloatBallIconMode.GIF
            ) View.VISIBLE else View.GONE
        }
        val slideshowControls = slideshowControls().apply {
            visibility = if (
                AppPrefs.floatBallIconMode(this@FloatBallSettingsActivity) == AppPrefs.FloatBallIconMode.STATIC &&
                AppPrefs.floatBallStaticUris(this@FloatBallSettingsActivity).size >= 2
            ) View.VISIBLE else View.GONE
        }
        addView(gifControls)
        addView(slideshowControls)
    }

    private fun currentIconSummary(): String = when (AppPrefs.floatBallIconMode(this)) {
        AppPrefs.FloatBallIconMode.GIF ->
            if (AppPrefs.floatBallGifUri(this) != null) "Đang dùng ảnh GIF động." else "Chưa chọn ảnh GIF."
        AppPrefs.FloatBallIconMode.STATIC -> {
            val count = AppPrefs.floatBallStaticUris(this).size
            when {
                count > 1 -> "Đang dùng $count ảnh tĩnh (có thể bật trình chiếu)."
                count == 1 -> "Đang dùng 1 ảnh tĩnh."
                else -> "Đang dùng biểu tượng mặc định."
            }
        }
    }

    private fun displayCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(cardTitle(getString(R.string.float_ball_size).replaceFirstChar { it.uppercase() }))
        val sizeValue = GlassUi.bodyText(this@FloatBallSettingsActivity, "", 13f, GlassUi.ACCENT, true)
        var size = AppPrefs.floatBallSize(this@FloatBallSettingsActivity)
        sizeValue.text = "${size}dp"
        addView(sizeValue)
        addView(GlassUi.slider(this@FloatBallSettingsActivity).apply {
            max = 60
            progress = size - 20
            setOnSeekBarChangeListener(simpleListener { value ->
                size = value + 20
                sizeValue.text = "${size}dp"
                AppPrefs.setFloatBallSize(this@FloatBallSettingsActivity, size)
                updateFloatBall()
            })
        })
        addView(label(getString(R.string.float_ball_opacity), 14, GlassUi.TEXT_SECONDARY).apply {
            setPadding(0, dp(12), 0, 0)
        })
        val opacityValue = GlassUi.bodyText(this@FloatBallSettingsActivity, "", 13f, GlassUi.ACCENT, true)
        opacityValue.text = "${AppPrefs.floatBallOpacity(this@FloatBallSettingsActivity)}%"
        addView(opacityValue)
        addView(GlassUi.slider(this@FloatBallSettingsActivity).apply {
            max = 85
            progress = AppPrefs.floatBallOpacity(this@FloatBallSettingsActivity) - 15
            setOnSeekBarChangeListener(simpleListener { value ->
                AppPrefs.setFloatBallOpacity(this@FloatBallSettingsActivity, value + 15)
                opacityValue.text = "${value + 15}%"
                updateFloatBall()
            })
        })
        addView(label(getString(R.string.float_ball_corner), 14, GlassUi.TEXT_SECONDARY).apply {
            setPadding(0, dp(12), 0, 0)
        })
        val cornerValue = GlassUi.bodyText(this@FloatBallSettingsActivity, "", 13f, GlassUi.ACCENT, true)
        cornerValue.text = "${AppPrefs.floatBallCorner(this@FloatBallSettingsActivity)}%"
        addView(cornerValue)
        addView(GlassUi.slider(this@FloatBallSettingsActivity).apply {
            max = 100
            progress = AppPrefs.floatBallCorner(this@FloatBallSettingsActivity)
            setOnSeekBarChangeListener(simpleListener { value ->
                AppPrefs.setFloatBallCorner(this@FloatBallSettingsActivity, value)
                cornerValue.text = "$value%"
                updateFloatBall()
            })
        })
    }

    private fun behaviorCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(cardTitle("Hành vi"))
        addView(GlassUi.switch(
            this@FloatBallSettingsActivity,
            AppPrefs.startOnBoot(this@FloatBallSettingsActivity)
        ) { enabled -> AppPrefs.setStartOnBoot(this@FloatBallSettingsActivity, enabled) }.apply {
            text = getString(R.string.start_on_boot)
            setTextColor(GlassUi.TEXT_PRIMARY)
        })
        addView(hint(getString(R.string.start_on_boot_description)))
        addView(GlassUi.switch(
            this@FloatBallSettingsActivity,
            AppPrefs.snapToEdge(this@FloatBallSettingsActivity)
        ) { enabled -> AppPrefs.setSnapToEdge(this@FloatBallSettingsActivity, enabled) }.apply {
            text = getString(R.string.snap_to_edge)
            setTextColor(GlassUi.TEXT_PRIMARY)
        })
        addView(hint(getString(R.string.snap_to_edge_description)))
        val idleControls = idleDimControls().apply {
            visibility = if (AppPrefs.dimWhenIdle(this@FloatBallSettingsActivity)) View.VISIBLE else View.GONE
        }
        addView(GlassUi.switch(
            this@FloatBallSettingsActivity,
            AppPrefs.dimWhenIdle(this@FloatBallSettingsActivity)
        ) { enabled ->
            AppPrefs.setDimWhenIdle(this@FloatBallSettingsActivity, enabled)
            idleControls.visibility = if (enabled) View.VISIBLE else View.GONE
            updateFloatBall()
        }.apply {
            text = getString(R.string.dim_when_idle)
            setTextColor(GlassUi.TEXT_PRIMARY)
        })
        addView(hint(getString(R.string.dim_when_idle_description)))
        addView(idleControls)
    }

    private fun idleDimControls(): LinearLayout {
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val opacityValue = label(
            getString(R.string.idle_opacity_value, AppPrefs.idleOpacity(this)),
            13,
            GlassUi.ACCENT,
            true
        )
        controls.addView(label(getString(R.string.idle_opacity), 13, GlassUi.TEXT_SECONDARY))
        controls.addView(opacityValue)
        controls.addView(GlassUi.slider(this).apply {
            max = 75
            progress = AppPrefs.idleOpacity(this@FloatBallSettingsActivity) - 5
            setOnSeekBarChangeListener(simpleListener { value ->
                val opacity = value + 5
                AppPrefs.setIdleOpacity(this@FloatBallSettingsActivity, opacity)
                opacityValue.text = getString(R.string.idle_opacity_value, opacity)
                updateFloatBall()
            })
        })
        val delayValue = label(
            getString(R.string.idle_delay_value, AppPrefs.idleDelaySeconds(this)),
            13,
            GlassUi.ACCENT,
            true
        )
        controls.addView(label(getString(R.string.idle_delay), 13, GlassUi.TEXT_SECONDARY).apply {
            setPadding(0, dp(8), 0, 0)
        })
        controls.addView(delayValue)
        controls.addView(GlassUi.slider(this).apply {
            max = 59
            progress = AppPrefs.idleDelaySeconds(this@FloatBallSettingsActivity) - 1
            setOnSeekBarChangeListener(simpleListener { value ->
                val seconds = value + 1
                AppPrefs.setIdleDelaySeconds(this@FloatBallSettingsActivity, seconds)
                delayValue.text = getString(R.string.idle_delay_value, seconds)
                updateFloatBall()
            })
        })
        controls.addView(GlassUi.switch(
            this,
            AppPrefs.freezeAnimationWhenIdle(this)
        ) { enabled ->
            AppPrefs.setFreezeAnimationWhenIdle(this, enabled)
            updateFloatBall()
        }.apply {
            text = getString(R.string.freeze_animation_when_idle)
            setTextColor(GlassUi.TEXT_PRIMARY)
        })
        return controls
    }

    private fun edgeHideCard(): LinearLayout = GlassUi.glassCard(this).apply {
        addView(cardTitle(getString(R.string.float_ball_edge_hide)))
        addView(hint(getString(R.string.float_ball_edge_hide_description)))
        val options = LinearLayout(this@FloatBallSettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (AppPrefs.floatBallEdgeHideEnabled(this@FloatBallSettingsActivity)) {
                View.VISIBLE
            } else View.GONE
        }
        addView(GlassUi.switch(
            this@FloatBallSettingsActivity,
            AppPrefs.floatBallEdgeHideEnabled(this@FloatBallSettingsActivity)
        ) { enabled ->
            AppPrefs.setFloatBallEdgeHideEnabled(this@FloatBallSettingsActivity, enabled)
            options.visibility = if (enabled) View.VISIBLE else View.GONE
            updateFloatBall()
        }.apply {
            text = "Bật ẩn vào thanh bên"
            setTextColor(GlassUi.TEXT_PRIMARY)
        })
        options.addView(orientationSection(landscape = false, title = "Áp dụng cho màn hình dọc"))
        options.addView(orientationSection(landscape = true, title = "Áp dụng cho màn hình ngang"))
        options.addView(GlassUi.switch(
            this@FloatBallSettingsActivity,
            AppPrefs.floatBallAutoMirror(this@FloatBallSettingsActivity)
        ) { enabled ->
            AppPrefs.setFloatBallAutoMirror(this@FloatBallSettingsActivity, enabled)
            updateFloatBall()
        }.apply {
            text = getString(R.string.float_ball_auto_mirror)
            setTextColor(GlassUi.TEXT_PRIMARY)
        })
        options.addView(hint(getString(R.string.float_ball_auto_mirror_description)))
        addView(options)
    }

    private fun orientationSection(landscape: Boolean, title: String): LinearLayout {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        val sliderBlock = edgeSliderBlock(landscape).apply {
            visibility = if (AppPrefs.floatBallEdgeHideForOrientation(this@FloatBallSettingsActivity, landscape)) {
                View.VISIBLE
            } else View.GONE
        }
        section.addView(GlassUi.switch(
            this,
            AppPrefs.floatBallEdgeHideForOrientation(this, landscape)
        ) { enabled ->
            AppPrefs.setFloatBallEdgeHideForOrientation(this, landscape, enabled)
            sliderBlock.visibility = if (enabled) View.VISIBLE else View.GONE
            updateFloatBall()
        }.apply {
            text = title
            setTextColor(GlassUi.TEXT_PRIMARY)
        })
        section.addView(sliderBlock)
        return section
    }

    private fun edgeSliderBlock(landscape: Boolean): LinearLayout {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(6))
        }
        val valueLabel = GlassUi.bodyText(this, "", 13f, GlassUi.ACCENT, true)
        val saved = AppPrefs.floatBallEdgeValue(this, landscape)
        valueLabel.text = edgeValueText(saved)
        block.addView(valueLabel)
        val (container, bar) = GlassUi.notchedSlider(this)
        bar.max = 200
        bar.progress = saved + 100
        block.addView(container)
        block.addView(hint("Kéo sang trái hoặc phải để chọn cạnh ẩn. Mốc giữa = không ẩn."))
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                var value = progress - 100
                if (abs(value) <= 5) value = 0
                AppPrefs.setFloatBallEdgeValue(this@FloatBallSettingsActivity, landscape, value)
                valueLabel.text = edgeValueText(value)
                updateFloatBall()
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) {
                val progress = bar?.progress ?: 100
                if (abs(progress - 100) <= 5) bar?.progress = 100
            }
        })
        return block
    }

    private fun edgeValueText(value: Int): String = when {
        value == 0 -> "Không ẩn — bóng nổi giữ nguyên vị trí."
        value < 0 -> "Ẩn cạnh trái ${abs(value)}%."
        else -> "Ẩn cạnh phải $value%."
    }

    private fun gifAnimationControls(): LinearLayout {
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val idleOptions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        controls.addView(GlassUi.switch(
            this,
            AppPrefs.floatBallGifPlayEnabled(this)
        ) { enabled ->
            AppPrefs.setFloatBallGifPlayEnabled(this, enabled)
            idleOptions.visibility = if (enabled && AppPrefs.floatBallGifPauseWhenIdle(this)) {
                View.VISIBLE
            } else View.GONE
            updateFloatBall()
        }.apply {
            text = getString(R.string.float_ball_play_gif)
            setTextColor(GlassUi.TEXT_PRIMARY)
        })
        addHint(controls, getString(R.string.float_ball_play_gif_description))
        controls.addView(GlassUi.switch(
            this,
            AppPrefs.floatBallGifPauseWhenIdle(this)
        ) { enabled ->
            AppPrefs.setFloatBallGifPauseWhenIdle(this, enabled)
            idleOptions.visibility = if (AppPrefs.floatBallGifPlayEnabled(this) && enabled) {
                View.VISIBLE
            } else View.GONE
            updateFloatBall()
        }.apply {
            text = getString(R.string.float_ball_gif_pause_idle)
            setTextColor(GlassUi.TEXT_PRIMARY)
        })
        addHint(controls, getString(R.string.float_ball_gif_pause_idle_description))
        val idleValue = label(
            getString(R.string.float_ball_gif_idle_seconds, AppPrefs.floatBallGifIdleSeconds(this)),
            13,
            GlassUi.ACCENT,
            true
        )
        idleOptions.addView(idleValue)
        idleOptions.addView(GlassUi.slider(this).apply {
            max = 25
            progress = AppPrefs.floatBallGifIdleSeconds(this@FloatBallSettingsActivity) - 5
            setOnSeekBarChangeListener(simpleListener { value ->
                val seconds = value + 5
                AppPrefs.setFloatBallGifIdleSeconds(this@FloatBallSettingsActivity, seconds)
                idleValue.text = getString(R.string.float_ball_gif_idle_seconds, seconds)
                updateFloatBall()
            })
        })
        idleOptions.visibility = if (
            AppPrefs.floatBallGifPlayEnabled(this) && AppPrefs.floatBallGifPauseWhenIdle(this)
        ) View.VISIBLE else View.GONE
        controls.addView(idleOptions)
        return controls
    }

    private fun slideshowControls(): LinearLayout {
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        controls.addView(GlassUi.switch(
            this,
            AppPrefs.floatBallSlideshowEnabled(this)
        ) { enabled ->
            AppPrefs.setFloatBallSlideshowEnabled(this, enabled)
            updateFloatBall()
        }.apply {
            text = getString(R.string.float_ball_slideshow)
            setTextColor(GlassUi.TEXT_PRIMARY)
        })
        addHint(controls, getString(R.string.float_ball_slideshow_description))
        val intervalValue = label(
            getString(R.string.float_ball_slideshow_interval, AppPrefs.floatBallSlideshowIntervalSeconds(this)),
            13,
            GlassUi.ACCENT,
            true
        )
        controls.addView(intervalValue)
        controls.addView(GlassUi.slider(this).apply {
            max = 295
            progress = AppPrefs.floatBallSlideshowIntervalSeconds(this@FloatBallSettingsActivity) - 5
            setOnSeekBarChangeListener(simpleListener { value ->
                val seconds = value + 5
                AppPrefs.setFloatBallSlideshowIntervalSeconds(this@FloatBallSettingsActivity, seconds)
                intervalValue.text = getString(R.string.float_ball_slideshow_interval, seconds)
                updateFloatBall()
            })
        })
        controls.addView(GlassUi.switch(
            this,
            AppPrefs.floatBallSlideshowPauseWhenScreenOff(this)
        ) { enabled ->
            AppPrefs.setFloatBallSlideshowPauseWhenScreenOff(this, enabled)
            updateFloatBall()
        }.apply {
            text = getString(R.string.float_ball_slideshow_pause_screen_off)
            setTextColor(GlassUi.TEXT_PRIMARY)
        })
        addHint(controls, getString(R.string.float_ball_slideshow_pause_screen_off_description))
        return controls
    }

    private fun simpleListener(onChanged: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChanged(progress)
        }

        override fun onStartTrackingTouch(bar: SeekBar?) = Unit
        override fun onStopTrackingTouch(bar: SeekBar?) = Unit
    }

    private fun pickStaticImage() {
        ImagePicker.start(this, REQUEST_STATIC, multiple = true)
    }

    private fun pickGifImage() {
        ImagePicker.start(this, REQUEST_GIF, mime = "image/gif")
    }

    private fun openCrop(mode: AppPrefs.FloatBallIconMode) {
        val uri = if (mode == AppPrefs.FloatBallIconMode.GIF) AppPrefs.floatBallGifUri(this)
        else AppPrefs.floatBallStaticUris(this).firstOrNull()
        if (uri.isNullOrBlank()) return
        startActivityForResult(Intent(this, FloatBallIconCropActivity::class.java).apply {
            putExtra(FloatBallIconCropActivity.EXTRA_MODE, if (mode == AppPrefs.FloatBallIconMode.GIF) "gif" else "static")
            putExtra(FloatBallIconCropActivity.EXTRA_URI, uri)
        }, REQUEST_CROP)
    }

    @Deprecated("Handles the system picker result on supported Android versions")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CROP) {
            updateFloatBall()
            return
        }
        if ((requestCode != REQUEST_STATIC && requestCode != REQUEST_GIF) || resultCode != RESULT_OK) return
        val uris = buildList {
            data?.data?.let(::add)
            data?.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) add(clip.getItemAt(index).uri)
            }
        }.distinct()
        if (uris.isEmpty()) return
        try {
            uris.forEach { uri ->
                // Best effort: gallery URIs keep working for this session anyway.
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            if (requestCode == REQUEST_GIF) {
                AppPrefs.setFloatBallGifUri(this, uris.first().toString())
                AppPrefs.setFloatBallGifPlayEnabled(this, false)
                openCrop(AppPrefs.FloatBallIconMode.GIF)
            } else {
                val staticUris = uris.filterNot { uri ->
                    contentResolver.getType(uri)?.equals("image/gif", ignoreCase = true) == true ||
                        uri.toString().lowercase().endsWith(".gif")
                }
                if (staticUris.isEmpty()) {
                    Toast.makeText(this, R.string.gif_choose_from_gif_tab, Toast.LENGTH_SHORT).show()
                    return
                }
                AppPrefs.setFloatBallStaticUris(this, staticUris.map(Uri::toString))
                AppPrefs.setFloatBallSlideshowEnabled(this, false)
                openCrop(AppPrefs.FloatBallIconMode.STATIC)
            }
            updateFloatBall()
            render()
        } catch (error: SecurityException) {
            Toast.makeText(this, R.string.icon_permission_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun updateFloatBall() {
        if (FloatBallService.running) {
            startService(Intent(this, FloatBallService::class.java).apply {
                action = FloatBallService.ACTION_UPDATE_FLOAT_BALL
            })
        }
    }

    private fun cardTitle(text: String): TextView =
        GlassUi.cardTitle(this, text).apply {
            setPadding(0, 0, 0, dp(2))
        }

    private fun hint(text: String): TextView = label(text, 12, GlassUi.TEXT_MUTED).apply {
        setPadding(0, dp(4), 0, dp(6))
    }

    private fun addHint(parent: LinearLayout, text: String) {
        parent.addView(hint(text))
    }

    private fun label(
        text: String,
        sizeSp: Int,
        color: Int,
        bold: Boolean = false
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = sizeSp.toFloat()
        setTextColor(color)
        gravity = Gravity.START
        includeFontPadding = false
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun dp(value: Int): Int = GlassUi.dp(this, value)

    companion object {
        private const val REQUEST_STATIC = 101
        private const val REQUEST_GIF = 102
        private const val REQUEST_CROP = 103
    }
}
