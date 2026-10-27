package com.cryptochecker.app.data.remote

/**
 * Volumen der letzten abgeschlossenen Stunde im Vergleich zum Schnitt der
 * 24 Stunden davor.
 *
 * @property ratio z. B. 4.2 = «4,2-mal so viel wie üblich»
 * @property candleOpenTime Startzeit der bewerteten Stundenkerze (ms, UTC)
 * @property source Börse der Kerzen, wenn nicht die des Paars (Ausweich-Kette, z. B. «Binance»): im Alarmtext genannt;
 *   null = eigene Börse
 */
data class VolumeSpike(val ratio: Double, val candleOpenTime: Long, val source: String? = null)
