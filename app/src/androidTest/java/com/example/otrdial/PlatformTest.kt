package com.example.otrdial

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlatformTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun click(a: LibraryActivity, text: String) { all(a.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == text }.performClick() }
    @Test fun directoryLinksAndSavedSourcesSurviveBackup() {
        val directory = SourceDirectory.load(context)
        assertTrue(directory.size >= 180)
        assertEquals(directory.size, directory.map { it.id }.distinct().size)
        assertTrue(directory.count { it.category == "youtube" } >= 8)
        assertTrue(directory.all { EpisodeCatalogue.validUrl(it.page) })
        val stationIds = StationRepository.load(context).map { it.id }
        assertTrue(directory.all { it.stationId.isBlank() || it.stationId in stationIds })
        val store = LibraryStore(context)
        val id = directory.first { it.category == "youtube" }.id
        if (id !in store.directorySaved()) store.toggle("directory_saved", id)
        val backup = store.export(); store.toggle("directory_saved", id)
        store.restore(backup); assertTrue(id in store.directorySaved())
    }
    @Test fun longFeedsAndFlacCollections() {
        val source = EpisodeCatalogue.sources.first()
        val items = (1..300).joinToString("") { "<item><title>Episode $it</title><guid>$it</guid><enclosure type=\"audio/mpeg\" url=\"https://example.com/$it.mp3\"/></item>" }
        assertEquals(300, EpisodeCatalogue.parseRss(source, "<rss><channel>$items</channel></rss>").size)
        val files = JSONObject("""{"metadata":{},"files":[{"name":"one.flac"},{"name":"one.mp3","original":"one.flac"},{"name":"two.flac"},{"name":"private.flac","private":true}]}""")
        val parsed = EpisodeCatalogue.parseArchive(EpisodeCatalogue.sources[4], files)
        assertEquals(2, parsed.size); assertTrue(parsed.any { it.url.endsWith("one.mp3") }); assertTrue(parsed.any { it.url.endsWith("two.flac") })
    }
    @Test fun fiveTabsAndSourceFavouritesInBothThemes() {
        for (dark in listOf(false, true)) {
            context.getSharedPreferences("otr_dial", Context.MODE_PRIVATE).edit().putBoolean("dark_mode", dark).commit()
            ActivityScenario.launch(LibraryActivity::class.java).use { scenario ->
                scenario.onActivity { a ->
                    for (tab in listOf("Radio", "Repo", "Podcasts", "YouTube", "Favourites")) {
                        click(a, tab)
                        assertTrue(all(a.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == tab })
                    }
                    click(a, "YouTube")
                    assertTrue(all(a.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == "Old Time Radio Researchers (OTRR)" })
                    click(a, "Favourites")
                }
                scenario.recreate()
                scenario.onActivity { a -> assertTrue(all(a.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == "Your favourites" }) }
            }
        }
    }
}
