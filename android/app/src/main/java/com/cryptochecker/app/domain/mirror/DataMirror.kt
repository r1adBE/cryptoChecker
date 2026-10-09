package com.cryptochecker.app.domain.mirror

/**
 * Spiegel öffentlicher Marktdaten, die für alle Nutzer gleich sind (CoinGecko `/global` und
 * Top-Coins, Fear & Greed, Coin Metrics). Eine GitHub Action (`.github/workflows/market-data.yml`,
 * `.github/scripts/mirror_data.py`) holt sie stündlich und legt sie als Anhänge der Release «data»
 * ab. [com.cryptochecker.app.data.remote.callMarket] fragt für diese Adressen zuerst den Spiegel
 * und nur, wenn er fehlt oder zu alt ist, den Anbieter selbst — so fragen nicht alle Handys
 * einzeln. Gleich in iOS `DataMirror.swift`, gemeinsame Testfälle `data_mirror.json`.
 *
 * Datei: erste Zeile «CCDM1 <Abrufzeit in ms>», danach die Antwort des Anbieters unverändert.
 */
object DataMirror {
    const val BASE = "https://github.com/r1adBE/cryptoChecker/releases/download/data/"
    private const val MAGIC = "CCDM1"
    private const val HOUR = 60L * 60 * 1000

    /** Zeitpunkt in der Zukunft bis zu so viel gilt noch (Uhren gehen nicht genau gleich). */
    private const val CLOCK_SKEW_MILLIS = 5L * 60 * 1000

    /** Spiegel-Datei und wie alt sie höchstens sein darf. */
    data class Entry(val file: String, val maxAgeMillis: Long) {
        val url: String get() = BASE + file
    }

    private val EXACT = mapOf(
        "https://api.coingecko.com/api/v3/global" to Entry("global.txt", 2 * HOUR),
        "https://api.alternative.me/fng/?limit=31" to Entry("fng.txt", 4 * HOUR),
        "https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&order=market_cap_desc&per_page=30&page=1"
            to Entry("coins30.txt", 12 * HOUR),
        "https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&order=market_cap_desc&per_page=40&page=1"
            to Entry("coins40.txt", 12 * HOUR),
        "https://community-api.coinmetrics.io/v4/timeseries/asset-metrics" +
            "?assets=btc&metrics=PriceUSD&frequency=1d&start_time=2016-06-01&page_size=10000"
            to Entry("btcprice.txt", 36 * HOUR),
    )

    /** On-Chain-Werte: das Startdatum der Adresse wandert täglich mit. */
    private const val ONCHAIN_PREFIX = "https://community-api.coinmetrics.io/v4/timeseries/asset-metrics" +
        "?assets=btc&metrics=CapMVRVCur,IssTotUSD,HashRate&frequency=1d&start_time="
    private val ONCHAIN = Entry("onchain.txt", 36 * HOUR)

    /** Spiegel für diese Adresse (nur GET); null = keiner, direkt beim Anbieter. */
    fun entryFor(url: String): Entry? =
        EXACT[url] ?: if (url.startsWith(ONCHAIN_PREFIX) && url.endsWith("&page_size=1000")) ONCHAIN else null

    /**
     * Altcoin-Saison, fertig berechnet (BTC und 20 Altcoins, 90 Tage — gleiche Regel wie in der App):
     * `{"outperformers":12,"total":20,"provider":"Binance"}`. Spart pro Handy gut 20 Kurs-Abrufe.
     */
    val ALT_SEASON = Entry("altseason.txt", 6 * HOUR)

    /** Gelesene Altcoin-Saison; [provider] nur, wenn ein bekannter Anbieter der Kurse. */
    data class AltSeasonValue(val outperformers: Int, val total: Int, val provider: String?)

    private val ALT_SEASON_PROVIDERS = setOf("Binance", "Binance.US", "Coinbase")

    /** Inhalt von [ALT_SEASON]; null, wenn ungültig (dann rechnet die App selbst). */
    fun parseAltSeason(body: String?): AltSeasonValue? {
        if (body.isNullOrBlank()) return null
        val o = try {
            com.cryptochecker.app.domain.macro.MiniJson.parse(body) as? Map<*, *>
        } catch (e: IllegalArgumentException) {
            null
        } ?: return null
        val out = (o["outperformers"] as? Double)?.takeIf { it == Math.floor(it) }?.toInt() ?: return null
        val total = (o["total"] as? Double)?.takeIf { it == Math.floor(it) }?.toInt() ?: return null
        if (total !in 10..50 || out !in 0..total) return null
        return AltSeasonValue(out, total, (o["provider"] as? String)?.takeIf { it in ALT_SEASON_PROVIDERS })
    }

    /** Antwort des Anbieters aus der Spiegel-Datei, wenn sie gültig und frisch genug ist; sonst null. */
    fun unwrap(text: String?, now: Long, maxAgeMillis: Long): String? {
        if (text == null) return null
        val nl = text.indexOf('\n')
        if (nl <= 0) return null
        val head = text.substring(0, nl).trim().split(' ')
        if (head.size != 2 || head[0] != MAGIC) return null
        val fetched = head[1].toLongOrNull() ?: return null
        if (fetched <= 0 || fetched > now + CLOCK_SKEW_MILLIS || now - fetched >= maxAgeMillis) return null
        val body = text.substring(nl + 1)
        return body.takeIf { it.isNotBlank() }
    }
}
