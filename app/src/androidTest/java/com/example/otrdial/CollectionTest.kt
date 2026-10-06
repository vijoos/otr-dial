package com.example.otrdial

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CollectionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun clean() { context.getSharedPreferences("collections24", Context.MODE_PRIVATE).edit().clear().commit() }
    @Test fun collectionsRoundTripAndInvalidBackupAreAtomic() {
        clean(); val c = CollectionStore(context); val l = LibraryStore(context); val e = l.episodes().first()
        val id = c.createPlaylist("Evening mysteries"); c.editPlaylist(id, episodes = listOf(e.id, e.id)); assertEquals(1, c.playlistIds(id).size)
        c.bookmark(e.id, 42000, "Listen again"); c.followProgramme("Gunsmoke")
        val s = EpisodeSource("custom-test", "Test feed", "rss", "https://example.com/feed", "https://example.com", "Drama")
        c.addSource(s); val backup = l.export(); clean(); l.restore(backup)
        assertEquals(listOf(e.id), c.playlistIds(id)); assertEquals(42000L, c.bookmarks().first().getLong("position")); assertTrue("Gunsmoke" in c.followedProgrammes()); assertTrue(c.sources().any { it.id == s.id })
        l.restore(backup); assertEquals(1, c.bookmarks().size); assertEquals(1, c.playlists().size)
        val before = l.export(); val invalid = JSONObject(backup)
        invalid.getJSONObject("collections").getJSONArray("bookmarks").getJSONObject(0).put("position", -1)
        assertTrue(runCatching { l.restore(invalid.toString()) }.isFailure); assertEquals(before, l.export())
        val old = JSONObject(backup).put("version", 2).apply { remove("collections") }; l.restore(old.toString()); assertEquals(listOf(e.id), c.playlistIds(id))
        clean()
    }
    @Test fun explicitProgrammeMatchingAndFeedArtwork() {
        val e = Episode("episode:test:1", "relic", "Suspense and Gunsmoke", "Mixed feed", "https://example.com/a.mp3", "https://example.com")
        assertEquals(listOf("Gunsmoke", "Suspense"), ProgrammeIndex.names(e))
        assertFalse("Escape" in ProgrammeIndex.names(e.copy(title = "An escape from prison")))
        val rss = """<rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel><itunes:image href="https://example.com/cover.jpg"/><item><title>Test</title><guid>test</guid><enclosure url="https://example.com/a.mp3" type="audio/mpeg"/></item></channel></rss>"""
        assertEquals("https://example.com/cover.jpg", EpisodeCatalogue.parseRss(EpisodeCatalogue.sources.first(), rss).first().image)
    }
    @Test fun collectionScreensInBothThemes() {
        clean()
        for(dark in listOf(false, true)) {
            context.getSharedPreferences("otr_dial", Context.MODE_PRIVATE).edit().putBoolean("dark_mode", dark).commit()
            for(screen in listOf("Programmes", "Playlists", "Bookmarks", "Downloads", "History", "Sources")) {
                ActivityScenario.launch<CollectionActivity>(Intent(context, CollectionActivity::class.java).putExtra("screen", screen)).use { a ->
                    instrumentation.waitForIdleSync(); SystemClock.sleep(300)
                    if(screen in listOf("Programmes", "Downloads", "Sources")) instrumentation.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/collection-${if(dark) "dark" else "light"}-$screen.png").use { java.io.FileInputStream(it.fileDescriptor).use { input -> input.readBytes() } }
                    a.recreate()
                }
            }
        }
    }
    @Test fun downloadUsesLocalAudioAfterProviderDisappears() {
        val offline = OfflineAudio(context); offline.wifiOnly(false)
        val bytes = java.nio.ByteBuffer.allocate(16044).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(16036); put("WAVEfmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16); put("data".toByteArray()); putInt(16000)
        }.array()
        val server = java.net.ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        Thread { while(!server.isClosed) runCatching { server.accept().use { socket -> socket.soTimeout = 3000; val reader = socket.getInputStream().bufferedReader(); while(!reader.readLine().isNullOrEmpty()) { }; socket.getOutputStream().use { out -> out.write("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); out.write(bytes) } } } }.apply { isDaemon = true; start() }
        val e = Episode("episode:offline:test", "test", "Offline fixture", "Test", "http://127.0.0.1:${server.localPort}/audio.wav", "https://example.com")
        try {
            offline.start(e); var ready = false
            repeat(200) { if(!ready) { ready = offline.local(e.id) != null; SystemClock.sleep(100) } }
            assertTrue("Download should finish: ${offline.entry(e.id)}", ready); server.close()
            assertEquals("file", e.media(context).localConfiguration!!.uri.scheme)
            assertArrayEquals(bytes, offline.file(e.id).readBytes())
            lateinit var player: androidx.media3.exoplayer.ExoPlayer
            instrumentation.runOnMainSync { player = androidx.media3.exoplayer.ExoPlayer.Builder(context).build(); player.setMediaItem(e.media(context)); player.prepare() }
            try {
                var playable = false
                repeat(100) { if(!playable) { instrumentation.runOnMainSync { playable = player.playbackState == androidx.media3.common.Player.STATE_READY }; SystemClock.sleep(50) } }
                assertTrue("Downloaded audio must decode after the source server closes", playable)
            } finally { instrumentation.runOnMainSync { player.release() } }
            offline.remove(e.id); assertNull(offline.local(e.id)); assertFalse(offline.file(e.id).exists())
        } finally { server.close(); offline.remove(e.id); offline.wifiOnly(true) }
    }
}
