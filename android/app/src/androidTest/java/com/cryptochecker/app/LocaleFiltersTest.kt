package com.cryptochecker.app

import android.content.Context
import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `androidResources.localeFilters` (build.gradle.kts) darf keine App-Sprache wegfiltern: für Sprachen mit eigener Schrift
 * oder abweichendem Ordnernamen (iw/he, in/id, pt-rBR, b+zh+Hant für zh-Hant/zh-TW/zh-HK, fil auch als tl, b+yue,
 * b+sr+Latn für sr-Latn) liefert die installierte App einen übersetzten Text, nicht den englischen. Fehlt eine Sprache
 * im Filter, fällt sie still auf Englisch zurück — dieser Test macht das in CI sichtbar. Hongkong ohne App-Wahl bleibt
 * traditionelles Chinesisch, Kantonesisch (yue) gibt es nur auf ausdrücklichen Wunsch; Serbisch ohne Schriftangabe
 * bleibt kyrillisch, die lateinische Schrift (sr-Latn) ist ein eigener Ordner.
 */
@RunWith(AndroidJUnit4::class)
class LocaleFiltersTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun text(locale: Locale, id: Int): String {
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config).getString(id)
    }

    @Test
    fun appLanguagesSurviveTheLocaleFilter() {
        val english = text(Locale.ENGLISH, R.string.action_cancel)
        val tags = listOf(
            "ja", "ar", "iw", "he", "in", "id", "pt-BR", "zh", "zh-Hant", "zh-TW", "zh-HK", "ms", "fa",
            "fil", "fil-PH", "tl", "yue", "yue-HK", "sr", "sr-Latn", "sr-Latn-RS", "bs", "uz", "az", "kk", "mn", "pa",
            "ur", "my", "km", "si",
        )
        for (tag in tags) {
            val locale = Locale.forLanguageTag(tag)
            assertNotEquals("$tag: action_cancel", english, text(locale, R.string.action_cancel))
            // App-eigener Text (nicht nur aus einer Bibliothek): Hinweis der Stichtag-Datei
            assertNotEquals(
                "$tag: portfolio_export_address_lots_note",
                text(Locale.ENGLISH, R.string.portfolio_export_address_lots_note),
                text(locale, R.string.portfolio_export_address_lots_note),
            )
        }
    }

    @Test
    fun hongKongStaysTraditionalChineseAndCantoneseIsSeparate() {
        val id = R.string.portfolio_export_address_lots_note
        val hant = text(Locale.forLanguageTag("zh-Hant"), id)
        assertEquals(hant, text(Locale.forLanguageTag("zh-HK"), id))
        assertNotEquals(hant, text(Locale.forLanguageTag("yue-HK"), id))
    }

    @Test
    fun serbianLatinIsSeparateFromCyrillic() {
        val id = R.string.portfolio_export_address_lots_note
        val cyrillic = text(Locale.forLanguageTag("sr"), id)
        assertEquals(cyrillic, text(Locale.forLanguageTag("sr-RS"), id))
        assertNotEquals(cyrillic, text(Locale.forLanguageTag("sr-Latn-RS"), id))
    }
}
