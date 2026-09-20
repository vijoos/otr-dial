package com.example.otrdial

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.otrdial.databinding.ActivityMainBinding
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var stations: List<Station>
    private lateinit var adapter: StationAdapter
    private val prefs by lazy { getSharedPreferences("otr_dial", MODE_PRIVATE) }
    private var currentStation: Station? = null
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var genre = "All genres"
    private val handler = Handler(Looper.getMainLooper())
    private var sleepRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermissionIfNeeded()
        stations = StationRepository.load(this)
        setupPlayerConnection()
        setupList()
        setupFilters()
        setupControls()
    }

    private fun setupPlayerConnection() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture!!.addListener({
            runCatching { controllerFuture!!.get() }.onSuccess { c ->
                controller = c
                c.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) = updatePlayButton()
                    @androidx.annotation.OptIn(UnstableApi::class)
                    override fun onMetadata(metadata: Metadata) {
                        for (i in 0 until metadata.length()) {
                            val entry = metadata[i]
                            if (entry is IcyInfo && !entry.title.isNullOrBlank()) {
                                binding.currentMetadata.text = entry.title!!.trim()
                            }
                        }
                    }

                    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                        val title = mediaMetadata.title?.toString()?.trim().orEmpty()
                        val artist = mediaMetadata.artist?.toString()?.trim().orEmpty()
                        val raw = listOf(title, artist).filter { it.isNotBlank() }.distinct().joinToString(" — ")
                        if (raw.isNotBlank() && raw != currentStation?.name) binding.currentMetadata.text = raw
                    }
                })
                updatePlayButton()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupList() {
        adapter = StationAdapter(
            onPlay = { playStation(it) },
            isFavourite = { isFavourite(it) },
            onFavourite = { toggleFavourite(it) }
        )
        binding.stationList.layoutManager = LinearLayoutManager(this)
        binding.stationList.adapter = adapter
        applyFilters()
    }

    private fun setupFilters() {
        val genres = listOf("All genres") + stations.map { it.genre }.distinct().sorted()
        binding.genreSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, genres)
        binding.genreSpinner.setSelection(0)
        binding.genreSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                genre = genres[position]
                applyFilters()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        binding.searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = applyFilters()
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun setupControls() {
        binding.playPauseButton.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (c.isPlaying) c.pause() else if (c.currentMediaItem != null) c.play()
        }
        binding.favouriteButton.setOnClickListener { currentStation?.let { toggleFavourite(it) } }
        binding.recordButton.setOnClickListener { toggleRecording() }
        binding.sleepButton.setOnClickListener { showSleepTimer() }
    }

    private fun playStation(station: Station) {
        val c = controller
        if (c == null) {
            Toast.makeText(this, "Player is still starting", Toast.LENGTH_SHORT).show()
            return
        }
        currentStation = station
        binding.currentStation.text = station.name
        binding.currentMetadata.text = "${station.network} • ${station.genre}"
        updateFavouriteButton()

        val metadata = MediaMetadata.Builder()
            .setTitle(station.name)
            .setArtist(station.network)
            .setSubtitle(station.genre)
            .build()
        val item = MediaItem.Builder()
            .setUri(station.streamUrl)
            .setMediaId(station.id)
            .setMediaMetadata(metadata)
            .build()
        c.setMediaItem(item)
        c.prepare()
        c.play()
    }

    private fun applyFilters() {
        val q = binding.searchBox.text?.toString()?.trim()?.lowercase().orEmpty()
        val filtered = stations.filter { s ->
            (genre == "All genres" || s.genre == genre) &&
                (q.isBlank() || listOf(s.name, s.network, s.genre).any { it.lowercase().contains(q) })
        }
        adapter.submit(filtered)
    }

    private fun favouriteIds(): MutableSet<String> =
        prefs.getStringSet("favourites", emptySet())?.toMutableSet() ?: mutableSetOf()

    private fun isFavourite(station: Station): Boolean = favouriteIds().contains(station.id)

    private fun toggleFavourite(station: Station) {
        val ids = favouriteIds()
        if (!ids.add(station.id)) ids.remove(station.id)
        prefs.edit().putStringSet("favourites", ids).apply()
        adapter.notifyDataSetChanged()
        updateFavouriteButton()
    }

    private fun updateFavouriteButton() {
        binding.favouriteButton.text = if (currentStation?.let { isFavourite(it) } == true) "♥" else "♡"
    }

    private fun updatePlayButton() {
        binding.playPauseButton.text = if (controller?.isPlaying == true) "Pause" else "Play"
    }

    private fun toggleRecording() {
        val station = currentStation
        if (station == null) {
            Toast.makeText(this, "Choose a station first", Toast.LENGTH_SHORT).show()
            return
        }
        if (!station.recordable) {
            Toast.makeText(this, "Recording is unavailable for this stream type", Toast.LENGTH_LONG).show()
            return
        }
        if (StreamRecorder.isRecording) {
            StreamRecorder.stop()
            binding.recordButton.text = "● REC"
            Toast.makeText(this, "Stopping recording…", Toast.LENGTH_SHORT).show()
        } else {
            StreamRecorder.start(this, station) { ok, message ->
                runOnUiThread {
                    binding.recordButton.text = "● REC"
                    Toast.makeText(this, message, if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                }
            }
            binding.recordButton.text = "■ STOP"
            Toast.makeText(this, "Recording ${station.name}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSleepTimer() {
        val labels = arrayOf("15 minutes", "30 minutes", "60 minutes", "90 minutes", "Cancel timer")
        val mins = intArrayOf(15, 30, 60, 90, 0)
        AlertDialog.Builder(this)
            .setTitle("Sleep timer")
            .setItems(labels) { _, which ->
                sleepRunnable?.let { handler.removeCallbacks(it) }
                val m = mins[which]
                if (m == 0) {
                    sleepRunnable = null
                    Toast.makeText(this, "Sleep timer cancelled", Toast.LENGTH_SHORT).show()
                } else {
                    sleepRunnable = Runnable {
                        controller?.pause()
                        Toast.makeText(this, "Sleep timer stopped playback", Toast.LENGTH_SHORT).show()
                    }.also { handler.postDelayed(it, TimeUnit.MINUTES.toMillis(m.toLong())) }
                    Toast.makeText(this, "Playback will stop in $m minutes", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
    }

    override fun onDestroy() {
        controller?.release()
        controller = null
        super.onDestroy()
    }
}
