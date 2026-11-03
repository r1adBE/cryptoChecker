package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.logos.parseIconPack
import com.cryptochecker.app.domain.market.ExchangeLogos
import com.cryptochecker.marketdata.config.ExchangesConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Börsen-Logos (`testdata/parity/exchange_logos.json`): jede registrierte Börse hat genau ein mitgeliefertes Logo
 * oder ein festes Symbol; das Paket `assets/exchange_logos.bin` enthält genau diese Logos (PNG).
 * Gegenstück: `Tests/ExchangeLogosParityTests.swift`.
 */
class ExchangeLogosParityTest {

    private val fixture = ParityJson.load("exchange_logos.json")

    @Test
    fun jedeBoerseHatLogoOderSymbol() {
        val keys = fixture["keys"].obj()
        // Die Testfälle nennen genau die registrierten Börsen (neue Börse → hier, in iOS und im Generator eintragen)
        assertEquals(ExchangesConfig.EXCHANGES.keys, keys.keys)
        for ((key, raw) in keys) {
            val spec = raw.obj()
            assertEquals(key, spec["logo"], ExchangeLogos.logoName(key))
            assertEquals(key, spec["symbol"], ExchangeLogos.SYMBOLS[key]?.name)
            assertTrue(key, (ExchangeLogos.logoName(key) != null) != (key in ExchangeLogos.SYMBOLS))
        }
        for (group in fixture["shared"].list()) {
            assertEquals(group.toString(), 1, group.list().map { ExchangeLogos.logoName(it as String) }.toSet().size)
        }
        assertEquals(fixture["distinct"].num()!!.toInt(), ExchangeLogos.NAMES.size)
    }

    @Test
    fun paketEnthaeltJedesLogo() {
        val file = File(ParityJson.dir(), "../../app/src/main/assets/" + ExchangeLogos.PACK_FILE)
        assertTrue("assets/${ExchangeLogos.PACK_FILE} nicht gefunden (Pflicht)", file.isFile)
        val bytes = file.readBytes()
        val index = CoinLogos.parseIconPack(bytes)
        assertNotNull(index)
        if (index == null) return
        assertEquals(ExchangeLogos.NAMES.toSet(), index.keys)
        index.values.forEach { slice ->
            assertEquals(0x89.toByte(), bytes[slice.offset])
            assertEquals('P'.code.toByte(), bytes[slice.offset + 1])
        }
    }
}
