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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import android.graphics.Typeface
import android.view.Gravity
import android.content.Intent
import android.net.Uri
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
    private var network = "All networks"
    private var homeOpen = true
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
            override fun handleOnBackPressed() {
                if (!playerOpen) homeOpen = true
                showPlayer(false)
            }
        }
        onBackPressedDispatcher.addCallback(this, playerBack)

        requestNotificationPermissionIfNeeded()
        stations = StationRepository.load(this)
        val oldFavourites = favouriteIds()
        if (oldFavourites.remove("catalogue-science-fiction")) {
            oldFavourites.add("rokit-scifi")
            prefs.edit().putStringSet("favourites", oldFavourites).apply()
        }
        setupPlayerConnection()
        setupList()
        setupFilters()
        setupControls()
        homeOpen = savedInstanceState?.getBoolean("home_open") ?: true
        collection = savedInstanceState?.getInt("collection") ?: 0
        binding.collectionSpinner.setSelection(collection)
        renderHome()
        binding.currentMetadata.text = savedInstanceState?.getString("metadata") ?: "Live radio"
        binding.playerArtwork.setImageDrawable(RadioArtwork("Radio theatre"))
        binding.artworkCredits.setOnClickListener {
            currentStation?.let { station ->
                AlertDialog.Builder(this).setTitle("Artwork credits")
                    .setMessage(StationArt.credits(this, station)).setPositiveButton("Close", null).show()
            }
        }
        binding.metadataSource.text = savedInstanceState?.getString("metadata_source")
            ?: "Programme information will appear when supplied by the station"
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
                    binding.playerArtwork.setImageDrawable(StationArt.drawable(this, it))
                    binding.currentStation.text = it.name
                    binding.openPlayerButton.text = "${it.name}  ›"
                    binding.openPlayerButton.visibility = View.VISIBLE
                    syncMiniPlayer()
                    renderHome()
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
                                binding.metadataSource.text = "Live programme information from station stream"
                            }
                        }
                    }

                    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                        val title = mediaMetadata.title?.toString()?.trim().orEmpty()
                        val artist = mediaMetadata.artist?.toString()?.trim().orEmpty()
                        val raw = listOf(title, artist).filter { it.isNotBlank() }.distinct().joinToString(" — ")
                        if (title.isNotBlank() && title != currentStation?.name) {
                            binding.currentMetadata.text = raw
                            binding.metadataSource.text = "Programme information from station metadata"
                        }
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
            onFavourite = { toggleFavourite(it) },
            onInfo = { showStationDetails(it) }
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

        val networks = listOf("All networks") + stations.map { it.network }.distinct().sorted()
        binding.networkSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, networks)
        binding.networkSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                network = networks[position]
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
        binding.settingsButton.setOnClickListener {
            AlertDialog.Builder(this).setTitle("OTR Dial")
                .setItems(arrayOf("My recordings", "Switch light / dark mode", "About this catalogue")) { _, which ->
                    when (which) {
                        0 -> RecordingLibrary.show(this)
                        1 -> binding.themeSwitch.isChecked = !binding.themeSwitch.isChecked
                        2 -> AlertDialog.Builder(this).setTitle("Your radio theatre")
                            .setMessage("${stations.size} stations • OTR Dial 1.3\n\nArtwork is bundled for quick, offline display. Source credits are available in the player. Episode titles appear only when supplied by the broadcaster. Stream availability may change.")
                            .setPositiveButton("Close", null).show()
                    }
                }.show()
        }
        binding.exploreButton.setOnClickListener { browse(0) }
        binding.navPlayer.setOnClickListener {
            if (currentStation != null) showPlayer(true) else stations.firstOrNull()?.let { playStation(it) }
        }
        binding.miniPlayPause.setOnClickListener { binding.playPauseButton.performClick() }
        binding.previousStation.setOnClickListener { stepStation(-1) }
        binding.nextStation.setOnClickListener { stepStation(1) }
        binding.checkStreamButton.setOnClickListener { currentStation?.let { StreamHealth.check(this, it) } }
        binding.homeButton.setOnClickListener {
            homeOpen = true
            renderHome()
            showPlayer(false)
        }
        binding.favouritesButton.setOnClickListener {
            browse(1)
        }
        binding.recentButton.setOnClickListener {
            browse(2)
        }
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
        binding.scheduleButton.setOnClickListener { currentStation?.let { showStationDetails(it) } }
        binding.shareButton.setOnClickListener { currentStation?.let { shareStation(it) } }
        binding.recordButton.text = if (StreamRecorder.isRecording) "■ STOP" else "● REC"
    }

    private fun showPlayer(show: Boolean) {
        playerOpen = show
        binding.browserPanel.visibility = if (show) View.GONE else View.VISIBLE
        binding.playerScreen.visibility = if (show) View.VISIBLE else View.GONE
        binding.appHeader.visibility = if (show) View.GONE else View.VISIBLE
        binding.navigationBar.visibility = if (show) View.GONE else View.VISIBLE
        binding.homeScreen.visibility = if (homeOpen) View.VISIBLE else View.GONE
        binding.exploreScreen.visibility = if (homeOpen) View.GONE else View.VISIBLE
        syncMiniPlayer()
        binding.homeButton.isSelected = homeOpen
        binding.exploreButton.isSelected = !homeOpen && collection == 0
        binding.favouritesButton.isSelected = !homeOpen && collection == 1
        listOf(binding.homeButton, binding.exploreButton, binding.favouritesButton).forEach { button ->
            button.alpha = if (button.isSelected) 1f else 0.7f
        }
        playerBack.isEnabled = show || !homeOpen
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
        binding.playerArtwork.setImageDrawable(StationArt.drawable(this, station))
        val recent = (listOf(station.id) + recentIds().filter { it != station.id }).take(30)
        prefs.edit().putString("recent", org.json.JSONArray(recent).toString()).apply()
        applyFilters()
        renderHome()
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
        binding.metadataSource.text = if (station.scheduleUrl.isNotBlank()) {
            "Station schedule available · tap Details / schedule"
        } else {
            "Live programme information will appear when supplied by the station"
        }
        updateFavouriteButton()

        val metadata = MediaMetadata.Builder()
            .setArtworkData(StationArt.bytes(this, station), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
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

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun syncMiniPlayer() {
        val station = currentStation
        binding.miniPlayer.visibility = if (!playerOpen && station != null) View.VISIBLE else View.GONE
        station?.let {
            binding.miniArtwork.setImageDrawable(StationArt.drawable(this, it))
            binding.openPlayerButton.text = "${it.name}  ›"
            binding.openPlayerButton.contentDescription = "Open player for ${it.name}"
        }
    }

    private fun browse(which: Int, selectedGenre: String = "All genres") {
        homeOpen = false
        collection = which
        genre = selectedGenre
        network = "All networks"
        binding.searchBox.setText("")
        binding.collectionSpinner.setSelection(which)
        val genres = listOf("All genres") + stations.map { it.genre }.distinct().sorted()
        binding.genreSpinner.setSelection(genres.indexOf(selectedGenre).coerceAtLeast(0))
        binding.networkSpinner.setSelection(0)
        applyFilters()
        showPlayer(false)
    }

    private fun stepStation(direction: Int) {
        val index = stations.indexOfFirst { it.id == currentStation?.id }.coerceAtLeast(0)
        stations.getOrNull((index + direction + stations.size) % stations.size)?.let { playStation(it) }
    }

    private fun label(text: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.otr_ink))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(10), dp(5), dp(10), dp(5))
    }

    private fun stationCard(station: Station, compact: Boolean): LinearLayout = LinearLayout(this).apply {
        orientation = if (compact) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.glass_panel)
        setPadding(dp(6), dp(6), dp(6), dp(6))
        layoutParams = LinearLayout.LayoutParams(if (compact) -1 else dp(158), -2).apply {
            setMargins(0, 0, dp(12), dp(10))
        }
        addView(ImageView(this@MainActivity).apply {
            setImageDrawable(StationArt.drawable(this@MainActivity, station))
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(if (compact) dp(56) else -1, if (compact) dp(62) else dp(128))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.glass_panel)
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = if (compact) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2)
            addView(label(station.name, 15f, true))
            addView(label(station.genre, 12f))
        })
        if (compact) addView(label("▶", 20f))
        isClickable = true
        isFocusable = true
        contentDescription = "Play ${station.name}, ${station.genre}"
        descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        setOnClickListener { playStation(station) }
    }

    private fun renderHome() {
        val hero = currentStation ?: stations.firstOrNull { it.genre.contains("Mystery", true) } ?: stations.firstOrNull() ?: return
        binding.heroTitle.text = if (currentStation == null) "Stories worth tuning in for" else "Your next chapter"
        binding.heroSubtitle.text = "${hero.name} • ${hero.genre}"
        binding.heroArt.setImageDrawable(StationArt.drawable(this, hero))
        binding.heroPlay.text = if (currentStation == null) "▶  Listen live" else "▶  Continue listening"
        binding.heroPlay.setOnClickListener { playStation(hero) }
        binding.featuredCards.removeAllViews()
        val picks = (stations.filter { isFavourite(it) } + stations.distinctBy { it.genre }).distinctBy { it.id }.take(8)
        picks.forEach { binding.featuredCards.addView(stationCard(it, false)) }
        binding.genreCards.removeAllViews()
        val genres = stations.map { it.genre }.distinct().sorted()
        genres.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { genreName ->
                row.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(8), dp(4), dp(8))
                    addView(ImageView(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(36), dp(52))
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setImageDrawable(StationArt.drawable(this@MainActivity, stations.first { it.genre == genreName }))
                        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.glass_panel)
                        clipToOutline = true
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    })
                    addView(label("${genreName}  ›\n${stations.count { it.genre == genreName }} stations", 13f, true).apply {
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    })
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, dp(8), dp(8)) }
                    minimumHeight = dp(80)
                    gravity = Gravity.CENTER_VERTICAL
                    background = ContextCompat.getDrawable(this@MainActivity, R.drawable.glass_panel)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { browse(0, genreName) }
                })
            }
            binding.genreCards.addView(row)
        }
        binding.recentCards.removeAllViews()
        val recent = recentIds().mapNotNull { id -> stations.find { it.id == id } }.take(3)
        if (recent.isEmpty()) binding.recentCards.addView(label("Your listening history will appear here.", 14f))
        else recent.forEach { binding.recentCards.addView(stationCard(it, true)) }
    }

    private fun showStationDetails(station: Station) {
        val details = buildString {
            append(station.name)
            append("\n\nNetwork: ").append(station.network)
            append("\nGenre: ").append(station.genre)
            append("\n\nLast connection check:\n").append(prefs.getString("health_${station.id}", "Not checked on this device"))
            if (station.verification.isNotBlank()) append("\n\nVerification: ").append(station.verification)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("Station details")
            .setMessage(details)
            .setPositiveButton("Listen") { _, _ -> playStation(station) }
            .setNegativeButton("Close", null)
        if (station.homepage.isNotBlank() || station.scheduleUrl.isNotBlank()) {
            builder.setNeutralButton("Open website") { _, _ ->
                val url = station.scheduleUrl.ifBlank { station.homepage }
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    .onFailure { Toast.makeText(this, "No browser available", Toast.LENGTH_SHORT).show() }
            }
        }
        builder.show()
    }

    private fun shareStation(station: Station) {
        val text = "Listen to ${station.name} on OTR Dial\n${station.network}\n${station.streamUrl}"
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "${station.name} — OTR Dial")
            putExtra(Intent.EXTRA_TEXT, text)
        }, "Share station"))
    }

    private fun applyFilters() {
        val q = binding.searchBox.text?.toString()?.trim()?.lowercase().orEmpty()
        val recent = recentIds()
        val source = if (collection == 2) recent.mapNotNull { id -> stations.find { it.id == id } } else stations
        val filtered = source.filter { s ->
            (collection != 1 || isFavourite(s)) &&
            (genre == "All genres" || s.genre == genre) &&
            (network == "All networks" || s.network == network) &&
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
        renderHome()
        updateFavouriteButton()
    }

    private fun updateFavouriteButton() {
        binding.favouriteButton.text = if (currentStation?.let { isFavourite(it) } == true) "♥" else "♡"
        binding.favouriteButton.contentDescription = if (currentStation?.let { isFavourite(it) } == true) "Remove from favourites" else "Add to favourites"
    }

    private fun updatePlayButton() {
        val c = controller
        val playing = c?.playWhenReady == true
        binding.onAirLabel.text = if (c?.isPlaying == true) "ON AIR · LIVE RADIO" else "OTR DIAL · RADIO THEATRE"
        binding.playPauseButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        binding.playPauseButton.contentDescription = if (playing) "Pause" else "Play"
        binding.miniPlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        binding.miniPlayPause.contentDescription = if (playing) "Pause" else "Play"
        syncMiniPlayer()
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
        if (StreamRecorder.isRecording) {
            StreamRecorder.stop()
            binding.recordButton.text = "Stopping…"
            return
        }
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

    override fun onResume() {
        super.onResume()
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("player_open", playerOpen)
        outState.putBoolean("home_open", homeOpen)
        outState.putInt("collection", collection)
        outState.putString("metadata", binding.currentMetadata.text.toString())
        outState.putString("metadata_source", binding.metadataSource.text.toString())
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
