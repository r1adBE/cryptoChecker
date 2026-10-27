package com.cryptochecker.app.domain.portfolio

import java.time.LocalDate

/**
 * Hinweis auf die ESTV-Kursliste (ICTax) im Stichtag-Export: Für die Vermögenssteuer gelten die
 * offiziellen Jahresendkurse der ESTV. Es gibt keine dokumentierte, offene Schnittstelle dafür —
 * die App lädt nichts, sie verlinkt nur die Liste des Jahres (öffnet der Nutzer selbst).
 * Reines Kotlin, gleich wie `EstvKursliste.swift`.
 */
object EstvKursliste {

    const val HOST = "www.ictax.admin.ch"

    /** Sprachen der ICTax-Oberfläche; sonst Englisch. */
    private val LANGUAGES = setOf("de", "fr", "it", "en")

    /** Kursliste des Steuerjahrs [year] in der Sprache [language] (z. B. «de», «de-CH»). */
    fun listUrl(year: Int, language: String): String {
        val lang = language.lowercase().substringBefore('-').substringBefore('_')
        val code = if (lang in LANGUAGES) lang else "en"
        return "https://$HOST/extern/$code.html#/ratelist/$year"
    }

    /** Nur in CHF und per 31.12. (Stichtag der Steuerwerte). */
    fun applies(date: LocalDate, currency: String): Boolean =
        currency.uppercase() == "CHF" && date.monthValue == 12 && date.dayOfMonth == 31

    /** Hinweiszeile aus [format] (%1$s = Jahr, %2$s = Link) oder null, wenn kein Hinweis gilt. */
    fun noteLine(format: String?, date: LocalDate, currency: String, language: String): String? {
        if (format.isNullOrEmpty() || !applies(date, currency)) return null
        return format.format(date.year.toString(), listUrl(date.year, language))
    }
}
