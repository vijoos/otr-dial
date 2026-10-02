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
    private val work = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var mini: Button
    private var tab = "discover"
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
            runOnUiThread { if (!isDestroyed) { result.onSuccess { toast("Library restored"); render() }.onFailure { toast("Backup not imported: ${it.message}") } } }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.localNightMode = if (getSharedPreferences("otr_dial", MODE_PRIVATE).getBoolean("dark_mode", false)) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        super.onCreate(savedInstanceState)
        tab = savedInstanceState?.getString("tab") ?: if (intent.getBooleanExtra("player", false)) "player" else "discover"
        sourceId = savedInstanceState?.getString("source"); query = savedInstanceState?.getString("query").orEmpty()
        val root = column().apply { setBackgroundResource(R.drawable.aurora) }; setContentView(root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, i ->
            val bars = i.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); i
        }
        WindowCompat.getInsetsController(window, root).apply {
            val light = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES
            isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light
        }
        val header = row(); root.addView(header)
        header.addView(button("‹ Radio") { finish() })
        header.addView(label("Your listening library", 21, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("⋮") { settings() }.apply { contentDescription = "Library settings" })
        status = label("Podcasts & archive collections", 12); root.addView(status)
        val scroll = ScrollView(this); content = column(); scroll.addView(content); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        mini = button("Choose an episode") { tab = "player"; render() }; root.addView(mini)
        val nav = row(); root.addView(nav)
        listOf("discover" to "Discover", "library" to "My library", "queue" to "Queue").forEach { (key, title) ->
            nav.addView(button(title) { tab = key; sourceId = null; query = ""; page = 0; render() }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        future = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        future!!.addListener({ if (!isDestroyed) runCatching { future!!.get() }.onSuccess { c ->
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) { updatePlayer() }
                override fun onMediaItemTransition(item: androidx.media3.common.MediaItem?, reason: Int) { if (tab == "player") render() }
            })
            render()
        }.onFailure { status.text = "Player could not connect. Reopen the library to retry." } }, ContextCompat.getMainExecutor(this))
        render()
    }
    override fun onStart() { super.onStart(); handler.post(tick) }
    override fun onStop() { handler.removeCallbacks(tick); super.onStop() }
    override fun onDestroy() { future?.let { MediaController.releaseFuture(it) }; work.shutdownNow(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("tab", tab); outState.putString("source", sourceId); outState.putString("query", query); super.onSaveInstanceState(outState) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(6)) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun label(value: String, size: Int = 15, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(getColor(R.color.otr_ink)); setPadding(dp(4), dp(6), dp(4), dp(6))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    private fun button(value: String, action: () -> Unit) = androidx.appcompat.widget.AppCompatButton(this).apply {
        text = value; isAllCaps = false; textSize = 13f; minWidth = 0; minimumWidth = 0; minHeight = dp(48)
        setTextColor(getColor(R.color.otr_brown)); setOnClickListener { action() }
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun card() = column().apply {
        setBackgroundResource(R.drawable.glass_panel)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(6), 0, dp(6)) }
    }
    private fun render() {
        if (!::content.isInitialized) return
        content.removeAllViews(); playerTitle = null; playerTime = null; seek = null; playerToggle = null; playerEpisode = null
        when (tab) { "library" -> renderLibrary(); "queue" -> renderQueue(); "player" -> renderPlayer(); else -> renderDiscover() }
        updatePlayer()
    }
    private fun renderDiscover() {
        val source = EpisodeCatalogue.sources.find { it.id == sourceId }
        if (source == null) {
            content.addView(label("Stories, on your terms.", 29, true))
            content.addView(label("Choose a programme, save an episode and pick up where you left off. English-language selections.", 15))
            content.addView(button(if (busy) "Refreshing…" else "Refresh followed podcasts") { refresh(EpisodeCatalogue.sources.filter { it.kind == "rss" && it.id in store.follows() }) })
            for (kind in listOf("rss", "archive")) {
                content.addView(label(if (kind == "rss") "Podcasts" else "The archive shelves", 22, true))
                EpisodeCatalogue.sources.filter { it.kind == kind }.forEach { s ->
                    val c = card(); val row = row()
                    val representative = Episode("", s.id, "", s.title, "", s.page)
                    row.addView(ImageView(this).apply { setImageDrawable(StationArt.drawable(this@LibraryActivity, representative.artStation())); scaleType = ImageView.ScaleType.CENTER_CROP; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(62), dp(82)))
                    val title = column(); title.addView(label(s.title, 18, true)); title.addView(label("${store.episodes().count { it.source == s.id }} episodes • ${s.genre}", 12))
                    row.addView(title, LinearLayout.LayoutParams(0, -2, 1f)); c.addView(row)
                    c.addView(button("Browse episodes  ›") { sourceId = s.id; page = 0; render() }); content.addView(c)
                }
            }
            content.addView(label("Audio streams from the original provider. Catalogue entries are bundled for browsing; availability can change. Illustrative artwork uses the app’s existing credited collection.", 12))
        } else {
            content.addView(button("‹ All sources") { sourceId = null; query = ""; page = 0; render() })
            content.addView(label(source.title, 26, true))
            val controls = row()
            controls.addView(button(if (source.id in store.follows()) "✓ Following" else "+ Follow") { store.toggle("follows", source.id); render() }, LinearLayout.LayoutParams(0, -2, 1f))
            controls.addView(button("Refresh") { refresh(listOf(source)) }, LinearLayout.LayoutParams(0, -2, 1f))
            controls.addView(button("Source ↗") { openPage(source.page) }, LinearLayout.LayoutParams(0, -2, 1f)); content.addView(controls)
            val updated = store.updated(source.id)
            content.addView(label(if (updated == 0L) "Bundled catalogue • refresh to check for updates" else "Updated ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(updated))}", 12))
            searchControls()
            episodeList(store.episodes().filter { it.source == source.id && matches(it) })
        }
    }
    private fun searchControls() {
        val r = row(); val search = EditText(this).apply { hint = "Search these episodes"; setText(query); setSingleLine(); textSize = 14f; setTextColor(getColor(R.color.otr_ink)); setHintTextColor(getColor(R.color.otr_muted)) }
        r.addView(search, LinearLayout.LayoutParams(0, -2, 1f)); r.addView(button("Search") { query = search.text.toString().trim(); page = 0; render() }); content.addView(r)
    }
    private fun matches(e: Episode) = query.isBlank() || "${e.title} ${e.series} ${e.date}".contains(query, true)
    private fun episodeList(items: List<Episode>) {
        content.addView(label("${items.size} episodes", 12))
        if (items.isEmpty()) content.addView(label("Nothing here yet. Browse a source to find an episode."))
        page = page.coerceAtMost((items.size - 1).coerceAtLeast(0) / 25)
        items.drop(page * 25).take(25).forEach { episodeCard(it) }
        if (items.size > 25) {
            val r = row(); r.addView(button("‹ Previous") { if (page > 0) { page--; render() } }, LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(label("${page + 1} / ${(items.size + 24) / 25}")); r.addView(button("Next ›") { if ((page + 1) * 25 < items.size) { page++; render() } }, LinearLayout.LayoutParams(0, -2, 1f)); content.addView(r)
        }
    }
    private fun episodeCard(e: Episode) {
        val c = card(); c.addView(label(e.title, 18, true)); c.addView(label(e.series, 13))
        val details = listOf(e.date, if (e.duration > 0) time(e.duration) else "", if (store.completed(e.id)) "✓ Played" else if (store.progress(e.id) > 0) "Resume at ${time(store.progress(e.id))}" else "").filter { it.isNotBlank() }.joinToString(" • ")
        c.addView(label(details, 12)); val r = row()
        r.addView(button(if (store.progress(e.id) > 0) "▶ Resume" else "▶ Play") { play(e) }, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(button(if (e.id in store.saved()) "♥ Saved" else "♡ Save") { store.toggle("saved", e.id); render() }, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(button("More") { episodeDetails(e) }, LinearLayout.LayoutParams(0, -2, 1f)); c.addView(r); content.addView(c)
    }
    private fun episodeDetails(e: Episode) {
        val options = arrayOf(if (e.id in store.queue()) "Remove from queue" else "Add to queue", "Play from beginning", "Episode details", "Open original source")
        AlertDialog.Builder(this).setTitle(e.title).setItems(options) { _, index -> when (index) {
            0 -> { val q = store.queue().toMutableList(); if (!q.remove(e.id)) q.add(e.id); store.setQueue(q); syncQueue(); toast("Queue updated"); render() }
            1 -> play(e, true)
            2 -> AlertDialog.Builder(this).setTitle(e.title).setMessage(e.description.ifBlank { "No description supplied by this source." }).setPositiveButton("Close", null).show()
            3 -> openPage(e.page)
        } }.show()
    }
    private fun renderLibrary() {
        content.addView(label("My library", 29, true))
        val list = store.episodes()
        content.addView(label("Continue listening", 21, true))
        val progress = list.filter { store.progress(it.id) > 0 }.sortedByDescending { store.played(it.id) }.take(5)
        if (progress.isEmpty()) content.addView(label("Your listening progress will appear here.")) else progress.forEach { episodeCard(it) }
        content.addView(label("Following", 21, true))
        EpisodeCatalogue.sources.filter { it.id in store.follows() }.forEach { s -> content.addView(button("${s.title}  ›") { sourceId = s.id; tab = "discover"; page = 0; render() }) }
        if (store.follows().isEmpty()) content.addView(label("Follow a source from Discover to keep it close."))
        content.addView(label("Saved episodes", 21, true)); searchControls(); episodeList(list.filter { it.id in store.saved() && matches(it) })
    }
    private fun renderQueue() {
        content.addView(label("Your queue", 29, true)); content.addView(label("Episodes play in this order. Live radio remains a separate choice."))
        val queue = store.queue().mapNotNull { store.find(it) }
        if (queue.isEmpty()) content.addView(label("Use More → Add to queue on any episode."))
        else content.addView(button("▶ Start queue") { play(queue.first()) })
        queue.forEachIndexed { index, e ->
            val c = card(); c.addView(label("${index + 1}. ${e.title}", 17, true)); val r = row()
            r.addView(button("Play") { play(e) }, LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(button("Move up") { val q = store.queue().toMutableList(); if (index > 0) { java.util.Collections.swap(q, index, index - 1); store.setQueue(q); syncQueue(); render() } }, LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(button("Remove") { store.setQueue(store.queue() - e.id); syncQueue(); render() }, LinearLayout.LayoutParams(0, -2, 1f)); c.addView(r); content.addView(c)
        }
    }
    private fun syncQueue() {
        val c = controller ?: return; val current = store.find(c.currentMediaItem?.mediaId) ?: return
        if (c.mediaItemCount > c.currentMediaItemIndex + 1) c.removeMediaItems(c.currentMediaItemIndex + 1, c.mediaItemCount)
        c.addMediaItems(store.queue().filter { it != current.id }.mapNotNull { store.find(it)?.media(this) })
    }
    private fun play(e: Episode, restart: Boolean = false) {
        val c = controller ?: return toast("Player is connecting. Please try again.")
        if (!restart && c.currentMediaItem?.mediaId == e.id) { if (c.playbackState == Player.STATE_IDLE) c.prepare(); c.play() }
        else {
            val items = listOf(e) + store.queue().filter { it != e.id }.mapNotNull { store.find(it) }
            c.setMediaItems(items.map { it.media(this) }, 0, if (restart) 0 else store.progress(e.id)); c.prepare(); c.play()
        }
        tab = "player"; render()
    }
    private fun renderPlayer() {
        val e = store.find(controller?.currentMediaItem?.mediaId) ?: store.find(store.last())
        if (e == null) { content.addView(label("Choose an episode from Discover", 24, true)); return }
        playerEpisode = e
        content.addView(label("NOW LISTENING", 12, true))
        content.addView(ImageView(this).apply { setImageDrawable(StationArt.drawable(this@LibraryActivity, e.artStation())); scaleType = ImageView.ScaleType.FIT_CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(-1, dp(200)))
        playerTitle = label(e.title, 26, true).also { content.addView(it) }; content.addView(label(e.series, 16))
        playerTime = label("", 13).also { content.addView(it) }
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
            setOnClickListener { val c = controller; if (c?.currentMediaItem?.mediaId == e.id && c.playWhenReady) c.pause() else play(e) }
        }.also { r.addView(it, LinearLayout.LayoutParams(dp(76), dp(76))) }
        r.addView(button("30 sec ↷") { skip(30000) }); content.addView(r)
        content.addView(button(if (e.id in store.saved()) "♥ Saved to library" else "♡ Save to library") { store.toggle("saved", e.id); render() })
        content.addView(button("Queue & episode options") { episodeDetails(e) })
        content.addView(label(e.description.ifBlank { "No description supplied." }, 14)); content.addView(button("Original source ↗") { openPage(e.page) })
        content.addView(button("Artwork credits") { AlertDialog.Builder(this).setTitle("Illustrative artwork").setMessage(StationArt.credits(this, e.artStation())).setPositiveButton("Close", null).show() })
    }
    private fun skip(delta: Long) { val c = controller ?: return; if (c.currentMediaItem?.mediaId != playerEpisode?.id) return; c.seekTo((c.currentPosition + delta).coerceIn(0, if (c.duration > 0) c.duration else Long.MAX_VALUE)) }
    private fun updatePlayer() {
        val c = controller; val episode = c?.currentMediaItem?.mediaId?.startsWith("episode:") == true
        mini.text = if (episode) "${if (c?.isPlaying == true) "Ⅱ" else "▶"}  ${c?.mediaMetadata?.title}  ›" else "Open episode player  ›"
        val e = playerEpisode ?: return
        val active = c?.currentMediaItem?.mediaId == e.id
        val position = if (active) c!!.currentPosition else store.progress(e.id)
        val duration = if (active && c!!.duration > 0) c.duration else e.duration
        playerTime?.text = "${time(position)} / ${if (duration > 0) time(duration) else "—"}" + if (active) when {
            c!!.playerError != null -> " • Unable to play. Tap play to retry."
            c.playbackState == Player.STATE_BUFFERING -> " • Buffering…"
            c.isPlaying -> " • Playing"
            else -> " • Paused"
        } else ""
        if (!seeking) seek?.progress = if (duration > 0) (position * 1000 / duration).toInt().coerceIn(0, 1000) else 0
        seek?.isEnabled = active && c?.isCurrentMediaItemSeekable == true
        playerToggle?.setImageResource(if (active && c?.playWhenReady == true) R.drawable.ic_pause else R.drawable.ic_play)
        playerToggle?.contentDescription = if (active && c?.playWhenReady == true) "Pause" else "Play"
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
        AlertDialog.Builder(this).setTitle("Library settings").setItems(arrayOf("Switch light / dark mode", "Export library backup", "Import library backup", "About this preview")) { _, n -> when (n) {
            0 -> { val p = getSharedPreferences("otr_dial", MODE_PRIVATE); p.edit().putBoolean("dark_mode", !p.getBoolean("dark_mode", false)).apply(); recreate() }
            1 -> exportBackup.launch("OTR-Dial-library-backup.json")
            2 -> AlertDialog.Builder(this).setTitle("Merge library backup?").setMessage("Saved episodes, follows and queue will merge. Imported listening progress replaces progress for matching episodes. Audio files and live-radio favourites are not included.").setPositiveButton("Choose backup") { _, _ -> importBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }.setNegativeButton("Cancel", null).show()
            3 -> AlertDialog.Builder(this).setTitle("OTR Dial 2 preview").setMessage("Live radio + podcast and archive listening. Audio plays from original providers; saved episodes are bookmarks, not offline downloads. Followed feeds refresh when you request it. This preview has its own app storage, separate from OTR Dial 1.3.\n\nSources: Relic Radio and Old Time Radio Researchers collections on Internet Archive. Availability and source descriptions are controlled by their publishers.").setPositiveButton("Close", null).show()
        } }.show()
    }
}
