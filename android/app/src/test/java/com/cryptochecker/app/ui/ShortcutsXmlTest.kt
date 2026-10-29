package com.cryptochecker.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Statische App-Verknüpfungen (res/xml/shortcuts.xml): Manifest-Platzhalter wie `${applicationId}` werden dort nicht
 * ersetzt, darum hat die Debug-App eine eigene Kopie mit ihrem Paket. Beide müssen bis auf targetPackage gleich
 * bleiben, und jede Verknüpfung trägt eine lange Beschriftung (höchstens 25 Zeichen in allen Sprachen).
 */
class ShortcutsXmlTest {

    private fun file(path: String): File =
        listOf(File(path), File("app/$path")).first { it.exists() }

    private fun withoutComments(xml: String) = xml.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    private val main by lazy { withoutComments(file("src/main/res/xml/shortcuts.xml").readText()) }
    private val debug by lazy { withoutComments(file("src/debug/res/xml/shortcuts.xml").readText()) }

    private fun packages(xml: String) =
        Regex("""android:targetPackage="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toSet()

    @Test
    fun paketJeBuildTyp() {
        assertEquals(setOf("com.cryptochecker.app"), packages(main))
        assertEquals(setOf("com.cryptochecker.app.debug"), packages(debug))
    }

    @Test
    fun debugKopieGleichBisAufsPaket() {
        val normalized = debug.replace("\"com.cryptochecker.app.debug\"", "\"com.cryptochecker.app\"")
        assertEquals(main.trim(), normalized.trim())
    }

    @Test
    fun jedeVerknuepfungHatLangeBeschriftungMitHoechstens25Zeichen() {
        val shortcuts = Regex("""android:shortcutId="""").findAll(main).count()
        val longKeys = Regex("""android:shortcutLongLabel="@string/([a-z_]+)"""").findAll(main).map { it.groupValues[1] }.toList()
        assertEquals(shortcuts, longKeys.size)
        val res = file("src/main/res")
        val locales = res.listFiles().orEmpty().filter { File(it, "strings_common.xml").exists() }
        assertTrue(locales.size >= 75)
        for (dir in locales) {
            val xml = File(dir, "strings_common.xml").readText()
            for (key in longKeys) {
                val text = Regex("""<string name="$key">(.*?)</string>""").find(xml)?.groupValues?.get(1)
                assertTrue("$key fehlt in ${dir.name}", text != null)
                assertTrue("$key in ${dir.name} zu lang: $text", text!!.length <= 25)
            }
        }
    }
}
