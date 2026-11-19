package com.manh.panelsassistive

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Intent
import android.graphics.Color
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
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
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
    private var playButton: ImageButton? = null
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
        if (intent.action == ACTION_SHOW_RECORDINGS) {
            window.statusBarColor = Color.rgb(13, 15, 20)
            window.navigationBarColor = Color.rgb(13, 15, 20)
            setContentView(createLibraryContent())
            window.setLayout(dp(360), dp(520))
            return
        }
        val recordingUri = intent.data
        if (recordingUri == null) {
            finish()
            return
        }
        window.statusBarColor = Color.rgb(13, 15, 20)
        window.navigationBarColor = Color.rgb(13, 15, 20)
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
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = roundedBackground()
            addView(LinearLayout(this@RecordingPlaybackActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@RecordingPlaybackActivity).apply {
                    text = getString(R.string.recording_player_title)
                    textSize = 17f
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, dp(40), 1f))
                addView(ImageButton(this@RecordingPlaybackActivity).apply {
                    setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                    setColorFilter(Color.WHITE)
                    contentDescription = getString(R.string.close)
                    background = controlBackground()
                    setOnClickListener { finish() }
                }, LinearLayout.LayoutParams(dp(40), dp(40)))
            })
            positionLabel = TextView(this@RecordingPlaybackActivity).apply {
                text = getString(R.string.recording_player_loading)
                textSize = 13f
                setTextColor(Color.rgb(166, 173, 185))
                setPadding(0, dp(8), 0, 0)
            }.also { addView(it) }
            seekBar = SeekBar(this@RecordingPlaybackActivity).apply {
                max = 1
                isEnabled = false
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) updatePosition(progress)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {
                        isSeeking = true
                    }

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        isSeeking = false
                        player?.seekTo(seekBar?.progress ?: 0)
                    }
                })
            }.also { addView(it) }
            addView(LinearLayout(this@RecordingPlaybackActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(ImageButton(this@RecordingPlaybackActivity).apply {
                    playButton = this
                    setImageResource(android.R.drawable.ic_media_play)
                    setColorFilter(Color.WHITE)
                    contentDescription = getString(R.string.play_recording)
                    background = controlBackground()
                    setOnClickListener { togglePlayback() }
                }, LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                    setMargins(dp(8), dp(6), dp(8), dp(6))
                })
                addView(ImageButton(this@RecordingPlaybackActivity).apply {
                    setImageResource(R.drawable.ic_media_stop)
                    setColorFilter(Color.WHITE)
                    contentDescription = getString(R.string.stop_playback)
                    background = controlBackground()
                    setOnClickListener { stopPlayback() }
                }, LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                    setMargins(dp(8), dp(6), dp(8), dp(6))
                })
                addView(ImageButton(this@RecordingPlaybackActivity).apply {
                    setImageResource(R.drawable.ic_file_document)
                    setColorFilter(Color.WHITE)
                    contentDescription = getString(R.string.open_recording_file)
                    background = controlBackground()
                    setOnClickListener { openRecordingLibrary() }
                }, LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                    setMargins(dp(8), dp(6), dp(8), dp(6))
                })
            })
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
            playButton?.setImageResource(android.R.drawable.ic_media_play)
            playButton?.contentDescription = getString(R.string.play_recording)
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
            playButton?.setImageResource(android.R.drawable.ic_media_play)
            playButton?.contentDescription = getString(R.string.play_recording)
            handler.removeCallbacks(progressTicker)
        } else {
            current.start()
            playButton?.setImageResource(android.R.drawable.ic_media_pause)
            playButton?.contentDescription = getString(R.string.pause_recording)
            handler.removeCallbacks(progressTicker)
            handler.post(progressTicker)
        }
    }

    private fun stopPlayback() {
        val current = player ?: return
        if (!prepared) return
        if (current.isPlaying) current.pause()
        current.seekTo(0)
        playButton?.setImageResource(android.R.drawable.ic_media_play)
        playButton?.contentDescription = getString(R.string.play_recording)
        handler.removeCallbacks(progressTicker)
        seekBar?.progress = 0
        updatePosition(0)
    }

    private fun openRecordingLibrary() {
        player?.let { current ->
            if (prepared && current.isPlaying) {
                current.pause()
                playButton?.setImageResource(android.R.drawable.ic_media_play)
                playButton?.contentDescription = getString(R.string.play_recording)
                handler.removeCallbacks(progressTicker)
            }
        }
        startActivity(Intent(this, RecordingPlaybackActivity::class.java).apply {
            action = ACTION_SHOW_RECORDINGS
        })
    }

    private fun createLibraryContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = roundedBackground()
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@RecordingPlaybackActivity).apply {
                text = getString(R.string.recording_library_title)
                textSize = 17f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, dp(42), 1f))
            addView(ImageButton(this@RecordingPlaybackActivity).apply {
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                setColorFilter(Color.WHITE)
                contentDescription = getString(R.string.close)
                background = controlBackground()
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
        })
        val location = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${Environment.DIRECTORY_MUSIC}/Panels Assistive"
        } else {
            File(
                getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir,
                "Panels Assistive"
            ).absolutePath
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.recording_library_location, location)
            textSize = 12f
            setTextColor(Color.rgb(166, 173, 185))
            setPadding(0, dp(2), 0, dp(12))
        })

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val recordings = loadRecordings()
        if (recordings.isEmpty()) {
            list.addView(TextView(this).apply {
                text = getString(R.string.recording_library_empty)
                textSize = 14f
                setTextColor(Color.rgb(183, 191, 202))
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(36), dp(18), dp(36))
            })
        } else {
            recordings.forEach { recording ->
                list.addView(recordingRow(recording), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(64)
                ).apply { bottomMargin = dp(6) })
            }
        }
        root.addView(ScrollView(this).apply {
            addView(list)
        }, LinearLayout.LayoutParams(
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
        setPadding(dp(10), dp(6), dp(12), dp(6))
        background = controlBackground()
        isClickable = true
        isFocusable = true
        addView(ImageView(this@RecordingPlaybackActivity).apply {
            setImageResource(R.drawable.ic_file_document)
            setColorFilter(Color.rgb(125, 188, 255))
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
        })
        addView(LinearLayout(this@RecordingPlaybackActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
            addView(TextView(this@RecordingPlaybackActivity).apply {
                text = recording.name
                textSize = 13f
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
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
            addView(TextView(this@RecordingPlaybackActivity).apply {
                text = metadata
                textSize = 11f
                setTextColor(Color.rgb(166, 173, 185))
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(ImageButton(this@RecordingPlaybackActivity).apply {
            setImageResource(android.R.drawable.ic_menu_more)
            setColorFilter(Color.WHITE)
            contentDescription = getString(R.string.recording_more_actions)
            background = controlBackground()
            setOnClickListener { anchor ->
                PopupMenu(this@RecordingPlaybackActivity, anchor).apply {
                    menu.add(getString(R.string.share_recording)).setOnMenuItemClickListener {
                        shareRecording(recording)
                        true
                    }
                    menu.add(getString(R.string.delete_recording)).setOnMenuItemClickListener {
                        confirmDeleteRecording(recording)
                        true
                    }
                }.show()
            }
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
            marginStart = dp(6)
        })
        setOnClickListener {
            startActivity(Intent(this@RecordingPlaybackActivity, RecordingPlaybackActivity::class.java)
                .setData(recording.uri))
        }
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

    private fun confirmDeleteRecording(recording: RecordingEntry) {
        AlertDialog.Builder(this)
            .setMessage(R.string.delete_recording_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete_recording) { _, _ ->
                val deleted = if (recording.uri.scheme == ContentResolver.SCHEME_CONTENT) {
                    contentResolver.delete(recording.uri, null, null) > 0
                } else {
                    recording.uri.path?.let { File(it).delete() } == true
                }
                if (deleted) {
                    if (intent.action == ACTION_SHOW_RECORDINGS) setContentView(createLibraryContent())
                    else finish()
                } else {
                    Toast.makeText(this, R.string.recording_library_load_failed, Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun roundedBackground() = GradientDrawable().apply {
        val landscape = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
            AppPrefs.landscapeWidgetLayoutEnabled(this@RecordingPlaybackActivity)
        val page = AppPrefs.panelPages(this@RecordingPlaybackActivity, landscape)
            .firstOrNull {
                it.id == AppPrefs.currentPanelId(this@RecordingPlaybackActivity, landscape)
            }
        val color = page?.backgroundColor ?: Color.rgb(21, 36, 56)
        val alpha = (page?.backgroundOpacity ?: 78).coerceIn(0, 100) * 255 / 100
        setColor((color and 0x00FFFFFF) or (alpha shl 24))
        cornerRadius = dp(22).toFloat()
        setStroke(dp(1), Color.argb(110, 91, 174, 255))
    }

    private fun controlBackground() = GradientDrawable().apply {
        setColor(Color.rgb(39, 72, 108))
        cornerRadius = dp(18).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_SHOW_RECORDINGS = "com.manh.panelsassistive.action.SHOW_RECORDINGS"
        private const val TAG = "RecordingPlayback"
        private const val TICK_INTERVAL = 300L
    }

    private data class RecordingEntry(
        val uri: Uri,
        val name: String,
        val dateAddedMillis: Long,
        val durationMillis: Long
    )
}
