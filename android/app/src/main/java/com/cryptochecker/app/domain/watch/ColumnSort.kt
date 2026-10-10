package com.cryptochecker.app.domain.watch

/**
 * Sortieren nach Spalten wie an der Börse («Name ⇅ · Kurs ⇅ · 24h ⇅» über der Merkliste). Nur eine
 * Ansicht: die eigene Reihenfolge (⋯ › Sortieren) bleibt gespeichert und kommt mit dem dritten
 * Tipp zurück. Favoriten bleiben oben und werden unter sich sortiert. Kurse werden über einen
 * gemeinsamen Wert verglichen (im Hintergrund in CHF umgerechnet), sonst wären 1 BTC und 1 USDT
 * nicht vergleichbar. Paare ohne Wert stehen immer am Ende. Wie `ColumnSort.swift` (iOS).
 */
enum class SortKey { NAME, PRICE, CHANGE }

data class ColumnSort(val key: SortKey, val descending: Boolean) {

    /** Gespeicherte Form, z. B. «PRICE_DESC». */
    fun encode(): String = "${key.name}_${if (descending) "DESC" else "ASC"}"

    companion object {
        /** Gespeicherte Form lesen; unbekannt oder leer = eigene Reihenfolge (null). */
        fun decode(value: String?): ColumnSort? {
            val parts = value?.split('_') ?: return null
            if (parts.size != 2) return null
            val key = SortKey.entries.firstOrNull { it.name == parts[0] } ?: return null
            return when (parts[1]) {
                "DESC" -> ColumnSort(key, true)
                "ASC" -> ColumnSort(key, false)
                else -> null
            }
        }

        /**
         * Tipp auf eine Spalte: neue Spalte → erste Richtung (Name A–Z, Kurs und 24h gross zuerst),
         * gleiche Spalte → andere Richtung, dann wieder die eigene Reihenfolge (null).
         */
        fun next(current: ColumnSort?, tapped: SortKey): ColumnSort? {
            val first = tapped != SortKey.NAME
            return when {
                current?.key != tapped -> ColumnSort(tapped, first)
                current.descending == first -> ColumnSort(tapped, !first)
                else -> null
            }
        }

        /**
         * [items] in der eigenen Reihenfolge sortieren: Favoriten zuerst (unter sich sortiert),
         * dann die übrigen. Ohne [sort] unverändert. Gleiche Werte behalten ihre Reihenfolge.
         * @param value gemeinsamer Kurswert (z. B. in CHF); null = unbekannt (ans Ende)
         * @param change %-Änderung wie in der Pille; null = unbekannt (ans Ende)
         */
        fun <T> apply(
            items: List<T>,
            sort: ColumnSort?,
            favorite: (T) -> Boolean,
            name: (T) -> String,
            value: (T) -> Double?,
            change: (T) -> Double?,
        ): List<T> {
            if (sort == null) return items
            val (favorites, others) = items.partition(favorite)
            return sortPart(favorites, sort, name, value, change) + sortPart(others, sort, name, value, change)
        }

        private fun <T> sortPart(
            items: List<T>,
            sort: ColumnSort,
            name: (T) -> String,
            value: (T) -> Double?,
            change: (T) -> Double?,
        ): List<T> = when (sort.key) {
            SortKey.NAME -> {
                val byName = compareBy<T, String>(String.CASE_INSENSITIVE_ORDER) { name(it) }
                items.sortedWith(if (sort.descending) byName.reversed() else byName)
            }
            SortKey.PRICE -> byNumber(items, sort.descending, value)
            SortKey.CHANGE -> byNumber(items, sort.descending, change)
        }

        private fun <T> byNumber(items: List<T>, descending: Boolean, number: (T) -> Double?): List<T> {
            val (known, unknown) = items.partition { number(it)?.takeIf { v -> !v.isNaN() } != null }
            // sortedBy/sortedByDescending sind stabil: gleiche Werte behalten ihre Reihenfolge
            val sorted = if (descending) known.sortedByDescending { number(it)!! } else known.sortedBy { number(it)!! }
            return sorted + unknown
        }
    }
}
