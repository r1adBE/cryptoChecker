package com.cryptochecker.app.util

import com.cryptochecker.app.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Test

/** Widgets öffnen die App über den Namen der Start-Activity ([AppIntents]): er muss zur Klasse passen. */
class AppIntentsTest {

    @Test
    fun nameUndExtrasPassenZurStartActivity() {
        assertEquals(MainActivity::class.java.name, AppIntents.MAIN_ACTIVITY)
        assertEquals(MainActivity.EXTRA_WATCH_ID, AppIntents.EXTRA_WATCH_ID)
        assertEquals(MainActivity.EXTRA_OPEN, AppIntents.EXTRA_OPEN)
    }
}
