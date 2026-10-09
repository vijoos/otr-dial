package com.example.otrdial

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executors

/** Collection tools share the same catalogue, theme and player as the main screens. */
class CollectionActivity : AppCompatActivity() {
    private val library by lazy { LibraryStore(this) }
    private val collections by lazy { CollectionStore(this) }
    private val offline by lazy { OfflineAudio(this) }
    private val work = Executors.newSingleThreadExecutor()
    private lateinit var body: LinearLayout
    private var screen = "Programmes"
    private var selected: String? = null
    private var query = ""
    private var sort = "Title"
    private var mood = "All programmes"
    private var page = 0
    private var busy = false
    private var message = ""
    private var listScroll=0
    private var listQuery=""
    private var listPage=0
    private var lastSelected: String?=null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val labels = mutableMapOf<String, TextView>()
    private val poll = object : Runnable { override fun run() { labels.forEach { (id, view) -> offline.entry(id)?.let { view.text = offline.status(it) } }; handler.postDelayed(this, 1500) } }
    override fun onCreate(state: Bundle?) {
        delegate.localNightMode = if (getSharedPreferences("otr_dial", MODE_PRIVATE).getBoolean("dark_mode", false)) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        super.onCreate(state)
        screen = state?.getString("screen") ?: intent.getStringExtra("screen") ?: "Programmes"
        selected = state?.getString("selected") ?: intent.getStringExtra("programme"); query = state?.getString("query").orEmpty(); page = state?.getInt("page") ?: 0
        sort = state?.getString("sort") ?: "Title"
        val root = column(); root.setBackgroundResource(R.drawable.aurora); setContentView(root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, i -> val b = i.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()); v.setPadding(b.left + dp(10), b.top, b.right + dp(10), b.bottom); i }
        WindowCompat.getInsetsController(window, root).apply { val light = delegate.localNightMode != AppCompatDelegate.MODE_NIGHT_YES; isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light }
        val top = row(); top.addView(button("‹ Back") { finish() }); top.addView(text("Your OTR collection", 21, true)); root.addView(top)
        val tabs = HorizontalScrollView(this); val nav = row()
        listOf("Programmes", "Playlists", "Bookmarks", "Downloads", "History", "Sources").forEach { s -> nav.addView(button(s) { screen = s; selected = null; query = ""; page = 0; render() }) }
        tabs.addView(nav); root.addView(tabs)
        val scroll = ScrollView(this); body = column(); scroll.addView(body); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(PlaybackMiniBar(this))
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) { override fun handleOnBackPressed() { if (selected != null) { selected = null; page = listPage; query=listQuery; render(); (body.parent as? ScrollView)?.post { (body.parent as? ScrollView)?.scrollTo(0,listScroll) } } else finish() } })
        render(); scroll.post { scroll.scrollTo(0,state?.getInt("scroll") ?: 0) }
    }
    override fun onSaveInstanceState(out: Bundle) { out.putString("screen", screen); out.putString("selected", selected); out.putString("query", query); out.putString("sort", sort); out.putInt("page", page); out.putInt("scroll",(body.parent as? ScrollView)?.scrollY ?: 0); super.onSaveInstanceState(out) }
    override fun onStart() { super.onStart(); handler.post(poll) }
    override fun onStop() { handler.removeCallbacks(poll); super.onStop() }
    override fun onDestroy() { work.shutdownNow(); super.onDestroy() }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(5), dp(6), dp(5)) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun text(s: String, size: Int = 14, bold: Boolean = false) = TextView(this).apply { text = s; textSize = size.toFloat(); setTextColor(getColor(R.color.otr_ink)); setPadding(dp(4), dp(7), dp(4), dp(7)); if (bold) setTypeface(typeface, 1) }
    private fun button(s: String, action: () -> Unit) = androidx.appcompat.widget.AppCompatButton(this).apply {
        text = s; isAllCaps = false; textSize = 13f; minHeight = dp(48); setPadding(dp(12), dp(5), dp(12), dp(5))
        setBackgroundColor(android.graphics.Color.TRANSPARENT); supportBackgroundTintList = null; setTextColor(getColor(R.color.otr_brown))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(3), dp(4), dp(3), dp(4)) }; setOnClickListener { action() }
    }
    private fun card() = column().apply { setBackgroundResource(R.drawable.glass_panel); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(6), 0, dp(6)) } }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun ask(title: String, initial: String = "", action: (String) -> Unit) {
        val input = EditText(this).apply { setText(initial); maxLines = 4 }
        AlertDialog.Builder(this).setTitle(title).setView(input).setPositiveButton("Save") { _, _ -> runCatching { action(input.text.toString().trim()) }.onFailure { toast(it.message ?: "Could not save") } }.setNegativeButton("Cancel", null).show()
    }
    private fun confirm(title: String, action: () -> Unit) { AlertDialog.Builder(this).setTitle(title).setPositiveButton("Confirm") { _, _ -> action() }.setNegativeButton("Cancel", null).show() }
    private fun open(url: String) { if (EpisodeCatalogue.validUrl(url)) runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }.onFailure { toast("No browser available") } }
    private fun play(e: Episode, position: Long? = null) {
        val i = Intent(this, LibraryActivity::class.java).putExtra("episode_id", e.id).putExtra("player", true).putExtra("from_collection",true)
        position?.let { i.putExtra("episode_position", it) }; startActivity(i)
    }
    private fun render() {
        if (isDestroyed) return
        if(lastSelected==null && selected!=null) { listScroll=(body.parent as? ScrollView)?.scrollY ?: 0; listQuery=query; listPage=page }; lastSelected=selected
        labels.clear(); body.removeAllViews(); body.addView(text(selected ?: screen, 25, true))
        if (message.isNotBlank()) body.addView(text(message))
        when(screen) { "Programmes" -> programmes(); "Playlists" -> playlists(); "Bookmarks" -> bookmarks(); "Downloads" -> downloads(); "History" -> history(); else -> sources() }
    }
    private fun search() {
        val r = row(); val input = EditText(this).apply { hint = "Filter this collection"; setText(query); setSingleLine() }
        r.addView(input, LinearLayout.LayoutParams(0, -2, 1f)); r.addView(button("Find") { query = input.text.toString().trim(); page = 0; render() }); body.addView(r)
    }
    private fun selector(options: List<String>, value: String, action: (String) -> Unit) = Spinner(this).apply {
        adapter = ArrayAdapter(this@CollectionActivity, android.R.layout.simple_spinner_dropdown_item, options); setSelection(options.indexOf(value).coerceAtLeast(0)); minimumHeight = dp(48)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener { override fun onNothingSelected(p: AdapterView<*>?) {}; override fun onItemSelected(p: AdapterView<*>?, v: View?, n: Int, id: Long) { if (options[n] != value) action(options[n]) } }
    }
    private fun episodes(list: List<Episode>, playlist: String? = null) {
        val filtered = list.filter { "${it.title} ${it.series}".contains(query, true) }
        page = page.coerceAtMost((filtered.size - 1).coerceAtLeast(0) / 20)
        body.addView(text("${filtered.size} episodes"))
        filtered.drop(page * 20).take(20).forEach { e ->
            val c = card(); val head = row()
            head.addView(ImageView(this).apply { setImageDrawable(StationArt.drawable(this@CollectionActivity, e.artStation(this@CollectionActivity))); scaleType = ImageView.ScaleType.FIT_CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(56), dp(68)))
            head.addView(text(e.title, 17, true), LinearLayout.LayoutParams(0, -2, 1f)); c.addView(head)
            c.addView(text("${e.series} • ${collections.sources().find { it.id == e.source }?.title ?: e.source}", 12))
            c.addView(text(if (library.completed(e.id)) "✓ Played" else if (library.progress(e.id) > 0) "Resume at ${clock(library.progress(e.id))}" else if (e.duration > 0) "Duration ${clock(e.duration)}" else "Duration not supplied", 12))
            val r = row(); r.addView(button("▶ Play") { play(e) }); r.addView(button("Options") { options(e) }); c.addView(r)
            if (playlist != null) {
                val controls = row(); val ids = collections.playlistIds(playlist); val index = ids.indexOf(e.id)
                for ((label, delta) in listOf("↑" to -1, "↓" to 1)) controls.addView(button(label) {
                    val next = ids.toMutableList(); val target = index + delta
                    if (index >= 0 && target in next.indices) { java.util.Collections.swap(next, index, target); collections.editPlaylist(playlist, episodes = next); render() }
                }.apply { contentDescription = if (delta < 0) "Move episode up" else "Move episode down"; isEnabled = index + delta in ids.indices })
                controls.addView(button("Remove") { collections.editPlaylist(playlist, episodes = ids - e.id); render() }); c.addView(controls)
            }
            body.addView(c)
        }
        if (filtered.size > 20) { val r = row(); r.addView(button("‹ Previous") { if (page > 0) { page--; render() } }); r.addView(text("${page + 1}/${(filtered.size + 19) / 20}")); r.addView(button("Next ›") { if ((page + 1) * 20 < filtered.size) { page++; render() } }); body.addView(r) }
    }
    private fun programmes() {
        search()
        val catalogue = library.episodes()
        body.addView(selector(listOf("All programmes", "Something funny", "A mystery tonight", "Science-fiction adventures", "A short listen"), mood) { mood = it; page = 0; selected = null; render() })
        val all = catalogue.filter { e -> val genre = collections.sources().find { it.id == e.source }?.genre.orEmpty(); when(mood) {
            "Something funny" -> genre.equals("Comedy", true)
            "A mystery tonight" -> genre.contains("Detective", true) || genre.contains("Mystery", true)
            "Science-fiction adventures" -> genre.contains("Science", true)
            "A short listen" -> e.duration in 1..1200000
            else -> true
        } }; val name = selected
        if (name == null) {
            body.addView(text("Programme names are matched from explicit titles or source labels. Mixed podcast recordings remain complete; no chapter times are guessed.", 12))
            val followed = collections.followedProgrammes()
            (all.flatMap { ProgrammeIndex.names(it) } + ProgrammeDirectory.entries.map { it.name }).distinct().filter { it.contains(query, true) }.sortedWith(compareBy<String> { it !in followed }.thenBy { it }).forEach { p ->
                val count = all.count { p in ProgrammeIndex.names(it) }
                body.addView(button("${if(p in followed) "♥ " else ""}$p · $count") { selected = p; query = ""; page = 0; render() })
            }
            body.addView(text("Daily listening picks", 21, true)); body.addView(text(if (followed.isEmpty()) "App selections from the catalogue; not newly released recordings." else "App selections based on your programme follows.", 12))
            episodes(ProgrammeIndex.daily(all, followed))
        } else {
            val mapped=ProgrammeDirectory.entries.find { it.name==name }
            val sourceDirectory=SourceDirectory.load(this)
            val related=sourceDirectory.filter { it.id in mapped?.directoryIds.orEmpty() }
            val sourceIds=mapped?.sourceIds.orEmpty() + related.mapNotNull { it.episodeSource()?.id }
            val recordings=all.filter { it.source in sourceIds || name in ProgrammeIndex.names(it) }
            recordings.firstOrNull()?.let { first -> body.addView(button(if(library.progress(first.id)>0) "▶ Resume" else "▶ Play") { play(first) }) }
            if(mapped!=null) {
                body.addView(text("Listening sources",18,true))
                collections.sources().filter { it.id in mapped.sourceIds }.forEach { source -> body.addView(button("${source.title} • Browse episodes") { startActivity(Intent(this,LibraryActivity::class.java).putExtra("source_id",source.id).putExtra("from_collection",true)) }) }
                related.forEach { d -> val source=d.episodeSource(); body.addView(button("${d.title} • ${if(source!=null) "Browse episodes" else "Opens website"}") {
                    if(source==null) open(d.page) else { if(collections.sources().none { it.kind==source.kind && it.url==source.url }) collections.addSource(source); startActivity(Intent(this,LibraryActivity::class.java).putExtra("source_id",source.id).putExtra("from_collection",true)) }
                }) }
                StationRepository.load(this).filter { it.id in mapped.stationIds }.forEach { station -> body.addView(button("${station.name} • Play in app") { startActivity(Intent(this,LibraryActivity::class.java).putExtra("station_id",station.id).putExtra("from_collection",true)) }) }
            }
            body.addView(button(if (name in collections.followedProgrammes()) "♥ Following programme" else "Follow programme") { collections.followProgramme(name); render() })
            val list = recordings
            list.firstOrNull()?.let { e -> body.addView(ImageView(this).apply { setImageDrawable(StationArt.drawable(this@CollectionActivity, e.artStation(this@CollectionActivity))); scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(-1, dp(72))) }
            body.addView(text("${list.count { library.completed(it.id) }} of ${list.size} recordings played • illustrative cover", 12))
            body.addView(selector(listOf("Title", "Publication date", "Duration", "Unplayed first"), sort) { sort = it; page = 0; render() })
            body.addView(button("Possible alternative recordings") {
                val groups = list.groupBy { ProgrammeIndex.duplicateKey(it) }.values.filter { it.size > 1 }
                AlertDialog.Builder(this).setTitle("Conservative title matches").setMessage(if (groups.isEmpty()) "No exact title-and-series matches found. This does not prove all recordings are unique." else groups.joinToString("\n\n") { g -> g.first().title + "\n" + g.joinToString("\n") { it.source } }.take(12000)).setPositiveButton("Close", null).show()
            })
            val sorted = when(sort) { "Publication date" -> list.sortedByDescending { ProgrammeIndex.date(it) }; "Duration" -> list.sortedBy { if(it.duration > 0) it.duration else Long.MAX_VALUE }; "Unplayed first" -> list.sortedBy { library.completed(it.id) }; else -> list.sortedBy { it.title } }
            episodes(sorted)
        }
    }
    private fun playlists() {
        val id = selected
        if (id == null) {
            body.addView(button("+ New playlist") { ask("Playlist name") { collections.createPlaylist(it); render() } })
            body.addView(text("Add episodes through Options → Add to playlist. Playlists are saved independently of the playback queue."))
            collections.playlists().forEach { p -> body.addView(button("${p.getString("name")} · ${p.getJSONArray("episodes").length()}") { selected = p.getString("id"); render() }) }
        } else {
            val p = collections.playlists().find { it.getString("id") == id } ?: return
            body.getChildAt(0).let { (it as TextView).text = p.getString("name") }
            val items = collections.playlistIds(id).mapNotNull { library.find(it) }
            body.addView(button("▶ Play playlist") { if (items.isNotEmpty()) { library.setQueue(items.map { it.id }); play(items.first()) } })
            body.addView(button("Rename") { ask("Playlist name", p.getString("name")) { collections.editPlaylist(id, name = it); render() } })
            body.addView(button("Delete playlist") { confirm("Delete this playlist? Episodes will remain available.") { collections.removePlaylist(id); selected = null; render() } })
            search(); episodes(items, id)
        }
    }
    private fun bookmarks() {
        body.addView(text("Save a listening moment using Bookmark in the episode player."))
        collections.bookmarks().reversed().forEach { b ->
            val e = library.find(b.getString("episode")) ?: return@forEach
            val c = card(); c.addView(text(e.title, 17, true)); c.addView(text("${clock(b.getLong("position"))} · ${b.getString("note")}"))
            c.addView(button("▶ Play from bookmark") { play(e, b.getLong("position")) }); c.addView(button("Remove bookmark") { collections.removeBookmark(b.getString("id")); render() }); body.addView(c)
        }
    }
    private fun history() {
        body.addView(button("Clear listening history") { confirm("Clear last-listened dates? Saved positions and played status remain.") { library.clearHistory(); render() } })
        search(); episodes(library.episodes().filter { library.played(it.id) > 0 }.sortedByDescending { library.played(it.id) })
    }
    private fun downloads() {
        body.addView(text("Audio stored: ${"%.1f".format(offline.usedBytes() / 1048576.0)} MB"))
        body.addView(Switch(this).apply { text = "Wi-Fi only for new downloads"; isChecked = offline.wifiOnly(); setTextColor(getColor(R.color.otr_ink)); setOnCheckedChangeListener { _, b -> offline.wifiOnly(b) } })
        body.addView(text("Applies when a download is added. Cancel and retry an existing download to change its network setting. Audio files are not included in library backups.", 12))
        body.addView(button("Refresh downloads") { render() })
        val entries = offline.entries()
        if(entries.isEmpty()) body.addView(text("Use an episode’s Options → Download audio to keep it for offline listening."))
        entries.forEach { entry ->
            val e = library.find(entry.id) ?: return@forEach
            val c = card(); c.addView(text(e.title, 17, true)); val label = text(offline.status(entry)); labels[e.id] = label; c.addView(label)
            if (offline.local(e.id) != null) c.addView(button("▶ Play offline") { play(e) })
            if (entry.state == android.app.DownloadManager.STATUS_FAILED) c.addView(button("Retry download") { runCatching { offline.start(e); render() }.onFailure { toast(it.message.orEmpty()) } })
            c.addView(button("Cancel / remove download") { confirm("Remove this audio download?") { offline.remove(e.id); render() } }); body.addView(c)
        }
    }
    private fun options(e: Episode) {
        AlertDialog.Builder(this).setTitle(e.title).setItems(arrayOf("Add to playlist", "Download audio", "Episode details", "Original source")) { _, n -> when(n) {
            0 -> addToPlaylist(e)
            1 -> confirm("Download this episode from its provider for personal listening?") { runCatching { offline.start(e); toast("Download queued") }.onFailure { toast(it.message.orEmpty()) } }
            2 -> AlertDialog.Builder(this).setTitle(e.title).setMessage("${e.description}\n\nSource: ${e.page}\nDate supplied by provider: ${e.date.ifBlank { "Not supplied" }}").setPositiveButton("Close", null).show()
            3 -> open(e.page)
        } }.show()
    }
    private fun addToPlaylist(e: Episode) {
        val all = collections.playlists()
        AlertDialog.Builder(this).setTitle("Add to playlist").setItems((all.map { it.getString("name") } + "+ New playlist").toTypedArray()) { _, n ->
            if (n == all.size) ask("Playlist name") { val id = collections.createPlaylist(it); collections.editPlaylist(id, episodes = listOf(e.id)); toast("Added") }
            else { val id = all[n].getString("id"); collections.editPlaylist(id, episodes = collections.playlistIds(id) + e.id); toast("Added") }
        }.show()
    }
    private fun sources() {
        body.addView(text("Native sources support catalogue browsing and playback. Website sources open in your browser and are not included in app search.", 13))
        body.addView(button("+ Add podcast RSS feed") { importSource("rss") }.apply { isEnabled = !busy })
        body.addView(button("+ Add Internet Archive item") { importSource("archive") }.apply { isEnabled = !busy })
        body.addView(Switch(this).apply { text = "Daily followed-podcast update digest"; isChecked = FeedUpdates.enabled(this@CollectionActivity); setTextColor(getColor(R.color.otr_ink)); setOnCheckedChangeListener { _, b -> FeedUpdates.setEnabled(this@CollectionActivity, b) } })
        body.addView(text("Optional background refresh on an unmetered connection, approximately daily. Android controls timing; notification permission is required for alerts.", 12))
        collections.sources().forEach { s ->
            val c = card(); c.addView(text(s.title, 18, true)); c.addView(text("${if(s.kind == "rss") "Podcast RSS" else "Archive audio item"} • ${s.genre}", 12))
            val updated = library.updated(s.id)
            c.addView(text(collections.status(s.id) + if(updated > 0) "\nLast successful refresh: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(updated))}" else "", 12))
            c.addView(button(if(s.id in library.follows()) "✓ Following" else "Follow source") { library.toggle("follows", s.id); render() })
            c.addView(button("Refresh catalogue") { refresh(s) }.apply { isEnabled = !busy })
            c.addView(button("Browse episodes") { startActivity(Intent(this, LibraryActivity::class.java).putExtra("source_id", s.id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)); finish() })
            c.addView(button("Original website ↗") { open(s.page) })
            if(s.id.startsWith("custom-")) c.addView(button("Remove source subscription") { confirm("Remove this source? Existing episode entries will be retained.") { collections.removeSource(s.id); if(s.id in library.follows()) library.toggle("follows", s.id); render() } })
            body.addView(c)
        }
        body.addView(text("Website sources", 22, true))
        body.addView(text("OTRCAT • Today, Yesterday and 2 Days Ago are offered on its website. Availability is controlled by OTRCAT."))
        body.addView(button("OTRCAT daily selection ↗") { open("https://www.otrcat.com/") })
        body.addView(button("RadioEchoes catalogue ↗") { open("https://www.radioechoes.com/") })
        body.addView(text("These links do not provide native playback, background audio or automatic imports. YouTube is not integrated.", 12))
    }
    private fun refresh(s: EpisodeSource) {
        if(busy) return; busy = true; message = "Refreshing ${s.title}…"; render()
        work.execute { val result = runCatching { EpisodeCatalogue.refresh(s).also { library.update(s, it) } }
            runOnUiThread { busy = false; message = result.fold({ "${it.size} entries refreshed" }, { "Refresh failed: ${it.message}. Saved entries retained." }); collections.status(s.id, message); if(!isDestroyed) render() }
        }
    }
    private fun importSource(kind: String) {
        val box = column(); val title = EditText(this).apply { hint = "Source title" }; val url = EditText(this).apply { hint = if(kind == "rss") "https://… RSS feed" else "Archive item URL or identifier" }; box.addView(title); box.addView(url)
        val genre = EditText(this).apply { hint = "Genre (optional)" }; box.addView(genre)
        AlertDialog.Builder(this).setTitle("Preview source").setView(box).setPositiveButton("Preview") { _, _ ->
            runCatching {
                val value = url.text.toString().trim()
                val link = if(kind == "archive" && value.startsWith("https://archive.org/details/")) android.net.Uri.parse(value).pathSegments.getOrNull(1).orEmpty() else value
                require(if(kind == "rss") EpisodeCatalogue.validUrl(link) else link.matches(Regex("[A-Za-z0-9_.-]+"))) { "Enter a valid feed URL or Archive item identifier" }
                require(title.text.isNotBlank()) { "Enter a source title" }
                val id = "custom-" + EpisodeCatalogue.stableId(kind, link).substringAfterLast(':')
                val s = EpisodeSource(id, title.text.toString().trim().take(120), kind, link, if(kind == "rss") link else "https://archive.org/details/$link", genre.text.toString().trim().ifBlank { "Uncategorised" })
                busy = true; message = "Checking source…"; render()
                work.execute { val result = runCatching { EpisodeCatalogue.refresh(s) }
                    runOnUiThread {
                        busy = false; if(isDestroyed) return@runOnUiThread
                        result.onSuccess { list -> AlertDialog.Builder(this).setTitle("${s.title}: ${list.size} audio entries").setMessage("${list.take(4).joinToString("\n") { it.title }}\n\nCheck that this is the English-language OTR source you intended. Entries play from the original provider.")
                            .setPositiveButton("Add source") { _, _ -> collections.addSource(s); library.update(s, list); if(s.id !in library.follows()) library.toggle("follows", s.id); collections.status(s.id, "Import succeeded"); message = "Source added"; render() }.setNegativeButton("Cancel") { _, _ -> message = "Import cancelled"; render() }.show() }
                            .onFailure { message = "Could not import: ${it.message}" }
                        render()
                    }
                }
            }.onFailure { toast(it.message.orEmpty()) }
        }.setNegativeButton("Cancel", null).show()
    }
    private fun clock(ms: Long) = "%d:%02d".format(ms / 60000, ms / 1000 % 60)
}
