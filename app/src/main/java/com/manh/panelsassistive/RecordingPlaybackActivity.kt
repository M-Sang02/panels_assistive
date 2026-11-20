package com.manh.panelsassistive

import android.app.Activity
import android.app.Dialog
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class RecordingPlaybackActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var seekBar: SeekBar? = null
    private var positionLabel: TextView? = null
    private var playGlyph: LucideGlyphView? = null
    private var prepared = false
    private var isSeeking = false

    private val progressTicker = object : Runnable {
        override fun run() {
            val current = player
            if (prepared && current != null) {
                if (!isSeeking) {
                    seekBar?.progress = current.currentPosition
                    updatePosition(current.currentPosition)
                }
                if (current.isPlaying) handler.postDelayed(this, TICK_INTERVAL)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = GlassUi.PAGE_COLOR
        window.navigationBarColor = GlassUi.PAGE_COLOR
        if (intent.action == ACTION_SHOW_RECORDINGS) {
            setContentView(createLibraryContent())
            window.setLayout(dp(360), dp(520))
            return
        }
        val recordingUri = intent.data
        if (recordingUri == null) {
            finish()
            return
        }
        setContentView(createPlayerContent())
        window.setLayout(dp(340), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
        preparePlayer(recordingUri)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun createPlayerContent(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = panelBackground()
            addView(LinearLayout(this@RecordingPlaybackActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(GlassUi.iconTile(
                    this@RecordingPlaybackActivity,
                    "audio-lines",
                    sizeDp = 36,
                    glyphDp = 20
                ))
                addView(LinearLayout(this@RecordingPlaybackActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), 0, dp(8), 0)
                    addView(text(getString(R.string.recording_player_title), 16, GlassUi.TEXT_PRIMARY, true))
                    addView(text("Bảng ghi âm nhanh", 11, GlassUi.TEXT_MUTED).apply {
                        setPadding(0, dp(2), 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(closeButton())
            })
            val card = GlassUi.glassCard(this@RecordingPlaybackActivity).apply {
                setPadding(dp(14), dp(12), dp(14), dp(12))
                positionLabel = text(getString(R.string.recording_player_loading), 12, GlassUi.TEXT_SECONDARY)
                    .also { addView(it) }
                seekBar = GlassUi.slider(this@RecordingPlaybackActivity).apply {
                    max = 1
                    isEnabled = false
                    progressTintList = android.content.res.ColorStateList.valueOf(GlassUi.ACCENT)
                    progressBackgroundTintList =
                        android.content.res.ColorStateList.valueOf(Color.argb(40, 255, 255, 255))
                    thumbTintList = android.content.res.ColorStateList.valueOf(GlassUi.ACCENT)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                            if (fromUser) updatePosition(progress)
                        }

                        override fun onStartTrackingTouch(bar: SeekBar?) {
                            isSeeking = true
                        }

                        override fun onStopTrackingTouch(bar: SeekBar?) {
                            isSeeking = false
                            player?.seekTo(bar?.progress ?: 0)
                        }
                    })
                }.also { addView(it) }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(14) }
            }
            addView(card)
            addView(LinearLayout(this@RecordingPlaybackActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, dp(2))
                addView(glyphButton("rotate-ccw", 48, 20, getString(R.string.stop_playback)) {
                    stopPlayback()
                }, LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                    setMargins(dp(6), 0, dp(6), 0)
                })
                val glyph = LucideGlyphView(this@RecordingPlaybackActivity, "play", Color.rgb(6, 18, 31))
                playGlyph = glyph
                addView(FrameLayout(this@RecordingPlaybackActivity).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(GlassUi.ACCENT)
                    }
                    isClickable = true
                    isFocusable = true
                    contentDescription = getString(R.string.play_recording)
                    addView(glyph, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
                    setOnClickListener { togglePlayback() }
                }, LinearLayout.LayoutParams(dp(64), dp(64)).apply {
                    setMargins(dp(6), 0, dp(6), 0)
                })
                addView(glyphButton("folder-open", 48, 20, getString(R.string.open_recording_file)) {
                    openRecordingLibrary()
                }, LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                    setMargins(dp(6), 0, dp(6), 0)
                })
            })
        }

    private fun createLibraryContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            background = panelBackground()
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(GlassUi.iconTile(this@RecordingPlaybackActivity, "list-music", sizeDp = 36, glyphDp = 20))
            addView(LinearLayout(this@RecordingPlaybackActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, dp(8), 0)
                addView(text(getString(R.string.recording_library_title), 16, GlassUi.TEXT_PRIMARY, true))
                addView(text("Bảng ghi âm nhanh", 11, GlassUi.TEXT_MUTED).apply {
                    setPadding(0, dp(2), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(closeButton())
        })
        val location = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${Environment.DIRECTORY_MUSIC}/Panels Assistive"
        } else {
            File(
                getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir,
                "Panels Assistive"
            ).absolutePath
        }
        root.addView(text(getString(R.string.recording_library_location, location), 11, GlassUi.TEXT_MUTED).apply {
            setPadding(dp(2), dp(8), 0, dp(10))
        })

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val recordings = loadRecordings()
        if (recordings.isEmpty()) {
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(40), dp(18), dp(34))
                addView(GlassUi.iconTile(this@RecordingPlaybackActivity, "mic", sizeDp = 44, glyphDp = 22))
                addView(text(getString(R.string.recording_library_empty), 13, GlassUi.TEXT_SECONDARY).apply {
                    setPadding(0, dp(12), 0, 0)
                })
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        } else {
            recordings.forEach { recording ->
                list.addView(recordingRow(recording), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(66)
                ).apply {
                    topMargin = dp(6)
                })
            }
        }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        return root
    }

    private fun loadRecordings(): List<RecordingEntry> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val directory = File(
                getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir,
                "Panels Assistive"
            )
            return directory.listFiles()
                .orEmpty()
                .filter { it.isFile && it.name.startsWith("PanelsAssistive_", ignoreCase = true) }
                .sortedByDescending { it.lastModified() }
                .map { file ->
                    RecordingEntry(
                        Uri.fromFile(file),
                        file.name,
                        file.lastModified(),
                        0L
                    )
                }
        }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DURATION,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        val recordingDirectory = "${Environment.DIRECTORY_MUSIC}/Panels Assistive"
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("$recordingDirectory%")
        return try {
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                buildList {
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameColumn) ?: continue
                        if (!name.startsWith("PanelsAssistive_", ignoreCase = true)) continue
                        val path = cursor.getString(pathColumn)?.trimEnd('/')
                        if (path != recordingDirectory) continue
                        val id = cursor.getLong(idColumn)
                        add(RecordingEntry(
                            ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                            name,
                            cursor.getLong(dateColumn) * 1000L,
                            cursor.getLong(durationColumn)
                        ))
                    }
                }
            }.orEmpty()
        } catch (error: SecurityException) {
            Log.e(TAG, "Unable to access saved recordings", error)
            Toast.makeText(this, R.string.recording_library_load_failed, Toast.LENGTH_LONG).show()
            emptyList()
        }
    }

    private fun recordingRow(recording: RecordingEntry) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(6), dp(8), dp(6))
        background = GlassUi.glassSurface(this@RecordingPlaybackActivity, 16)
        isClickable = true
        isFocusable = true
        addView(GlassUi.iconTile(this@RecordingPlaybackActivity, "file-audio", sizeDp = 38, glyphDp = 20))
        addView(LinearLayout(this@RecordingPlaybackActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(8), 0)
            addView(text(recording.name, 13, GlassUi.TEXT_PRIMARY, true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            val date = DateFormat.getDateTimeInstance(
                DateFormat.MEDIUM,
                DateFormat.SHORT,
                Locale.getDefault()
            ).format(Date(recording.dateAddedMillis))
            val metadata = if (recording.durationMillis > 0) {
                getString(
                    R.string.recording_library_metadata,
                    date,
                    formatTime(recording.durationMillis.toInt())
                )
            } else {
                date
            }
            addView(text(metadata, 11, GlassUi.TEXT_MUTED).apply { setPadding(0, dp(2), 0, 0) })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(glyphButton(
            "more-vertical",
            36,
            18,
            getString(R.string.recording_more_actions)
        ) { showRecordingActions(recording) }, LinearLayout.LayoutParams(dp(38), dp(38)))
        setOnClickListener { openRecording(recording) }
    }

    private fun showRecordingActions(recording: RecordingEntry) {
        lateinit var content: LinearLayout
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(14))
            background = dialogPanelBackground()
            addView(text(recording.name, 15, GlassUi.TEXT_PRIMARY, true).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(text("Chọn thao tác cho bản ghi này.", 12, GlassUi.TEXT_MUTED).apply {
                setPadding(0, dp(4), 0, dp(10))
            })
            addView(GlassUi.actionButton(this@RecordingPlaybackActivity, getString(R.string.share_recording)) {
                dialogFor(content)?.dismiss()
                shareRecording(recording)
            })
            addView(GlassUi.actionButton(this@RecordingPlaybackActivity, getString(R.string.delete_recording)) {
                dialogFor(content)?.dismiss()
                confirmDeleteRecording(recording)
            }.apply { setTextColor(DANGER) })
            addView(text(getString(android.R.string.cancel), 13, GlassUi.TEXT_SECONDARY).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(2))
                isClickable = true
                setOnClickListener { dialogFor(content)?.dismiss() }
            })
        }
        showGlassDialog(content)
    }

    private fun confirmDeleteRecording(recording: RecordingEntry) {
        lateinit var content: LinearLayout
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(14))
            background = dialogPanelBackground()
            addView(text("Xóa bản ghi?", 16, GlassUi.TEXT_PRIMARY, true))
            addView(text(getString(R.string.delete_recording_confirm), 13, GlassUi.TEXT_SECONDARY).apply {
                setPadding(0, dp(6), 0, dp(12))
            })
            addView(GlassUi.actionButton(
                this@RecordingPlaybackActivity,
                getString(R.string.delete_recording),
                prominent = false
            ) {
                dialogFor(content)?.dismiss()
                val deleted = if (recording.uri.scheme == ContentResolver.SCHEME_CONTENT) {
                    contentResolver.delete(recording.uri, null, null) > 0
                } else {
                    recording.uri.path?.let { File(it).delete() } == true
                }
                if (deleted) {
                    if (intent.action == ACTION_SHOW_RECORDINGS) setContentView(createLibraryContent())
                    else finish()
                } else {
                    Toast.makeText(this@RecordingPlaybackActivity, R.string.recording_library_load_failed, Toast.LENGTH_LONG).show()
                }
            }.apply { setTextColor(DANGER) })
            addView(text(getString(android.R.string.cancel), 13, GlassUi.TEXT_SECONDARY).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(2))
                isClickable = true
                setOnClickListener { dialogFor(content)?.dismiss() }
            })
        }
        showGlassDialog(content)
    }

    private fun showGlassDialog(content: LinearLayout) {
        val dialog = Dialog(this)
        dialog.setContentView(content)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dp(312), ViewGroup.LayoutParams.WRAP_CONTENT)
            attributes = attributes.apply { dimAmount = 0.45f }
        }
        content.tag = dialog
        dialog.show()
    }

    private fun dialogFor(view: View): Dialog? = view.tag as? Dialog

    private fun openRecording(recording: RecordingEntry) {
        startActivity(Intent(this, RecordingPlaybackActivity::class.java).setData(recording.uri))
    }

    private fun preparePlayer(uri: Uri) {
        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        mediaPlayer.setOnPreparedListener { ready ->
            prepared = true
            seekBar?.max = ready.duration.coerceAtLeast(1)
            seekBar?.isEnabled = true
            updatePosition(0)
        }
        mediaPlayer.setOnCompletionListener {
            it.seekTo(0)
            setPlayGlyph("play", getString(R.string.play_recording))
            handler.removeCallbacks(progressTicker)
            updatePosition(0)
        }
        mediaPlayer.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, R.string.recording_playback_failed, Toast.LENGTH_LONG).show()
            finish()
            true
        }
        try {
            mediaPlayer.setDataSource(this, uri)
            mediaPlayer.prepareAsync()
        } catch (error: IOException) {
            Toast.makeText(this, R.string.recording_playback_failed, Toast.LENGTH_LONG).show()
            finish()
        } catch (error: SecurityException) {
            Toast.makeText(this, R.string.recording_playback_failed, Toast.LENGTH_LONG).show()
            finish()
        } catch (error: IllegalArgumentException) {
            Toast.makeText(this, R.string.recording_playback_failed, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun togglePlayback() {
        val current = player ?: return
        if (!prepared) return
        if (current.isPlaying) {
            current.pause()
            setPlayGlyph("play", getString(R.string.play_recording))
            handler.removeCallbacks(progressTicker)
        } else {
            current.start()
            setPlayGlyph("pause", getString(R.string.pause_recording))
            handler.removeCallbacks(progressTicker)
            handler.post(progressTicker)
        }
    }

    private fun stopPlayback() {
        val current = player ?: return
        if (!prepared) return
        if (current.isPlaying) current.pause()
        current.seekTo(0)
        setPlayGlyph("play", getString(R.string.play_recording))
        handler.removeCallbacks(progressTicker)
        seekBar?.progress = 0
        updatePosition(0)
    }

    private fun openRecordingLibrary() {
        player?.let { current ->
            if (prepared && current.isPlaying) {
                current.pause()
                setPlayGlyph("play", getString(R.string.play_recording))
                handler.removeCallbacks(progressTicker)
            }
        }
        startActivity(Intent(this, RecordingPlaybackActivity::class.java).apply {
            action = ACTION_SHOW_RECORDINGS
        })
    }

    private fun shareRecording(recording: RecordingEntry) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = contentResolver.getType(recording.uri) ?: "audio/*"
            putExtra(Intent.EXTRA_STREAM, recording.uri)
            clipData = android.content.ClipData.newUri(
                contentResolver,
                recording.name,
                recording.uri
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_recording)))
        } catch (error: android.content.ActivityNotFoundException) {
            Log.e(TAG, "No app can receive this recording", error)
            Toast.makeText(this, R.string.share_recording_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setPlayGlyph(icon: String, description: String) {
        playGlyph?.setIconName(icon)
        playGlyph?.parent?.let { parent ->
            (parent as? View)?.contentDescription = description
        }
    }

    private fun updatePosition(position: Int) {
        val duration = player?.duration ?: 0
        positionLabel?.text = getString(
            R.string.recording_playback_position,
            formatTime(position),
            formatTime(duration)
        )
    }

    private fun formatTime(milliseconds: Int): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1000
        return String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60)
    }

    private fun closeButton(): FrameLayout = glyphButton(
        "x",
        36,
        17,
        getString(R.string.close)
    ) { finish() }.apply {
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
    }

    private fun glyphButton(
        icon: String,
        sizeDp: Int,
        glyphDp: Int,
        description: String,
        onClick: () -> Unit
    ): FrameLayout = FrameLayout(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(24, 224, 236, 255))
        }
        contentDescription = description
        isClickable = true
        isFocusable = true
        addView(
            LucideGlyphView(this@RecordingPlaybackActivity, icon, GlassUi.TEXT_PRIMARY),
            FrameLayout.LayoutParams(dp(glyphDp), dp(glyphDp), Gravity.CENTER)
        )
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun panelBackground() = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(0xFF0A1728.toInt(), 0xFF0D1E30.toInt(), 0xFF08121F.toInt())
    ).apply {
        cornerRadius = dp(24).toFloat()
        setStroke(dp(1), Color.argb(90, 124, 190, 255))
    }

    private fun dialogPanelBackground() = GradientDrawable().apply {
        setColor(Color.argb(246, 16, 31, 49))
        cornerRadius = dp(22).toFloat()
        setStroke(dp(1), Color.argb(110, 124, 190, 255))
    }

    private fun text(value: String, sizeSp: Int, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = value
            textSize = sizeSp.toFloat()
            setTextColor(color)
            includeFontPadding = false
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_SHOW_RECORDINGS = "com.manh.panelsassistive.action.SHOW_RECORDINGS"
        private const val TAG = "RecordingPlayback"
        private const val TICK_INTERVAL = 300L
        private val DANGER = Color.rgb(255, 122, 122)
    }

    private data class RecordingEntry(
        val uri: Uri,
        val name: String,
        val dateAddedMillis: Long,
        val durationMillis: Long
    )
}
