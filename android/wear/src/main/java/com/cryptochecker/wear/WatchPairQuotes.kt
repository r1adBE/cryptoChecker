package com.cryptochecker.wear

import com.cryptochecker.marketdata.config.ExchangeRetirement
import com.cryptochecker.marketdata.watch.WatchPair
import org.json.JSONArray
import org.json.JSONObject

/**
 * Selbst abgefragter Kurs eines Favoriten des Handys ([WatchPair], «Wie Handy»): zuerst bei derselben Börse und
 * Kontraktart wie auf dem Handy, andere Börsen nur, wenn diese nicht antwortet oder das Paar dort fehlt — Spot bei
 * Binance, Bybit, OKX; Perpetuals bei Binance USDⓈ-M (`fapi`), Bybit linear, OKX Swap. Die Börse im Ergebnis ist die,
 * die wirklich geliefert hat. Andere Kontrakte und Pools fragt die Uhr nicht selbst ab ([WatchPair.selfFetchable]).
 * Wie iOS `WatchPairQuotes` (Paritäts-Fixture `watch_quotes.json`, Abschnitte «pairVenues», «pairUrls», «pairFetch»).
 */
object WatchPairQuotes {

    /** Börse und Kontraktart, die die Uhr selbst abfragen kann; [label] = Name wie auf dem Handy. */
    enum class Venue(val label: String, val perp: Boolean) {
        BINANCE("Binance", false),
        BYBIT("Bybit", false),
        OKX("OKX", false),
        BINANCE_FUTURES("Binance Futures", true),
        BYBIT_FUTURES("Bybit Futures", true),
        OKX_FUTURES("OKX Futures", true),
    }

    /** Reihenfolge der Abfrage: Börse des Handys zuerst (falls die Uhr sie kennt), dann die anderen gleicher Art. */
    fun venues(pair: WatchPair, now: Long = ExchangeRetirement.clock()): List<Venue> {
        // Börse des Handys stillgelegt (ExchangeRetirement): keine Ersatzbörse, das Paar zeigt «—» mit Grund
        if (!pair.selfFetchable || retired(pair, now)) return emptyList()
        val kind = Venue.entries.filter { it.perp == pair.perp }
        val own = kind.firstOrNull { it.label.equals(pair.exchange, ignoreCase = true) }
        return listOfNotNull(own) + (kind - setOfNotNull(own))
    }

    /**
     * Fragt die Uhr die Börse des Paars selbst ab (erste in [venues])? Nein bei Börsen, die sie nicht kennt (Kraken-Spot-Paar:
     * nur Binance, Bybit, OKX) und bei Paaren, die sie gar nicht selbst lädt — dann sagt «—» nicht «Kraken nicht
     * erreichbar» (`missingReason`).
     */
    fun asksOwnExchange(pair: WatchPair, now: Long = ExchangeRetirement.clock()): Boolean =
        venues(pair, now).firstOrNull()?.label?.equals(pair.exchange, ignoreCase = true) == true

    /** Ergebnis des letzten eigenen Abrufs eines Paars — für den Grund bei «—» ([missingReason]). */
    enum class Outcome {
        /** Eine Börse lieferte einen Kurs. */
        QUOTE,

        /** Alle gefragten Börsen antworteten, keine führt das Paar. */
        NOT_LISTED,

        /** Gefragt, aber mindestens eine Börse antwortete nicht (Netz, Sperre, Frist) und keine lieferte einen Kurs. */
        UNREACHABLE,
    }

    /**
     * Hat eine Börse geantwortet? Ja bei JSON (Objekt oder Liste) — auch «Paar unbekannt», z. B. Binance HTTP 400
     * `{"code":-1121}`, Bybit `retCode` 10001, OKX `code` 51001. Nein ohne Antwort (null) oder bei anderem Inhalt (Sperr-
     * oder Anmeldeseite eines WLANs): daraus folgt nicht, dass das Paar fehlt.
     */
    fun answered(body: String?): Boolean {
        val text = body?.trim().orEmpty()
        return try {
            when {
                text.startsWith("{") -> JSONObject(text).length() >= 0
                text.startsWith("[") -> JSONArray(text).length() >= 0
                else -> false
            }
        } catch (e: org.json.JSONException) {
            false
        }
    }

    /** Ergebnis ohne Kurs aus den Antworten der gefragten Börsen ([answered] je Börse): alle → NOT_LISTED, sonst UNREACHABLE. */
    fun outcomeWithoutQuote(answers: List<Boolean>): Outcome =
        if (answers.isNotEmpty() && answers.all { it }) Outcome.NOT_LISTED else Outcome.UNREACHABLE

    /** Börse des Paars stillgelegt ([ExchangeRetirement], Uhr der Regel)? Dann weder Kurs noch Abruf. */
    fun retired(pair: WatchPair, now: Long = ExchangeRetirement.clock()): Boolean =
        ExchangeRetirement.isRetired(pair.exchange, now)

    /** Grund für «—» eines Paars ([missingReason]). */
    enum class Missing {
        /** «<Börse> liefert keine Kurse mehr» ([ExchangeRetirement]). */
        RETIRED,

