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
    private lateinit var content: ScreenList
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
    private var renderedRoute: Bundle? = null
    private val backRoutes = arrayListOf<Bundle>()
    private var restoringRoute = false
    private var restoredScroll: android.os.Parcelable? = null
    private fun route() = Bundle().apply { putString("tab",tab); putString("source",sourceId); putString("query",query); putInt("page",page); putString("filter",libraryView); putString("search_type",searchType); putString("genre",searchGenre) }
    private fun routeKey(b: Bundle) = listOf(b.getString("tab"),b.getString("source"),b.getString("query"),b.getInt("page"),b.getString("filter"),b.getString("search_type"),b.getString("genre")).joinToString("|")
    private fun goBack() {
        val b=if(backRoutes.isNotEmpty()) backRoutes.removeAt(backRoutes.lastIndex) else null
        if(b==null) { if(intent.getBooleanExtra("from_collection",false)) { finish(); return }; if(tab=="home") { finish(); return }; tab="home"; sourceId=null; query=""; page=0 }
        else { tab=b.getString("tab") ?: "home"; sourceId=b.getString("source"); query=b.getString("query").orEmpty(); page=b.getInt("page"); libraryView=b.getString("filter") ?: "Saved"; searchType=b.getString("search_type") ?: "All sources"; searchGenre=b.getString("genre") ?: "All genres"; restoredScroll=b.getParcelable("scroll") }
        restoringRoute=true; render(false)
    }
    private val tick = object : Runnable { override fun run() { updatePlayer(); handler.postDelayed(this, 1000) } }
    private val exportBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) work.execute {
            val result=runCatching { val backup=store.export(); contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(backup) } ?: error("Cannot open file") }
            runOnUiThread { result.onSuccess { toast("Library backup saved. Audio is excluded.") }.onFailure { toast("Could not save backup: ${it.message}") } }
        }
    }
    private val importBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work.execute {
            val result = runCatching {
                val raw = contentResolver.openInputStream(uri)?.use { it.readLimited(LibraryStore.BACKUP_LIMIT) } ?: error("Cannot open file")
                require(raw.size <= LibraryStore.BACKUP_LIMIT) { "Backup too large" }; store.restore(raw.toString(Charsets.UTF_8))
            }
            runOnUiThread { if (!isDestroyed) { result.onSuccess { toast("Library and radio favourites restored"); recreate() }.onFailure { toast("Backup not imported: ${it.message}") } } }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.localNightMode = if (getSharedPreferences("otr_dial", MODE_PRIVATE).getBoolean("dark_mode", false)) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                goBack()
            }
        })
        tab = savedInstanceState?.getString("tab") ?: if (intent.getBooleanExtra("player", false)) "player" else "home"
        savedInstanceState?.getParcelableArrayList<Bundle>("routes")?.let { backRoutes.addAll(it) }; restoredScroll=savedInstanceState?.getParcelable("scroll")
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
        status = label("", 11); root.addView(status)
        content = ScreenList(this); root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        val miniRow = row(); root.addView(miniRow)
        miniArtwork = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundResource(R.drawable.glass_panel); clipToOutline = true; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        miniRow.addView(miniArtwork, LinearLayout.LayoutParams(dp(48), dp(48)))
        mini = button("Choose an episode") { if (tab != "player") returnTab = tab; tab = "player"; render() }.apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }; miniRow.addView(mini, LinearLayout.LayoutParams(0, -2, 1f))
        miniPause = iconButton(R.drawable.ic_play, "Play") { val c = controller; if(c?.playWhenReady == true) c.pause() else if(c?.currentMediaItem != null) { if(c.playbackState == Player.STATE_IDLE) c.prepare(); c.play() } else store.find(store.last())?.let { play(it) } }; miniRow.addView(miniPause)
        val nav = row(); navigation = nav; root.addView(nav)
        listOf("home" to "Radio", "discover" to "Archives", "podcasts" to "Podcasts", "youtube" to "YouTube", "library" to "Library").forEach { (key, title) ->
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
            intent.getStringExtra("station_id")?.let { id -> intent.removeExtra("station_id"); openRadio(id) }
            val requested = intent.getStringExtra("episode_id")
            intent.removeExtra("episode_id")
            store.find(requested)?.let { e ->
                val position = intent.getLongExtra("episode_position", -1)
                play(e)
                syncQueue()
                if(position >= 0) c.seekTo(position)
                intent.removeExtra("episode_position")
            }
        }.onFailure { status.text = "Player could not connect. Reopen the library to retry." } }, ContextCompat.getMainExecutor(this))
        render()
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 10)
    }
    override fun onStart() { super.onStart(); handler.post(tick); if (::content.isInitialized && tab == "home") render(false) }
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
        renderedRoute?.putParcelable("scroll",content.state()); outState.putParcelable("scroll",content.state()); outState.putParcelableArrayList("routes",backRoutes)
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
        if (value.startsWith("▶")) setBackgroundResource(R.drawable.accent_gradient) else setBackgroundColor(android.graphics.Color.TRANSPARENT)
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
        val previousScroll = content.state()
        val next=route(); val old=renderedRoute
        if(old!=null && routeKey(old)!=routeKey(next) && !restoringRoute) { old.putParcelable("scroll",previousScroll); backRoutes.add(Bundle(old)); if(backRoutes.size>40) backRoutes.removeAt(0) }
        renderedRoute=next; restoringRoute=false
        content.clearRows(); playerTitle = null; playerTime = null; seek = null; playerToggle = null; playerEpisode = null; errorActions = null
        when (tab) { "home" -> renderRadio(); "podcasts" -> renderDiscover(); "youtube" -> renderYouTube(); "radio-directory" -> renderRadioDirectory(); "search" -> renderSearch(); "library" -> renderLibrary(); "queue" -> renderQueue(); "player" -> renderPlayer(); "picks" -> renderHome(); else -> renderDiscover() }
        navigation.visibility = if (tab == "player") View.GONE else View.VISIBLE
        screenHeader.visibility = navigation.visibility
        status.visibility = if(status.text.isNotBlank() && tab != "player") View.VISIBLE else View.GONE
        navButtons.forEach { (key, b) -> b.setBackgroundResource(if (key == tab) R.drawable.accent_gradient else R.drawable.glass_panel); val colour = if (key == tab) android.graphics.Color.WHITE else getColor(R.color.otr_brown); b.setTextColor(colour); b.compoundDrawables.filterNotNull().forEach { it.setTint(colour) } }
        updatePlayer()
        if(restoredScroll!=null) { content.restore(restoredScroll); restoredScroll=null } else if (resetScroll) content.scrollToPosition(0) else content.restore(previousScroll)
    }
    private fun openRadio(id: String? = null) {
        val c=controller ?: return toast("Player is connecting. Please try again.")
        val station=stations.find { it.id==(id ?: c.currentMediaItem?.mediaId) } ?: return
        if(id!=null) {
            if(c.currentMediaItem?.mediaId!=station.id) {
                val metadata=androidx.media3.common.MediaMetadata.Builder().setTitle(station.name).setArtist(station.network).setSubtitle(station.genre).setArtworkData(StationArt.bytes(this,station),androidx.media3.common.MediaMetadata.PICTURE_TYPE_FRONT_COVER).build()
                c.setMediaItem(androidx.media3.common.MediaItem.Builder().setMediaId(station.id).setUri(station.streamUrl).setMediaMetadata(metadata).build()); c.prepare()
            } else if(c.playbackState==Player.STATE_IDLE) c.prepare()
            c.play()
        }
        if(tab!="player") returnTab=tab; tab="player"; render()
    }
    private fun renderLivePlayer(station: Station) {
        content.add(button("‹ Back") { goBack() })
        content.add(label("LIVE RADIO • NOW LISTENING",12,true).apply { gravity=Gravity.CENTER })
        content.add(ImageView(this).apply { setImageDrawable(StationArt.drawable(this@LibraryActivity,station)); scaleType=ImageView.ScaleType.FIT_CENTER; minimumHeight=dp(220); maxHeight=dp(260); adjustViewBounds=true; contentDescription="Illustrative station artwork" })
        content.add(label(station.name,24,true).apply { gravity=Gravity.CENTER })
        playerTitle=label(station.network,15).apply { gravity=Gravity.CENTER }.also { content.add(it) }
        playerTime=label("",13).apply { gravity=Gravity.CENTER }.also { content.add(it) }
        val controls=row().apply { gravity=Gravity.CENTER }
        fun adjacent(delta:Int) { val index=stations.indexOfFirst { it.id==station.id }; openRadio(stations[(index+delta+stations.size)%stations.size].id) }
        controls.addView(iconButton(R.drawable.ic_previous,"Previous station") { adjacent(-1) })
        playerToggle=iconButton(R.drawable.ic_play,"Play") { controller?.let { if(it.playWhenReady) it.pause() else { if(it.playbackState==Player.STATE_IDLE) it.prepare(); it.play() } } }.apply { setBackgroundResource(R.drawable.accent_gradient); imageTintList=android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE); layoutParams=LinearLayout.LayoutParams(dp(76),dp(76)) }.also { controls.addView(it) }
        controls.addView(iconButton(R.drawable.ic_next,"Next station") { adjacent(1) }); content.add(controls)
        content.add(button("Station options ⋮") { AlertDialog.Builder(this).setTitle(station.name).setItems(arrayOf("Favourite station", "Opens website", "Artwork credits", "Recording, schedule and stream tools")) { _,n -> when(n) {
            0 -> { val ids=radioPrefs.getStringSet("favourites",emptySet()).orEmpty().toMutableSet(); if(!ids.add(station.id)) ids.remove(station.id); radioPrefs.edit().putStringSet("favourites",ids).apply(); toast(if(station.id in ids) "Favourite station added" else "Favourite station removed") }
            1 -> openPage(station.homepage)
            2 -> AlertDialog.Builder(this).setTitle("Illustrative artwork").setMessage(StationArt.credits(this,station)).setPositiveButton("Close",null).show()
            3 -> startActivity(Intent(this,MainActivity::class.java).putExtra("from_platform",true).putExtra("player",true))
        } }.show() })
    }
    private fun collection(screen: String) { startActivity(Intent(this, CollectionActivity::class.java).putExtra("screen", screen)) }
    private fun showSource(id: String) { sourceId = id; tab = if (collections.sources().find { it.id == id }?.kind == "rss") "podcasts" else "discover"; query = ""; page = 0; render() }
    private fun artwork(e: Episode, height: Int) = ImageView(this).apply {
        setImageDrawable(StationArt.drawable(this@LibraryActivity, e.artStation(this@LibraryActivity)))
        FeedArtwork.load(applicationContext, this, e.image)
        scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundResource(R.drawable.glass_panel); clipToOutline = true
        contentDescription = if(e.image.isBlank()) "Illustrative artwork; not an official cover" else "Programme artwork"
        layoutParams = LinearLayout.LayoutParams(-1, dp(height)).apply { bottomMargin = dp(8) }
    }
    private fun banner(title: String, detail: String) {
        val hero = card().apply { setBackgroundResource(R.drawable.hero_gradient) }
        hero.addView(label(title, 27, true).apply { setTextColor(android.graphics.Color.WHITE) })
        hero.addView(label(detail, 14).apply { setTextColor(android.graphics.Color.WHITE) })
        content.add(hero)
    }
    private fun renderRadio() {
        content.add(label("Radio", 24, true))
        searchControls("Find a station or genre")
        if(query.isBlank() && page == 0) {
            val resume = store.find(store.last())
            val currentStation = stations.find { it.id == controller?.currentMediaItem?.mediaId }
            if(resume != null || currentStation != null) {
                content.add(label("Continue listening", 18, true))
                if(currentStation != null) stationCard(currentStation) else resume?.let { episodeCard(it) }
            }
            val favourites = stations.filter { it.id in radioPrefs.getStringSet("favourites", emptySet()).orEmpty() }
            if(favourites.isNotEmpty()) { content.add(label("Favourite stations", 18, true)); favourites.take(3).forEach { stationCard(it) } }
        }
        val found = stations.filter { query.isBlank() || "${it.name} ${it.network} ${it.genre}".contains(query, true) }
        content.add(label("${found.size} stations • availability depends on the broadcaster", 12))
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
        content.add(label("Opens YouTube or your browser. Video playback and background listening are controlled by YouTube.", 14))
        directory.filter { it.category == "youtube" }.forEach { directoryCard(it) }
    }
    private fun directoryCard(d: DirectorySource) { content.addLazy {
        val c = card()
        val art = Station(d.id, d.title, d.title, d.genre, "", d.page, "", false, "")
        val heading = row()
        heading.addView(ImageView(this).apply { setImageDrawable(StationArt.drawable(this@LibraryActivity, art)); scaleType = ImageView.ScaleType.FIT_CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(64), dp(74)))
        heading.addView(label(d.title, 18, true), LinearLayout.LayoutParams(0, -2, 1f)); c.addView(heading)
        c.addView(label(d.notes, 13))
        if (d.warning.isNotBlank()) c.addView(label(d.warning, 12).apply { setTextColor(getColor(R.color.otr_gold)) })
        val source = collections.sources().find { it.id == d.sourceId }
            ?: d.episodeSource()?.let { suggested -> collections.sources().find { it.kind == suggested.kind && it.url == suggested.url } ?: suggested }
        val station = stations.find { it.id == d.stationId }
        if (station != null) c.addView(button("▶ Play in app") { openRadio(station.id) })
        else if (source != null) c.addView(button(if(source != null && store.count(source.id)==0) "Load episodes" else "Browse episodes") {
            if (collections.sources().none { it.id == source.id }) collections.addSource(source)
            showSource(source.id)
            if (store.count(source.id)==0) refresh(listOf(source))
        })
        else c.addView(label(if(d.category=="youtube") "Opens YouTube" else "Opens website", 12))
        val actions = row()
        actions.addView(button(if (d.category == "youtube" && d.page.contains("youtube.com")) "Open YouTube ↗" else "Open website ↗") { openPage(d.page) }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(button(if (d.id in store.directorySaved()) "♥ Saved" else "♡ Save source") { store.toggle("directory_saved", d.id); render(false) }, LinearLayout.LayoutParams(0, -2, 1f))
        c.addView(actions); c
    } }
    private fun renderHome() {
        val hero = card().apply { setBackgroundResource(R.drawable.hero_gradient) }
        hero.addView(label("A world of stories.", 25, true).apply { setTextColor(android.graphics.Color.WHITE) })
        hero.addView(label("Listen live. Explore. Keep your favourites.", 14).apply { setTextColor(android.graphics.Color.WHITE) })
        hero.addView(button("Explore the shows  ›") { tab = "discover"; sourceId = null; render() }); content.add(hero)
        val shortcuts = HorizontalScrollView(this); val shortcutRow = row()
        listOf("Programmes", "Playlists", "Bookmarks", "Downloads", "Sources").forEach { s -> shortcutRow.addView(button(s) { collection(s) }) }; shortcuts.addView(shortcutRow); content.add(shortcuts)
        val episodes = store.episodes()
        content.add(label("Your daily picks", 21, true)); content.add(label("App selections, not new releases • ${if(collections.followedProgrammes().isEmpty()) "from your catalogue" else "based on programme follows"}", 12))
        val shelf = HorizontalScrollView(this); val shelfRow = row()
        ProgrammeIndex.daily(episodes, collections.followedProgrammes()).forEach { e -> val tile = card(); tile.layoutParams = LinearLayout.LayoutParams(dp(160), -2).apply { setMargins(dp(3), 0, dp(5), 0) }; tile.addView(artwork(e, 100)); tile.addView(label(e.title, 14, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }); tile.addView(button("▶ Listen") { play(e) }); shelfRow.addView(tile) }; shelf.addView(shelfRow); content.add(shelf)
        content.add(label("Continue listening", 22, true))
        val unfinished = episodes.filter { store.progress(it.id) > 0 }.sortedByDescending { store.played(it.id) }.take(3)
        if (unfinished.isEmpty()) content.add(label("Start an episode and your place will be remembered here.", 14))
        else unfinished.forEach { episodeCard(it) }
        content.add(label("Favourite stations", 22, true))
        val favs = radioPrefs.getStringSet("favourites", emptySet()).orEmpty()
        val favourites = stations.filter { it.id in favs }
        if (favourites.isEmpty()) content.add(button("Find a favourite among ${stations.size} live stations  ›") { openRadio() })
        else favourites.take(6).forEach { stationCard(it) }
        if (favourites.size > 6) content.add(button("All live stations  ›") { openRadio() })
        content.add(label("Followed shows", 22, true))
        val followed = collections.sources().filter { it.id in store.follows() }
        if (followed.isEmpty()) content.add(label("Follow a show to keep it on your home screen.", 14))
        followed.forEach { s -> content.add(button("${s.title}  ›") { showSource(s.id) }) }
        content.add(button("Refresh followed podcasts") { refresh(followed.filter { it.kind == "rss" }) })
        content.add(label("Recently published podcasts", 22, true))
        content.add(label(if (followed.any { it.kind == "rss" }) "From the podcasts you follow • publisher dates" else "From the podcast catalogue • publisher dates", 12))
        val followedPodcasts = followed.filter { it.kind == "rss" }.map { it.id }
        episodes.filter { e -> collections.sources().any { it.id == e.source && it.kind == "rss" } && (followedPodcasts.isEmpty() || e.source in followedPodcasts) }
            .sortedByDescending { published(it.date) }.take(4).forEach { episodeCard(it) }
    }
    private fun published(date: String) = runCatching {
        java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.US).parse(date)?.time ?: 0L
    }.getOrDefault(0L)
    private fun stationCard(s: Station) { content.addLazy {
        val c = card(); val r = row()
        r.addView(ImageView(this).apply { setImageDrawable(StationArt.drawable(this@LibraryActivity, s)); scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "Illustrative station artwork" }, LinearLayout.LayoutParams(dp(52), dp(64)))
        val titles = column(); titles.addView(label(s.name, 16, true)); titles.addView(label("Live • ${s.genre}", 12))
        r.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(iconButton(R.drawable.ic_play, "Play ${s.name}") { openRadio(s.id) })
        r.addView(button("⋮") {
            val ids = radioPrefs.getStringSet("favourites", emptySet()).orEmpty().toMutableSet()
            AlertDialog.Builder(this).setTitle(s.name).setItems(arrayOf(if(s.id in ids) "Remove favourite station" else "Favourite station", "Opens website", "Artwork credits")) { _,n -> when(n) {
                0 -> { if(!ids.add(s.id)) ids.remove(s.id); radioPrefs.edit().putStringSet("favourites",ids).apply(); toast(if(s.id in ids) "Favourite station added" else "Favourite station removed") }
                1 -> openPage(s.homepage)
                2 -> AlertDialog.Builder(this).setTitle("Illustrative artwork").setMessage(StationArt.credits(this,s)).setPositiveButton("Close",null).show()
            } }.show()
        }.apply { contentDescription="Station options for ${s.name}" })
        c.addView(r); c
    } }
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
        content.add(label("Find your next story", 27, true)); searchControls("Search stations, shows and episodes")
        if(query.isBlank() && collections.recentSearches().isNotEmpty()) {
            content.add(label("Recent searches", 16, true)); collections.recentSearches().forEach { q -> content.add(button(q) { query = q; page = 0; render() }) }
            content.add(button("Clear recent searches") { collections.clearSearches(); render() })
        }
        if(query.isNotBlank()) {
            val programmes=ProgrammeDirectory.entries.filter { it.name.contains(query,true) }
            if(programmes.isNotEmpty()) content.add(label("Programmes",18,true))
            programmes.forEach { p -> content.add(button("${p.name} • all listening sources") { startActivity(Intent(this,CollectionActivity::class.java).putExtra("screen","Programmes").putExtra("programme",p.name)) }) }
            content.add(label("Sources • includes unloaded catalogues",18,true))
            collections.sources().filter { it.title.contains(query,true) }.forEach { source -> content.add(button("${source.title} • ${if(store.count(source.id)>0) "Browse episodes" else "Load episodes"}") { showSource(source.id) }) }
            directory.filter { !isAdded(it) && "${it.title} ${it.genre} ${it.notes}".contains(query,true) }.take(30).forEach { directoryCard(it) }
        }
        content.add(selector(listOf("All sources", "Live radio", "Podcasts", "Archive"), searchType) { searchType = it; page = 0; render() })
        val genres = listOf("All genres") + (stations.map { it.genre } + collections.sources().map { it.genre }).distinct().sorted()
        content.add(selector(genres, searchGenre) { searchGenre = it; page = 0; render() })
        if (searchType == "All sources" || searchType == "Live radio") {
            val found = stations.filter { genreMatches(it.genre) && (query.isBlank() || "${it.name} ${it.network} ${it.genre}".contains(query, true)) }
            content.add(label("Live stations · ${found.size}", 21, true))
            if (searchType == "Live radio") {
                page = page.coerceAtMost((found.size - 1).coerceAtLeast(0) / 15)
                found.drop(page * 15).take(15).forEach { stationCard(it) }
                if (found.size > 15) pager(found.size, 15)
            } else {
                found.take(6).forEach { stationCard(it) }
                if (found.size > 6) content.add(button("Show all ${found.size} station results") { searchType = "Live radio"; page = 0; render() })
            }
        }
        if (searchType != "Live radio") {
            content.add(label("Episodes", 21, true))
            if(searchType=="All sources" && searchGenre=="All genres") databaseEpisodeList()
            else episodeList(store.page(query=query,limit=store.count()).filter { e -> val source=collections.sources().find { it.id==e.source }; genreMatches(source?.genre.orEmpty()) && (searchType=="All sources" || source?.kind==if(searchType=="Podcasts") "rss" else "archive") })
        }
    }
    private fun pager(count: Int, size: Int) {
        val r = row(); r.addView(button("‹ Previous") { if (page > 0) { page--; render() } }, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(label("${page + 1} / ${(count + size - 1) / size}")); r.addView(button("Next ›") { if ((page + 1) * size < count) { page++; render() } }, LinearLayout.LayoutParams(0, -2, 1f)); content.add(r)
    }
    private fun renderDiscover() {
        val source = collections.sources().find { it.id == sourceId }
        if (source == null) {
            content.add(label(if (tab == "podcasts") "Podcasts" else "Archives", 24, true))
            content.add(button("Programme pages & daily picks") { collection("Programmes") })
            if(tab == "podcasts") content.add(button(if (busy) "Refreshing…" else "Refresh followed podcasts") { refresh(collections.sources().filter { it.kind == "rss" && it.id in store.follows() }) })
            for (kind in listOf(if (tab == "podcasts") "rss" else "archive")) {
                content.add(label(if (kind == "rss") "Podcasts" else "The archive shelves", 22, true))
                collections.sources().filter { it.kind == kind }.forEach { s ->
                    val c = card(); val row = row()
                    val representative = Episode("", s.id, "", s.title, "", s.page)
                    row.addView(ImageView(this).apply { setImageDrawable(StationArt.drawable(this@LibraryActivity, representative.artStation(this@LibraryActivity))); scaleType = ImageView.ScaleType.FIT_CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(56), dp(64)))
                    val title = column(); title.addView(label(s.title, 18, true)); title.addView(label("${store.count(s.id)} episodes • ${s.genre}", 12))
                    row.addView(title, LinearLayout.LayoutParams(0, -2, 1f)); c.addView(row)
                    c.addView(button("Browse episodes  ›") { showSource(s.id) }); content.add(c)
                }
            }
            content.add(label("Explore more sources", 22, true))
            directory.filter { it.category == (if (tab == "podcasts") "podcast" else "archive") && !isAdded(it) }.forEach { directoryCard(it) }
            if (tab != "podcasts") { content.add(label("Other resources", 22, true)); directory.filter { it.category == "other" }.forEach { directoryCard(it) } }
            content.add(label("Audio streams from the original provider. Catalogue entries are bundled for browsing; availability can change. Illustrative artwork uses the app’s existing credited collection.", 12))
        } else {
            content.add(button("‹ All sources") { goBack() })
            val representative = store.page(source.id, limit=1).firstOrNull()
            val heading = row()
            if(representative!=null) heading.addView(artwork(representative,72),LinearLayout.LayoutParams(dp(72),dp(80)))
            val titles=column(); titles.addView(label(source.title,21,true)); titles.addView(label(if(source.kind=="rss") "Podcast • publisher episode catalogue" else "Internet Archive • recordings and editions",12))
            heading.addView(titles,LinearLayout.LayoutParams(0,-2,1f)); content.add(heading)
            val resume = store.page(source.id, limit=100).find { store.progress(it.id)>0 } ?: representative
            val actions=row()
            if(resume!=null) actions.addView(button(if(store.progress(resume.id)>0) "▶ Resume" else "▶ Play") { play(resume) })
            actions.addView(button("⋮ Details") { sourceOptions(source,representative) })
            content.add(actions)
            val count=store.count(source.id)
            if(busy) content.add(label("Loading episodes… Your saved catalogue remains available.",12))
            val sourceStatus=collections.status(source.id)
            if(sourceStatus.startsWith("Unavailable")) { content.add(label(sourceStatus,12)); content.add(button("Retry loading episodes") { refresh(listOf(source)) }) }
            if(count==0 && !busy) { content.add(label("Episodes have not been loaded on this device.")); content.add(button("Load episodes") { refresh(listOf(source)) }) }
            searchControls()
            databaseEpisodeList(source.id)

        }
    }
    private fun isAdded(d: DirectorySource): Boolean {
        val all=collections.sources(); return all.any { it.id==d.sourceId } || d.episodeSource()?.let { candidate -> all.any { it.kind==candidate.kind && it.url==candidate.url } } == true
    }
    private fun sourceOptions(s: EpisodeSource, e: Episode?) {
        val updated=store.updated(s.id)
        AlertDialog.Builder(this).setTitle(s.title).setItems(arrayOf(if(s.id in store.follows()) "Unfollow source" else "Follow source", "Refresh episodes", "Opens website", "Source and artwork details")) { _,n -> when(n) {
            0 -> { store.toggle("follows",s.id); toast(if(s.id in store.follows()) "Following source" else "Source unfollowed") }
            1 -> refresh(listOf(s))
            2 -> openPage(s.page)
            3 -> AlertDialog.Builder(this).setTitle("Source details").setMessage("${s.page}\n" + (if(updated>0) "Last successful refresh: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(updated))}" else "No successful refresh recorded on this device") + "\n\n" + (e?.let { artworkDescription(it) } ?: "Illustrative artwork")).setPositiveButton("Close",null).show()
        } }.show()
    }
    private fun artworkDescription(e: Episode) = (if(e.image.isNotBlank()) "Publisher image: ${e.image}\nFallback if unavailable:\n" else "Illustrative fallback, not an official cover.\n") + StationArt.credits(this,e.artStation(this))
    private fun databaseEpisodeList(source: String? = null) {
        val batch=store.page(source,query,page*25,26)
        if(batch.isEmpty()) content.add(label(if(query.isNotBlank()) "No matching loaded episodes." else "No loaded episodes."))
        batch.take(25).forEach { episodeCard(it) }
        val r=row(); if(page>0) r.addView(button("‹ Previous") { page--; render() }); r.addView(label("Page ${page+1}",12)); if(batch.size>25) r.addView(button("Next ›") { page++; render() }); content.add(r)
    }
    private fun searchControls(hintText: String = "Search these episodes") {
        val r = row(); val search = EditText(this).apply { hint = hintText; setText(query); setSingleLine(); textSize = 14f; setTextColor(getColor(R.color.otr_ink)); setHintTextColor(getColor(R.color.otr_muted)) }
        r.addView(search, LinearLayout.LayoutParams(0, -2, 1f)); r.addView(button("Find") { query = search.text.toString().trim(); collections.rememberSearch(query); page = 0; WindowCompat.getInsetsController(window, content).hide(WindowInsetsCompat.Type.ime()); render() }); content.add(r)
    }
    private fun matches(e: Episode) = query.isBlank() || "${e.title} ${e.series} ${e.date} ${e.description}".contains(query, true)
    private fun episodeList(items: List<Episode>) {
        content.add(label("${items.size} episodes", 12))
        if (items.isEmpty()) content.add(label(if(query.isNotBlank()) "No matching loaded episodes. Search the source directory to find more programmes." else "No episodes in this view yet."))
        page = page.coerceAtMost((items.size - 1).coerceAtLeast(0) / 25)
        items.drop(page * 25).take(25).forEach { episodeCard(it) }
        if (items.size > 25) pager(items.size, 25)
    }
    private fun episodeCard(e: Episode) { content.addLazy {
        val c = card(); val heading = row()
        heading.addView(artwork(e, 56), LinearLayout.LayoutParams(dp(52),dp(64)))
        val titles = column(); titles.addView(label(e.title, 16, true))
        titles.addView(label(e.series, 12))
        val flags = listOf(if(store.progress(e.id)>0) "Resume ${time(store.progress(e.id))}" else if(store.completed(e.id)) "Played" else if(e.duration>0) time(e.duration) else "",
            if(e.id in store.saved()) "Saved" else "", if(OfflineAudio(this).local(e.id)!=null) "Downloaded" else "", if(e.id in store.queue()) "Queued" else "").filter { it.isNotBlank() }
        if(flags.isNotEmpty()) titles.addView(label(flags.joinToString(" • "),11))
        heading.addView(titles,LinearLayout.LayoutParams(0,-2,1f))
        heading.addView(iconButton(R.drawable.ic_play, if(store.progress(e.id)>0) "Resume ${e.title}" else "Play ${e.title}") { play(e) })
        heading.addView(button("⋮") { episodeDetails(e) }.apply { contentDescription="Episode options for ${e.title}" })
        c.addView(heading); c
    } }
    private fun episodeDetails(e: Episode) {
        val options = arrayOf("Play next", "Add to end", "Remove from queue", "Play from beginning", if (store.completed(e.id)) "Mark unplayed" else "Mark played", "Episode details", "Open original source", "Open show page", "Download audio", "Add to playlist", if(e.id in store.saved()) "Remove saved episode" else "Save episode")
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
            10 -> { store.toggle("saved", e.id); toast(if(e.id in store.saved()) "Episode saved" else "Episode removed from saved") }
        } }.show()
    }
    private fun renderLibrary() {
        content.add(label("Library", 24, true))
        content.add(button("Queue") { tab = "queue"; render() })
        content.add(button("Daily picks & continue listening") { tab = "picks"; render() })
        content.add(label("Favourite stations", 21, true))
        stations.filter { it.id in radioPrefs.getStringSet("favourites", emptySet()).orEmpty() }.forEach { stationCard(it) }
        content.add(label("Saved sources & channels", 21, true))
        directory.filter { it.id in store.directorySaved() }.forEach { directoryCard(it) }
        val tools = HorizontalScrollView(this); val r = row(); listOf("Programmes", "Playlists", "Bookmarks", "Downloads", "History").forEach { s -> r.addView(button(s) { collection(s) }) }; tools.addView(r); content.add(tools)
        val list = store.episodes()
        content.add(label("Following", 21, true))
        collections.sources().filter { it.id in store.follows() }.forEach { s -> content.add(button("${s.title}  ›") { showSource(s.id) }) }
        if (store.follows().isEmpty()) content.add(label("Follow a source from Archives or Podcasts to keep it close."))
        
        content.add(selector(listOf("Saved", "In progress", "Played", "Unplayed", "All episodes"), libraryView) { libraryView = it; page = 0; render() })
        content.add(label(if (libraryView == "Saved") "Saved episodes" else libraryView, 21, true)); searchControls()
        episodeList(list.filter { e -> matches(e) && when (libraryView) { "Saved" -> e.id in store.saved(); "In progress" -> store.progress(e.id) > 0; "Played" -> store.completed(e.id); "Unplayed" -> !store.completed(e.id); else -> true } }.let { if (libraryView in listOf("In progress", "Played")) it.sortedByDescending { e -> store.played(e.id) } else it })
    }
    private fun renderQueue() {
        content.add(label("Your queue", 29, true)); content.add(label("Episodes play in this order. Live radio remains a separate choice."))
        val queue = store.queue().mapNotNull { store.find(it) }
        if (queue.isEmpty()) content.add(label("Use More → Play next or Add to end on any episode."))
        else content.add(button("▶ Start queue") { play(queue.first()) })
        queue.forEachIndexed { index, e ->
            val c = card(); c.addView(label("${index + 1}. ${e.title}", 17, true)); val r = row()
            r.addView(button("Play") { play(e) }, LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(button("↑") { store.moveQueue(e.id, -1); syncQueue(); render(false) }.apply { contentDescription = "Move up"; isEnabled = index > 0 }, LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(button("↓") { store.moveQueue(e.id, 1); syncQueue(); render(false) }.apply { contentDescription = "Move down"; isEnabled = index < queue.lastIndex }, LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(button("Remove") { store.setQueue(store.queue() - e.id); syncQueue(); render() }, LinearLayout.LayoutParams(0, -2, 1f)); c.addView(r); content.add(c)
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
        stations.find { it.id==controller?.currentMediaItem?.mediaId }?.let { renderLivePlayer(it); return }
        val e = store.find(controller?.currentMediaItem?.mediaId) ?: store.find(store.last())
        if (e == null) { content.add(label("Choose an episode from Archives or Podcasts", 24, true)); return }
        playerEpisode = e
        content.add(button("‹ Back") { goBack() })
        content.add(label("EPISODE  •  NOW LISTENING", 12, true).apply { gravity = Gravity.CENTER })
        content.add(artwork(e, 280).apply { scaleType = ImageView.ScaleType.FIT_CENTER })
        playerTitle = label(e.title, 24, true).apply { gravity = Gravity.CENTER }.also { content.add(it) }; content.add(label(e.series, 15).apply { gravity = Gravity.CENTER; setTextColor(getColor(R.color.otr_muted)) })
        playerTime = label("", 13).apply { gravity = Gravity.CENTER; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE }.also { content.add(it) }
        errorActions = column().apply {
            setBackgroundResource(R.drawable.glass_panel)
            addView(label("The source could not be reached. You can retry, move on, or check its original page.", 14))
            val choices = row()
            choices.addView(button("Retry") { play(e) }, LinearLayout.LayoutParams(0, -2, 1f))
            choices.addView(button("Next") { nextEpisode() }, LinearLayout.LayoutParams(0, -2, 1f))
            choices.addView(button("Source ↗") { openPage(e.page) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(choices)
        }.also { content.add(it) }
        seek = SeekBar(this).apply {
            max = 1000; contentDescription = "Episode position"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) = Unit
                override fun onStartTrackingTouch(bar: SeekBar?) { seeking = true }
                override fun onStopTrackingTouch(bar: SeekBar?) { controller?.let { if (it.currentMediaItem?.mediaId == e.id && it.duration > 0) it.seekTo(it.duration * (bar?.progress ?: 0) / 1000) }; seeking = false }
            })
        }.also { content.add(it) }
        val r = row(); r.gravity = Gravity.CENTER
        r.addView(button("↶ 15 sec") { skip(-15000) })
        playerToggle = ImageButton(this).apply {
            setImageResource(R.drawable.ic_play); setBackgroundResource(R.drawable.accent_gradient); setPadding(dp(22), dp(22), dp(22), dp(22)); imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            setOnClickListener { val c = controller; if (c?.currentMediaItem?.mediaId == e.id && c.playWhenReady && c.playbackState != Player.STATE_ENDED) c.pause() else play(e) }
        }.also { r.addView(it, LinearLayout.LayoutParams(dp(76), dp(76))) }
        r.addView(button("30 sec ↷") { skip(30000) }); content.add(r)
        val actions = row().apply { gravity = Gravity.CENTER }
        actions.addView(iconButton(R.drawable.ic_heart, if (e.id in store.saved()) "Remove saved episode" else "Save episode") {
            store.toggle("saved", e.id); render(false)
        }.apply { alpha = if (e.id in store.saved()) 1f else 0.55f })
        actions.addView(button("Queue") { tab = "queue"; render() })
        actions.addView(iconButton(R.drawable.ic_next, "Next queued episode") { nextEpisode() })
        actions.addView(button("More ⋯") { episodePlayerOptions(e) })
        content.add(actions)
    }
    private fun episodePlayerOptions(e: Episode) {
        AlertDialog.Builder(this).setTitle(e.series)
            .setItems(arrayOf("Bookmark this moment", "Episode details", "Queue & download options", "Original source", "Artwork credits")) { _, which ->
                when (which) {
                    0 -> { val input = EditText(this).apply { hint = "Optional note" }; val position = if(controller?.currentMediaItem?.mediaId == e.id) controller!!.currentPosition else store.progress(e.id); AlertDialog.Builder(this).setTitle("Bookmark ${time(position)}").setView(input).setPositiveButton("Save") { _, _ -> collections.bookmark(e.id, position, input.text.toString()); toast("Bookmark saved") }.setNegativeButton("Cancel", null).show() }
                    1 -> AlertDialog.Builder(this).setTitle(e.title).setMessage(e.description.ifBlank { "No description supplied." } + "\n\nProvider date: ${e.date.ifBlank { "Not supplied" }}\nSource: ${e.page}").setPositiveButton("Close", null).show()
                    2 -> episodeDetails(e)
                    3 -> openPage(e.page)
                    4 -> AlertDialog.Builder(this).setTitle("Artwork credits").setMessage((if (e.image.isNotBlank()) "Publisher-supplied image: ${e.image}\nSource: ${e.page}\nFallback artwork:\n\n" else "") + StationArt.credits(this, e.artStation(this@LibraryActivity))).setPositiveButton("Close", null).show()
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
            val station = item?.artStation(this) ?: stations.find { it.id == artId }
            if (station != null) miniArtwork.setImageDrawable(StationArt.drawable(this, station)) else miniArtwork.setImageResource(R.drawable.ic_wave)
            item?.let { FeedArtwork.load(applicationContext, miniArtwork, it.image) }
        }
        mini.text = if (c?.currentMediaItem != null) "${c.mediaMetadata.title ?: "Open player"}  ›" else "Resume your last episode  ›"
        mini.setOnClickListener { if (c?.currentMediaItem != null && !episode) openRadio() else { if (tab != "player") returnTab = tab; tab = "player"; render() } }
        if(tab=="player" && c?.currentMediaItem!=null && !episode) {
            val station=stations.find { it.id==c.currentMediaItem?.mediaId }
            val title=c.mediaMetadata.title?.toString().orEmpty()
            playerTitle?.text=if(title.isNotBlank() && title!=station?.name) title else "Programme information appears when supplied by the station"
            playerTime?.text=when { c.playerError!=null -> "Source unavailable • tap Play to retry"; c.playbackState==Player.STATE_BUFFERING -> "Connecting…"; c.isPlaying -> "Live • playing"; else -> "Live • paused" }
            playerToggle?.setImageResource(if(canPauseMini) R.drawable.ic_pause else R.drawable.ic_play); playerToggle?.contentDescription=if(canPauseMini) "Pause" else "Play"
            return
        }
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
        busy = true; status.text = "Loading ${sources.size} source(s)…"; render(false)
        work.execute {
            val errors = mutableListOf<String>(); var count = 0
            for (s in sources) {
                if (Thread.currentThread().isInterrupted) break
                runCatching { EpisodeCatalogue.refresh(s) }.onSuccess { store.update(s, it); collections.status(s.id,"Catalogue available"); count++ }.onFailure { collections.status(s.id,"Unavailable: check your connection or try again later. Saved episodes are retained."); errors += "${s.title}: could not reach a usable catalogue. Check your connection or retry later. Saved entries retained." }
            }
            runOnUiThread { if (!isDestroyed) {
                busy = false; status.text = "$count source(s) updated" + if (errors.isNotEmpty()) " • ${errors.size} unavailable; saved entries kept" else ""; render(false)
                if (errors.isNotEmpty()) AlertDialog.Builder(this).setTitle("Some sources could not refresh").setMessage(errors.joinToString("\n\n")).setPositiveButton("Close", null).show()
            } }
        }
    }
    private fun openPage(url: String) { if (EpisodeCatalogue.validUrl(url)) runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { toast("No browser available") } }
    private fun settings() {
        AlertDialog.Builder(this).setTitle("Library settings").setItems(arrayOf("Switch light / dark mode", "Export library backup", "Import library backup", "About this preview", "Sources & feed updates", "Downloads", "Playlists", "Bookmarks", "History")) { _, n -> when (n) {
            0 -> { val p = getSharedPreferences("otr_dial", MODE_PRIVATE); p.edit().putBoolean("dark_mode", !p.getBoolean("dark_mode", false)).apply(); recreate() }
            1 -> AlertDialog.Builder(this).setTitle("Export library backup").setMessage("Includes your catalogue and personal collections. Downloaded audio and recordings are excluded.").setPositiveButton("Export") { _, _ -> exportBackup.launch("OTR-Dial-library-backup.json") }.setNegativeButton("Cancel",null).show()
            2 -> AlertDialog.Builder(this).setTitle("Merge app backup?").setMessage("Saved episodes, followed shows, queue and radio favourites will merge. Imported listening progress and theme replace matching settings. Audio and recordings are not included. Backups from the earlier preview are also supported.").setPositiveButton("Choose backup") { _, _ -> importBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }.setNegativeButton("Cancel", null).show()
            3 -> AlertDialog.Builder(this).setTitle("OTR Dial 2.8 preview").setMessage("Live radio, podcasts, archives, offline episodes and your personal collection. This release adds checked English-language streams, publisher RSS feeds and on-demand Internet Archive collections. Backups include playlists, bookmarks and custom sources, but not downloaded audio. Original providers control availability. YouTube channels and daily OTRCAT selections open on their original websites.").setPositiveButton("Close", null).show()
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
