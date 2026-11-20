package com.manh.panelsassistive

import android.app.Activity
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.*
import android.content.Context
import android.content.Intent
import android.graphics.ImageDecoder
import android.os.Build
import android.provider.MediaStore
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight 1:1 crop editor for the floating icon.
 * The editor stores transform metadata instead of re-encoding the source GIF,
 * so animated GIFs keep their original frames and are not duplicated on disk.
 */
class FloatBallIconCropActivity : Activity() {
    private lateinit var preview: CropPreviewView
    private lateinit var zoomValue: TextView
    private lateinit var cornerValue: TextView
    private var mode = AppPrefs.FloatBallIconMode.STATIC
    private var sourceUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = if (intent.getStringExtra(EXTRA_MODE) == "gif") {
            AppPrefs.FloatBallIconMode.GIF
        } else AppPrefs.FloatBallIconMode.STATIC
        sourceUri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
        if (sourceUri == null) { finish(); return }

        val bg = GlassUi.PAGE_COLOR
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GlassUi.pageBackground()
            setPadding(dp(18), dp(16), dp(18), dp(18))
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.brand_name)
            textSize = 11f
            setTextColor(GlassUi.ACCENT)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.18f
        })
        root.addView(TextView(this).apply {
            text = if (mode == AppPrefs.FloatBallIconMode.GIF) getString(R.string.crop_gif_title)
            else getString(R.string.crop_static_title)
            textSize = 22f
            setTextColor(GlassUi.TEXT_PRIMARY)
            setPadding(0, dp(6), 0, 0)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.crop_icon_hint)
            textSize = 13f
            setTextColor(GlassUi.TEXT_SECONDARY)
            setPadding(0, dp(6), 0, dp(12))
        })

        preview = CropPreviewView(this)
        val previewHolder = FrameLayout(this).apply {
            background = GlassUi.glassSurface(this@FloatBallIconCropActivity, 24)
            addView(preview, FrameLayout.LayoutParams(dp(300), dp(300), Gravity.CENTER))
        }
        root.addView(previewHolder, LinearLayout.LayoutParams(-1, dp(320)))

        zoomValue = TextView(this).apply {
            setTextColor(GlassUi.TEXT_SECONDARY)
            textSize = 13f
        }
        root.addView(zoomValue)
        val zoomSlider = GlassUi.slider(this).apply {
            max = 300
            progress = 100
            setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val scale = 1f + p / 100f
                    preview.scale = scale
                    zoomValue.text = getString(R.string.crop_zoom_value, scale)
                    preview.invalidate()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        root.addView(zoomSlider)

        cornerValue = TextView(this).apply {
            setTextColor(GlassUi.TEXT_SECONDARY)
            textSize = 13f
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(cornerValue)
        val cornerSlider = GlassUi.slider(this).apply {
            max = 100
            progress = AppPrefs.floatBallCrop(this@FloatBallIconCropActivity, mode).corner
            setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    preview.corner = p
                    cornerValue.text = getString(R.string.crop_corner_value, p)
                    preview.invalidate()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        root.addView(cornerSlider)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        buttons.addView(GlassUi.actionButton(this, getString(android.R.string.cancel)) {
            finish()
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(6) })
        buttons.addView(GlassUi.actionButton(this, getString(R.string.crop_save), prominent = true) {
            saveAndFinish()
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(6) })
        root.addView(buttons, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)

        val saved = AppPrefs.floatBallCrop(this, mode)
        preview.scale = saved.scale
        preview.offsetX = saved.x
        preview.offsetY = saved.y
        preview.corner = saved.corner
        zoomSlider.post {
            zoomSlider.progress = ((saved.scale - 1f) * 100f).toInt().coerceIn(0, 300)
        }
        cornerValue.text = getString(R.string.crop_corner_value, saved.corner)
        zoomValue.text = getString(R.string.crop_zoom_value, saved.scale)
        preview.load(sourceUri!!)
    }

    private fun saveAndFinish() {
        AppPrefs.setFloatBallCrop(this, mode, preview.scale, preview.offsetX, preview.offsetY, preview.corner)
        setResult(RESULT_OK)
        if (FloatBallService.running) {
            startService(Intent(this, FloatBallService::class.java).apply {
                action = FloatBallService.ACTION_UPDATE_FLOAT_BALL
            })
        }
        finish()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private class CropPreviewView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private var bitmap: Bitmap? = null
        var scale = 1f
        var offsetX = 0f
        var offsetY = 0f
        var corner = 70
        private var lastX = 0f
        private var lastY = 0f
        private var dragging = false

        fun load(uri: Uri) {
            bitmap = try {
                if (Build.VERSION.SDK_INT >= 28) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                        val target = 600
                        val s = min(target.toFloat()/info.size.width, target.toFloat()/info.size.height)
                        decoder.setTargetSize((info.size.width*s).toInt().coerceAtLeast(1), (info.size.height*s).toInt().coerceAtLeast(1))
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
                    }
                } else {
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                }
            } catch (_: Exception) { null }
            invalidate()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX=event.x; lastY=event.y; dragging=true; return true }
                MotionEvent.ACTION_MOVE -> if (dragging) {
                    val w = width.toFloat().coerceAtLeast(1f)
                    val h = height.toFloat().coerceAtLeast(1f)
                    offsetX = (offsetX + (event.x-lastX)/w*2f).coerceIn(-1.5f,1.5f)
                    offsetY = (offsetY + (event.y-lastY)/h*2f).coerceIn(-1.5f,1.5f)
                    lastX=event.x; lastY=event.y; invalidate(); return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging=false; return true }
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val b = bitmap ?: return
            val side = min(width,height).toFloat()
            val cx = width/2f + offsetX*side/2f
            val cy = height/2f + offsetY*side/2f
            val fit = max(side/b.width, side/b.height) * scale
            val dw=b.width*fit; val dh=b.height*fit
            val path=Path().apply { addRoundRect(RectF(0f,0f,width.toFloat(),height.toFloat()), side*corner/200f, side*corner/200f, Path.Direction.CW) }
            canvas.save(); canvas.clipPath(path)
            canvas.drawBitmap(b,null,RectF(cx-dw/2,cy-dh/2,cx+dw/2,cy+dh/2),paint)
            canvas.restore()
            paint.style=Paint.Style.STROKE; paint.strokeWidth=dp(1.0f); paint.color=Color.argb(100,255,255,255)
            canvas.drawRoundRect(RectF(.5f,.5f,width-.5f,height-.5f),side*corner/200f,side*corner/200f,paint)
            paint.style=Paint.Style.FILL
        }
        private fun dp(v: Float)=v*resources.displayMetrics.density
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_URI = "uri"
    }
}
