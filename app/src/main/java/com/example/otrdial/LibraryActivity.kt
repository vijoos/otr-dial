package com.example.otrdial

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executors

class LibraryActivity : AppCompatActivity() {
    private val store by lazy { LibraryStore(this) }
    private val collections by lazy { CollectionStore(this) }
    private val work = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var mini: Button
    private lateinit var miniPause: ImageButton
    private lateinit var miniArtwork: ImageView
    private var miniArtworkId: String? = null
    private lateinit var screenHeader: LinearLayout
    private var tab = "home"
    private var returnTab = "home"
    private val directory by lazy { SourceDirectory.load(this) }
    private lateinit var navigation: LinearLayout
    private var libraryView = "Saved"
    private var searchType = "All sources"
    private var searchGenre = "All genres"
    private val stations by lazy { StationRepository.load(this) }
    private val radioPrefs by lazy { getSharedPreferences("otr_dial", MODE_PRIVATE) }
    private var errorActions: View? = null
    private var sourceId: String? = null
    private var busy = false
    private var page = 0
    private var query = ""
    private var playerTitle: TextView? = null
    private var playerTime: TextView? = null
    private var playerToggle: ImageButton? = null
    private var seek: SeekBar? = null
    private var seeking = false
    private var playerEpisode: Episode? = null
    private val navButtons = mutableMapOf<String, Button>()
    private val tick = object : Runnable { override fun run() { updatePlayer(); handler.postDelayed(this, 1000) } }
    private val exportBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching { contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(store.export()) } ?: error("Cannot open file") }
            .onSuccess { toast("Library backup saved") }.onFailure { toast("Could not save backup: ${it.message}") }
    }
    private val importBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work.execute {
            val result = runCatching {
                val raw = contentResolver.openInputStream(uri)?.use { it.readLimited(12 * 1024 * 1024) } ?: error("Cannot open file")
                require(raw.size <= 12 * 1024 * 1024) { "Backup too large" }; store.restore(raw.toString(Charsets.UTF_8))
            }
            runOnUiThread { if (!isDestroyed) { result.onSuccess { toast("Library and radio favourites restored"); recreate() }.onFailure { toast("Backup not imported: ${it.message}") } } }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.localNightMode = if (getSharedPreferences("otr_dial", MODE_PRIVATE).getBoolean("dark_mode", false)) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    tab == "player" -> { tab = returnTab; page = 0; render() }
                    sourceId != null -> { sourceId = null; query = ""; page = 0; render() }
                    tab != "home" -> { tab = "home"; page = 0; render() }
                    else -> finish()
                }
            }
        })
        tab = savedInstanceState?.getString("tab") ?: if (intent.getBooleanExtra("player", false)) "player" else "home"
        returnTab = savedInstanceState?.getString("return_tab") ?: "home"
        libraryView = savedInstanceState?.getString("library_view") ?: "Saved"
        searchType = savedInstanceState?.getString("search_type") ?: "All sources"
        searchGenre = savedInstanceState?.getString("search_genre") ?: "All genres"
        page = savedInstanceState?.getInt("page") ?: 0
        sourceId = savedInstanceState?.getString("source") ?: intent.getStringExtra("source_id"); query = savedInstanceState?.getString("query").orEmpty()
        if (sourceId != null && savedInstanceState == null && tab != "player") tab = if (collections.sources().find { it.id == sourceId }?.kind == "rss") "podcasts" else "discover"
        val root = column().apply { setBackgroundResource(R.drawable.aurora) }; setContentView(root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, i ->
            val bars = i.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left + dp(8), bars.top, bars.right + dp(8), bars.bottom); i
        }
        WindowCompat.getInsetsController(window, root).apply {
            val light = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES
            isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light
        }
        val header = row(); screenHeader = header; root.addView(header)
        header.addView(label("OTR Dial", 22, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("Search") { tab = "search"; sourceId = null; page = 0; render() })
        header.addView(button("◐") { radioPrefs.edit().putBoolean("dark_mode", !radioPrefs.getBoolean("dark_mode", false)).apply(); recreate() }.apply { contentDescription = "Switch light / dark mode" })
        header.addView(button("⋮") { settings() }.apply { contentDescription = "Library settings" })
        status = label("LIVE RADIO  •  PODCASTS  •  THE ARCHIVES", 11); root.addView(status)
        val scroll = ScrollView(this); content = column(); scroll.addView(content); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val miniRow = row(); root.addView(miniRow)
        miniArtwork = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundResource(R.drawable.glass_panel); clipToOutline = true; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        miniRow.addView(miniArtwork, LinearLayout.LayoutParams(dp(48), dp(48)))
        mini = button("Choose an episode") { if (tab != "player") returnTab = tab; tab = "player"; render() }.apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }; miniRow.addView(mini, LinearLayout.LayoutParams(0, -2, 1f))
        miniPause = iconButton(R.drawable.ic_play, "Play") { val c = controller; if(c?.playWhenReady == true) c.pause() else if(c?.currentMediaItem != null) { if(c.playbackState == Player.STATE_IDLE) c.prepare(); c.play() } else store.find(store.last())?.let { play(it) } }; miniRow.addView(miniPause)
        val nav = row(); navigation = nav; root.addView(nav)
        listOf("home" to "Radio", "discover" to "Repo", "podcasts" to "Podcasts", "youtube" to "YouTube", "library" to "Favourites").forEach { (key, title) ->
            val b = button(title) { tab = key; sourceId = null; query = ""; page = 0; render() }.apply { textSize = 10f; setPadding(0, dp(6), 0, dp(6)); maxLines = 1; minimumWidth = 0; minWidth = 0; contentDescription = title }
            val icon = ContextCompat.getDrawable(this, when(key) { "home" -> R.drawable.ic_wave; "discover" -> R.drawable.ic_explore; "library" -> R.drawable.ic_heart; "podcasts" -> R.drawable.ic_podcast; else -> R.drawable.ic_video })?.mutate()
            icon?.setBounds(0, 0, dp(18), dp(18)); b.setCompoundDrawables(null, icon, null, null); b.compoundDrawablePadding = dp(2)
            navButtons[key] = b; nav.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(2), dp(6), dp(2), dp(6)) })
        }
        future = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        future!!.addListener({ if (!isDestroyed) runCatching { future!!.get() }.onSuccess { c ->
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) { updatePlayer() }
                override fun onMediaItemTransition(item: androidx.media3.common.MediaItem?, reason: Int) { if (tab == "player" || tab == "queue") render() }
            })
            render()
            val requested = intent.getStringExtra("episode_id")
            intent.removeExtra("episode_id")
            store.find(requested)?.let { e ->
                val position = intent.getLongExtra("episode_position", -1)
                play(e, forceReload = true)
                syncQueue()
                if(position >= 0) c.seekTo(position)
                intent.removeExtra("episode_position")
            }
        }.onFailure { status.text = "Player could not connect. Reopen the library to retry." } }, ContextCompat.getMainExecutor(this))
        render()
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 10)
    }
    override fun onStart() { super.onStart(); handler.post(tick); if (::content.isInitialized && tab == "home") render() }
    override fun onResume() {
        super.onResume()
        val wanted = if (radioPrefs.getBoolean("dark_mode", false)) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        if (delegate.localNightMode != wanted) delegate.localNightMode = wanted
        else if (::content.isInitialized && tab in listOf("home", "library")) render(false)
    }
    override fun onStop() { handler.removeCallbacks(tick); super.onStop() }
    override fun onDestroy() { future?.let { MediaController.releaseFuture(it) }; work.shutdownNow(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("return_tab", returnTab); outState.putString("tab", tab); outState.putString("source", sourceId); outState.putString("query", query)
        outState.putString("library_view", libraryView); outState.putString("search_type", searchType); outState.putString("search_genre", searchGenre); outState.putInt("page", page)
        super.onSaveInstanceState(outState)
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(6)) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun label(value: String, size: Int = 15, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(getColor(R.color.otr_ink)); setPadding(dp(4), dp(6), dp(4), dp(6))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    private fun button(value: String, action: () -> Unit) = androidx.appcompat.widget.AppCompatButton(this).apply {
        text = value; isAllCaps = false; textSize = 13f; minWidth = dp(48); minimumWidth = dp(48); minHeight = dp(48)
        setPadding(dp(10), dp(6), dp(10), dp(6)); elevation = 0f
        setBackgroundResource(if (value.startsWith("▶")) R.drawable.accent_gradient else R.drawable.glass_panel)
        backgroundTintList = null; supportBackgroundTintList = null
        setTextColor(if (value.startsWith("▶")) android.graphics.Color.WHITE else getColor(R.color.otr_brown))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(2), dp(4), dp(2), dp(4)) }
        setOnClickListener { action() }
    }
    private fun iconButton(icon: Int, description: String, action: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon); contentDescription = description
        setPadding(dp(13), dp(13), dp(13), dp(13))
        setBackgroundResource(R.drawable.glass_panel)
        imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.otr_brown))
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
        setOnClickListener { action() }
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun card() = column().apply {
        setBackgroundResource(R.drawable.glass_panel)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(6), 0, dp(6)) }
    }
    private fun render(resetScroll: Boolean = true) {
        if (!::content.isInitialized) return
        content.removeAllViews(); playerTitle = null; playerTime = null; seek = null; playerToggle = null; playerEpisode = null; errorActions = null
        when (tab) { "home" -> renderRadio(); "podcasts" -> renderDiscover(); "youtube" -> renderYouTube(); "radio-directory" -> renderRadioDirectory(); "search" -> renderSearch(); "library" -> renderLibrary(); "queue" -> renderQueue(); "player" -> renderPlayer(); "picks" -> renderHome(); else -> renderDiscover() }
        navigation.visibility = if (tab == "player") View.GONE else View.VISIBLE
        screenHeader.visibility = navigation.visibility
        status.visibility = navigation.visibility
        navButtons.forEach { (key, b) -> b.setBackgroundResource(if (key == tab) R.drawable.accent_gradient else R.drawable.glass_panel); val colour = if (key == tab) android.graphics.Color.WHITE else getColor(R.color.otr_brown); b.setTextColor(colour); b.compoundDrawables.filterNotNull().forEach { it.setTint(colour) } }
        updatePlayer()
        if (resetScroll) (content.parent as? ScrollView)?.scrollTo(0, 0)
    }
    private fun openRadio(id: String? = null) {
        startActivity(Intent(this, MainActivity::class.java).putExtra("station_id", id).putExtra("from_platform", true).putExtra("player", id == null))
    }
    private fun collection(screen: String) { startActivity(Intent(this, CollectionActivity::class.java).putExtra("screen", screen)) }
    private fun showSource(id: String) { sourceId = id; tab = if (collections.sources().find { it.id == id }?.kind == "rss") "podcasts" else "discover"; query = ""; page = 0; render() }
    private fun artwork(e: Episode, height: Int) = ImageView(this).apply {
        setImageDrawable(StationArt.drawable(this@LibraryActivity, e.artStation()))
        FeedArtwork.load(applicationContext, this, e.image)
        scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundResource(R.drawable.glass_panel); clipToOutline = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(-1, dp(height)).apply { bottomMargin = dp(8) }
    }
    private fun banner(title: String, detail: String) {
        val hero = card().apply { setBackgroundResource(R.drawable.hero_gradient) }
        hero.addView(label(title, 27, true).apply { setTextColor(android.graphics.Color.WHITE) })
        hero.addView(label(detail, 14).apply { setTextColor(android.graphics.Color.WHITE) })
        content.addView(hero)
    }
    private fun renderRadio() {
        banner("Turn on a little wonder.", "English-language radio • stories around the clock")
        content.addView(button("Browse source directory") { tab = "radio-directory"; page = 0; query = ""; render() })
        content.addView(button("Queue") { tab = "queue"; render() })
        searchControls("Find a station or genre")
        val found = stations.filter { query.isBlank() || "${it.name} ${it.network} ${it.genre}".contains(query, true) }
        content.addView(label("${found.size} stations • availability depends on the broadcaster", 12))
        page = page.coerceAtMost((found.size - 1).coerceAtLeast(0) / 20)
        found.drop(page * 20).take(20).forEach { stationCard(it) }
        if (found.size > 20) pager(found.size, 20)
    }
    private fun renderRadioDirectory() {
        banner("More ways to tune in", "Original source directory • some players open on the web")
        searchControls("Search the radio directory")
        directory.filter { it.category == "radio" && (query.isBlank() || "${it.title} ${it.genre}".contains(query, true)) }.forEach { directoryCard(it) }
    }
    private fun renderYouTube() {
        banner("Watch. Listen. Rediscover.", "Classic radio channels on YouTube")
        content.addView(label("Opens YouTube or your browser. Video playback and background listening are controlled by YouTube.", 14))
        directory.filter { it.category == "youtube" }.forEach { directoryCard(it) }
    }
    private fun directoryCard(d: DirectorySource) {
        val c = card()
        val art = Station(d.id, d.title, d.title, d.genre, "", d.page, "", false, "")
        val heading = row()
        heading.addView(ImageView(this).apply { setImageDrawable(StationArt.drawable(this@LibraryActivity, art)); scaleType = ImageView.ScaleType.CENTER_CROP; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(64), dp(74)))
        heading.addView(label(d.title, 18, true), LinearLayout.LayoutParams(0, -2, 1f)); c.addView(heading)
        c.addView(label(d.notes, 13))
        if (d.warning.isNotBlank()) c.addView(label(d.warning, 12).apply { setTextColor(getColor(R.color.otr_gold)) })
        val source = collections.sources().find { it.id == d.sourceId }
            ?: d.episodeSource()?.let { suggested -> collections.sources().find { it.kind == suggested.kind && it.url == suggested.url } ?: suggested }
        val station = stations.find { it.id == d.stationId }
        if (station != null) c.addView(button("▶ Listen in app") { openRadio(station.id) })
        else if (source != null) c.addView(button("Browse episodes  ›") {
            if (collections.sources().none { it.id == source.id }) collections.addSource(source)
            showSource(source.id)
            if (store.episodes().none { it.source == source.id }) refresh(listOf(source))
        })
        else c.addView(label("Listen or browse on the provider’s website", 12))
        val actions = row()
        actions.addView(button(if (d.category == "youtube" && d.page.contains("youtube.com")) "Open YouTube ↗" else "Open website ↗") { openPage(d.page) }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(button(if (d.id in store.directorySaved()) "♥ Saved" else "♡ Save source") { store.toggle("directory_saved", d.id); render(false) }, LinearLayout.LayoutParams(0, -2, 1f))
        c.addView(actions); content.addView(c)
    }
    private fun renderHome() {
        val hero = card().apply { setBackgroundResource(R.drawable.hero_gradient) }
        hero.addView(label("A world of stories.", 25, true).apply { setTextColor(android.graphics.Color.WHITE) })
        hero.addView(label("Listen live. Explore. Keep your favourites.", 14).apply { setTextColor(android.graphics.Color.WHITE) })
        hero.addView(button("Explore the shows  ›") { tab = "discover"; sourceId = null; render() }); content.addView(hero)
        val shortcuts = HorizontalScrollView(this); val shortcutRow = row()
        listOf("Programmes", "Playlists", "Bookmarks", "Downloads", "Sources").forEach { s -> shortcutRow.addView(button(s) { collection(s) }) }; shortcuts.addView(shortcutRow); content.addView(shortcuts)
        val episodes = store.episodes()
        content.addView(label("Your daily picks", 21, true)); content.addView(label("App selections, not new releases • ${if(collections.followedProgrammes().isEmpty()) "from your catalogue" else "based on programme follows"}", 12))
        val shelf = HorizontalScrollView(this); val shelfRow = row()
        ProgrammeIndex.daily(episodes, collections.followedProgrammes()).forEach { e -> val tile = card(); tile.layoutParams = LinearLayout.LayoutParams(dp(160), -2).apply { setMargins(dp(3), 0, dp(5), 0) }; tile.addView(artwork(e, 100)); tile.addView(label(e.title, 14, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }); tile.addView(button("▶ Listen") { play(e) }); shelfRow.addView(tile) }; shelf.addView(shelfRow); content.addView(shelf)
        content.addView(label("Continue listening", 22, true))
        val unfinished = episodes.filter { store.progress(it.id) > 0 }.sortedByDescending { store.played(it.id) }.take(3)
        if (unfinished.isEmpty()) content.addView(label("Start an episode and your place will be remembered here.", 14))
        else unfinished.forEach { episodeCard(it) }
        content.addView(label("Favourite stations", 22, true))
        val favs = radioPrefs.getStringSet("favourites", emptySet()).orEmpty()
        val favourites = stations.filter { it.id in favs }
        if (favourites.isEmpty()) content.addView(button("Find a favourite among ${stations.size} live stations  ›") { openRadio() })
        else favourites.take(6).forEach { stationCard(it) }
        if (favourites.size > 6) content.addView(button("All live stations  ›") { openRadio() })
        content.addView(label("Followed shows", 22, true))
        val followed = collections.sources().filter { it.id in store.follows() }
        if (followed.isEmpty()) content.addView(label("Follow a show to keep it on your home screen.", 14))
        followed.forEach { s -> content.addView(button("${s.title}  ›") { showSource(s.id) }) }
        content.addView(button("Refresh followed podcasts") { refresh(followed.filter { it.kind == "rss" }) })
        content.addView(label("Recently published podcasts", 22, true))
        content.addView(label(if (followed.any { it.kind == "rss" }) "From the podcasts you follow • publisher dates" else "From the podcast catalogue • publisher dates", 12))
        val followedPodcasts = followed.filter { it.kind == "rss" }.map { it.id }
        episodes.filter { e -> collections.sources().any { it.id == e.source && it.kind == "rss" } && (followedPodcasts.isEmpty() || e.source in followedPodcasts) }
            .sortedByDescending { published(it.date) }.take(4).forEach { episodeCard(it) }
    }
    private fun published(date: String) = runCatching {
        java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.US).parse(date)?.time ?: 0L
    }.getOrDefault(0L)
    private fun stationCard(s: Station) {
        val c = card(); val r = row()
        r.addView(ImageView(this).apply { setImageDrawable(StationArt.drawable(this@LibraryActivity, s)); scaleType = ImageView.ScaleType.CENTER_CROP; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(52), dp(64)))
        r.addView(label(s.name, 18, true), LinearLayout.LayoutParams(0, -2, 1f)); c.addView(r)
        c.addView(label("Live radio • ${s.genre} • ${s.network}", 12)); c.addView(button("▶ Listen live") { openRadio(s.id) }); c.addView(button(if (s.id in radioPrefs.getStringSet("favourites", emptySet()).orEmpty()) "♥ Favourite" else "♡ Favourite") { val ids = radioPrefs.getStringSet("favourites", emptySet()).orEmpty().toMutableSet(); if (!ids.add(s.id)) ids.remove(s.id); radioPrefs.edit().putStringSet("favourites", ids).apply(); render(false) }); content.addView(c)
    }
    private fun selector(options: List<String>, selected: String, change: (String) -> Unit): Spinner {
        return Spinner(this).apply {
            adapter = ArrayAdapter(this@LibraryActivity, android.R.layout.simple_spinner_dropdown_item, options)
            setSelection(options.indexOf(selected).coerceAtLeast(0)); minimumHeight = dp(48)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { if (options[position] != selected) change(options[position]) }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
    }
    private fun genreMatches(value: String) = searchGenre == "All genres" || value.equals(searchGenre, true)
    private fun renderSearch() {
        content.addView(label("Find your next story", 27, true)); searchControls("Search stations, shows and episodes")
        if(query.isBlank() && collections.recentSearches().isNotEmpty()) {
            content.addView(label("Recent searches", 16, true)); collections.recentSearches().forEach { q -> content.addView(button(q) { query = q; page = 0; render() }) }
            content.addView(button("Clear recent searches") { collections.clearSearches(); render() })
        }
        if(query.isNotBlank()) { content.addView(label("Shows", 21, true)); collections.sources().filter { it.title.contains(query, true) }.forEach { s -> content.addView(button(s.title) { showSource(s.id) }) } }
        content.addView(selector(listOf("All sources", "Live radio", "Podcasts", "Archive"), searchType) { searchType = it; page = 0; render() })
        val genres = listOf("All genres") + (stations.map { it.genre } + collections.sources().map { it.genre }).distinct().sorted()
        content.addView(selector(genres, searchGenre) { searchGenre = it; page = 0; render() })
        if (searchType == "All sources" || searchType == "Live radio") {
            val found = stations.filter { genreMatches(it.genre) && (query.isBlank() || "${it.name} ${it.network} ${it.genre}".contains(query, true)) }
            content.addView(label("Live stations · ${found.size}", 21, true))
            if (searchType == "Live radio") {
                page = page.coerceAtMost((found.size - 1).coerceAtLeast(0) / 15)
                found.drop(page * 15).take(15).forEach { stationCard(it) }
                if (found.size > 15) pager(found.size, 15)
            } else {
                found.take(6).forEach { stationCard(it) }
                if (found.size > 6) content.addView(button("Show all ${found.size} station results") { searchType = "Live radio"; page = 0; render() })
            }
        }
        if (searchType != "Live radio") {
            content.addView(label("Episodes", 21, true))
            episodeList(store.episodes().filter { e -> val source = collections.sources().find { it.id == e.source }
                matches(e) && genreMatches(source?.genre.orEmpty()) && (searchType == "All sources" || source?.kind == if (searchType == "Podcasts") "rss" else "archive") })
        }
    }
    private fun pager(count: Int, size: Int) {
        val r = row(); r.addView(button("‹ Previous") { if (page > 0) { page--; render() } }, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(label("${page + 1} / ${(count + size - 1) / size}")); r.addView(button("Next ›") { if ((page + 1) * size < count) { page++; render() } }, LinearLayout.LayoutParams(0, -2, 1f)); content.addView(r)
    }
    private fun renderDiscover() {
        val source = collections.sources().find { it.id == sourceId }
        if (source == null) {
            content.addView(label(if (tab == "podcasts") "Podcasts" else "The repositories", 29, true))
            content.addView(button("Programme pages & daily picks") { collection("Programmes") })
            content.addView(button("Manage / add sources") { collection("Sources") })
            content.addView(label("Choose a programme, save an episode and pick up where you left off. English-language selections.", 15))
            content.addView(button(if (busy) "Refreshing…" else "Refresh followed podcasts") { refresh(collections.sources().filter { it.kind == "rss" && it.id in store.follows() }) })
            for (kind in listOf(if (tab == "podcasts") "rss" else "archive")) {
                content.addView(label(if (kind == "rss") "Podcasts" else "The archive shelves", 22, true))
                collections.sources().filter { it.kind == kind }.forEach { s ->
                    val c = card(); val row = row()
                    val representative = Episode("", s.id, "", s.title, "", s.page)
                    row.addView(ImageView(this).apply { setImageDrawable(StationArt.drawable(this@LibraryActivity, representative.artStation())); scaleType = ImageView.ScaleType.CENTER_CROP; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(62), dp(82)))
                    val title = column(); title.addView(label(s.title, 18, true)); title.addView(label("${store.episodes().count { it.source == s.id }} episodes • ${s.genre}", 12))
                    row.addView(title, LinearLayout.LayoutParams(0, -2, 1f)); c.addView(row)
                    c.addView(button("Browse episodes  ›") { showSource(s.id) }); content.addView(c)
                }
            }
            content.addView(label("Explore more sources", 22, true))
            directory.filter { it.category == (if (tab == "podcasts") "podcast" else "archive") && it.sourceId.isBlank() }.forEach { directoryCard(it) }
            if (tab != "podcasts") { content.addView(label("Other resources", 22, true)); directory.filter { it.category == "other" }.forEach { directoryCard(it) } }
            content.addView(label("Audio streams from the original provider. Catalogue entries are bundled for browsing; availability can change. Illustrative artwork uses the app’s existing credited collection.", 12))
        } else {
            content.addView(button("‹ All sources") { sourceId = null; query = ""; page = 0; render() })
            content.addView(label(source.title, 26, true))
            val sourceEpisodes = store.episodes().filter { it.source == source.id }
            val representative = sourceEpisodes.firstOrNull()
            if (representative != null) content.addView(artwork(representative, 180))
            content.addView(label(if (source.kind == "rss") "Podcast feed. Entries may contain multiple programmes; titles and descriptions are supplied by the publisher." else "${source.title} recordings from Internet Archive. Recording details vary by file.", 14))
            val done = sourceEpisodes.count { store.completed(it.id) }
            content.addView(label("$done / ${sourceEpisodes.size} episodes played", 13))
            content.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = sourceEpisodes.size.coerceAtLeast(1); progress = done; contentDescription = "$done episodes played" })
            if (representative != null) content.addView(button("Cover illustration credits") { AlertDialog.Builder(this).setTitle("Artwork credits").setMessage(StationArt.credits(this, representative.artStation())).setPositiveButton("Close", null).show() })
            val controls = row()
            controls.addView(button(if (source.id in store.follows()) "✓ Following" else "+ Follow") { store.toggle("follows", source.id); render(false) }, LinearLayout.LayoutParams(0, -2, 1f))
            controls.addView(button("Refresh") { refresh(listOf(source)) }, LinearLayout.LayoutParams(0, -2, 1f))
            controls.addView(button("Source ↗") { openPage(source.page) }, LinearLayout.LayoutParams(0, -2, 1f)); content.addView(controls)
            val updated = store.updated(source.id)
            content.addView(label(if (updated == 0L) "Bundled catalogue • refresh to check for updates" else "Updated ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(updated))}", 12))
            searchControls()
            episodeList(store.episodes().filter { it.source == source.id && matches(it) })
        }
    }
    private fun searchControls(hintText: String = "Search these episodes") {
        val r = row(); val search = EditText(this).apply { hint = hintText; setText(query); setSingleLine(); textSize = 14f; setTextColor(getColor(R.color.otr_ink)); setHintTextColor(getColor(R.color.otr_muted)) }
        r.addView(search, LinearLayout.LayoutParams(0, -2, 1f)); r.addView(button("Find") { query = search.text.toString().trim(); collections.rememberSearch(query); page = 0; WindowCompat.getInsetsController(window, content).hide(WindowInsetsCompat.Type.ime()); render() }); content.addView(r)
    }
    private fun matches(e: Episode) = query.isBlank() || "${e.title} ${e.series} ${e.date} ${e.description}".contains(query, true)
    private fun episodeList(items: List<Episode>) {
        content.addView(label("${items.size} episodes", 12))
        if (items.isEmpty()) content.addView(label("Nothing here yet. Browse a source to find an episode."))
        page = page.coerceAtMost((items.size - 1).coerceAtLeast(0) / 25)
        items.drop(page * 25).take(25).forEach { episodeCard(it) }
        if (items.size > 25) pager(items.size, 25)
    }
    private fun episodeCard(e: Episode) {
        val c = card(); val heading = row(); heading.addView(artwork(e, 58), LinearLayout.LayoutParams(dp(58), dp(66))); heading.addView(label(e.title, 17, true), LinearLayout.LayoutParams(0, -2, 1f)); c.addView(heading); c.addView(label(e.series, 13))
        val date = runCatching { java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.US).parse(e.date)?.let { java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.UK).format(it) } }.getOrNull() ?: e.date
        val details = listOf(date, if (e.duration > 0) time(e.duration) else "", if (store.completed(e.id)) "✓ Played" else if (store.progress(e.id) > 0) "Resume at ${time(store.progress(e.id))}" else "").filter { it.isNotBlank() }.joinToString(" • ")
        c.addView(label(details, 12)); val r = row()
        if (store.progress(e.id) > 0 && e.duration > 0) c.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progress = (store.progress(e.id) * 1000 / e.duration).toInt().coerceIn(0, 1000); contentDescription = "Listening progress" })
        r.addView(button(if (store.progress(e.id) > 0) "▶ Resume" else "▶ Play") { play(e) }, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(button(if (e.id in store.saved()) "♥ Saved" else "♡ Save") { store.toggle("saved", e.id); render(false) }, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(button("More") { episodeDetails(e) }, LinearLayout.LayoutParams(0, -2, 1f)); c.addView(r); content.addView(c)
    }
    private fun episodeDetails(e: Episode) {
        val options = arrayOf("Play next", "Add to end", "Remove from queue", "Play from beginning", if (store.completed(e.id)) "Mark unplayed" else "Mark played", "Episode details", "Open original source", "Open show page", "Download audio", "Add to playlist")
        AlertDialog.Builder(this).setTitle(e.title).setItems(options) { _, index -> when (index) {
            0, 1 -> { store.enqueue(e.id, index == 0); syncQueue(); toast(if (index == 0) "Added to play next" else "Added to end"); render(false) }
            2 -> { store.setQueue(store.queue() - e.id); syncQueue(); render(false) }
            3 -> play(e, true)
            4 -> { if (controller?.currentMediaItem?.mediaId == e.id) toast("Choose another episode before changing its played status") else { store.markPlayed(e.id, !store.completed(e.id)); render(false) } }
            5 -> AlertDialog.Builder(this).setTitle(e.title).setMessage(e.description.ifBlank { "No description supplied by this source." }).setPositiveButton("Close", null).show()
            6 -> openPage(e.page)
            7 -> showSource(e.source)
            8 -> AlertDialog.Builder(this).setTitle("Download for personal listening?").setMessage("Audio comes from the original provider. Manage network preferences and files in Downloads.").setPositiveButton("Download") { _, _ -> runCatching { OfflineAudio(this).start(e); toast("Download queued") }.onFailure { toast(it.message.orEmpty()) } }.setNegativeButton("Cancel", null).show()
            9 -> addToPlaylist(e)
        } }.show()
    }
    private fun renderLibrary() {
        content.addView(label("Your favourites", 29, true))
        content.addView(button("Queue") { tab = "queue"; render() })
        content.addView(button("Daily picks & continue listening") { tab = "picks"; render() })
        content.addView(label("Favourite stations", 21, true))
        stations.filter { it.id in radioPrefs.getStringSet("favourites", emptySet()).orEmpty() }.forEach { stationCard(it) }
        content.addView(label("Saved sources & channels", 21, true))
        directory.filter { it.id in store.directorySaved() }.forEach { directoryCard(it) }
        val tools = HorizontalScrollView(this); val r = row(); listOf("Playlists", "Bookmarks", "Downloads", "History").forEach { s -> r.addView(button(s) { collection(s) }) }; tools.addView(r); content.addView(tools)
        val list = store.episodes()
        content.addView(label("Following", 21, true))
        collections.sources().filter { it.id in store.follows() }.forEach { s -> content.addView(button("${s.title}  ›") { showSource(s.id) }) }
        if (store.follows().isEmpty()) content.addView(label("Follow a source from Repo or Podcasts to keep it close."))
        content.addView(label("Save keeps an episode in your library. Download audio separately for offline listening.", 13))
        content.addView(selector(listOf("Saved", "In progress", "Played", "Unplayed", "All episodes"), libraryView) { libraryView = it; page = 0; render() })
        content.addView(label(if (libraryView == "Saved") "Saved episodes" else libraryView, 21, true)); searchControls()
        episodeList(list.filter { e -> matches(e) && when (libraryView) { "Saved" -> e.id in store.saved(); "In progress" -> store.progress(e.id) > 0; "Played" -> store.completed(e.id); "Unplayed" -> !store.completed(e.id); else -> true } }.let { if (libraryView in listOf("In progress", "Played")) it.sortedByDescending { e -> store.played(e.id) } else it })
    }
    private fun renderQueue() {
        content.addView(label("Your queue", 29, true)); content.addView(label("Episodes play in this order. Live radio remains a separate choice."))
        val queue = store.queue().mapNotNull { store.find(it) }
        if (queue.isEmpty()) content.addView(label("Use More → Play next or Add to end on any episode."))
        else content.addView(button("▶ Start queue") { play(queue.first()) })
        queue.forEachIndexed { index, e ->
            val c = card(); c.addView(label("${index + 1}. ${e.title}", 17, true)); val r = row()
            r.addView(button("Play") { play(e) }, LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(button("↑") { store.moveQueue(e.id, -1); syncQueue(); render(false) }.apply { contentDescription = "Move up"; isEnabled = index > 0 }, LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(button("↓") { store.moveQueue(e.id, 1); syncQueue(); render(false) }.apply { contentDescription = "Move down"; isEnabled = index < queue.lastIndex }, LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(button("Remove") { store.setQueue(store.queue() - e.id); syncQueue(); render() }, LinearLayout.LayoutParams(0, -2, 1f)); c.addView(r); content.addView(c)
        }
    }
    private fun syncQueue() {
        val c = controller ?: return; val current = store.find(c.currentMediaItem?.mediaId) ?: return
        if (c.mediaItemCount > c.currentMediaItemIndex + 1) c.removeMediaItems(c.currentMediaItemIndex + 1, c.mediaItemCount)
        c.addMediaItems(store.queue().filter { it != current.id }.mapNotNull { store.find(it)?.media(this) })
    }
    private fun play(e: Episode, restart: Boolean = false, forceReload: Boolean = false) {
        val c = controller ?: return toast("Player is connecting. Please try again.")
        if (!restart && !forceReload && c.currentMediaItem?.mediaId == e.id && c.playbackState != Player.STATE_ENDED && c.playerError == null) { if (c.playbackState == Player.STATE_IDLE) c.prepare(); c.play() }
        else {
            val items = listOf(e) + store.queue().filter { it != e.id }.mapNotNull { store.find(it) }
            c.setMediaItems(items.map { it.media(this) }, 0, if (restart) 0 else store.progress(e.id)); c.prepare(); c.play()
        }
        if (tab != "player") returnTab = tab
        tab = "player"; render()
    }
    private fun renderPlayer() {
        val e = store.find(controller?.currentMediaItem?.mediaId) ?: store.find(store.last())
        if (e == null) { content.addView(label("Choose an episode from Repo or Podcasts", 24, true)); return }
        playerEpisode = e
        content.addView(button("‹ Back") { tab = returnTab; render() })
        content.addView(label("EPISODE  •  NOW LISTENING", 12, true).apply { gravity = Gravity.CENTER })
        content.addView(artwork(e, 280).apply { scaleType = ImageView.ScaleType.FIT_CENTER })
        playerTitle = label(e.title, 24, true).apply { gravity = Gravity.CENTER }.also { content.addView(it) }; content.addView(label(e.series, 15).apply { gravity = Gravity.CENTER; setTextColor(getColor(R.color.otr_muted)) })
        playerTime = label("", 13).apply { gravity = Gravity.CENTER; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE }.also { content.addView(it) }
        errorActions = column().apply {
            setBackgroundResource(R.drawable.glass_panel)
            addView(label("The source could not be reached. You can retry, move on, or check its original page.", 14))
            val choices = row()
            choices.addView(button("Retry") { play(e) }, LinearLayout.LayoutParams(0, -2, 1f))
            choices.addView(button("Next") { nextEpisode() }, LinearLayout.LayoutParams(0, -2, 1f))
            choices.addView(button("Source ↗") { openPage(e.page) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(choices)
        }.also { content.addView(it) }
        seek = SeekBar(this).apply {
            max = 1000; contentDescription = "Episode position"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) = Unit
                override fun onStartTrackingTouch(bar: SeekBar?) { seeking = true }
                override fun onStopTrackingTouch(bar: SeekBar?) { controller?.let { if (it.currentMediaItem?.mediaId == e.id && it.duration > 0) it.seekTo(it.duration * (bar?.progress ?: 0) / 1000) }; seeking = false }
            })
        }.also { content.addView(it) }
        val r = row(); r.gravity = Gravity.CENTER
        r.addView(button("↶ 15 sec") { skip(-15000) })
        playerToggle = ImageButton(this).apply {
            setImageResource(R.drawable.ic_play); setBackgroundResource(R.drawable.accent_gradient); setPadding(dp(22), dp(22), dp(22), dp(22)); imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            setOnClickListener { val c = controller; if (c?.currentMediaItem?.mediaId == e.id && c.playWhenReady && c.playbackState != Player.STATE_ENDED) c.pause() else play(e) }
        }.also { r.addView(it, LinearLayout.LayoutParams(dp(76), dp(76))) }
        r.addView(button("30 sec ↷") { skip(30000) }); content.addView(r)
        val actions = row().apply { gravity = Gravity.CENTER }
        actions.addView(iconButton(R.drawable.ic_heart, if (e.id in store.saved()) "Remove from favourites" else "Add to favourites") {
            store.toggle("saved", e.id); render(false)
        }.apply { alpha = if (e.id in store.saved()) 1f else 0.55f })
        actions.addView(button("Queue") { tab = "queue"; render() })
        actions.addView(iconButton(R.drawable.ic_next, "Next queued episode") { nextEpisode() })
        actions.addView(button("More ⋯") { episodePlayerOptions(e) })
        content.addView(actions)
    }
    private fun episodePlayerOptions(e: Episode) {
        AlertDialog.Builder(this).setTitle(e.series)
            .setItems(arrayOf("Bookmark this moment", "Episode details", "Queue & download options", "Original source", "Artwork credits")) { _, which ->
                when (which) {
                    0 -> { val input = EditText(this).apply { hint = "Optional note" }; val position = if(controller?.currentMediaItem?.mediaId == e.id) controller!!.currentPosition else store.progress(e.id); AlertDialog.Builder(this).setTitle("Bookmark ${time(position)}").setView(input).setPositiveButton("Save") { _, _ -> collections.bookmark(e.id, position, input.text.toString()); toast("Bookmark saved") }.setNegativeButton("Cancel", null).show() }
                    1 -> AlertDialog.Builder(this).setTitle(e.title).setMessage(e.description.ifBlank { "No description supplied." } + "\n\nProvider date: ${e.date.ifBlank { "Not supplied" }}\nSource: ${e.page}").setPositiveButton("Close", null).show()
                    2 -> episodeDetails(e)
                    3 -> openPage(e.page)
                    4 -> AlertDialog.Builder(this).setTitle("Artwork credits").setMessage((if (e.image.isNotBlank()) "Publisher-supplied image: ${e.image}\nSource: ${e.page}\nFallback artwork:\n\n" else "") + StationArt.credits(this, e.artStation())).setPositiveButton("Close", null).show()
                }
            }.show()
    }

    private fun skip(delta: Long) { val c = controller ?: return; if (c.currentMediaItem?.mediaId != playerEpisode?.id) return; c.seekTo((c.currentPosition + delta).coerceIn(0, if (c.duration > 0) c.duration else Long.MAX_VALUE)) }
    private fun nextEpisode() {
        val c = controller ?: return
        if (!c.hasNextMediaItem()) { toast("Your queue has no next episode"); return }
        val next = c.nextMediaItemIndex; val item = c.getMediaItemAt(next)
        store.setQueue(store.queue() - c.currentMediaItem?.mediaId.orEmpty())
        c.seekTo(next, store.progress(item.mediaId)); c.prepare(); c.play(); render()
    }
    private fun updatePlayer() {
        val c = controller; val episode = c?.currentMediaItem?.mediaId?.startsWith("episode:") == true
        mini.visibility = if (tab != "player" && (c?.currentMediaItem != null || store.last() != null)) View.VISIBLE else View.GONE
        miniPause.visibility = mini.visibility; miniArtwork.visibility = mini.visibility
        val canPauseMini = c?.playWhenReady == true && c.playbackState != Player.STATE_ENDED
        miniPause.setImageResource(if (canPauseMini) R.drawable.ic_pause else R.drawable.ic_play)
        miniPause.contentDescription = if (canPauseMini) "Pause" else "Play"
        val artId = c?.currentMediaItem?.mediaId ?: store.last()
        if (artId != miniArtworkId) {
            miniArtworkId = artId; miniArtwork.tag = null
            val item = store.find(artId)
            val station = item?.artStation() ?: stations.find { it.id == artId }
            if (station != null) miniArtwork.setImageDrawable(StationArt.drawable(this, station)) else miniArtwork.setImageResource(R.drawable.ic_wave)
            item?.let { FeedArtwork.load(applicationContext, miniArtwork, it.image) }
        }
        mini.text = if (c?.currentMediaItem != null) "${c.mediaMetadata.title ?: "Open player"}  ›" else "Resume your last episode  ›"
        mini.setOnClickListener { if (c?.currentMediaItem != null && !episode) openRadio() else { if (tab != "player") returnTab = tab; tab = "player"; render() } }
        val e = playerEpisode ?: return
        val active = c?.currentMediaItem?.mediaId == e.id
        errorActions?.visibility = if (active && c?.playerError != null && !c.playWhenReady) View.VISIBLE else View.GONE
        val position = if (active) c!!.currentPosition else store.progress(e.id)
        val duration = if (active && c!!.duration > 0) c.duration else e.duration
        playerTime?.text = "${time(position)} / ${if (duration > 0) time(duration) else "—"}" + if (active) when {
            c!!.playerError != null -> if (c.playWhenReady) " • Trying to reconnect…" else " • Source unavailable"
            c.playbackState == Player.STATE_ENDED -> " • Completed"
            c.playbackState == Player.STATE_BUFFERING -> " • Buffering…"
            c.isPlaying -> " • Playing"
            else -> " • Paused"
        } else ""
        if (!seeking) seek?.progress = if (duration > 0) (position * 1000 / duration).toInt().coerceIn(0, 1000) else 0
        seek?.isEnabled = active && c?.isCurrentMediaItemSeekable == true
        val canPause = active && c?.playWhenReady == true && c.playbackState != Player.STATE_ENDED
        playerToggle?.setImageResource(if (canPause) R.drawable.ic_pause else R.drawable.ic_play)
        playerToggle?.contentDescription = if (canPause) "Pause" else "Play"
    }
    private fun time(ms: Long): String { val s = ms.coerceAtLeast(0) / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }
    private fun refresh(sources: List<EpisodeSource>) {
        if (busy) return
        if (sources.isEmpty()) return toast("Follow a podcast first, or open a source and tap Refresh.")
        busy = true; status.text = "Refreshing ${sources.size} source(s)…"
        work.execute {
            val errors = mutableListOf<String>(); var count = 0
            for (s in sources) {
                if (Thread.currentThread().isInterrupted) break
                runCatching { EpisodeCatalogue.refresh(s) }.onSuccess { store.update(s, it); count++ }.onFailure { errors += "${s.title}: ${it.message}" }
            }
            runOnUiThread { if (!isDestroyed) {
                busy = false; status.text = "$count source(s) updated" + if (errors.isNotEmpty()) " • ${errors.size} unavailable; saved entries kept" else ""; render()
                if (errors.isNotEmpty()) AlertDialog.Builder(this).setTitle("Some sources could not refresh").setMessage(errors.joinToString("\n\n")).setPositiveButton("Close", null).show()
            } }
        }
    }
    private fun openPage(url: String) { if (EpisodeCatalogue.validUrl(url)) runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { toast("No browser available") } }
    private fun settings() {
        AlertDialog.Builder(this).setTitle("Library settings").setItems(arrayOf("Switch light / dark mode", "Export library backup", "Import library backup", "About this preview", "Sources & feed updates", "Downloads", "Playlists", "Bookmarks", "History")) { _, n -> when (n) {
            0 -> { val p = getSharedPreferences("otr_dial", MODE_PRIVATE); p.edit().putBoolean("dark_mode", !p.getBoolean("dark_mode", false)).apply(); recreate() }
            1 -> exportBackup.launch("OTR-Dial-library-backup.json")
            2 -> AlertDialog.Builder(this).setTitle("Merge app backup?").setMessage("Saved episodes, followed shows, queue and radio favourites will merge. Imported listening progress and theme replace matching settings. Audio and recordings are not included. Backups from the earlier preview are also supported.").setPositiveButton("Choose backup") { _, _ -> importBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }.setNegativeButton("Cancel", null).show()
            3 -> AlertDialog.Builder(this).setTitle("OTR Dial 2.7 preview").setMessage("Live radio, podcasts, archives, offline episodes and your personal collection. This release adds checked English-language streams, publisher RSS feeds and on-demand Internet Archive collections. Backups include playlists, bookmarks and custom sources, but not downloaded audio. Original providers control availability. YouTube channels and daily OTRCAT selections open on their original websites.").setPositiveButton("Close", null).show()
            4 -> collection("Sources")
            5 -> collection("Downloads")
            6 -> collection("Playlists")
            7 -> collection("Bookmarks")
            8 -> collection("History")
        } }.show()
    }
    private fun addToPlaylist(e: Episode) {
        val all = collections.playlists()
        AlertDialog.Builder(this).setTitle("Add to playlist").setItems((all.map { it.getString("name") } + "+ New playlist").toTypedArray()) { _, n ->
            if(n == all.size) { val input = EditText(this); AlertDialog.Builder(this).setTitle("Playlist name").setView(input).setPositiveButton("Create") { _, _ -> runCatching { val id = collections.createPlaylist(input.text.toString()); collections.editPlaylist(id, episodes = listOf(e.id)); toast("Added to playlist") }.onFailure { toast("Enter a playlist name") } }.setNegativeButton("Cancel", null).show() }
            else { val id = all[n].getString("id"); collections.editPlaylist(id, episodes = collections.playlistIds(id) + e.id); toast("Added to playlist") }
        }.show()
    }
}
