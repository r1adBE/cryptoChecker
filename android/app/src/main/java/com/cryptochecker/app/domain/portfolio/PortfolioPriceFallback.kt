package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.domain.refresh.OutdatedRule

/** Ein gemerkter USDT-Kurs und wann er geholt wurde (Epoch-ms). */
data class StoredPrice(
    val price: Double,
    val at: Long,
    /**
     * Binance-Kurs mit unbestätigtem Token («Kurs ungeprüft»): gilt als Wert, aber nie als bestätigter
     * Bezug (Kerzen, Tagesschlüsse) — auch nach einem Neustart, darum mit dem Kurs gemerkt.
     */
    val unverified: Boolean = false,
) {
    /** Brauchbar: endlicher, positiver Kurs mit Zeitpunkt. */
    val valid: Boolean get() = price.isFinite() && price > 0.0 && at > 0L
}

/** Zustand der gezeigten Portfolio-Kurse für die Status-Pille (wie die Merkliste). */
enum class PortfolioPriceState {
    /** Nichts zu zeigen (keine offenen Coins oder noch nie ein Kurs, Gerät online). */
    NONE,

    /** Kurse jünger als die Frische-Schwelle. */
    FRESH,

    /** Online, aber die Kurse sind älter als die Schwelle (z. B. Abruf gescheitert). */
    STALE,

    /** Gerät ohne Netz: letzter Stand mit Uhrzeit. */
    OFFLINE,
}

/**
 * Letzter bekannter Stand der Portfolio-Kurse — damit das Portfolio ohne Netz (und nach einem
 * Neustart) nicht «0.00 USDT» zeigt, sondern den letzten Wert mit «Stand …». Reine Logik
 * (ohne Android), wie iOS `PortfolioPriceFallback`:
 *  - [merge]: Speicher und gemerkter Stand, je Coin gewinnt der neuere Kurs
 *  - [prune]: höchstens [MAX_AGE_MILLIS] alt und [MAX_ENTRIES] Einträge (die neuesten)
 *  - [encode]/[decode]: kleines Zeilenformat «COIN;Kurs;Zeit» (Version in der ersten Zeile)
 *  - [state]/[mayRecord]: aktuell, veraltet oder offline; ob ein Stand für Widget/Verlauf taugt
 */
object PortfolioPriceFallback {
    /** Ältere Kurse werden nicht mehr gezeigt (lieber «—» als ein Monate alter Wert). */
    const val MAX_AGE_MILLIS = 30L * 24 * 60 * 60_000

    /** Obergrenze der gemerkten Coins (auch Kurse aus dem Erfassen-Blatt landen im Speicher). */
    const val MAX_ENTRIES = 300

    /** Kopfzeile der Datei; eine andere Version gilt als «nichts gespeichert». */
    const val FORMAT_HEADER = "portfolio-prices-v1"

    /** 4. Feld einer Zeile: Kurs ungeprüft ([StoredPrice.unverified]); ältere Dateien ohne Feld = geprüft. */
    const val UNVERIFIED_MARK = "u"

    /**
     * Frische-Schwelle der Portfolio-Kurse: wie die Merkliste ohne Live-Modus ([OutdatedRule]) —
     * dreimal das Hintergrund-Intervall, mindestens 15 Minuten. Der Live-Modus zählt nicht: Das
     * Portfolio holt seine Kurse beim Öffnen und per Ziehen, nicht im Live-Takt.
     */
    fun staleAfterMillis(backgroundIntervalMinutes: Int): Long =
        OutdatedRule.afterMillis(backgroundIntervalMinutes)

    /** Je Coin der neuere gültige Kurs aus [current] und [stored]; ungültige fallen weg. */
    fun merge(current: Map<String, StoredPrice>, stored: Map<String, StoredPrice>): Map<String, StoredPrice> {
        val out = HashMap<String, StoredPrice>()
        for ((coin, entry) in stored) if (entry.valid) out[coin] = entry
        for ((coin, entry) in current) {
            if (!entry.valid) continue
            val known = out[coin]
            if (known == null || entry.at >= known.at) out[coin] = entry
        }
        return out
    }

