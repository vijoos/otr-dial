package com.example.otrdial

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Release28Test {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(n: Int) = Episode("episode:large:$n", "large", "Gunsmoke recording $n", "Gunsmoke", "https://example.com/$n.mp3", "https://example.com", "Preserved archive description. ".repeat(24))
    @Test fun largeCatalogueMigrationBackupAndMalformedInput() {
        val prefs = context.getSharedPreferences("episode_library", Context.MODE_PRIVATE)
        val original = LibraryStore(context).export()
        val values = (0 until 25000).map { fixture(it) }
        try {
            CatalogueDatabase.resetForTest(context)
            prefs.edit().putString("catalogue", JSONArray(values.map { it.json() }).toString()).putStringSet("saved", setOf(values.first().id)).putLong("position_${values.first().id}", 42000).commit()
            var start = android.os.SystemClock.elapsedRealtime()
            val store = LibraryStore(context)
            val migration = android.os.SystemClock.elapsedRealtime()-start
            assertEquals(25000, store.count()); assertEquals(42000L, store.progress(values.first().id)); assertTrue(values.first().id in store.saved())
            start = android.os.SystemClock.elapsedRealtime(); val page = store.page(query="Gunsmoke recording 24999", limit=25); val search = android.os.SystemClock.elapsedRealtime()-start
            assertEquals(1, page.size); assertEquals(values.last().id, page.first().id)
            start = android.os.SystemClock.elapsedRealtime(); val backup = store.export(); val export = android.os.SystemClock.elapsedRealtime()-start
            assertTrue(backup.toByteArray().size > 12*1024*1024)
            CatalogueDatabase.resetForTest(context); prefs.edit().clear().putString("catalogue", "[]").commit()
            val target = LibraryStore(context); start=android.os.SystemClock.elapsedRealtime(); target.restore(backup); val restore=android.os.SystemClock.elapsedRealtime()-start
            assertEquals(25000, target.count()); assertEquals(42000L,target.progress(values.first().id)); assertEquals(values.last(), target.find(values.last().id))
            val invalid=JSONObject().put("format","otr-dial-library").put("version",4).put("episodes",JSONArray().put(fixture(30000).json())).put("state",JSONObject().put("position_bad",-1))
            assertTrue(runCatching { target.restore(invalid.toString()) }.isFailure); assertEquals(25000,target.count()); assertNull(target.find(fixture(30000).id)); assertEquals(42000L,target.progress(values.first().id))
            target.update(EpisodeSource("large", "Gunsmoke", "archive", "large", "https://example.com", "Western"), values.take(2))
            assertEquals(2,target.count()); assertEquals(42000L,target.progress(values.first().id))
            android.util.Log.i("OTR28_PERF", "25000 episodes: migration=${migration}ms search=${search}ms export=${export}ms restore=${restore}ms bytes=${backup.toByteArray().size}; emulator only")
        } finally {
            CatalogueDatabase.resetForTest(context); prefs.edit().clear().putString("catalogue", "[]").commit(); LibraryStore(context).restore(original)
        }
    }
    @Test fun replacementFailurePreservesOfflineCopy() {
        val audio=OfflineAudio(context); val e=fixture(1); val file=audio.file(e.id)
        val old="existing-offline-fixture".toByteArray(); file.writeBytes(old)
        val prefs=context.getSharedPreferences("offline24",Context.MODE_PRIVATE); prefs.edit().putBoolean("ready:${e.id}",true).commit()
        try {
            audio.start(e.copy(url="http://127.0.0.1:1/unavailable.mp3"))
            assertNotNull(audio.local(e.id)); assertArrayEquals(old,file.readBytes())
            val corrupt=java.io.File(file.parentFile,"invalid-replacement.part"); corrupt.writeText("<html>provider error</html>")
            assertFalse(audio.promote(e.id,corrupt,corrupt.length())); assertArrayEquals(old,file.readBytes()); corrupt.delete()
        } finally { audio.remove(e.id) }
    }
}
