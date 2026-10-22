package com.cryptochecker.app.settings

import com.cryptochecker.app.util.SourceGuard
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

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
    fun `all launcher aliases are exactly one per accent`() {
        val all = AccentColor.allLauncherAliases()
        assertEquals(AccentColor.entries.map { it.launcherAlias() }, all)
    }

    /**
     * Manifest und Code kennen dieselben Aliase (das Umschalten eines unbekannten Alias wirft), und ab
     * Installation ist genau einer aktiv: der der Standardfarbe.
     */
    @Test
    fun `manifest declares exactly these aliases with only the default enabled`() {
        val manifest = SourceGuard.android(findManifest(), "src/main/AndroidManifest.xml")
        val aliases = Regex("""<activity-alias\b(.*?)>""", RegexOption.DOT_MATCHES_ALL).findAll(manifest.readText())
            .map { it.groupValues[1] }
            .toList()
        val names = aliases.map { Regex("""android:name="\.(\w+)"""").find(it)!!.groupValues[1] }
        assertEquals(
            AccentColor.allLauncherAliases().map { it.removePrefix("com.cryptochecker.app.") }.sorted(),
            names.sorted(),
        )
        val enabled = aliases.filter { it.contains("""android:enabled="true"""") }
            .map { Regex("""android:name="\.(\w+)"""").find(it)!!.groupValues[1] }
        assertEquals(listOf(AccentColor.DEFAULT.launcherAlias().removePrefix("com.cryptochecker.app.")), enabled)
    }

    private fun findManifest(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (rel in listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml", "android/app/src/main/AndroidManifest.xml")) {
                val f = File(dir, rel)
                if (f.isFile) return f
            }
            dir = dir.parentFile
        }
        return null
    }
}
