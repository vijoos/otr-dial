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
    private fun fixture(n: Int) = Episode("episode:large:$n", "custom-large", "Gunsmoke recording $n", "Gunsmoke", "https://example.com/$n.mp3", "https://example.com", "Preserved archive description. ".repeat(24))
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
            val largeSource=EpisodeSource("custom-large","Performance fixture","archive","large","https://example.com","Western")
            CollectionStore(context).addSource(largeSource)
            start=android.os.SystemClock.elapsedRealtime()
            androidx.test.core.app.ActivityScenario.launch<LibraryActivity>(android.content.Intent(context,LibraryActivity::class.java).putExtra("source_id",largeSource.id)).use { scenario ->
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { a -> assertTrue(views(a.window.decorView).filterIsInstance<android.widget.TextView>().any { it.text.toString()=="Performance fixture" }) }
            }
            val browse=android.os.SystemClock.elapsedRealtime()-start
            val invalid=JSONObject().put("format","otr-dial-library").put("version",4).put("episodes",JSONArray().put(fixture(30000).json())).put("state",JSONObject().put("position_bad",-1))
            assertTrue(runCatching { target.restore(invalid.toString()) }.isFailure); assertEquals(25000,target.count()); assertNull(target.find(fixture(30000).id)); assertEquals(42000L,target.progress(values.first().id))
            start=android.os.SystemClock.elapsedRealtime(); target.update(EpisodeSource("custom-large", "Gunsmoke", "archive", "large", "https://example.com", "Western"), values.take(2))
            val refresh=android.os.SystemClock.elapsedRealtime()-start
            assertEquals(2,target.count()); assertEquals(42000L,target.progress(values.first().id))
            android.util.Log.i("OTR28_PERF", "25000 episodes: migration=${migration}ms search=${search}ms export=${export}ms restore=${restore}ms browse=${browse}ms refresh=${refresh}ms bytes=${backup.toByteArray().size}; emulator only")
        } finally {
            CollectionStore(context).removeSource("custom-large"); CatalogueDatabase.resetForTest(context); prefs.edit().clear().putString("catalogue", "[]").commit(); LibraryStore(context).restore(original)
        }
    }
    @Test fun replacementFailurePreservesOfflineCopy() {
        val audio=OfflineAudio(context); val e=fixture(1); val file=audio.file(e.id)
        val old=java.nio.ByteBuffer.allocate(16044).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(16036); put("WAVEfmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16); put("data".toByteArray()); putInt(16000)
        }.array(); file.writeBytes(old)
        val prefs=context.getSharedPreferences("offline24",Context.MODE_PRIVATE); prefs.edit().putBoolean("ready:${e.id}",true).commit()
        fun network(command:String) { InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { java.io.FileInputStream(it.fileDescriptor).readBytes() } }
        try {
            network("svc wifi disable"); network("svc data disable")
            audio.start(e.copy(url="http://127.0.0.1:1/unavailable.mp3"))
            assertNotNull(audio.local(e.id)); assertArrayEquals(old,file.readBytes())
            val corrupt=java.io.File(file.parentFile,"invalid-replacement.part"); corrupt.writeText("<html>provider error</html>")
            assertFalse(audio.promote(e.id,corrupt,corrupt.length())); assertArrayEquals(old,file.readBytes()); corrupt.delete()
        } finally { network("svc wifi enable"); network("svc data enable"); audio.remove(e.id) }
    }
    private fun views(v: android.view.View): List<android.view.View> = listOf(v) + if(v is android.view.ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    @Test fun searchDirectoryAndBackRestoreBrowsingState() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        androidx.test.core.app.ActivityScenario.launch(LibraryActivity::class.java).use { scenario ->
            android.os.SystemClock.sleep(1000)
            scenario.onActivity { a -> views(a.window.decorView).filterIsInstance<android.widget.TextView>().first { it.text.toString()=="Search" }.performClick() }
            instrumentation.waitForIdleSync()
            scenario.onActivity { a -> views(a.window.decorView).filterIsInstance<android.widget.EditText>().first().setText("Gunsmoke"); views(a.window.decorView).filterIsInstance<android.widget.TextView>().first { it.text.toString()=="Find" }.performClick() }
            instrumentation.waitForIdleSync()
            scenario.onActivity { a ->
                assertTrue(views(a.window.decorView).filterIsInstance<android.widget.TextView>().any { it.text.toString()=="Gunsmoke • all listening sources" })
                views(a.window.decorView).filterIsInstance<android.widget.TextView>().first { it.text.toString()=="Gunsmoke • Browse episodes" }.performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }; instrumentation.waitForIdleSync()
            scenario.onActivity { a -> assertEquals("Gunsmoke",views(a.window.decorView).filterIsInstance<android.widget.EditText>().first().text.toString()) }
            scenario.recreate(); instrumentation.waitForIdleSync()
            scenario.onActivity { a -> assertEquals("Gunsmoke",views(a.window.decorView).filterIsInstance<android.widget.EditText>().first().text.toString()) }
        }
    }

}
