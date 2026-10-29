package com.cryptochecker.app.data.remote

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/** Antwort einer Testroute: Text und HTTP-Code. */
internal data class Reply(val body: String, val code: Int = 200)

/**
 * OkHttp ohne Netz für die Datenquellen-Tests: [route] beantwortet jede Anfrage nach ihrer vollen Adresse
 * (null = offline, [IOException] wie ohne Verbindung). [requested] hält alle Adressen in Reihenfolge — auch die
 * Spiegel-Anfragen an GitHub, die hier immer offline sind (dann fragt die App direkt beim Anbieter).
 */
internal class RouteHttp(private val route: (String) -> Reply?) {
    val requested: MutableList<String> = CopyOnWriteArrayList()

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val request = chain.request()
        val url = request.url.toString()
        requested += url
        val reply = route(url) ?: throw IOException("offline: $url")
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(reply.code)
            .message(if (reply.code == 200) "OK" else "Error")
            .body(reply.body.toResponseBody("application/json".toMediaType())).build()
    }).build()

    /** Anzahl Anfragen, deren Adresse [part] enthält. */
    fun count(part: String): Int = requested.count { part in it }

    companion object {
        /**
         * Hosts der Kerzen-Kette ([CandleDataSource]); prozessweit gesperrt/pausiert (andere Testklassen im selben
         * Prozess) → vor dem Test freigeben, damit er immer läuft (nie still übersprungen).
         */
        val CHAIN_HOSTS = listOf(
            "data-api.binance.vision", "api.binance.com", "fapi.binance.com", "api.binance.us", "api.exchange.coinbase.com",
        )

        fun releaseChain() {
            for (host in CHAIN_HOSTS) BlockedSources.clearForTest(host)
        }
    }
}

/**
 * Kerzen-Antworten der Ausweich-Kette, zeitlich passend zu «jetzt» (sonst gälte die Reihe als beendet,
 * [com.cryptochecker.app.domain.activity.CandleSeries.isLive]).
 */
internal object Klines {
    const val HOUR = 3_600_000L
    const val DAY = 24 * HOUR
    const val WEEK = 7 * DAY

    /** Beginn des laufenden UTC-Tags. */
    fun today(now: Long = System.currentTimeMillis()): Long = now - now % DAY

    /** Beginn der Kerze [i] von [count], die letzte beginnt bei [last]. */
    fun openTime(i: Int, count: Int, step: Long, last: Long): Long = last - (count - 1 - i) * step

    /**
     * Binance-Kline-Liste (älteste zuerst): Hoch = Schluss × 1.01, Tief = Schluss × 0.99, Volumen 10,
     * Quote-Umsatz = Schluss × 10.
     */
    fun binance(count: Int, step: Long = DAY, last: Long = today(), close: (Int) -> Double): String =
        (0 until count).joinToString(",", "[", "]") { i ->
            val t = openTime(i, count, step, last)
            val c = close(i)
            """[$t,"$c","${c * 1.01}","${c * 0.99}","$c","10.0",${t + step - 1},"${c * 10}"]"""
        }

    /** Coinbase-Kerzen [Zeit (s), Tief, Hoch, Eröffnung, Schluss, Volumen], NEUESTE zuerst. */
    fun coinbase(count: Int, step: Long, last: Long, close: (Int) -> Double): String =
        (count - 1 downTo 0).joinToString(",", "[", "]") { i ->
            val t = openTime(i, count, step, last) / 1000
            val c = close(i)
            "[$t,${c * 0.99},${c * 1.01},$c,$c,2.0]"
        }

    /** Bybit-Antwort `result.list`, NEUESTE zuerst: [Zeit (ms, Text), Eröffnung, Hoch, Tief, Schluss, …]. */
    fun bybit(count: Int, step: Long = DAY, last: Long = today(), close: (Int) -> Double): String =
        (count - 1 downTo 0).joinToString(",", """{"retCode":0,"result":{"list":[""", "]}}") { i ->
            val t = openTime(i, count, step, last)
            val c = close(i)
            """["$t","$c","${c * 1.01}","${c * 0.99}","$c","1","1"]"""
        }
}
