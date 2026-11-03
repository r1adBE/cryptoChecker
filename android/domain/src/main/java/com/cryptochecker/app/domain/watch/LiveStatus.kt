package com.cryptochecker.app.domain.watch

import com.cryptochecker.app.domain.live.LiveRules
import com.cryptochecker.app.domain.refresh.OutdatedRule

/**
 * «Live» muss stimmen: Frische-Schwelle je Paar und das Wort «Live» in der Status-Pille.
 *
 *  - Paar am Live-Strom (seine Börse liefert gerade per WebSocket Kurse, nur bei «Live, solange
 *    offen»): Frische am letzten Takt des Stroms gemessen ([freshnessTime]), nicht am letzten
 *    Kurswechsel — ein ruhiges Paar an einem gesunden Strom ist nicht veraltet. Bleiben die Takte
 *    aus, fällt das Paar aus dem Strom ([LiveRules.FRESH_MILLIS]) und es gilt die Abfrage-Schwelle;
 *    [LIVE_STALE_MILLIS] (2 Min.) bleibt die Schwelle am Strom.
 *  - sonst die Abfrage-Schwelle: dreimal das Hintergrund-Intervall, mindestens 15 Min.
 *    ([OutdatedRule]). Das gilt in beiden Modi — auch bei «Live, solange offen» fragt die App
 *    Börsen ohne WebSocket bei offener Merkliste nicht selbst im Takt ab (nur Hintergrund oder
 *    von Hand).
 *  - «Live» in der Pille nur, wenn ALLE gehandelten, aktiven Paare der Ansicht am Strom und
 *    frisch sind; sonst steht der gewohnte Satz («Kurse der gewählten Börsen · vor 12 Min.» mit
 *    der Zeit des ältesten Kurses, [shownTime]).
 *
 * Reine Logik ohne Android (LiveStatusTest), Swift-Spiegel LiveStatus.swift.
 */
object LiveStatus {

    /** Ohne neuen Kurs aus dem Strom so lange frisch. */
    const val LIVE_STALE_MILLIS = OutdatedRule.LIVE_MILLIS

    /** Schwelle für abgefragte Paare (beide Modi): max(3 × Hintergrund-Intervall, 15 Min.). */
    fun pollingStaleAfterMillis(backgroundIntervalMinutes: Int): Long =
        OutdatedRule.afterMillis(backgroundIntervalMinutes)

    /** Schwelle eines Paars: am Strom 2 Min. (ab [freshnessTime]), sonst die Abfrage-Schwelle [pollingAfter]. */
    fun staleAfterMillis(onLiveSocket: Boolean, pollingAfter: Long): Long =
        if (onLiveSocket) minOf(LIVE_STALE_MILLIS, pollingAfter) else pollingAfter

    /**
     * Zeit, an der die Frische eines Paars gemessen wird. Am Live-Strom ([onLiveSocket]: letzter Takt höchstens
     * [LiveRules.FRESH_MILLIS] alt) der letzte Takt — auch ein Takt ohne Kurswechsel bestätigt den Kurs. Vorsichtig
     * die früheste mögliche Taktzeit (jetzt − [LiveRules.FRESH_MILLIS]), nie vor [lastUpdate]. Sonst [lastUpdate]
     * (letzter Abruf bzw. Kurswechsel). Nur für die Anzeige; gespeichert bleibt `lastUpdate`.
     */
    fun freshnessTime(lastUpdate: Long, onLiveSocket: Boolean, now: Long): Long =
        if (onLiveSocket) maxOf(lastUpdate, now - LiveRules.FRESH_MILLIS) else lastUpdate

    /**
     * «Live» in der Pille? Nur mit mindestens einem Paar, keinem veralteten ([stale]) und allen
     * [tradedIds] (gehandelt, aktiv) in [liveIds].
     */
    fun allLive(tradedIds: Collection<Long>, liveIds: Set<Long>, stale: Int): Boolean =
        tradedIds.isNotEmpty() && stale == 0 && tradedIds.all { it in liveIds }

    /**
     * Zeit hinter «vor X Min.»: der älteste Kurs der gezeigten Paare (Zeiten > 0), damit der Kopf
     * nicht «vor 1 Min.» sagt, während einzelne Zeilen fast 15 Min. alt sind. 0 = nie.
     */
    fun shownTime(updateTimes: List<Long>): Long = updateTimes.filter { it > 0 }.minOrNull() ?: 0L
}
