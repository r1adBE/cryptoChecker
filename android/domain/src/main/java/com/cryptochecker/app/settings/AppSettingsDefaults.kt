// Grenzen und Geräte-Standards der Einstellungen (Teil von `AppSettings.kt`, gespiegelt in
// `AppSettings+Defaults.swift`). Erweiterungen von `AppSettings.Companion`, damit `AppSettings.clampBackgroundInterval(…)` usw.
// gelten; die Konstanten stehen in [AppSettings].
package com.cryptochecker.app.settings

import com.cryptochecker.app.settings.AppSettings.Companion.MAX_ALARM_COOLDOWN_MINUTES
import com.cryptochecker.app.settings.AppSettings.Companion.MAX_BACKGROUND_INTERVAL_MINUTES
import com.cryptochecker.app.settings.AppSettings.Companion.MIN_BACKGROUND_INTERVAL_MINUTES

fun AppSettings.Companion.clampBackgroundInterval(minutes: Int): Int =
    minutes.coerceIn(MIN_BACKGROUND_INTERVAL_MINUTES, MAX_BACKGROUND_INTERVAL_MINUTES)

fun AppSettings.Companion.clampAlarmCooldown(minutes: Int): Int = minutes.coerceIn(0, MAX_ALARM_COOLDOWN_MINUTES)

/**
 * Portfolio nach Standardwerten einschalten? Ja, wenn die Einstellungen neu angelegt wurden ([resetDefaults], z. B.
 * nach beschädigter Datei), das Portfolio aus ist und trotzdem Portfolio-Daten da sind (Transaktionen, Adressen,
 * Portfolio-Alarme) — sonst blieben Tab und Portfolio-Alarme stumm. Wie iOS `AppSettings.enablePortfolioForData`.
 */
fun AppSettings.Companion.enablePortfolioForData(resetDefaults: Boolean, portfolioEnabled: Boolean, hasPortfolioData: Boolean): Boolean =
    resetDefaults && !portfolioEnabled && hasPortfolioData

/** Regionen, in denen Rot für steigende Kurse steht. */
private val INVERTED_PRICE_COLOR_REGIONS = setOf("CN", "TW", "HK", "MO", "JP", "KR")

/**
 * Standard für «Farben tauschen»: an in CN, TW, HK, MO, JP und KR (Region des Geräts).
 * Region aus der System-Konfiguration: Ist in der App eine Sprache gewählt, setzt
 * [AppLanguages] `Locale.setDefault` auf ein Tag ohne Region (z. B. «ja»), dann wäre
 * `Locale.getDefault().country` leer.
 */
fun defaultPriceColorsInverted(locale: java.util.Locale = deviceRegionLocale()): Boolean =
    locale.country.uppercase() in INVERTED_PRICE_COLOR_REGIONS

/** Region des Geräts (System-Konfiguration), auch wenn in der App eine Sprache gewählt ist. */
fun deviceRegionLocale(): java.util.Locale =
    runCatching { android.content.res.Resources.getSystem().configuration.locales[0] }.getOrNull()
        ?.takeIf { it.country.isNotEmpty() }
        ?: java.util.Locale.getDefault()

/**
 * Dezimalzeichen für Zahleneingaben (Schwellwerte, Menge/Kurs im Bestand): Sprache der App,
 * Region des Geräts — gleich wie `ThresholdParser.localeDecimalSeparator` (iOS, `Locale.current`).
 */
fun inputDecimalSeparator(): Char =
    com.cryptochecker.app.util.LocaleNumbers.inputDecimalSeparator(java.util.Locale.getDefault(), deviceRegionLocale().country)

/**
 * Standard-Umrechnungswährung: die Währung des Geräte-Landes (Schweiz → CHF,
 * Deutschland → EUR, USA → USD …), sofern dafür ein Devisenkurs verfügbar ist,
 * sonst USD. Gilt nur, bis jemand selbst eine Währung wählt.
 */
fun defaultCurrency(locale: java.util.Locale = java.util.Locale.getDefault()): String {
    val code = runCatching { java.util.Currency.getInstance(locale)?.currencyCode }.getOrNull()
    return code?.takeIf { it in com.cryptochecker.app.domain.convert.FxCurrencies.ALL } ?: "USD"
}

/**
 * Wahlmöglichkeiten der Einstellungen — aus `AppSettings.kt` ausgelagert (Datei über 400 Zeilen). `AppSettings.Companion` erbt sie: Aufrufe bleiben `AppSettings.GAS_ETH_CHOICES` usw.
 */
open class AppSettingsChoices constructor() {
    val BACKGROUND_INTERVAL_CHOICES = listOf(15, 30, 60)
    val ALARM_COOLDOWN_CHOICES = listOf(0, 5, 15, 60)
    val FEAR_GREED_BELOW_CHOICES = listOf(0, 10, 20, 25)
    val FEAR_GREED_ABOVE_CHOICES = listOf(0, 75, 80, 90)

    /** Gas-Alarm Ethereum in Zehntel-gwei: aus, 0.5, 1, 2, 5. */
    val GAS_ETH_CHOICES = listOf(0, 5, 10, 20, 50)

    /** Gas-Alarm Bitcoin in sat/vB. */
    val GAS_BTC_CHOICES = listOf(0, 1, 2, 5, 10)
}
