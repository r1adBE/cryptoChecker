package com.cryptochecker.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B3: App-Icon hängt nur an der Akzentfarbe (kein Wechsel bei Hell/Dunkel). */
class AccentColorTest {

    @Test
    fun `one launcher alias per accent, matching the manifest names`() {
        assertEquals("com.cryptochecker.app.LauncherOrange", AccentColor.ORANGE.launcherAlias())
        assertEquals("com.cryptochecker.app.LauncherRed", AccentColor.RED.launcherAlias())
        assertEquals("com.cryptochecker.app.LauncherBlue", AccentColor.BLUE.launcherAlias())
        assertEquals("com.cryptochecker.app.LauncherGreen", AccentColor.GREEN.launcherAlias())
        assertEquals("com.cryptochecker.app.LauncherMarrsGreen", AccentColor.MARRS_GREEN.launcherAlias())
        // Eindeutig je Farbe
        assertEquals(AccentColor.entries.size, AccentColor.entries.map { it.launcherAlias() }.toSet().size)
    }

    @Test
    fun `legacy light aliases are listed so the migration disables them`() {
        val all = AccentColor.allLauncherAliases()
        assertEquals(AccentColor.entries.size * 2, all.toSet().size)
        AccentColor.entries.forEach { accent ->
            assertTrue(accent.launcherAlias() in all)
            assertTrue(accent.legacyLightAlias() in all)
            assertEquals(accent.launcherAlias() + "Light", accent.legacyLightAlias())
        }
        // Kein Ziel-Alias ist ein früherer Hell-Alias
        assertFalse(AccentColor.entries.any { it.launcherAlias().endsWith("Light") })
    }
}
