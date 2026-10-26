package com.cryptochecker.app.ui.components

/** Eintrag der Aufklappliste: Stelle in der vollen Liste und Text; [header] = Titelzeile eines Abschnitts (nicht wählbar). */
internal data class ComboEntry(val index: Int, val text: String, val header: Boolean = false)

/**
 * Einträge in Anzeigereihenfolge. Mit Suchtext nur Treffer — erst die, die damit beginnen, dann
 * die, die ihn enthalten (ohne Gross-/Kleinschreibung). Favoriten stehen jeweils vorne. Mit [sections]
 * (Titel je Eintrag) folgen nach den Favoriten die Abschnitte in der Reihenfolge ihres ersten Eintrags, je mit
 * Titelzeile; Abschnitte ohne Treffer fallen weg.
 */
internal fun comboEntries(
    items: List<String>,
    query: String?,
    favorites: Set<String>,
    sections: List<String>? = null,
): List<ComboEntry> {
    val needle = query?.trim().orEmpty()
    val all = items.mapIndexed { index, text -> ComboEntry(index, text) }
    val matching = if (needle.isEmpty()) all else {
        val (prefix, rest) = all.partition { it.text.startsWith(needle, ignoreCase = true) }
        prefix + rest.filter { it.text.contains(needle, ignoreCase = true) }
    }
    val (favored, others) = matching.partition { it.text in favorites }
    if (sections == null || sections.size != items.size) return favored + others
    val bySection = others.groupBy { sections[it.index] }
    return favored + sections.distinct().flatMap { title ->
        bySection[title]?.let { listOf(ComboEntry(-1, title, header = true)) + it }.orEmpty()
    }
}
