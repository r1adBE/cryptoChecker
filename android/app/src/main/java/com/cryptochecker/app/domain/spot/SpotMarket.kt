package com.cryptochecker.app.domain.spot

import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.macro.MiniJson
import com.cryptochecker.app.util.LocaleNumbers
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.abs

/** Spot-Kennzahlen eines Coins aus CoinGecko `/coins/markets` (alle Beträge in USD). */
data class SpotData(
    /** CoinGecko-Kennung («bitcoin»). */
    val id: String,
    /** Kürzel gross («BTC»). */
    val symbol: String,
    val name: String?,
    val priceUsd: Double?,
    val marketCap: Double?,
    /** Veränderung der Marktkapitalisierung über 24 h in Prozent. */
    val marketCapChange24h: Double?,
    val volume24h: Double?,
    /** Voll verwässerte Bewertung (Fully Diluted Valuation). */
    val fdv: Double?,
    val circulatingSupply: Double?,
    val totalSupply: Double?,
    /** null: keine Obergrenze oder unbekannt. */
    val maxSupply: Double?,
    /** Stand bei CoinGecko (Epoch-ms). */
    val lastUpdated: Long?,
)

/**
 * Regeln für den Spot-Tab der Karte «Marktdaten» im Aktionsblatt (gleich in iOS `SpotMarket.swift`,
 * gemeinsame Testfälle `testdata/parity/spot_market.json`):
 *
 * - Quelle zuerst die CoinGecko-Rangliste (Top 40, [TOP_URL], für alle Nutzer gleich, direkt bei CoinGecko),
 *   sonst EINE Anfrage `/coins/markets?vs_currency=usd&symbols=<kürzel>` ([url]) — nur, wenn der
 *   Nutzer den Spot-Tab öffnet, ~10 min im Speicher ([CACHE_TTL_MILLIS]).
 * - Kein Abruf für TradFi-Paare und DEX-Pools ([eligible]) und für Kürzel ausserhalb A–Z/0–9
 *   ([lookupSymbol]): Dort heisst oft ein fremder Token gleich.
 * - Teilen sich Coins ein Kürzel, gewinnt der mit der grössten Marktkapitalisierung ([pick]).
 * - Beträge ≤ 0 (CoinGecko meldet 0 für «unbekannt») gelten als fehlend; Volumen 0 bleibt.
 */
object SpotMarket {

    /** Top-40-Rangliste, für alle Nutzer gleich (direkt bei CoinGecko, nicht gespiegelt). */
    const val TOP_URL =
        "https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&order=market_cap_desc&per_page=40&page=1"

    /** Einzelabfrage nach Kürzel; CoinGecko liefert dazu den grössten Coin mit diesem Kürzel. */
    const val SYMBOL_URL_PREFIX = "https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&symbols="

    /** So lange gilt ein Ergebnis (auch «keine Daten») im Speicher. */
    const val CACHE_TTL_MILLIS = 10L * 60 * 1000

    private val SYMBOL = Regex("^[A-Z0-9]{1,15}$")

    /** Spot-Daten für dieses Paar überhaupt suchen? Nie für TradFi und DEX-Pools. */
    fun eligible(marketKey: String?, tradFi: Boolean): Boolean = !tradFi && CoinLogos.allowedFor(marketKey)

    /** Kürzel für die Suche («1000PEPE» → «PEPE», «XBT» → «BTC»); null, wenn nicht nur A–Z/0–9. */
    fun lookupSymbol(baseAsset: String): String? =
        CoinLogos.normalize(baseAsset).takeIf { SYMBOL.matches(it) }

    /** Adresse der Einzelabfrage für ein Kürzel aus [lookupSymbol]. */
    fun url(symbol: String): String = SYMBOL_URL_PREFIX + symbol.lowercase(Locale.ROOT)

    /** Antwort von `/coins/markets` lesen; Einträge ohne Kennung oder Kürzel fallen weg. */
    fun parse(text: String?): List<SpotData> {
        if (text.isNullOrBlank()) return emptyList()
        val root = try {
            MiniJson.parse(text) as? List<*>
        } catch (e: IllegalArgumentException) {
            null
        } ?: return emptyList()
        return root.mapNotNull { item -> (item as? Map<*, *>)?.let(::entry) }
    }