        /** Pool, Laufzeit-Kontrakt, unbekannte Kontrakt-Börse: «die Uhr kann dieses Paar nicht abrufen». */
        PHONE_ONLY,

        /** «Kurse von <Börse> erhält die Uhr nur vom Handy». */
        VIA_PHONE,

        /** «<Börse> nicht erreichbar» (eigene Börse des Paars, noch kein eigenes Ergebnis). */
        OWN_UNREACHABLE,

        /** «Börsen gerade nicht erreichbar». */
        UNREACHABLE;

        /** Sichtbarer Kurzgrund in der Zeile der Uhr-App: «nur vom Handy» (true) oder «nicht erreichbar» (false). */
        val viaPhone: Boolean get() = this == PHONE_ONLY || this == VIA_PHONE
    }

    /**
     * Grund für «—»: [WatchPair.selfFetchable] nein → [Missing.PHONE_ONLY]; die Uhr hat gefragt ([outcome]) und keine
     * gefragte Börse führt das Paar → [Missing.VIA_PHONE]; gefragt, aber keine antwortete → [Missing.UNREACHABLE]; noch
     * kein eigenes Ergebnis → wie bisher: eigene Börse ([asksOwnExchange]) → [Missing.OWN_UNREACHABLE], sonst
     * [Missing.VIA_PHONE]. Wie iOS (`watch_pairs.json` › «missing»).
     */
    fun missingReason(pair: WatchPair, outcome: Outcome?, now: Long = ExchangeRetirement.clock()): Missing = when {
        retired(pair, now) -> Missing.RETIRED
        !pair.selfFetchable -> Missing.PHONE_ONLY
        outcome == Outcome.NOT_LISTED -> Missing.VIA_PHONE
        outcome == Outcome.UNREACHABLE -> Missing.UNREACHABLE
        asksOwnExchange(pair, now) -> Missing.OWN_UNREACHABLE
        else -> Missing.VIA_PHONE
    }

    /** Abfrage eines Paars bei [venue] (Kürzel nur `[A-Z0-9]`, siehe [WatchPair.of]). */
    fun url(venue: Venue, pair: WatchPair): String {
        val symbol = pair.base + pair.quote
        return when (venue) {
            Venue.BINANCE -> "https://api.binance.com/api/v3/ticker/24hr?symbol=$symbol"
            Venue.BINANCE_FUTURES -> "https://fapi.binance.com/fapi/v1/ticker/24hr?symbol=$symbol"
            Venue.BYBIT -> "https://api.bybit.com/v5/market/tickers?category=spot&symbol=$symbol"
            Venue.BYBIT_FUTURES -> "https://api.bybit.com/v5/market/tickers?category=linear&symbol=$symbol"
            Venue.OKX -> "https://www.okx.com/api/v5/market/ticker?instId=${pair.base}-${pair.quote}"
            Venue.OKX_FUTURES -> "https://www.okx.com/api/v5/market/ticker?instId=${pair.base}-${pair.quote}-SWAP"
        }
    }

    /** Antwort von [venue] für [pair]: Kurs > 0 unter dem Schlüssel des Paars, Börse = [Venue.label]; sonst null. */
    fun parse(venue: Venue, body: String, pair: WatchPair): WatchQuote? {
        val symbol = pair.base + pair.quote
        return when (venue) {
            Venue.BINANCE, Venue.BINANCE_FUTURES -> {
                // Ein Paar: Objekt; zur Sicherheit auch ein Array
                val text = body.trim()
                val rows = if (text.startsWith("[")) objects(JSONArray(text)) else listOf(JSONObject(text))
                rows.firstOrNull { it.optString("symbol") == symbol }?.let { o ->
                    quote(pair, venue, o.optString("lastPrice"), o.optString("priceChangePercent").toDoubleOrNull())
                }
            }
            Venue.BYBIT, Venue.BYBIT_FUTURES -> {
                val list = JSONObject(body).optJSONObject("result")?.optJSONArray("list") ?: return null
                objects(list).firstOrNull { it.optString("symbol") == symbol }?.let { o ->
                    quote(pair, venue, o.optString("lastPrice"), o.optString("price24hPcnt").toDoubleOrNull()?.times(100))
                }
            }
            Venue.OKX, Venue.OKX_FUTURES -> {
                val inst = "${pair.base}-${pair.quote}" + if (venue == Venue.OKX_FUTURES) "-SWAP" else ""
                val data = JSONObject(body).optJSONArray("data") ?: return null
                objects(data).firstOrNull { it.optString("instId") == inst }?.let { o ->
                    val last = o.optString("last").toDoubleOrNull()
                    val open = o.optString("open24h").toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }
                    quote(pair, venue, o.optString("last"), if (last != null && open != null) (last / open - 1) * 100 else null)
                }
            }
        }
    }

    private fun quote(pair: WatchPair, venue: Venue, price: String, change: Double?): WatchQuote? {
        val p = price.toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() } ?: return null
        return WatchQuote(pair.key, p, change?.takeIf { it.isFinite() }, venue.label)
    }

    private fun objects(a: JSONArray): List<JSONObject> = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
}
