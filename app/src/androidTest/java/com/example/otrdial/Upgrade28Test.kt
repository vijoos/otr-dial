package com.example.otrdial

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Upgrade28Test {
    @Test fun installedOver27RetainsOriginalState() {
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        val id="episode:upgrade:1"; val library=LibraryStore(c); val collections=CollectionStore(c)
        assertEquals("Upgrade preservation fixture",library.find(id)?.title)
        assertTrue(id in library.saved()); assertEquals(42000L,library.progress(id)); assertEquals(listOf(id),library.queue())
        assertEquals(listOf(id),collections.playlistIds("upgrade-list")); assertTrue(collections.bookmarks().any { it.getString("id")=="upgrade-bookmark" })
        assertTrue(collections.sources().any { it.id=="custom-upgrade" }); assertTrue("Gunsmoke" in collections.followedProgrammes())
        assertTrue("v13-gunsmoke-24-7" in c.getSharedPreferences("otr_dial",Context.MODE_PRIVATE).getStringSet("favourites",emptySet()).orEmpty())
        assertEquals(1234567L,c.getSharedPreferences("offline24",Context.MODE_PRIVATE).getLong("job:$id",-1))
        for(name in listOf("episode_library","collections24","otr_dial","offline24")) c.getSharedPreferences(name,Context.MODE_PRIVATE).edit().clear().commit()
        CatalogueDatabase.resetForTest(c)
    }
}
