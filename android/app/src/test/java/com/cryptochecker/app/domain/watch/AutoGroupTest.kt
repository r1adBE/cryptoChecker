package com.cryptochecker.app.domain.watch

import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.marketdata.model.FuturesContractType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoGroupTest {

    private fun pair(contract: FuturesContractType = FuturesContractType.NONE, tradFi: Boolean = false) =
        CurrencyPairInfo("X", "USDT", "XUSDT", contract, tradFi)

    @Test
    fun `tradfi and dated futures get their own group`() {
        assertEquals("TradFi", AutoGroup.forPair(pair(FuturesContractType.PERPETUAL, tradFi = true)))
        assertEquals("QTLY", AutoGroup.forPair(pair(FuturesContractType.QUARTERLY)))
        assertEquals("QTLY", AutoGroup.forPair(pair(FuturesContractType.BIQUARTERLY)))
        assertNull(AutoGroup.forPair(pair(FuturesContractType.PERPETUAL)))
        assertNull(AutoGroup.forPair(pair(FuturesContractType.INVERSE_PERPETUAL)))
        assertNull(AutoGroup.forPair(pair()))
    }

    @Test
    fun `chosen group always wins`() {
        val tsla = pair(FuturesContractType.PERPETUAL, tradFi = true)
        assertEquals("Meine", AutoGroup.resolve(" Meine ", tsla))
        assertEquals("TradFi", AutoGroup.resolve(null, tsla))
        assertEquals("TradFi", AutoGroup.resolve("  ", tsla))
        assertNull(AutoGroup.resolve(null, pair()))
    }
}
