package com.cryptochecker.app

import android.content.Context
import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `androidResources.localeFilters` (build.gradle.kts) darf keine App-Sprache wegfiltern: für Sprachen mit eigener Schrift
 * oder abweichendem Ordnernamen (iw/he, in/id, pt-rBR, b+zh+Hant für zh-Hant/zh-TW/zh-HK) liefert die installierte App
 * einen übersetzten Text, nicht den englischen. Fehlt eine Sprache im Filter, fällt sie still auf Englisch zurück —
 * dieser Test macht das in CI sichtbar.
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
        val tags = listOf("ja", "ar", "iw", "he", "in", "id", "pt-BR", "zh", "zh-Hant", "zh-TW", "zh-HK", "ms", "fa")
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
}
