package com.cryptochecker.app.domain.market

import com.cryptochecker.marketdata.model.market.KrakenAssetCodes
import org.junit.Assert.assertEquals
import org.junit.Test

class KrakenAssetCodesTest {

    private fun n(code: String) = KrakenAssetCodes.normalize(code)

    @Test
    fun legacyCryptoCodesAreStripped() {
        assertEquals("BTC", n("XXBT"))
        assertEquals("ETH", n("XETH"))
        assertEquals("LTC", n("XLTC"))
        assertEquals("XRP", n("XXRP"))
        assertEquals("XLM", n("XXLM"))
        assertEquals("XMR", n("XXMR"))
        assertEquals("ZEC", n("XZEC"))
        assertEquals("ETC", n("XETC"))
        assertEquals("MLN", n("XMLN"))
        assertEquals("REP", n("XREP"))
        assertEquals("DOGE", n("XXDG"))
    }

    @Test
    fun legacyFiatCodesAreStripped() {
        assertEquals("USD", n("ZUSD"))
        assertEquals("EUR", n("ZEUR"))
        assertEquals("GBP", n("ZGBP"))
        assertEquals("CAD", n("ZCAD"))
        assertEquals("JPY", n("ZJPY"))
        assertEquals("CHF", n("ZCHF"))
        assertEquals("AUD", n("ZAUD"))
    }

    @Test
    fun krakenAliasesAreMapped() {
        assertEquals("BTC", n("XBT"))
        assertEquals("DOGE", n("XDG"))
    }

    @Test
    fun otherCodesStayUntouched() {
        assertEquals("XTZ", n("XTZ"))
        assertEquals("ZRX", n("ZRX"))
        assertEquals("ZEC", n("ZEC"))
        assertEquals("DOT", n("DOT"))
        assertEquals("ZK", n("ZK"))
        assertEquals("ZETA", n("ZETA"))
        assertEquals("XCN", n("XCN"))
        assertEquals("USDT", n("USDT"))
        assertEquals("XUSD", n("XUSD"))
        assertEquals("ZETH", n("ZETH"))
        assertEquals("X", n("X"))
        assertEquals("", n(""))
    }
}
