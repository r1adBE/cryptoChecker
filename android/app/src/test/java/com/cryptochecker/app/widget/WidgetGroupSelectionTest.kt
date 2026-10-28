package com.cryptochecker.app.widget

import com.cryptochecker.app.domain.watch.WatchFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Gegenstück: WidgetGroupSelectionTests in ios/Tests/WatchFilterTests.swift. */
class WidgetGroupSelectionTest {

    private val groups = listOf(null, "Majors", "Alts", "Alts")

    @Test
    fun allStaysAll() {
        assertNull(WidgetGroupSelection.resolve(null, groups))
    }

    @Test
    fun existingGroupStays() {
        assertEquals("Majors", WidgetGroupSelection.resolve("Majors", groups))
    }

    @Test
    fun deletedGroupFallsBackToAll() {
        assertNull(WidgetGroupSelection.resolve("Memes", groups))
        // Ohne Paare überhaupt: «Alle», kein Absturz
        assertNull(WidgetGroupSelection.resolve("Majors", emptyList()))
    }

    @Test
    fun builtInFiltersStay() {
        assertEquals(WatchFilter.FAVORITES, WidgetGroupSelection.resolve(WatchFilter.FAVORITES, groups))
        assertEquals(WatchFilter.TOKENS, WidgetGroupSelection.resolve(WatchFilter.TOKENS, emptyList()))
    }

    @Test
    fun renamedGroupFollowsTheNewName() {
        assertEquals("Large caps", WidgetGroupSelection.resolve("Majors", listOf("Large caps"), mapOf("Majors" to "Large caps")))
        // Zweimal umbenannt
        assertEquals("C", WidgetGroupSelection.resolve("A", listOf("C"), mapOf("A" to "B", "B" to "C")))
    }

    @Test
    fun existingGroupWinsOverAnOldRename() {
        // «Majors» → «Large caps» umbenannt, später eine neue «Majors» angelegt
        assertEquals("Majors", WidgetGroupSelection.resolve("Majors", listOf("Majors", "Large caps"), mapOf("Majors" to "Large caps")))
    }

    @Test
    fun renamedThenDeletedFallsBackToAll() {
        assertNull(WidgetGroupSelection.resolve("Majors", listOf("Alts"), mapOf("Majors" to "Large caps")))
    }

    @Test
    fun renameLoopEndsWithAll() {
        assertNull(WidgetGroupSelection.resolve("A", listOf("C"), mapOf("A" to "B", "B" to "A")))
    }

    private fun title(group: String?) = WidgetGroupSelection.title(group, "Merkliste") { filter ->
        when (filter) {
            WatchFilter.FAVORITES -> "Favoriten"
            WatchFilter.TOKENS -> "Token"
            else -> "Alpha-Tokens"
        }
    }

    @Test
    fun headerShowsTheSelectedGroup() {
        assertEquals("Merkliste", title(null))
        assertEquals("Favoriten", title(WatchFilter.FAVORITES))
        assertEquals("Token", title(WatchFilter.TOKENS))
        assertEquals("Alpha-Tokens", title(WatchFilter.ALPHA))
        assertEquals("TradFi", title("TradFi"))
        assertEquals("Majors", title("Majors"))
    }

    @Test
    fun headerTruncatesLongNames() {
        // 24 Zeichen (längster Gruppenname) → 15 + «…»
        assertEquals("Langer Gruppenn…", title("Langer Gruppenname Nr. 1"))
        assertEquals(WidgetGroupSelection.TITLE_MAX_CHARS, title("Langer Gruppenname Nr. 1").length)
        // Genau 16 Zeichen bleiben ganz; Emoji werden nicht zerteilt
        assertEquals("1234567890123456", title("1234567890123456"))
        assertEquals("🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀…", title("🚀".repeat(20)))
    }

    /** Paar für [WidgetGroupSelection.pick]: Gruppe, Favorit, Alpha-Token. */
    private data class P(val name: String, val group: String?, val fav: Boolean = false, val alpha: Boolean = false)

    private val pairs = listOf(
        P("BTC", null, fav = true),
        P("AIA", null, alpha = true),
        P("NVDA", "TradFi"),
        P("ETH", "Majors"),
    )

    private fun pick(selection: String?, renames: Map<String, String> = emptyMap()) =
        WidgetGroupSelection.pick(selection, pairs, { it.group }, { it.fav }, { it.alpha }, renames)
            .let { (group, items) -> group to items.map { it.name } }

    @Test
    fun pickFiltersBySelection() {
        assertEquals(null to listOf("BTC", "AIA", "NVDA", "ETH"), pick(null))
        assertEquals(WatchFilter.FAVORITES to listOf("BTC"), pick(WatchFilter.FAVORITES))
        assertEquals(WatchFilter.TOKENS to listOf("BTC", "AIA", "ETH"), pick(WatchFilter.TOKENS))
        // «Alpha»: nur Alpha-Tokens
        assertEquals(WatchFilter.ALPHA to listOf("AIA"), pick(WatchFilter.ALPHA))
        assertEquals("Majors" to listOf("ETH"), pick("Majors"))
        assertEquals("TradFi" to listOf("NVDA"), pick("TradFi"))
    }

    @Test
    fun pickFallsBackToAllInsteadOfAnEmptyWidget() {
        // Gelöschte Gruppe
        assertEquals(null to listOf("BTC", "AIA", "NVDA", "ETH"), pick("Memes"))
        // Umbenannt: folgt dem neuen Namen
        assertEquals("Majors" to listOf("ETH"), pick("Large caps", mapOf("Large caps" to "Majors")))
        // «Alpha» ohne Alpha-Tokens (Liste noch nicht geladen): alle, Titel «Merkliste»
        val noAlpha = WidgetGroupSelection.pick(WatchFilter.ALPHA, pairs, { it.group }, { it.fav })
        assertNull(noAlpha.first)
        assertEquals(pairs, noAlpha.second)
    }

    @Test
    fun optionsFollowTheChipOrder() {
        val groupNames = pairs.map { it.group }
        val alphaFlags = pairs.map { it.alpha }
        assertEquals(
            listOf(null, WatchFilter.FAVORITES, WatchFilter.TOKENS, WatchFilter.ALPHA, "Majors", "TradFi"),
            WidgetGroupSelection.options(groupNames, alphaFlags),
        )
        // Ohne TradFi bzw. ohne Alpha-Tokens kein «Token» bzw. «Alpha»
        assertEquals(
            listOf(null, WatchFilter.FAVORITES, "alts", "Majors"),
            WidgetGroupSelection.options(listOf("Majors", "alts", null), listOf(false, false, false)),
        )
        // Nur Alpha-Tokens: «Alpha» wäre gleich «Alle»
        assertEquals(listOf(null, WatchFilter.FAVORITES), WidgetGroupSelection.options(listOf(null), listOf(true)))
    }
}
