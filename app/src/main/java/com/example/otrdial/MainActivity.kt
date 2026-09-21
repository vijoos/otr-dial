package com.example.otrdial

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.content.res.Configuration
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Toast
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

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var stations: List<Station>
    private lateinit var adapter: StationAdapter
    private val prefs by lazy { getSharedPreferences("otr_dial", MODE_PRIVATE) }
    private var currentStation: Station? = null
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var genre = "All genres"
    private var collection = 0
    private var playerOpen = false
    private lateinit var playerBack: OnBackPressedCallback

    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.localNightMode = if (prefs.getBoolean("dark_mode", false)) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        playerBack = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = showPlayer(false)
        }
        onBackPressedDispatcher.addCallback(this, playerBack)

        requestNotificationPermissionIfNeeded()
        stations = StationRepository.load(this)
        setupPlayerConnection()
        setupList()
        setupFilters()
        setupControls()
        binding.currentMetadata.text = savedInstanceState?.getString("metadata") ?: "Live radio"
        showPlayer(savedInstanceState?.getBoolean("player_open") ?: false)
    }

    private fun setupPlayerConnection() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture!!.addListener({
            runCatching { controllerFuture!!.get() }.onSuccess { c ->
                controller = c
                currentStation = stations.find { it.id == c.currentMediaItem?.mediaId }
                currentStation?.let {
                    binding.currentStation.text = it.name
                    binding.openPlayerButton.text = "${it.name}  ›"
                    binding.openPlayerButton.visibility = View.VISIBLE
                    updateFavouriteButton()
                }
                c.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) = updatePlayButton()
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
        binding.collectionSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("All stations", "Favourites", "Recently played"))
        binding.collectionSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                collection = position
                applyFilters()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
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
            if (c.playWhenReady) c.pause() else if (c.currentMediaItem != null) {
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                c.play()
            }
        }
        binding.favouriteButton.setOnClickListener { currentStation?.let { toggleFavourite(it) } }
        binding.recordButton.setOnClickListener { toggleRecording() }
        binding.themeSwitch.isChecked = prefs.getBoolean("dark_mode", false)
        binding.themeSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("dark_mode", checked).apply()
            delegate.localNightMode = if (checked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        }
        binding.backButton.setOnClickListener { showPlayer(false) }
        binding.openPlayerButton.setOnClickListener { showPlayer(true) }
        binding.recordButton.text = if (StreamRecorder.isRecording) "■ STOP" else "● REC"
    }

    private fun showPlayer(show: Boolean) {
        playerOpen = show
        binding.browserPanel.visibility = if (show) View.GONE else View.VISIBLE
        binding.playerScreen.visibility = if (show) View.VISIBLE else View.GONE
        playerBack.isEnabled = show
        if (show) {
            binding.searchBox.clearFocus()
            WindowCompat.getInsetsController(window, binding.root).hide(WindowInsetsCompat.Type.ime())
        }
    }

    private fun playStation(station: Station) {
        val c = controller
        if (c == null) {
            Toast.makeText(this, "Player is still starting", Toast.LENGTH_SHORT).show()
            return
        }
        currentStation = station
        val recent = (listOf(station.id) + recentIds().filter { it != station.id }).take(30)
        prefs.edit().putString("recent", org.json.JSONArray(recent).toString()).apply()
        applyFilters()
        binding.openPlayerButton.text = "${station.name}  ›"
        binding.openPlayerButton.visibility = View.VISIBLE
        showPlayer(true)
        if (c.currentMediaItem?.mediaId == station.id) {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
            return
        }
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
        val recent = recentIds()
        val source = if (collection == 2) recent.mapNotNull { id -> stations.find { it.id == id } } else stations
        val filtered = source.filter { s ->
            (collection != 1 || isFavourite(s)) &&
            (genre == "All genres" || s.genre == genre) &&
                (q.isBlank() || listOf(s.name, s.network, s.genre).any { it.lowercase().contains(q) })
        }
        adapter.submit(filtered)
        binding.listSummary.text = if (filtered.isEmpty()) {
            when (collection) {
                1 -> "No favourites match. Tap a station’s heart to save it."
                2 -> "No recent stations match. Choose a station from All stations."
                else -> "No stations match your search."
            }
        } else "${filtered.size} stations"
    }

    private fun recentIds(): List<String> = runCatching {
        val array = org.json.JSONArray(prefs.getString("recent", "[]"))
        (0 until array.length()).map { array.getString(it) }
    }.getOrDefault(emptyList())

    private fun favouriteIds(): MutableSet<String> =
        prefs.getStringSet("favourites", emptySet())?.toMutableSet() ?: mutableSetOf()

    private fun isFavourite(station: Station): Boolean = favouriteIds().contains(station.id)

    private fun toggleFavourite(station: Station) {
        val ids = favouriteIds()
        if (!ids.add(station.id)) ids.remove(station.id)
        prefs.edit().putStringSet("favourites", ids).apply()
        applyFilters()
        updateFavouriteButton()
    }

    private fun updateFavouriteButton() {
        binding.favouriteButton.text = if (currentStation?.let { isFavourite(it) } == true) "♥" else "♡"
        binding.favouriteButton.contentDescription = if (currentStation?.let { isFavourite(it) } == true) "Remove from favourites" else "Add to favourites"
    }

    private fun updatePlayButton() {
        val c = controller
        val playing = c?.playWhenReady == true
        binding.playPauseButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        binding.playPauseButton.contentDescription = if (playing) "Pause" else "Play"
        binding.connectionStatus.text = when {
            c?.currentMediaItem == null -> "Choose a station"
            c.playerError != null && c.playWhenReady -> "Connection lost · retrying automatically"
            c.playerError != null -> "Unable to connect · tap play to retry"
            !c.playWhenReady -> "Paused"
            c.playbackState == Player.STATE_BUFFERING -> "Connecting…"
            c.isPlaying -> "Playing live"
            else -> "Waiting for audio…"
        }
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

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("player_open", playerOpen)
        outState.putString("metadata", binding.currentMetadata.text.toString())
        super.onSaveInstanceState(outState)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onDestroy()
    }
}
