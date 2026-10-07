package com.cryptochecker.app.settings

import androidx.core.content.edit
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/** Eine wählbare Sprache, angezeigt in ihrer eigenen Schreibweise. */
data class AppLanguage(val tag: String, val nativeName: String)

/**
 * Sprachwahl der App. Leerer Tag = Systemsprache (Standard bei der Installation).
 *
 * Ab Android 13 verwaltet das System die Sprache pro App selbst (auch in den
 * Android-Einstellungen änderbar). Darunter speichert die App die Wahl und
 * setzt sie für Fenster (AppCompat) und für den App-Kontext — sonst blieben
 * Benachrichtigungen, Widgets und Sprachausgabe in der Systemsprache.
 */
object AppLanguages {

    val ALL: List<AppLanguage> = listOf(
        AppLanguage("en", "English"),
        AppLanguage("de", "Deutsch"),
        AppLanguage("fr", "Français"),
        AppLanguage("it", "Italiano"),
        AppLanguage("es", "Español"),
        AppLanguage("pt", "Português (Portugal)"),
        AppLanguage("pt-BR", "Português (Brasil)"),
        AppLanguage("nl", "Nederlands"),
        AppLanguage("pl", "Polski"),
        AppLanguage("cs", "Čeština"),
        AppLanguage("hu", "Magyar"),
        AppLanguage("ro", "Română"),
        AppLanguage("sq", "Shqip"),
        AppLanguage("el", "Ελληνικά"),
        AppLanguage("tr", "Türkçe"),
        AppLanguage("sv", "Svenska"),
        AppLanguage("da", "Dansk"),
        AppLanguage("nb", "Norsk bokmål"),
        AppLanguage("fi", "Suomi"),
        AppLanguage("ru", "Русский"),
        AppLanguage("uk", "Українська"),
        AppLanguage("ar", "العربية"),
        AppLanguage("he", "עברית"),
        AppLanguage("fa", "فارسی"),
        AppLanguage("hi", "हिन्दी"),
        AppLanguage("th", "ไทย"),
        AppLanguage("vi", "Tiếng Việt"),
        AppLanguage("id", "Bahasa Indonesia"),
        AppLanguage("zh", "中文（简体）"),
        AppLanguage("ja", "日本語"),
        AppLanguage("ko", "한국어"),
    )

    private const val PREFS = "app_language"
    private const val KEY_TAG = "tag"

    private fun storedTag(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TAG, "") ?: ""

    /** Aktuell gewählte Sprache; leer = Systemsprache. */
    fun currentTag(context: Context): String {
        val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag().orEmpty()
        } else {
            storedTag(context)
        }
        return normalize(tag)
    }

    /** Sprache wählen; leerer Tag = zurück zur Systemsprache. Fenster bauen sich neu auf. */
    fun select(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_TAG, tag) }
        AppCompatDelegate.setApplicationLocales(
            if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(tag)
        )
    }

    /** Beim App-Start bis Android 12: gespeicherte Wahl an AppCompat übergeben. */
    fun restoreForActivities(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        val tag = storedTag(context)
        if (tag.isNotEmpty()) AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    /**
     * Bis Android 12: App-Kontext in der gewählten Sprache, damit auch
     * Benachrichtigungen, Widgets und Ansagen sie verwenden.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = storedTag(base)
        if (tag.isEmpty()) return base

        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration).apply {
            setLocales(LocaleList(locale))
        }
        return base.createConfigurationContext(config)
    }

    /**
     * Alte Android-Codes (iw, in) auf die heutigen abbilden und nur die Sprache behalten —
     * ausser bei Brasil-Portugiesisch (pt-BR), das als eigene Sprache wählbar ist.
     */
    private fun normalize(tag: String): String {
        if (tag.isEmpty()) return ""
        val locale = Locale.forLanguageTag(tag)
        return when (val lang = locale.language) {
            "iw" -> "he"
            "in" -> "id"
            "pt" -> if (locale.country == "BR") "pt-BR" else "pt"
            else -> lang
        }
    }
}
