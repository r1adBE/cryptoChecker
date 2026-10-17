package com.cryptochecker.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Wie `SettingsSearchTests.swift`. */
class SettingsSearchTest {

    private val de = Locale.GERMAN
    private val entries = listOf(
        SettingsSearchEntry("updates", "Aktualisierung", "Allgemein"),
        SettingsSearchEntry(
            "updates.background", "Im Hintergrund aktualisieren", "Aktualisierung",
            listOf("Prüft Kurse und Alarme regelmässig, auch wenn die App zu ist.")
        ),
        SettingsSearchEntry("alarms", "Alarme", "Alarme & Mitteilungen"),
        SettingsSearchEntry(
            "alarms.quiet", "Ruhezeit", "Alarme",
            listOf("In dieser Zeit klingeln Alarme nicht.")
        ),
        SettingsSearchEntry("display.contrast", "Hoher Kontrast", "Modus", listOf("Kräftigere Farben und Linien.")),
        SettingsSearchEntry("about.privacy", "Datenschutzerklärung", "Über"),
    )

    private fun ids(query: String, locale: Locale = de) =
        SettingsSearch.search(entries, query, locale).map { it.id }

    @Test
    fun emptyOrBlankQuery_findsNothing() {
        assertEquals(emptyList<String>(), ids(""))
        assertEquals(emptyList<String>(), ids("   "))
    }

    @Test
    fun caseAndDiacritics_areIgnored() {
        assertEquals("display.contrast", ids("KONTRAST").first())
        assertEquals("about.privacy", ids("datenschutzerklarung").first())
        assertEquals("about.privacy", ids("DATENSCHUTZERKLÄRUNG").first())
        // Akzent in der Suche, keiner im Titel
        assertEquals("alarms.quiet", ids("Rühezeit").first())
    }

    @Test
    fun titleStart_beatsWordStart_beatsPart_beatsPathAndSynonym() {
        // «Alarme» (Titel beginnt) vor «Ruhezeit» (Seite Alarme) vor «Im Hintergrund …» (Hinweis)
        assertEquals(listOf("alarms", "alarms.quiet", "updates.background"), ids("alarm"))
        // Titel beginnt vor Wortanfang im Titel
        assertEquals(listOf("updates", "updates.background"), ids("aktualis"))
        assertEquals(listOf("updates.background"), ids("hintergr"))
    }

    @Test
    fun everyToken_mustMatch() {
        assertEquals(listOf("updates.background"), ids("hintergrund kurse"))
        assertEquals(emptyList<String>(), ids("hintergrund kontrast"))
    }

    @Test
    fun synonyms_needTwoCharacters() {
        // Ein Zeichen: nur Titel und Seite zählen, nicht die Hinweise
        assertEquals(listOf("alarms.quiet", "about.privacy"), ids("z"))
        assertTrue(ids("kl").contains("alarms.quiet")) // «klingeln» im Hinweis
    }

    @Test
    fun duplicateIds_countOnce() {
        val twice = entries + SettingsSearchEntry("alarms", "Alarme (doppelt)")
        assertEquals(entries.size, SettingsSearch.index(twice, de).size)
    }

    @Test
    fun normalize_specialLetters() {
        assertEquals("strasse", SettingsSearch.normalize("Straße", de))
        assertEquals("oresund", SettingsSearch.normalize("Øresund", Locale.ROOT))
        assertEquals("a b", SettingsSearch.normalize("  A \n\t B  ", Locale.ROOT))
        // Türkisch: «I» → «ı» → «i», «İ» → «i»
        val tr = Locale.forLanguageTag("tr")
        assertEquals("iletisim", SettingsSearch.normalize("İLETİŞİM", tr))
        assertEquals("isik", SettingsSearch.normalize("IŞIK", tr))
        // Englisch: «İ» verliert nur den Punkt
        assertEquals("i", SettingsSearch.normalize("İ", Locale.ENGLISH))
    }

    @Test
    fun normalize_keepsOtherScripts() {
        // Dakuten bleiben (が ≠ か), Hangul und Han unverändert
        assertEquals("が", SettingsSearch.normalize("が", Locale.JAPANESE))
        assertEquals("알림", SettingsSearch.normalize("알림", Locale.KOREAN))
        // Arabisch: Vokalzeichen und Tatweel weg
        assertEquals("تنبيه", SettingsSearch.normalize("تَنْبِيـه", Locale.forLanguageTag("ar")))
        // Kyrillisch und Griechisch: Kleinbuchstaben, Akzente weg
        assertEquals("уведомления", SettingsSearch.normalize("Уведомления", Locale.forLanguageTag("ru")))
        assertEquals("ειδοποιηση", SettingsSearch.normalize("Ειδοποίηση", Locale.forLanguageTag("el")))
    }

    @Test
    fun scriptsWithoutSpaces_matchInsideTitle() {
        val zh = listOf(
            SettingsSearchEntry("a", "价格提醒", "提醒"),
            SettingsSearchEntry("b", "语音播报", "通知", listOf("朗读价格变化")),
        )
        assertEquals(listOf("a", "b"), SettingsSearch.search(zh, "价格", Locale.CHINESE).map { it.id })
    }
}