    private fun entry(o: Map<*, *>): SpotData? {
        val id = (o["id"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val symbol = (o["symbol"] as? String)?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        val marketCap = positive(o["market_cap"])
        return SpotData(
            id = id,
            symbol = symbol,
            name = (o["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
            priceUsd = positive(o["current_price"]),
            marketCap = marketCap,
            // Ohne Marktkapitalisierung ist auch ihre Veränderung bedeutungslos
            marketCapChange24h = if (marketCap == null) null else finite(o["market_cap_change_percentage_24h"]),
            volume24h = finite(o["total_volume"])?.takeIf { it >= 0 },
            fdv = positive(o["fully_diluted_valuation"]),
            circulatingSupply = positive(o["circulating_supply"]),
            totalSupply = positive(o["total_supply"]),
            maxSupply = positive(o["max_supply"]),
            lastUpdated = isoMillis(o["last_updated"] as? String),
        )
    }

    private fun finite(v: Any?): Double? = (v as? Number)?.toDouble()?.takeIf { it.isFinite() }

    private fun positive(v: Any?): Double? = finite(v)?.takeIf { it > 0 }

    /** «2026-10-11T12:34:56.789Z» → Epoch-ms; null, wenn ungültig. */
    fun isoMillis(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        return try {
            Instant.parse(text.trim()).toEpochMilli().takeIf { it > 0 }
        } catch (e: DateTimeParseException) {
            null
        }
    }

    /** Eintrag zum Kürzel: bei mehreren der mit der grössten Marktkapitalisierung (sonst der erste). */
    fun pick(entries: List<SpotData>, symbol: String): SpotData? {
        var best: SpotData? = null
        for (e in entries) {
            if (e.symbol != symbol) continue
            if (best == null || (e.marketCap ?: -1.0) > (best.marketCap ?: -1.0)) best = e
        }
        return best
    }

    /** Volumen im Verhältnis zur Marktkapitalisierung in Prozent; null ohne beide Werte. */
    fun volumeToMarketCap(volume: Double?, marketCap: Double?): Double? {
        if (volume == null || marketCap == null || !volume.isFinite() || !marketCap.isFinite() || marketCap <= 0) return null
        if (volume < 0) return null
        return volume / marketCap * 100.0
    }

    /** Anteil Umlauf an der Höchstmenge (0…1); null ohne Höchstmenge. */
    fun supplyRatio(circulating: Double?, max: Double?): Double? {
        if (circulating == null || max == null || !circulating.isFinite() || !max.isFinite() || max <= 0) return null
        if (circulating < 0) return null
        return (circulating / max).coerceIn(0.0, 1.0)
    }

    private val UNITS = listOf(1e12 to "T", 1e9 to "B", 1e6 to "M", 1e3 to "K")

    /**
     * Grosse Zahl kurz mit zwei Nachkommastellen: 1.66T, 20.09M, 950.00 — Ziffern und Dezimalzeichen
     * der App-Sprache ([LocaleNumbers]). Rundet ein Wert auf 1000 einer Einheit, nimmt er die nächste
     * (999.999M → 1.00B). Nicht endlich → «—».
     */
    fun compact(value: Double, locale: Locale = Locale.getDefault()): String {
        if (!value.isFinite()) return "—"
        val a = abs(value)
        var index = UNITS.indexOfFirst { a >= it.first }
        // Auf 1000 gerundet: eine Einheit höher
        if (index > 0 && Math.round(a / UNITS[index].first * 100) / 100.0 >= 1000) index--
        if (index < 0 && Math.round(a * 100) / 100.0 >= 1000) index = UNITS.lastIndex
        val (div, unit) = if (index >= 0) UNITS[index] else (1.0 to "")
        val sign = if (value < 0) "−" else ""
        return sign + LocaleNumbers.decimal(a / div, maxDecimals = 2, locale = locale, grouping = true) + unit
    }

    /** Betrag in USD kurz: «$1.66T». */
    fun usd(value: Double?, locale: Locale = Locale.getDefault()): String =
        value?.takeIf { it.isFinite() }?.let { "$" + compact(it, locale) } ?: "—"

    /** Menge mit Kürzel: «20.09M BTC». */
    fun supply(value: Double?, symbol: String, locale: Locale = Locale.getDefault()): String =
        value?.takeIf { it.isFinite() }?.let { compact(it, locale) + " " + symbol } ?: "—"

    /** Prozent mit zwei Nachkommastellen ohne Vorzeichen: «2.73%». */
    fun percent(value: Double?, locale: Locale = Locale.getDefault()): String =
        value?.takeIf { it.isFinite() }?.let { LocaleNumbers.decimal(it, maxDecimals = 2, locale = locale) + "%" } ?: "—"
}