    /**
     * Nur gültige Einträge, nicht älter als [MAX_AGE_MILLIS] (Zeit in der Zukunft — Uhr verstellt —
     * bleibt stehen), höchstens [MAX_ENTRIES], die neuesten zuerst behalten.
     */
    fun prune(entries: Map<String, StoredPrice>, now: Long): Map<String, StoredPrice> =
        entries.filter { (coin, entry) -> coin.isNotBlank() && entry.valid && now - entry.at <= MAX_AGE_MILLIS }
            .entries
            .sortedByDescending { it.value.at }
            .take(MAX_ENTRIES)
            .associate { it.key to it.value }

    /**
     * Nur bestätigte Kurse als Bezug (Kerzen-Prüfung): ohne «Kurs ungeprüft» ([StoredPrice.unverified]) — sonst
     * bestätigte ein gemerkter Binance-Kurs eines womöglich fremden Tokens nach einem Ausfall dessen Kerzenkurs.
     */
    fun confirmed(entries: Map<String, StoredPrice>): Map<String, StoredPrice> = entries.filterValues { !it.unverified }

    /**
     * Coins aus [coins] mit «Kurs ungeprüft» laut [entries]; ein gekapselter Coin ohne eigenen Kurs, bewertet über seine
     * Basis ([via], [ChainAssets.priceViaBase]), erbt den Stand der Basis.
     */
    fun unverified(coins: Collection<String>, entries: Map<String, StoredPrice>, via: Map<String, String>): Set<String> =
        coins.filterTo(LinkedHashSet()) { coin -> (entries[coin] ?: via[coin]?.let { entries[it] })?.unverified == true }

    /** Zeilenformat: Kopfzeile, dann je Coin «COIN;Kurs;Zeit», bei «Kurs ungeprüft» «COIN;Kurs;Zeit;u». */
    fun encode(entries: Map<String, StoredPrice>): String = buildString {
        append(FORMAT_HEADER)
        for ((coin, entry) in entries.entries.sortedBy { it.key }) {
            if (!entry.valid || coin.isBlank() || coin.contains(';') || coin.contains('\n')) continue
            append('\n').append(coin).append(';').append(entry.price.toString()).append(';').append(entry.at)
            if (entry.unverified) append(';').append(UNVERIFIED_MARK)
        }
    }

    /** Liest [encode]; fremde Version oder kaputte Zeilen ergeben nichts bzw. fallen weg (ohne 4. Feld: geprüft). */
    fun decode(text: String?): Map<String, StoredPrice> {
        if (text.isNullOrEmpty()) return emptyMap()
        val lines = text.split('\n')
        if (lines.first().trim() != FORMAT_HEADER) return emptyMap()
        val out = HashMap<String, StoredPrice>()
        for (line in lines.drop(1)) {
            val parts = line.trim().split(';')
            if (parts.size != 3 && !(parts.size == 4 && parts[3].trim() == UNVERIFIED_MARK)) continue
            val coin = parts[0].trim()
            val price = parts[1].toDoubleOrNull() ?: continue
            val at = parts[2].toLongOrNull() ?: continue
            val entry = StoredPrice(price, at, unverified = parts.size == 4)
            if (coin.isNotEmpty() && entry.valid) out[coin] = entry
        }
        return out
    }

    /** Älter als [staleAfterMillis]? Ohne Zeitpunkt (≤ 0) nie; Zeit in der Zukunft gilt als frisch. */
    fun isStale(updatedAt: Long, now: Long, staleAfterMillis: Long): Boolean =
        updatedAt > 0L && now - updatedAt > staleAfterMillis

