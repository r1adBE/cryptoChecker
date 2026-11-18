package com.cryptochecker.app.data.market

import com.cryptochecker.app.domain.market.MarketCacheKinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketCacheAdapterTest {
    @Test
    fun everyKindHasExactlyOneCodec() {
        MarketCacheKinds.all.forEach { kind -> assertTrue("keine Umwandlung für $kind", MarketCacheAdapter.hasCodec(kind)) }
        assertEquals(MarketCacheKinds.all.size, MarketCacheKinds.all.map { it.name }.toSet().size)
    }

    @Test
    fun codecsAreDistinctPerKind() {
        val codecs = MarketCacheKinds.all.map { MarketCacheAdapter.codec(it) }
        assertEquals(codecs.size, codecs.toSet().size)
    }
}
