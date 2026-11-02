package com.cryptochecker.app.ui.features.watchlist

import org.junit.Assert.assertEquals
import org.junit.Test

/** Kontrakt («PERP») fällt nie weg ([PairRestFit]); gleiche Regel wie iOS `WatchlistRow.pairName`. */
class PairRestFitTest {
    @Test
    fun fullRestOnlyWhenWholeRestFits() {
        assertEquals(PairRestFit.FULL, PairRestFit.choose(room = 80, restWidth = 80, hasRest = true, contractWidth = 30))
    }

    /** Band, das grosse Schrift erzeugt: «/USDT» passt, «/USDT PERP» nicht → Kontrakt statt Ellipse über PERP. */
    @Test
    fun contractWhenOnlyFirstWordOfRestFits() {
        assertEquals(PairRestFit.CONTRACT, PairRestFit.choose(room = 60, restWidth = 80, hasRest = true, contractWidth = 30))
    }

    @Test
    fun spotDropsQuoteWhenTight() {
        assertEquals(PairRestFit.NONE, PairRestFit.choose(room = 20, restWidth = 50, hasRest = true, contractWidth = null))
    }

    @Test
    fun futuresKeepContractWhenQuoteDoesNotFit() {
        assertEquals(PairRestFit.CONTRACT, PairRestFit.choose(room = 40, restWidth = 50, hasRest = true, contractWidth = 30))
    }

    @Test
    fun futuresShrinkBaseRatherThanDropContract() {
        assertEquals(PairRestFit.CONTRACT_SHRINK_BASE, PairRestFit.choose(room = 10, restWidth = 50, hasRest = true, contractWidth = 30))
    }
}
