package com.example.otrdial

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(600)
        instrumentation.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/$name.png").use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() }
        }
    }
    @Test fun lightAndDarkNavigationAndPlayer() {
        for (dark in listOf(false, true)) {
            instrumentation.targetContext.getSharedPreferences("otr_dial", Context.MODE_PRIVATE).edit().clear().putBoolean("dark_mode", dark).commit()
            val mode = if (dark) "dark" else "light"
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                Thread.sleep(1800)
                scenario.onActivity { activity ->
                    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.homeScreen).visibility)
                }
                capture("$mode-home")
                scenario.onActivity { it.findViewById<View>(R.id.exploreButton).performClick() }
                capture("$mode-explore")
                scenario.onActivity { activity ->
                    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.exploreScreen).visibility)
                    assertTrue(activity.findViewById<TextView>(R.id.listSummary).text.contains(StationRepository.load(activity).size.toString()))
                    activity.findViewById<View>(R.id.favouritesButton).performClick()
                    assertTrue(activity.findViewById<TextView>(R.id.listSummary).text.startsWith("No favourites"))
                    activity.findViewById<View>(R.id.homeButton).performClick()
                    activity.findViewById<View>(R.id.heroPlay).performClick()
                }
                Thread.sleep(1000)
                scenario.onActivity { activity ->
                    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.playerScreen).visibility)
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.navigationBar).visibility)
                    activity.findViewById<View>(R.id.favouriteButton).performClick()
                }
                capture("$mode-player")
                scenario.onActivity { activity ->
                    activity.findViewById<View>(R.id.backButton).performClick()
                    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.miniPlayer).visibility)
                    activity.findViewById<View>(R.id.favouritesButton).performClick()
                    assertEquals("1 stations", activity.findViewById<TextView>(R.id.listSummary).text.toString())
                }
                capture("$mode-favourites")
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.exploreScreen).visibility)
                }
            }
        }
    }
}
