package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.data.local.model.AlarmCondition

/**
 * «1 h später» aus der Alarm-Mitteilung: Nur die nächste Meldung wartet bis [snoozeUntil] — wie die Nachtruhe im
 * Melde-Schritt ([AlarmDelivery]), nie in der Prüfung selbst ([AlarmEvaluator] bleibt unverändert). Angeboten nur,
 * wo das wirkt ([offeredFor]): Prozent- und Bewegungsalarme bleiben nach dem Auslösen scharf, Kursmarken stellt die
 * Pause wieder scharf ([rearmsOnSnooze]); ist die Bedingung nach der Pause noch erfüllt, melden sie sich beim
 * nächsten Durchlauf. Reine Logik — wie `AlarmSnooze.swift`
 * (gemeinsamer Testfall `testdata/parity/alarm_snooze.json`).
 */
object AlarmSnooze {

    /** Dauer von «1 h später». */
    const val SNOOZE_MILLIS = 60L * 60_000

    /** Ende der Pause, wenn jetzt ([now]) «1 h später» getippt wird. */
    fun snoozeUntil(now: Long): Long = now + SNOOZE_MILLIS

    /**
     * Hält die Pause die Meldung jetzt zurück? Nur zwischen jetzt und [until]; liegt [until] weiter als
     * [SNOOZE_MILLIS] in der Zukunft (Uhr zurückgestellt), gilt die Pause als vorbei — ein Alarm soll nie
     * länger als die versprochene Stunde schweigen.
     */
    fun isSnoozed(until: Long?, now: Long): Boolean {
        if (until == null || until <= 0) return false
        val rest = until - now
        return rest > 0 && rest <= SNOOZE_MILLIS
    }

    /**
     * Knopf «1 h später» zeigen? Prozent- und Bewegungsalarme ([AlarmCondition.isPercent]) nur, wenn sie wiederholen:
     * Sie bleiben nach dem Auslösen scharf (neuer Bezug bzw. neues Fenster, [AlarmEvaluator.triggered]), ein
     * einmaliger ist nach dem Melden aus. Kursmarken (über/unter) immer: Die Pause stellt sie wieder scharf
     * ([rearmsOnSnooze]) — meldet nach einer Stunde erneut, falls der Kurs dann noch jenseits der Marke ist, sonst
     * wie gewohnt beim nächsten Überqueren. Funding und Open Interest werden erst wieder scharf, wenn der Wert auf die
     * andere Seite zurückkehrt; «Nahe am Hoch/Tief» erst ausserhalb der Zone oder frühestens 1 h später bei einem
     * weiteren Hoch/Tief; der Volumen-Spike meldet ohnehin höchstens einmal je Stundenkerze — dort hielte die Pause
     * nichts zurück.
     */
    fun offeredFor(condition: AlarmCondition, repeating: Boolean): Boolean =
        (repeating && condition.isPercent) || rearmsOnSnooze(condition)

    /**
     * Stellt «1 h später» den Alarm wieder scharf (und schaltet einen einmaligen wieder ein)? Nur Kursmarken: Nach
     * dem Melden warten sie sonst auf die Rückkehr des Kurses (wiederholend) bzw. sind aus (einmalig) — die Pause
     * allein hielte nichts zurück. Wieder scharf meldet der Alarm während der Pause nicht (Melde-Schritt, wie die
     * Nachtruhe), danach beim ersten Durchlauf, in dem der Kurs noch jenseits der Marke liegt.
     */
    fun rearmsOnSnooze(condition: AlarmCondition): Boolean = condition.isPriceThreshold

    /** Nur noch laufende Pausen (abgelaufene fallen beim Speichern weg). */
    fun prune(all: Map<Long, Long>, now: Long): Map<Long, Long> = all.filter { (_, until) -> isSnoozed(until, now) }
}
