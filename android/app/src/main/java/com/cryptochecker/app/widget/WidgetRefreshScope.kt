package com.cryptochecker.app.widget

/**
 * Welche Widgets nach der Aktualisierung EINES Paares neu gezeichnet werden müssen
 * (reines Kotlin, testbar). Alles andere bleibt, wie es ist.
 */
object WidgetRefreshScope {

    /**
     * Zeigt ein Listen-Widget mit der Gruppe [widgetGroup] das Paar aus [watchGroup]?
     * Wie [PriceWidgetService]: ohne Gruppe alle Paare; gibt es die Gruppe nicht (mehr),
     * also kein Paar mit diesem Gruppennamen in [existingGroups], ebenfalls alle.
     */
    fun listShowsWatch(widgetGroup: String?, watchGroup: String?, existingGroups: Set<String?>): Boolean =
        widgetGroup == null || widgetGroup !in existingGroups || watchGroup == widgetGroup

    /**
     * Portfolio-Widgets neu zeichnen? Nur wenn sich die Momentaufnahme (Gesamtwert, Zeit)
     * seit dem letzten Zeichnen geändert hat; ist nichts bekannt ([drawn] null), sicherheitshalber ja.
     */
    fun portfolioChanged(drawn: PortfolioDrawState?, current: PortfolioDrawState): Boolean =
        drawn == null || drawn != current
}

/** Zuletzt gezeichneter Stand der Portfolio-Momentaufnahme (null-Werte = keine Aufnahme). */
data class PortfolioDrawState(val total: Double?, val time: Long?)

/**
 * Hält je Widget nur das zuletzt gezeichnete Ergebnis samt Schlüssel. Passt der Schlüssel,
 * wird es wiederverwendet statt neu gezeichnet. Thread-sicher; [remove] beim Löschen des Widgets.
 */
class LatestPerWidget<K : Any, V : Any> {
    private val entries = java.util.concurrent.ConcurrentHashMap<Int, Pair<K, V>>()

    /** Das gespeicherte Ergebnis, wenn es mit genau diesem [key] entstand; sonst null. */
    fun get(appWidgetId: Int, key: K): V? =
        entries[appWidgetId]?.takeIf { it.first == key }?.second

    fun put(appWidgetId: Int, key: K, value: V) {
        entries[appWidgetId] = key to value
    }

    fun remove(appWidgetId: Int) {
        entries.remove(appWidgetId)
    }

    val size: Int get() = entries.size
}
