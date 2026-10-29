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
        AppLanguage("ca", "Català"),
        AppLanguage("gl", "Galego"),
        AppLanguage("eu", "Euskara"),
        AppLanguage("pt", "Português (Portugal)"),
        AppLanguage("pt-BR", "Português (Brasil)"),
        AppLanguage("nl", "Nederlands"),
        AppLanguage("af", "Afrikaans"),
        AppLanguage("ga", "Gaeilge"),
        AppLanguage("pl", "Polski"),
        AppLanguage("cs", "Čeština"),
        AppLanguage("sk", "Slovenčina"),
        AppLanguage("hu", "Magyar"),
        AppLanguage("ro", "Română"),
        AppLanguage("sl", "Slovenščina"),
        AppLanguage("hr", "Hrvatski"),
        AppLanguage("bs", "Bosanski"),
        AppLanguage("sr", "Српски"),
        AppLanguage("sr-Latn", "Srpski (latinica)"),
        AppLanguage("mk", "Македонски"),
        AppLanguage("bg", "Български"),
        AppLanguage("sq", "Shqip"),
        AppLanguage("el", "Ελληνικά"),
        AppLanguage("tr", "Türkçe"),
        AppLanguage("az", "Azərbaycan dili"),
        AppLanguage("sv", "Svenska"),
        AppLanguage("da", "Dansk"),
        AppLanguage("nb", "Norsk bokmål"),
        AppLanguage("is", "Íslenska"),
        AppLanguage("fi", "Suomi"),
        AppLanguage("et", "Eesti"),
        AppLanguage("lv", "Latviešu"),
        AppLanguage("lt", "Lietuvių"),
        AppLanguage("ru", "Русский"),
        AppLanguage("uk", "Українська"),
        AppLanguage("kk", "Қазақ тілі"),
        AppLanguage("uz", "Oʻzbekcha"),
        AppLanguage("mn", "Монгол"),
        AppLanguage("ka", "ქართული"),
        AppLanguage("hy", "Հայերեն"),
        AppLanguage("ar", "العربية"),
        AppLanguage("he", "עברית"),
        AppLanguage("am", "አማርኛ"),
        AppLanguage("sw", "Kiswahili"),
        AppLanguage("zu", "isiZulu"),
        AppLanguage("fa", "فارسی"),
        AppLanguage("ur", "اردو"),
        AppLanguage("hi", "हिन्दी"),
        AppLanguage("mr", "मराठी"),
        AppLanguage("ne", "नेपाली"),
        AppLanguage("bn", "বাংলা"),
        AppLanguage("pa", "ਪੰਜਾਬੀ"),
        AppLanguage("gu", "ગુજરાતી"),
        AppLanguage("ta", "தமிழ்"),
        AppLanguage("te", "తెలుగు"),
        AppLanguage("kn", "ಕನ್ನಡ"),
        AppLanguage("ml", "മലയാളം"),
        AppLanguage("si", "සිංහල"),
        AppLanguage("th", "ไทย"),
        AppLanguage("lo", "ລາວ"),
        AppLanguage("km", "ខ្មែរ"),
        AppLanguage("my", "မြန်မာ"),
        AppLanguage("vi", "Tiếng Việt"),
        AppLanguage("id", "Bahasa Indonesia"),
        AppLanguage("ms", "Bahasa Melayu"),
        AppLanguage("fil", "Filipino"),
        AppLanguage("zh", "中文（简体）"),
        AppLanguage("zh-Hant", "中文（繁體）"),
        AppLanguage("yue", "粵語"),
        AppLanguage("ja", "日本語"),
        AppLanguage("ko", "한국어"),
    )

    /** Regionen, in denen Chinesisch ohne Schriftangabe traditionell geschrieben wird. */
    private val TRADITIONAL_CHINESE_REGIONS = setOf("TW", "HK", "MO")

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
        applyNumberLocale()
        val config = Configuration(base.resources.configuration).apply {
            setLocales(LocaleList(locale))
        }
        return base.createConfigurationContext(config)
    }

    /**
     * Zahlen-Locale (Anzeige und Eingabe) = App-Sprache + Region des Geräts
     * ([com.cryptochecker.app.util.LocaleNumbers.formatLocale]): Deutsch auf einem Schweizer
     * Gerät zeigt «90’000.00» wie die Eingabe, nicht «90.000,00».
     */
    fun applyNumberLocale() {
        com.cryptochecker.app.util.LocaleNumbers.applyFormatLocale(Locale.getDefault(), deviceRegionLocale().country)
        // Kursformate (zwischengespeicherte Trennzeichen) neu aufbauen
        com.cryptochecker.marketdata.util.DisplayFormat.updateLocale()
    }

    /**
     * Alte Android-Codes (iw, in, tl) auf die heutigen abbilden und nur die Sprache behalten —
     * ausser bei Brasil-Portugiesisch (pt-BR), traditionellem Chinesisch (zh-Hant, auch zh-TW/-HK/-MO) und
     * Serbisch in lateinischer Schrift (sr-Latn), die als eigene Sprachen wählbar sind.
     */
    private fun normalize(tag: String): String {
        if (tag.isEmpty()) return ""
        val locale = Locale.forLanguageTag(tag)
        return when (val lang = locale.language) {
            "iw" -> "he"
            "in" -> "id"
            "tl" -> "fil"
            "pt" -> if (locale.country == "BR") "pt-BR" else "pt"
            "zh" -> if (locale.script == "Hant" || locale.country in TRADITIONAL_CHINESE_REGIONS) "zh-Hant" else "zh"
            "sr" -> if (locale.script == "Latn") "sr-Latn" else "sr"
            else -> lang
        }
    }
}
