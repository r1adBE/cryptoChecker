package com.cryptochecker.app.domain.refresh

/**
 * Reihenfolge der Einzelabfragen einer Börse: Paare mit aktivem Alarm zuerst. Im Ruhemodus bleibt nach
 * einem pünktlichen Wecker nur ein kurzes Netz-Fenster ([CheckBudget.IDLE_NETWORK_MILLIS]); was danach
 * noch aussteht, fällt diesmal weg — dann lieber Kurse ohne Alarm. Sonst bleibt die Reihenfolge, wie sie
 * ist (stabil). Reine Logik, wie `FetchOrder.swift`.
 */
object FetchOrder {
    fun <T> alarmsFirst(items: List<T>, alarmed: Set<Long>, id: (T) -> Long): List<T> {
        if (alarmed.isEmpty() || items.size < 2) return items
        val (first, rest) = items.partition { id(it) in alarmed }
        return if (first.isEmpty() || rest.isEmpty()) items else first + rest
    }
}
