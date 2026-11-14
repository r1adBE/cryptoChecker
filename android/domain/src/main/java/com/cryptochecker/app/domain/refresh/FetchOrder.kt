package com.cryptochecker.app.domain.refresh

/**
 * Reihenfolge der Einzelabfragen einer Börse: Paare mit aktivem Alarm zuerst. Im Ruhemodus bleibt nach
 * einem pünktlichen Wecker nur ein kurzes Netz-Fenster ([CheckBudget.IDLE_NETWORK_MILLIS]); was danach
 * noch aussteht, fällt diesmal weg — dann lieber Kurse ohne Alarm. Sonst bleibt die Reihenfolge, wie sie
 * ist (stabil). Reine Logik, wie `FetchOrder.swift`.
 */
object FetchOrder {
    /**
     * [rotation]: mit Zeitbudget je Lauf ein anderer Beginn (beide Teile rotiert) — sonst fielen jedes Mal dieselben
     * Paare am Ende aus dem Budget; 0 = stabile Reihenfolge.
     */
    fun <T> alarmsFirst(items: List<T>, alarmed: Set<Long>, rotation: Int = 0, id: (T) -> Long): List<T> {
        if (items.size < 2) return items
        val (first, rest) = items.partition { id(it) in alarmed }
        if (rotation == 0 && (first.isEmpty() || rest.isEmpty())) return items
        return rotated(first, rotation) + rotated(rest, rotation)
    }

    private fun <T> rotated(list: List<T>, by: Int): List<T> {
        if (list.size < 2) return list
        val k = Math.floorMod(by, list.size)
        return if (k == 0) list else list.drop(k) + list.take(k)
    }
}
