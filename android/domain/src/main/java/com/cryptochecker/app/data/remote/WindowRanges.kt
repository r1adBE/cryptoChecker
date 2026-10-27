package com.cryptochecker.app.data.remote

import com.cryptochecker.app.domain.alarm.NearExtreme

/**
 * Hoch und Tief der letzten 30 / 90 / 365 Tage je Paar für den Alarm «Nahe am Hoch/Tief».
 *
 * @property currency Währung der Werte: die Quote des Paars, oder «USDT», wenn nur
 *   das USDT-Paar Kerzen hat (der Aufrufer rechnet dann um)
 * @property ranges Zeitraum in Tagen → Hoch/Tief; fehlt, wenn die Reihe zu kurz ist
 * @property lastClose letzter Schluss der Reihe ([currency]) — Kursband-Prüfung beim Aufrufer ([NearExtreme.seriesAllowed])
 * @property provider Anbieter der Reihe («Binance», «Coinbase» …) für die Namensprüfung
 *   (`TokenIdentityStore.fallbackSeriesAllowed`); null = unbekannt
 */
data class WindowRanges(
    val currency: String,
    val ranges: Map<Int, NearExtreme.Range>,
    val savedAt: Long,
    val lastClose: Double,
    val provider: String?,
)
