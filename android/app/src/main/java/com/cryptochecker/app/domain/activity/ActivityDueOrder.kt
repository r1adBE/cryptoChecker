package com.cryptochecker.app.domain.activity

/**
 * Reihenfolge der fälligen Paare für «Ungewöhnliche Aktivität» (seit Build 102). Der Durchlauf hat ein Zeitbudget;
 * in Listenreihenfolge kämen bei grossen Merklisten immer dieselben vorderen Paare dran und die hinteren nie
 * (beim nächsten Lauf sind alle wieder fällig, gleicher Abbruch). Darum: am längsten nicht geprüfte zuerst, nie
 * geprüfte ganz vorn. Ein Versuch ohne Ergebnis (Fehler, Abbruch) zählt wie eine Prüfung, damit ein Paar ohne
 * Kerzen nicht jeden Lauf vorn steht. Reines Kotlin; gleich wie iOS `ActivityDueOrder`.
 */
object ActivityDueOrder {

    /**
     * [items] nach der letzten Prüfung bzw. dem letzten Versuch aufsteigend (stabil: Gleichstand in Listenreihenfolge).
     * [computedAt] = Zeit des gespeicherten Berichts (null = nie), [attemptedAt] = letzter Versuch je Paar-id.
     */
    fun <T> order(
        items: List<T>,
        id: (T) -> Long,
        computedAt: (T) -> Long?,
        attemptedAt: Map<Long, Long>,
    ): List<T> = items.sortedBy { item -> lastTouched(computedAt(item), attemptedAt[id(item)]) }

    /** Später von Bericht und Versuch; nie berührt = ganz vorn. */
    fun lastTouched(computedAt: Long?, attemptedAt: Long?): Long =
        maxOf(computedAt ?: Long.MIN_VALUE, attemptedAt ?: Long.MIN_VALUE)
}
