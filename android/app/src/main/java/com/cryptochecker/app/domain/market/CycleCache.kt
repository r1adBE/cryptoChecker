package com.cryptochecker.app.domain.market

/**
 * Bereiche des Markt-Tabs mit eigenem Zwischenspeicher auf dem Gerät.
 * [ttlMillis] = so lange gilt ein gespeicherter Wert als frisch (danach wird im
 * Hintergrund neu geladen, der alte Wert bleibt bis dahin sichtbar);
 * [timeoutMillis] = harte Obergrenze für einen Abruf dieses Bereichs.
 */
enum class CycleSource(val key: String, val ttlMillis: Long, val timeoutMillis: Long) {
    PULSE("pulse", 5 * MINUTE, 12_000L),

    /** «Heute auffällig»: Binance-24-h-Ticker und Funding aller Perpetuals (je ein Abruf; einmal am Tag dazu die Coin-Liste). */
    UNUSUAL("unusual", 10 * MINUTE, 20_000L),

    /** Marktphase (Scores aus Kursen und On-Chain-Daten); mehrere Abrufe mit Ausweich-Kette. */
    MARKET("market", HOUR, 20_000L),

    /** On-Chain-Werte von Coin Metrics (MVRV, Puell, Hashrate) — Teil von [MARKET]. */
    ON_CHAIN("onchain", 12 * HOUR, 20_000L),
    FEAR_GREED("fear_greed", 30 * MINUTE, 12_000L),

    /** CoinGecko /global: Marktkapitalisierung, Volumen, Dominanz. */
    GLOBAL("global", 15 * MINUTE, 12_000L),

    /** Altcoin-Saison: rund 20 Verläufe, fünf gleichzeitig. */
    ALT_SEASON("alt_season", HOUR, 20_000L),

    /** Zyklus-Vergleich seit 2016; im Notfall mehrere Abrufe nacheinander. */
    HISTORY("history", 12 * HOUR, 20_000L),

    /** Coin-Karte, je Coin ein eigener Eintrag ([CycleCachePolicy.coinName]). */
    COIN("coin", 15 * MINUTE, 12_000L),
    GAS("gas", MINUTE, 12_000L),
}

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE

/**
 * Entscheidungen rund um den Zwischenspeicher des Markt-Tabs (reines Kotlin, testbar):
 * Was ist noch frisch, was muss neu geladen werden, welcher «Stand» wird gezeigt.
 */
object CycleCachePolicy {

    /**
     * Version des Speicherformats. Steht im Dateinamen und in der Datei; ein anderes
     * (älteres) Format wird nie gelesen, sondern wie «kein Zwischenspeicher» behandelt.
     */
    const val FORMAT_VERSION = 1

    /**
     * Frisch = gespeichert vor weniger als [ttlMillis]. Ein Zeitpunkt in der
     * Zukunft (Uhr verstellt) oder ≤ 0 gilt nie als frisch.
     */
    fun isFresh(savedAt: Long?, now: Long, ttlMillis: Long): Boolean =
        savedAt != null && savedAt in 1..now && now - savedAt < ttlMillis

    /** Neu laden? Immer bei [force] (nach unten ziehen, «Erneut»), sonst wenn nicht frisch. */
    fun needsRefresh(savedAt: Long?, now: Long, ttlMillis: Long, force: Boolean): Boolean =
        force || !isFresh(savedAt, now, ttlMillis)

    /** Gespeichertes Format lesbar? Nur genau die aktuelle Version. */
    fun isCurrentFormat(version: Int?): Boolean = version == FORMAT_VERSION

    /** Dateiname eines Eintrags, z. B. «coin_ETH_v1.json»; nur Buchstaben, Ziffern und «_». */
    fun fileName(name: String): String {
        val clean = name.filter { it.isLetterOrDigit() || it == '_' }.ifEmpty { "entry" }
        return "${clean}_v$FORMAT_VERSION.json"
    }

    /** Eintrag der Coin-Karte für [symbol], z. B. «coin_ETH». */
    fun coinName(symbol: String): String =
        CycleSource.COIN.key + "_" + symbol.uppercase().filter { it.isLetterOrDigit() }

    /**
     * Zeile «Stand … · wird aktualisiert …»: der älteste angezeigte Zeitpunkt der
     * Bereiche, die gerade neu laden. null = keine Zeile (nichts läuft, oder die
     * laufenden Bereiche zeigen noch gar nichts an — dort steht ein Ladezustand).
     */
    fun dataAsOf(shownAt: Map<CycleSource, Long>, running: Set<CycleSource>): Long? =
        running.mapNotNull { shownAt[it] }.filter { it > 0 }.minOrNull()
}

/** On-Chain-Werte (Coin Metrics) für das Zyklus-Modell; einzeln zwischengespeichert. */
data class OnChainValues(
    val mvrv: Double?,
    val puell: Double?,
    val hash30d: Double?,
    val hash60d: Double?,
) {
    /** Mindestens ein Wert vorhanden (sonst lohnt sich das Speichern nicht). */
    val hasAny: Boolean get() = mvrv != null || puell != null || hash30d != null || hash60d != null
}
