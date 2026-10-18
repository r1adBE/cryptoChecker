package com.cryptochecker.app.widget

import com.cryptochecker.app.domain.watch.WatchFilter

/**
 * Gruppe eines Listen-Widgets «Merkliste» auflösen (reines Kotlin, testbar). Gegenstück:
 * `WidgetGroupSelection` in Shared/Storage/WidgetGroupSelection.swift — dort zusätzlich die Liste der
 * Umbenennungen, weil die App die Einrichtung eines iOS-Widgets nicht umschreiben kann. Android
 * schreibt eine Umbenennung direkt in die Widget-Einstellung ([WidgetPrefs.replaceGroup]) und
 * übergibt deshalb keine [renames].
 */
object WidgetGroupSelection {

    /**
     * - null bleibt null («Alle»)
     * - FAV, «Token», «Alpha» und die übrigen eingebauten Filter ([WatchFilter.isBuiltIn]) bleiben (ohne passende
     *   Paare zeigt das Widget alle)
     * - eine vorhandene Gruppe bleibt
     * - eine umbenannte folgt [renames] (alt → neu, auch über mehrere Umbenennungen), sofern es den
     *   neuen Namen gibt
     * - sonst (gelöscht, letztes Paar entfernt) null = «Alle», nie ein leeres Widget
     *
     * @param groupNames Gruppe je Paar der Merkliste (null = keine)
     */
    fun resolve(
        selection: String?,
        groupNames: Iterable<String?>,
        renames: Map<String, String> = emptyMap(),
    ): String? {
        if (selection == null || WatchFilter.isBuiltIn(selection)) return selection
        val existing = groupNames.filterNotNullTo(HashSet())
        var name: String = selection
        // Höchstens so viele Schritte wie Einträge: schützt vor Schleifen (A → B → A)
        repeat(renames.size + 1) {
            if (name in existing) return name
            name = renames[name] ?: return null
        }
        return null
    }

    /**
     * Wirksame Gruppe und ihre Paare aus [items] (App-Reihenfolge): [selection] wird erst aufgelöst
     * ([resolve]), dann gefiltert ([WatchFilter.matches]; [alpha] = Paar ist ein Alpha-Token, nur für
     * «Alpha» gefragt). Gibt es die Gruppe nicht (mehr) oder passt kein Paar, wieder alle Paare und
     * Gruppe null («Alle», Titel «Merkliste») — nie ein leeres Widget. Wie
     * `WidgetData.groupWatches` (iOS).
     */
    fun <T> pick(
        selection: String?,
        items: List<T>,
        groupName: (T) -> String?,
        favorite: (T) -> Boolean,
        alpha: (T) -> Boolean = { false },
        renames: Map<String, String> = emptyMap(),
    ): Pair<String?, List<T>> {
        val group = resolve(selection, items.map(groupName), renames) ?: return null to items
        val needsAlpha = WatchFilter.isAlpha(group)
        val matching = items.filter { WatchFilter.matches(group, groupName(it), favorite(it), needsAlpha && alpha(it)) }
        return if (matching.isEmpty()) null to items else group to matching
    }

    /**
     * Wählbare Einträge der Widget-Einrichtung in der Reihenfolge der Chips der App: «Alle» (null),
     * FAV, «Token» (nur mit [WatchFilter.showsTokens]), «Alpha» (nur mit [WatchFilter.showsAlpha]),
     * dann die eigenen Gruppen alphabetisch (auch «TradFi», «QTLY»). [groupNames]/[alphaFlags] je
     * Paar. Wie `WidgetGroupQuery.suggestedEntities` (iOS).
     */
    fun options(groupNames: List<String?>, alphaFlags: List<Boolean>): List<String?> = buildList {
        add(null)
        add(WatchFilter.FAVORITES)
        if (WatchFilter.showsTokens(groupNames)) add(WatchFilter.TOKENS)
        if (WatchFilter.showsAlpha(alphaFlags)) add(WatchFilter.ALPHA)
        addAll(groupNames.filterNotNull().distinct().sortedWith(String.CASE_INSENSITIVE_ORDER))
    }

    /** So viele Zeichen eines Gruppennamens zeigt der Kopf des Widgets; längere enden mit «…». */
    const val TITLE_MAX_CHARS = 16

    /**
     * Titel im Kopf des Listen-Widgets: [default] («Merkliste») bei «Alle» ([group] null), sonst die
     * Beschriftung des Filters ([filterLabel] für FAV, «Token», «Alpha») bzw. der Gruppenname (auch
     * «TradFi»), auf [TITLE_MAX_CHARS] Zeichen gekürzt. Wie `WidgetGroupSelection.title` (iOS).
     */
    fun title(group: String?, default: String, filterLabel: (String) -> String): String {
        if (group == null) return default
        val name = if (WatchFilter.isBuiltIn(group)) filterLabel(group) else group
        return truncate(name, TITLE_MAX_CHARS)
    }

    /** Höchstens [max] Zeichen (Unicode-Codepunkte, Emoji bleiben ganz); länger: [max] − 1 Zeichen + «…». */
    fun truncate(text: String, max: Int): String {
        val trimmed = text.trim()
        if (max < 1 || trimmed.codePointCount(0, trimmed.length) <= max) return trimmed
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, max - 1)).trimEnd() + "…"
    }
}
