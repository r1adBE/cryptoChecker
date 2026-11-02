package com.cryptochecker.app.domain.spot

import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.macro.MiniJson
import com.cryptochecker.app.domain.portfolio.PortfolioPriceIdentity
import com.cryptochecker.app.domain.watch.TokenIdentity
import com.cryptochecker.app.util.LocaleNumbers
import com.cryptochecker.app.util.PercentText
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
 * - Welcher Treffer zum Paar gehört, entscheidet der Name ([pick], [Identity]) — wie beim Portfolio-Kurs
 *   ([PortfolioPriceIdentity]): gemessen am Namen des Paars (sonst Binances Namen); ohne passenden Namen
 *   lieber keine Spot-Daten als die eines anderen Tokens. Die grösste Marktkapitalisierung entscheidet nur
 *   noch unter Treffern mit gleichem Namen.
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
    fun eligible(exchangeKey: String?, tradFi: Boolean): Boolean = !tradFi && CoinLogos.allowedFor(exchangeKey)

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

    /**
     * Wen meint das Paar? [ownName]: Name aus der Paarliste der eigenen Börse; [binanceName]: `fullName` von Binance;
     * [otherNames]: Namen des Kürzels in den übrigen Paarlisten (je Börse einer). Alles optional.
     */
    data class Identity(
        val ownName: String? = null,
        val binanceName: String? = null,
        val otherNames: List<String?> = emptyList(),
    )

    /**
     * Name, an dem die CoinGecko-Treffer gemessen werden — Regel des Portfolio-Kurses
     * ([PortfolioPriceIdentity.binanceCheck]): der eigene Name, sonst Binances Name, ausser das Kürzel ist ein bekanntes
     * Doppel-Kürzel ([PortfolioPriceIdentity.knownCollision]) oder die Paarlisten führen darunter klar verschiedene
     * Token ([PortfolioPriceIdentity.ambiguous]). null = kein brauchbarer Name.
     */
    fun referenceName(symbol: String, identity: Identity): String? {
        identity.ownName?.takeIf { TokenIdentity.normalizeName(it, symbol) != null }?.let { return it }
        val binance = identity.binanceName?.takeIf { TokenIdentity.normalizeName(it, symbol) != null } ?: return null
        if (PortfolioPriceIdentity.knownCollision(symbol)) return null
        if (PortfolioPriceIdentity.ambiguous(symbol, binance, identity.otherNames)) return null
        return binance
    }

    /**
     * Eintrag zum Kürzel nach Namen ([TokenIdentity.matchNames] gegen [referenceName]), nicht nach Grösse:
     *  - gleicher Name ([TokenIdentity.Match.MEDIUM]): dieser; mehrere mit gleichem Namen → grösste Marktkapitalisierung;
     *  - nur ähnlicher Name ([TokenIdentity.Match.WEAK]): nur, wenn er der einzige Treffer ist;
     *  - klar anderer Name: nie.
     * Ohne brauchbaren Namen nur ein einziger Treffer, und nicht bei bekanntem Doppel-Kürzel oder widersprüchlichen
     * Paarlisten. Sonst null — lieber keine Spot-Daten als die eines anderen Tokens.
     */
    fun pick(entries: List<SpotData>, symbol: String, identity: Identity = Identity()): SpotData? {
        val hits = entries.filter { it.symbol == symbol }
        if (hits.isEmpty()) return null
        val reference = referenceName(symbol, identity)
        if (reference == null) {
            if (hits.size != 1 || PortfolioPriceIdentity.knownCollision(symbol)) return null
            if (PortfolioPriceIdentity.ambiguous(symbol, identity.binanceName, identity.otherNames)) return null
            return hits[0]
        }
        val matches = hits.map { it to TokenIdentity.matchNames(symbol, reference, it.name) }
        var best: SpotData? = null
        for ((e, match) in matches) {
            if (match != TokenIdentity.Match.MEDIUM && match != TokenIdentity.Match.STRONG) continue
            if (best == null || (e.marketCap ?: -1.0) > (best.marketCap ?: -1.0)) best = e
        }
        if (best != null) return best
        return matches.singleOrNull()?.takeIf { it.second == TokenIdentity.Match.WEAK }?.first
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

    /** Prozent mit zwei Nachkommastellen ohne Vorzeichen: «2.73%», «2,73 %» (Schreibweise je Sprache, [PercentText]). */
    fun percent(value: Double?, locale: Locale = Locale.getDefault()): String =
        value?.takeIf { it.isFinite() }?.let { PercentText.unsigned(it, 2, locale) } ?: "—"
}
