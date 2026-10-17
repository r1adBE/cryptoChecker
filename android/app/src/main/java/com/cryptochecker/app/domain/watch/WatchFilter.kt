package com.cryptochecker.app.domain.watch

/**
 * Ansichten der Merkliste über den Gruppen-Chips: «Alle» (null), «Favoriten» ([FAVORITES]),
 * «Token» ([TOKENS]) und die eigenen Gruppen. FAV ist keine Gruppe, sondern ein Filter auf das
 * Favoriten-Kennzeichen: Ein Paar bleibt in seiner Gruppe und steht als Favorit zusätzlich unter
 * FAV — Favorit an, gleich drin; aus, gleich draussen. «Token» ist ebenfalls keine Gruppe: alle
 * Paare ausser denen der Gruppe «TradFi» ([AutoGroup.TRADFI]) — also auch Paare ohne Gruppe,
 * «QTLY» und eigene Gruppen. Gilt auch für Listen-Widgets. Wie `WatchFilter.swift` (iOS).
 * «Alpha» ([ALPHA]) ist ebenfalls keine Gruppe: nur Paare der Art [AssetKind.ALPHA] (Binance-Alpha-
 * Liste); sie stehen weiterhin auch unter «Token» (ein Alpha-Token ist auch ein Token). Auch in der
 * Einrichtung der Listen-Widgets wählbar (gespeicherte Alpha-Liste, [com.cryptochecker.app.widget.WidgetGroupSelection]).
 */
object WatchFilter {
    /** Gespeicherter Wert für «FAV» (Steuerzeichen, kann kein Gruppenname sein). */
    const val FAVORITES = "\u0001FAV"

    /** Gespeicherter Wert für «Token» (Steuerzeichen, kann kein Gruppenname sein). */
    const val TOKENS = "\u0001TOK"

    /** Gespeicherter Wert für «Alpha» (Steuerzeichen, kann kein Gruppenname sein; gleich wie iOS). */
    const val ALPHA = "\u0001ALP"

    fun isFavorites(selection: String?): Boolean = selection == FAVORITES

    fun isTokens(selection: String?): Boolean = selection == TOKENS

    fun isAlpha(selection: String?): Boolean = selection == ALPHA

    /** Ein Filter statt einer Gruppe (FAV, Token, Alpha): nicht bearbeitbar und kein Ziel für «Gruppe». */
    fun isBuiltIn(selection: String?): Boolean = isFavorites(selection) || isTokens(selection) || isAlpha(selection)

    /**
     * Steht ein Paar ([groupName], [favorite]) in der Ansicht [selection]? [alpha]: Paar ist ein
     * Alpha-Token ([AssetKind.ALPHA]); nur für «Alpha» nötig.
     */
    fun matches(selection: String?, groupName: String?, favorite: Boolean, alpha: Boolean = false): Boolean =
        when (selection) {
            null -> true
            FAVORITES -> favorite
            TOKENS -> groupName != AutoGroup.TRADFI
            ALPHA -> alpha
            else -> groupName == selection
        }

    /**
     * «Token»-Chip zeigen? Nur wenn mindestens ein Paar in «TradFi» steht UND mindestens eines nicht —
     * sonst wäre die Ansicht gleich «Alle» oder leer. [groupNames]: Gruppe je Paar (null = keine).
     */
    fun showsTokens(groupNames: Iterable<String?>): Boolean =
        groupNames.any { it == AutoGroup.TRADFI } && groupNames.any { it != AutoGroup.TRADFI }

    /**
     * «Alpha»-Chip zeigen? Nur wenn mindestens ein Paar ein Alpha-Token ist UND mindestens eines
     * nicht — sonst wäre die Ansicht gleich «Alle» oder leer. [alphaFlags]: je Paar, ob Alpha.
     */
    fun showsAlpha(alphaFlags: Iterable<Boolean>): Boolean =
        alphaFlags.any { it } && alphaFlags.any { !it }

    /**
     * Gilt die gespeicherte Auswahl [selection] noch? «Alle» und FAV immer, «Token» nur mit
     * [showsTokens], «Alpha» nur mit [showsAlpha] ([alphaFlags] je Paar), eine Gruppe, solange ein
     * Paar sie nutzt. Sonst gilt wieder «Alle».
     */
    fun isAvailable(
        selection: String?,
        groupNames: Iterable<String?>,
        alphaFlags: Iterable<Boolean> = emptyList(),
    ): Boolean = when {
        selection == null || isFavorites(selection) -> true
        isTokens(selection) -> showsTokens(groupNames)
        isAlpha(selection) -> showsAlpha(alphaFlags)
        else -> groupNames.any { it == selection }
    }
}