    /**
     * Zustand für die Status-Pille: ohne offene Coins nichts; ohne Netz «Offline» (mit Stand, falls
     * es einen gibt); sonst ohne Kurs nichts, mit altem Kurs «veraltet», sonst «aktuell».
     */
    fun state(updatedAt: Long, now: Long, staleAfterMillis: Long, online: Boolean, hasOpenCoins: Boolean): PortfolioPriceState =
        when {
            !hasOpenCoins -> PortfolioPriceState.NONE
            !online -> PortfolioPriceState.OFFLINE
            updatedAt <= 0L -> PortfolioPriceState.NONE
            isStale(updatedAt, now, staleAfterMillis) -> PortfolioPriceState.STALE
            else -> PortfolioPriceState.FRESH
        }

    /**
     * Darf mit diesen Kursen eine neue Momentaufnahme (Widget, «heute», Wertverlauf, Alarme)
     * entstehen? Nur mit frischen Kursen — ein gemerkter alter Stand würde sonst als «jetzt»
     * aufgenommen und Verlauf wie Vergleichsbasis verfälschen.
     */
    fun mayRecord(updatedAt: Long, now: Long, staleAfterMillis: Long): Boolean =
        updatedAt > 0L && !isStale(updatedAt, now, staleAfterMillis)

    /** Mindestanteil des Werts mit frischem Kurs für eine neue Aufnahme ([mayRecordShare]). */
    const val MIN_FRESH_SHARE = 0.8

    /**
     * Anteil des aktuellen Werts, dessen Kurs frisch ist (je Coin geprüft, nicht der älteste für alle):
     * [values] = Wert je Coin in USDT, [times] = Abfragezeit je Coin. USDT (Bewertungsbasis) zählt immer
     * als frisch. null = kein Wert.
     */
    fun freshShare(values: Map<String, Double>, times: Map<String, Long>, now: Long, staleAfterMillis: Long): Double? {
        var total = 0.0
        var fresh = 0.0
        for ((coin, value) in values) {
            if (!(value > 0.0) || !value.isFinite()) continue
            total += value
            val at = times[coin] ?: 0L
            if (!PortfolioStables.needsQuote(coin) || (at > 0L && !isStale(at, now, staleAfterMillis))) fresh += value
        }
        return if (total > 0.0) fresh / total else null
    }

    /**
     * Darf eine neue Momentaufnahme (Widget, «heute», Wertverlauf) entstehen? Ja, wenn mindestens
     * [MIN_FRESH_SHARE] des Werts einen frischen Kurs hat — ein einzelner Coin ohne frischen Kurs hält die
     * übrigen nicht mehr an; er zählt mit seinem letzten Kurs, die Aufnahme trägt dann [staleCoins] als
     * «teilweise alter Kurs» ([PortfolioSnapshot.stalePrices]). Portfolio-Alarme prüfen nur ohne wesentliche alte Kurse
     * ([com.cryptochecker.app.domain.alarm.PortfolioAlarmLogic.staleMaterial]).
     * Ohne offene Coins mit Wert: nur mit irgendeinem Kurs ([anyPrice]).
     */
    fun mayRecordShare(values: Map<String, Double>, times: Map<String, Long>, now: Long, staleAfterMillis: Long, anyPrice: Boolean): Boolean {
        val share = freshShare(values, times, now, staleAfterMillis) ?: return anyPrice
        return share >= MIN_FRESH_SHARE - 1e-9
    }

    /**
     * Coins mit Wert (Schlüssel wie [values], sortiert), deren Kurs NICHT frisch ist — USDT zählt immer als
     * frisch. Leer = alle bewerteten Bestände frisch; sonst prüfen Portfolio-Alarme nur, wenn ihr Wert unter 1 % liegt
     * (weder melden noch wieder scharf stellen); Widget und Verlauf zeigen den Wert, markiert als «teilweise alter Kurs».
     */
    fun staleCoins(values: Map<String, Double>, times: Map<String, Long>, now: Long, staleAfterMillis: Long): List<String> =
        values.filter { (coin, value) ->
            val at = times[coin] ?: 0L
            value > 0.0 && value.isFinite() && PortfolioStables.needsQuote(coin) &&
                !(at > 0L && !isStale(at, now, staleAfterMillis))
        }.keys.sorted()
}
