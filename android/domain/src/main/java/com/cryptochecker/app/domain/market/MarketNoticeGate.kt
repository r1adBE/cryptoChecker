package com.cryptochecker.app.domain.market

/**
 * Wann ZoneCheckWorker einen neuen Fear-&-Greed-Stand speichern darf —
 * gleiche Regel wie bei den Paar-Alarmen (AppNotifier.canShowAlarm): Kann die Meldung gerade
 * nicht erscheinen (Erlaubnis fehlt, Benachrichtigungen aus, Kanal stumm), bleibt der alte
 * Stand liegen; der Wechsel wird gemeldet, sobald die Meldung wieder ankommt. Gemerkt wird
 * erst nach dem erfolgreichen Absetzen, eine fehlgeschlagene Meldung kommt also beim
 * nächsten Lauf (zweimal täglich) noch einmal — nicht bei jedem Takt.
 *
 * Reines Kotlin ohne Android, damit es ohne Gerät testbar ist (iOS: `MarketNoticeGate` in ZoneCheck.swift).
 */
object MarketNoticeGate {

    /** Was mit dem neuen Stand geschieht. */
    enum class Step {
        /** Nichts zu melden: neuen Stand einfach merken. */
        STORE,

        /** Melden; den neuen Stand erst nach erfolgreichem Absetzen merken ([storeAfterNotify]). */
        NOTIFY,

        /** Wäre zu melden, kommt aber gerade nicht an: alten Stand behalten. */
        HOLD,
    }

    /** Richtung einer Fear-&-Greed-Meldung. */
    enum class FearGreedKind { BELOW, ABOVE }

    /** Entscheid für Fear & Greed: [kind] nur bei [Step.NOTIFY] bzw. [Step.HOLD] gesetzt. */
    data class FearGreedStep(val step: Step, val kind: FearGreedKind? = null)

    /**
     * Fear & Greed: einmal melden, wenn der Index die Grenze [below] unter- bzw. [above]
     * überschreitet (Grenze ≤ 0 = aus); [last] null = noch kein Stand gemerkt.
     * [belowChanged]/[aboveChanged]: Diese Grenze ist seit dem gemerkten Stand eine andere ([boundChanged]) —
     * dann zählt [last] für sie nicht, sodass ein schon jenseits der neuen Grenze liegender Wert gemeldet wird.
     * Die unveränderte andere Grenze bewertet weiter mit [last] (keine Doppelmeldung).
     */
    fun fearGreed(
        value: Int, last: Int?, below: Int, above: Int, canNotify: Boolean,
        belowChanged: Boolean = false, aboveChanged: Boolean = false,
    ): FearGreedStep {
        val kind = when {
            below > 0 && value <= below && (last == null || belowChanged || last > below) -> FearGreedKind.BELOW
            above > 0 && value >= above && (last == null || aboveChanged || last < above) -> FearGreedKind.ABOVE
            else -> null
        }
        return when {
            kind == null -> FearGreedStep(Step.STORE)
            canNotify -> FearGreedStep(Step.NOTIFY, kind)
            else -> FearGreedStep(Step.HOLD, kind)
        }
    }

    /**
     * Gilt die Grenze [current] nicht mehr für den gemerkten Stand? [stored] = mit dem Stand gemerkte Grenze;
     * null = keine Grenze gemerkt: als geändert werten.
     */
    fun boundChanged(stored: Int?, current: Int): Boolean = stored != current

    /**
     * Abruf gescheitert: so oft erneut je Lauf (WorkManager-Backoff des ZoneCheckWorker), danach erst der nächste
     * 12-h-Lauf. Keine Doppelmeldung: Gemeldetes ist vermerkt. iOS gleich (`BackgroundRefresh.runZoneCheckIfDue`).
     */
    const val MAX_RETRIES = 3

    /** Erste Pause vor einem erneuten Versuch; jede weitere doppelt so lang (30 s, 1 Min., 2 Min.). */
    const val RETRY_BASE_MILLIS = 30_000L

    /** Pause vor dem erneuten Versuch Nummer [attempt] (ab 1). */
    fun retryDelayMillis(attempt: Int): Long = RETRY_BASE_MILLIS shl (attempt.coerceIn(1, MAX_RETRIES) - 1)

    /** Neuen Stand speichern? [posted] = die Meldung wurde abgesetzt (nur bei [Step.NOTIFY] von Belang). */
    fun storeAfterNotify(step: Step, posted: Boolean): Boolean = when (step) {
        Step.STORE -> true
        Step.NOTIFY -> posted
        Step.HOLD -> false
    }
}
