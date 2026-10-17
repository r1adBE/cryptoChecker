package com.cryptochecker.app.domain.logos

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Erster Schritt jedes Logo-Abgleichs (Build 46): Binance-Symbolliste und Alpha-Liste **zuerst und
 * gleichzeitig** holen — unabhängig von CoinGecko und von den Bildern. Namen und Alpha-Kennzeichen
 * stehen so wenige Sekunden nach dem Start bereit; vorher kamen sie erst nach allen Seiten der
 * CoinGecko-Rangliste (Pausen, «429»-Wartezeiten) bzw. auf iOS sogar erst nach den Paarlisten.
 *
 * Jede Quelle ist für sich gekapselt: Scheitert eine, bleibt die andere. Fehlt eine fällige Liste
 * direkt, kommt sie aus der Ersatzdatei `coin_meta.json` ([CoinMeta.URL], nur Binance-Daten), falls
 * diese neuer ist als der letzte direkte Stand. Reines Kotlin, gleich in iOS `CoinLists.swift`.
 */
object CoinLists {

    /** Woher eine Liste in diesem Abgleich kam. */
    enum class Source { NONE, DIRECT, MIRROR }

    /**
     * Was zu holen ist: [binanceDue] / [alphaDue] (fällig, z. B. [CoinLogos.alphaListDue]), letzter
     * direkter Erfolg je Liste (ms, 0 = nie), [mirrorAllowed] = Ersatzdatei darf gefragt werden.
     */
    data class Request(
        val binanceDue: Boolean,
        val alphaDue: Boolean,
        val lastBinanceAt: Long,
        val lastAlphaAt: Long,
        val now: Long,
        val mirrorAllowed: Boolean = true,
    )

    /**
     * Ergebnis: neue Listen (null = nicht neu, bisherige bleiben) mit Herkunft; [networkError] = eine
     * fällige Quelle scheiterte am Netz; [mirrorFailed] = Ersatzdatei gefragt, aber unbrauchbar.
     */
    data class Result(
        val binance: List<CoinLogos.BinanceEntry>?,
        val binanceSource: Source,
        val alpha: List<CoinLogos.AlphaEntry>?,
        val alphaSource: Source,
        val networkError: Boolean,
        val mirrorFailed: Boolean,
    ) {
        /** Eine fällige Liste fehlt auch nach der Ersatzdatei. */
        fun missing(request: Request): Boolean =
            (request.binanceDue && binance == null) || (request.alphaDue && alpha == null)
    }

    private class Attempt<T>(val value: T?, val networkError: Boolean)

    private suspend fun <T> attempt(isNetworkError: (Exception) -> Boolean, block: suspend () -> T?): Attempt<T> =
        try {
            Attempt(block(), false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Attempt(null, isNetworkError(e))
        }

    /**
     * Beide Listen gleichzeitig holen, dann bei Bedarf die Ersatzdatei. [fetcher]: Text der Antwort,
     * wirft bei Fehlern (Netz, HTTP-Status); [isNetworkError]: Fehler am Netz (keine Verbindung,
     * Zeitüberschreitung) statt am Server.
     */
    suspend fun fetch(
        fetcher: suspend (String) -> String,
        request: Request,
        isNetworkError: (Exception) -> Boolean,
    ): Result {
        val (b, a) = coroutineScope {
            val binance = async {
                if (!request.binanceDue) Attempt<List<CoinLogos.BinanceEntry>>(null, false)
                else attempt(isNetworkError) { CoinMeta.parseBinanceList(fetcher(CoinLogos.BINANCE_LIST_URL))?.takeIf { it.isNotEmpty() } }
            }
            val alpha = async {
                if (!request.alphaDue) Attempt<List<CoinLogos.AlphaEntry>>(null, false)
                else attempt(isNetworkError) { CoinLogos.parseAlpha(fetcher(CoinLogos.ALPHA_LIST_URL)) }
            }
            binance.await() to alpha.await()
        }
        var binance = b.value
        var alpha = a.value
        var binanceSource = if (binance != null) Source.DIRECT else Source.NONE
        var alphaSource = if (alpha != null) Source.DIRECT else Source.NONE
        var network = b.networkError || a.networkError
        // Ersatzdatei nur für fällige Listen, die direkt fehlten (ein direkter Erfolg gilt ab jetzt)
        val wantBinance = request.binanceDue &&
            CoinMeta.needed(binance != null, if (binance != null) request.now else request.lastBinanceAt, request.now)
        val wantAlpha = request.alphaDue &&
            CoinMeta.needed(alpha != null, if (alpha != null) request.now else request.lastAlphaAt, request.now)
        var mirrorFailed = false
        if (request.mirrorAllowed && (wantBinance || wantAlpha)) {
            val m = attempt(isNetworkError) { CoinMeta.parse(fetcher(CoinMeta.URL)) }
            val meta = m.value?.takeIf { CoinMeta.isUsable(it, request.now) }
            if (meta == null) {
                mirrorFailed = true
                network = network || m.networkError
            } else {
                // Nur, wenn die Ersatzdatei neuer ist als der letzte direkte Stand
                if (wantBinance && meta.binance.isNotEmpty() && meta.generatedAt > request.lastBinanceAt) {
                    binance = meta.binance
                    binanceSource = Source.MIRROR
                }
                if (wantAlpha && meta.alpha.isNotEmpty() && meta.generatedAt > request.lastAlphaAt) {
                    alpha = meta.alpha
                    alphaSource = Source.MIRROR
                }
            }
        }
        return Result(binance, binanceSource, alpha, alphaSource, network, mirrorFailed)
    }

    /**
     * Namen nach den Binance-Listen: [known] (gespeichert, mit CoinGecko-Namen aus dem letzten ganzen
     * Abgleich) behält Vorrang, die Listen füllen Lücken. Stammt [known] nur aus dem mitgelieferten
     * Auszug ([knownIsSnapshot]), haben die frischen Listen Vorrang.
     */
    fun publishNames(known: Map<String, String>, knownIsSnapshot: Boolean, listNames: Map<String, String>): Map<String, String> =
        if (knownIsSnapshot || known.isEmpty()) CoinLogos.withKnown(listNames, known)
        else CoinLogos.withKnown(known, listNames)
}
