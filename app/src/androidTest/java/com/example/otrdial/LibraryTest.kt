package com.example.otrdial

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LibraryTest {
    @get:org.junit.Rule val notificationPermission: androidx.test.rule.GrantPermissionRule = androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun reset() { context.getSharedPreferences("episode_library", Context.MODE_PRIVATE).edit().clear().commit() }
    @Test fun catalogueParsersAndBackupValidation() {
        reset(); val store = LibraryStore(context)
        val bundled = store.episodes()
        assertTrue(bundled.size >= 950)
        assertEquals(bundled.size, bundled.map { it.id }.distinct().size)
        val source = EpisodeCatalogue.sources.first()
        val rss = """<rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel><item><title>A &amp; B</title><guid>fixed-guid</guid><description><![CDATA[<p>A story</p>]]></description><itunes:duration>1:02:03</itunes:duration><enclosure type="audio/mpeg" url="https://example.com/a.mp3"/></item><item><title>No audio</title></item></channel></rss>"""
        val parsed = EpisodeCatalogue.parseRss(source, rss)
        assertEquals(1, parsed.size); assertEquals("A & B", parsed.first().title)
        assertEquals("A story", parsed.first().description); assertEquals(3723000L, parsed.first().duration)
        assertEquals(parsed.first().id, EpisodeCatalogue.parseRss(source, rss.replace("a.mp3", "new.mp3")).first().id)
        val archive = EpisodeCatalogue.parseArchive(EpisodeCatalogue.sources[4], JSONObject("""{"metadata":{"title":"Test"},"files":[{"name":"A story.mp3","length":"30.5"},{"name":"private.mp3","private":true},{"name":"cover.jpg"}]}"""))
        assertEquals(1, archive.size); assertTrue(archive.first().url.endsWith("A%20story.mp3")); assertEquals(30500L, archive.first().duration)
        val e = bundled.first(); store.toggle("saved", e.id); store.toggle("follows", e.source)
        store.setQueue(listOf(e.id)); store.saveProgress(e.id, 12000, 300000)
        val radio = context.getSharedPreferences("otr_dial", Context.MODE_PRIVATE)
        radio.edit().putStringSet("favourites", setOf("test-station")).putString("recent", "[\"test-station\"]").putBoolean("dark_mode", true).commit()
        val backup = store.export(); reset(); radio.edit().clear().commit(); store.restore(backup)
        assertEquals(setOf("test-station"), radio.getStringSet("favourites", emptySet()))
        assertTrue(radio.getBoolean("dark_mode", false))
        assertTrue(e.id in store.saved()); assertTrue(e.source in store.follows()); assertEquals(listOf(e.id), store.queue()); assertEquals(12000L, store.progress(e.id))
        val before = store.export()
        val bad = JSONObject(backup); bad.getJSONObject("state").put("position_bad", "not a number")
        assertTrue(runCatching { store.restore(bad.toString()) }.isFailure)
        assertEquals(before, store.export())
        store.restore(backup); assertEquals(1, store.queue().size)
        val legacy = JSONObject(backup).put("version", 1).apply { remove("radio") }
        store.restore(legacy.toString()); assertTrue(e.id in store.saved())
        store.enqueue(bundled[1].id, false); store.enqueue(bundled[2].id, true)
        assertEquals(listOf(bundled[2].id, e.id, bundled[1].id), store.queue())
        store.moveQueue(bundled[1].id, -1)
        assertEquals(listOf(bundled[2].id, bundled[1].id, e.id), store.queue())
        assertEquals(12000L, LibraryStore(context).progress(e.id))
        radio.edit().clear().commit()
    }
    private fun descendants(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
    private fun click(activity: LibraryActivity, text: String) {
        val view = descendants(activity.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == text }
        assertTrue(view.performClick())
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync(); SystemClock.sleep(400)
        instrumentation.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/$name.png").use { java.io.FileInputStream(it.fileDescriptor).use { input -> input.readBytes() } }
    }
    @Test fun libraryNavigationInBothThemes() {
        reset()
        for (dark in listOf(false, true)) {
            context.getSharedPreferences("otr_dial", Context.MODE_PRIVATE).edit().putBoolean("dark_mode", dark).commit()
            ActivityScenario.launch(LibraryActivity::class.java).use { scenario ->
                SystemClock.sleep(1500); capture("library-${if (dark) "dark" else "light"}-home")
                scenario.onActivity { a -> click(a, "Archives") }
                capture("library-${if (dark) "dark" else "light"}-shows")
                scenario.onActivity { a -> click(a, "Browse episodes  ›") }
                capture("library-${if (dark) "dark" else "light"}-episodes")
                scenario.onActivity { a -> LibraryStore(context).episodes().first().let { LibraryStore(context).toggle("saved",it.id) }; click(a, "Library") }
                capture("library-${if (dark) "dark" else "light"}-saved")
                scenario.recreate(); scenario.onActivity { a -> assertTrue(descendants(a.window.decorView).filterIsInstance<TextView>().any { it.text == "Library" }); click(a, "Queue") }
                scenario.onActivity { a -> click(a,"Search") }; instrumentation.waitForIdleSync()
                scenario.onActivity { a -> descendants(a.window.decorView).filterIsInstance<android.widget.EditText>().first().setText("Gunsmoke"); click(a, "Find") }
                instrumentation.waitForIdleSync()
                scenario.onActivity { a -> assertTrue(descendants(a.window.decorView).filterIsInstance<TextView>().any { it.text.toString().contains("Gunsmoke") }) }
                capture("library-${if (dark) "dark" else "light"}-search")
            }
            reset()
        }
    }
    @Test fun serviceSavesSeekAndPauseAndAdvancesQueue() {
        reset(); val store = LibraryStore(context)
        val foreground = ActivityScenario.launch(LibraryActivity::class.java)
        instrumentation.waitForIdleSync()
        // Local PCM makes progress/queue tests independent of public stream availability.
        val file = java.io.File(context.cacheDir, "library-test.wav")
        val bytes = 8000 * 2 * 12
        val buffer = java.nio.ByteBuffer.allocate(44 + bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16).put("data".toByteArray()).putInt(bytes)
        file.writeBytes(buffer.array())
        val server = java.net.ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        val served = java.util.concurrent.atomic.AtomicBoolean(false)
        Thread {
            while (!server.isClosed) runCatching {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().use { out ->
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: ${buffer.array().size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(buffer.array()); out.flush(); served.set(true)
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
        instrumentation.runOnMainSync { future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
        val c = future.get(15, TimeUnit.SECONDS)
        val first = store.episodes()[0].id; val second = store.episodes()[1].id
        store.setQueue(listOf(first, second)); store.saveProgress(second, 1800, 12000)
        fun item(id: String) = MediaItem.Builder().setMediaId(id)
            .setUri(if (id == first) android.net.Uri.parse("http://127.0.0.1:1/unavailable.wav") else android.net.Uri.fromFile(file))
            .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(store.find(id)!!.title).setArtist(store.find(id)!!.series)
                .setExtras(android.os.Bundle().apply { if (id == first) putString("fallback_url", "http://127.0.0.1:${server.localPort}/audio.wav") }).build()).build()
        try {
            instrumentation.runOnMainSync { c.setMediaItems(listOf(item(first), item(second)), 0, 0); c.prepare(); c.play() }
            var ready = false
            repeat(100) { if (!ready) { instrumentation.runOnMainSync { ready = c.playbackState == Player.STATE_READY }; SystemClock.sleep(100) } }
            var failureDetails = ""
            instrumentation.runOnMainSync { failureDetails = "state=${c.playbackState}, ready=${c.playWhenReady}, error=${c.playerError}, cause=${c.playerError?.cause}, extras=${c.mediaMetadata.extras}, served=${served.get()}" }
            assertTrue("Local fixture should play: $failureDetails", ready)
            assertTrue("Unavailable primary should use the fallback", served.get())
            instrumentation.runOnMainSync { c.seekTo(4000); c.pause() }; SystemClock.sleep(500)
            assertTrue(store.progress(first) in 3900..4800)
            for (dark in listOf(false, true)) {
                context.getSharedPreferences("otr_dial", Context.MODE_PRIVATE).edit().putBoolean("dark_mode", dark).commit()
                ActivityScenario.launch<LibraryActivity>(android.content.Intent(context, LibraryActivity::class.java).putExtra("player", true)).use {
                    SystemClock.sleep(800); capture("library-${if (dark) "dark" else "light"}-player")
                }
            }
            instrumentation.runOnMainSync { c.seekTo(11700); c.play() }
            var advanced = false
            repeat(80) { if (!advanced) { instrumentation.runOnMainSync { advanced = c.currentMediaItem?.mediaId == second }; SystemClock.sleep(100) } }
            assertTrue("Queue should advance", advanced); assertTrue(store.completed(first)); assertFalse(first in store.queue())
            instrumentation.runOnMainSync { assertTrue(c.currentPosition >= 1700); c.pause() }
            assertTrue(store.progress(second) >= 1700)
            instrumentation.runOnMainSync { c.setMediaItem(MediaItem.Builder().setMediaId("radio-test").setUri(android.net.Uri.fromFile(file)).build()); c.prepare() }
            SystemClock.sleep(400)
            assertTrue(store.progress(second) >= 1700)
            // Both primary and fallback fail. Recovery must stop and keep the queue.
            val failures = java.util.concurrent.atomic.AtomicInteger()
            instrumentation.runOnMainSync {
                c.addListener(object : Player.Listener { override fun onPlayerError(error: androidx.media3.common.PlaybackException) { failures.incrementAndGet() } })
                c.setMediaItems(listOf(MediaItem.Builder().setMediaId("episode:unavailable:test").setUri("http://127.0.0.1:1/missing.wav")
                    .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setExtras(android.os.Bundle().apply { putString("fallback_url", "http://127.0.0.1:2/missing.wav") }).build()).build(), item(second)))
                c.prepare(); c.play()
            }
            var stopped = false
            repeat(250) { if (!stopped) { instrumentation.runOnMainSync { stopped = c.playerError != null && !c.playWhenReady }; SystemClock.sleep(100) } }
            assertTrue("Repeated failures must stop", stopped)
            val count = failures.get(); assertTrue(count in 1..3)
            SystemClock.sleep(4000); assertEquals("No endless retry loop", count, failures.get())
            instrumentation.runOnMainSync { assertEquals(2, c.mediaItemCount) }
        } finally { instrumentation.runOnMainSync { c.stop(); c.clearMediaItems(); MediaController.releaseFuture(future) }; foreground.close(); server.close(); file.delete() }
    }
}
