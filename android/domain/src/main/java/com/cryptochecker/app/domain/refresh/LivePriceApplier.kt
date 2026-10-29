package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.data.PairIdentity
import com.cryptochecker.app.data.pairIdentity
import com.cryptochecker.app.domain.live.LiveExchange
import com.cryptochecker.app.domain.live.LiveQuote
import com.cryptochecker.app.domain.live.LiveRules
import com.cryptochecker.app.domain.watch.ChangeBasisMath
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.marketdata.model.SimpleTicker
import com.cryptochecker.app.util.appScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Live-Kurse aus dem WebSocket-Strom ([com.cryptochecker.app.data.live.LivePriceStream]) speichern und wie eine
 * Aktualisierung auswerten — unter derselben Refresh-Sperre ([RefreshLock]) und über denselben letzten Schritt
 * ([RefreshResultProcessor]) wie [PriceRefresher]. Charts mit fehlenden Kerzen lädt danach [ChartRefresher]
 * ausserhalb der Sperre.
 */
class LivePriceApplier constructor(
    private val refreshLock: RefreshLock,
    private val watchRepository: RefreshStore,
    private val settingsRepository: RefreshSettings,
    private val refreshStats: RefreshStatsPort,
    /** Live-Takte zeichnen die Widgets nur über dieses Tor (Bildschirm aus: aufschieben). */
    private val liveWidgetGate: RefreshWidgets,
    private val chartRefresher: RefreshCharts,
    private val resultProcessor: RefreshResultProcessor,
    /** Kurs-Meldungen nach dem Speichern: Nacharbeit unter der weitergereichten Sperre ([RefreshLock.withLockThen]). */
    private val tailScope: CoroutineScope = appScope(Dispatchers.IO, "LivePriceApplier"),
) {
    /**
     * Live-Kurse (gesammelt, höchstens alle 10 s) speichern und wie eine Aktualisierung auswerten: derselbe Weg
     * für Alarme (Kreuzung zum zuletzt gespeicherten Kurs, Abklingzeit, «erst wieder scharf») und Kurs-Meldungen —
     * also keine doppelten Meldungen. Keine Kursansagen (sonst alle 10 s) und keine Volumen-Alarme (hängen an
     * Stundenkerzen, nicht am Kurs; die REST-Abfrage prüft sie). 24-h-Veränderung nach [LiveRules.chooseChange].
     *
     * Läuft gerade eine Aktualisierung, wird nichts gespeichert: Rückgabe false, der Aufrufer
     * versucht es beim nächsten Mal wieder. [redrawWidgets]: danach die Widgets neu zeichnen
     * (Bildschirm aus: nur vormerken, [LiveWidgetGate] zeichnet beim Einschalten einmal).
     *
     * [pairs]: für welches Paar jeder Kurs abonniert war. Ein Kurs, dessen Eintrag inzwischen ein
     * anderes Paar ist («Paar bearbeiten», Sicherung), wird verworfen — auch beim Speichern
     * ([com.cryptochecker.app.data.local.dao.WatchDao.updatePriceForPair]). null = ohne diese Prüfung (nur Tests).
     */
    suspend fun applyLive(
        quotes: Map<Long, LiveQuote>,
        redrawWidgets: Boolean,
        pairs: Map<Long, PairIdentity>? = null,
    ): Boolean {
        if (quotes.isEmpty()) return true
        refreshLock.tryWithLockThen(tailScope) { lock -> applyLocked(lock, quotes, redrawWidgets, pairs) }
            ?: return false
        afterUnlock()
        return true
    }

    /**
     * Letztes Speichern beim Beenden des Stroms ([com.cryptochecker.app.data.live.LivePriceStream.stopAll]):
     * wie [applyLive], wartet aber höchstens [timeoutMillis] auf die Sperre statt sofort aufzugeben —
     * sonst gingen die letzten Live-Kurse während einer laufenden Aktualisierung verloren.
     *
     * Auf die Kurs-Meldungen (Nacharbeit) wartet es nicht: Sie laufen im App-weiten Bereich weiter und halten die
     * Sperre bis zu ihrem Ende, kein anderer Schreiber kommt vorher dran. Warten verlängerte nur das Beenden des
     * Stroms (läuft ohne Abbruch, [kotlinx.coroutines.NonCancellable]) um die Drosselung von einigen Sekunden — ohne
     * dass die Meldungen dadurch sicherer kämen (gleiche Prozess-Lebensdauer).
     * @return false, wenn die Sperre nicht rechtzeitig frei wurde (nichts gespeichert)
     */
    suspend fun applyLiveFinal(
        quotes: Map<Long, LiveQuote>,
        redrawWidgets: Boolean,
        pairs: Map<Long, PairIdentity>? = null,
        timeoutMillis: Long = FINAL_SAVE_WAIT_MILLIS,
    ): Boolean {
        if (quotes.isEmpty()) return true
        refreshLock.withLockWithinThen(timeoutMillis, tailScope) { lock ->
            applyLocked(lock, quotes, redrawWidgets, pairs)
        } ?: return false
        afterUnlock()
        return true
    }

    /** Charts mit fehlenden Kerzen (auch Vorgemerktes eines früheren Laufs) ausserhalb der Sperre nachladen. */
    private fun afterUnlock() = chartRefresher.launchIfPending()

    /**
     * Arbeit unter der Sperre ([lock] als Beweis): Kurse übernehmen, Widgets ohne Kerzen-Abruf zeichnen. Rückgabe: die
     * Kurs-Meldungen als Nacharbeit nach den Widgets (die Sperre bleibt bis zu ihrem Ende gehalten); null = keine.
     */
    private suspend fun applyLocked(
        lock: RefreshLockScope,
        quotes: Map<Long, LiveQuote>,
        redrawWidgets: Boolean,
        pairs: Map<Long, PairIdentity>?,
    ): Locked<Unit> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val settings = settingsRepository.current()
            val rollingBasis = !settings.changeBasis.isDay
            val stampCurrent = refreshStats.changeStamp.value == ChangeBasisMath.stamp(settings.changeBasis, now)
            // Nicht mehr gehandelte und deaktivierte Paare bleiben, wie sie sind (abonniert werden
            // sie ohnehin nicht; ein später Takt nach dem Deaktivieren wird verworfen)
            val watches = watchRepository.getWatches().filter {
                it.id in quotes && !it.isNotTraded && !it.disabled &&
                    // Abo für ein anderes Paar (Eintrag inzwischen bearbeitet): alter Kurs, verwerfen
                    (pairs == null || LiveRules.samePair(pairs[it.id], it.pairIdentity))
            }
            val fetched = HashMap<Long, Fetched>()
            for (watch in watches) {
                val quote = quotes.getValue(watch.id)
                val ticker = SimpleTicker().apply {
                    last = quote.price
                    timestamp = quote.time
                    exchangeTime = LiveRules.exchangeTime(quote, watch.lastPrice, watch.exchangeTime)
                    change24hPercent = LiveRules.chooseChange(
                        rollingBasis, stampCurrent,
                        LiveExchange.fromExchangeKey(watch.exchangeKey)?.rollingChange == true,
                        quote.change24h, watch.change24h,
                    )
                }
                fetched[watch.id] = Fetched(ticker, null)
            }
            if (watches.isEmpty()) return@withContext Unit to null
            val processed = resultProcessor.process(
                lock, watches, fetched, settings, dayStart = null, live = true, deferPrices = true,
            )
            // Kurs-Meldungen gleich vormerken: Sie laufen nach den Widgets (die Sperre bleibt bis zu ihrem Ende
            // gehalten) — auch wenn das Zeichnen scheitert, denn die Kurse sind gespeichert
            val prices = resultProcessor.pricesTail(processed)
            refreshLock.stageTail(lock, prices)
            if (redrawWidgets) {
                // Zeit zuerst (Widget-Kopfzeile), die Dauer des letzten Durchlaufs bleibt
                refreshStats.setLastRefresh(refreshStats.lastDurationMillis.value)
                // Bildschirm aus: nur merken, beim Einschalten einmal nachzeichnen.
                // Unter der Sperre ohne Kerzen-Abruf, Charts danach ausserhalb
                if (liveWidgetGate.drawAll(deferWhenOff = true, fetch = false)) chartRefresher.markIfStale()
            }
            // Kurs-Meldungen (gedrosselt bis einige Sekunden) erst nach den Widgets
            Unit to prices
        }

    private companion object {
        /** Letztes Live-Speichern ([applyLiveFinal]): höchstens so lange auf die Sperre warten. */
        const val FINAL_SAVE_WAIT_MILLIS = 3_000L
    }
}
