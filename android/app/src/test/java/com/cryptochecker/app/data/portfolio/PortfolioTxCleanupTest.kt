package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.domain.portfolio.PortfolioDecimal
import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Seit Build 102: Mengen mit mehr als 18 Nachkommastellen werden vor der Prüfung gerundet (eine Menge unter 10⁻¹⁸ ist
 * ungültig statt als 0 gespeichert), und Rechnen ([PortfolioDecimal.amount]) und Speichern ([PortfolioDecimal.text])
 * runden gleich (halbe auf gerade). Wie iOS (`PortfolioDecimalRoundingTests`).
 */
class PortfolioTxCleanupTest {

    private fun tx(amount: String, fee: String? = null, toAmount: String? = null, type: PortfolioTxType = PortfolioTxType.BUY) =
        PortfolioTxEntity(
            id = 0, coin = "eth", type = type, amount = BigDecimal(amount), time = 1_000L,
            fee = fee?.let { BigDecimal(it) }, feeUnit = fee?.let { PortfolioFeeUnit.COIN.name },
            toCoin = toAmount?.let { "BTC" }, toAmount = toAmount?.let { BigDecimal(it) },
        )

    @Test
    fun mengeUnterEinemWeiWirdNullUndAbgelehnt() {
        val clean = cleanForSave(tx("0.0000000000000000004"))
        // PortfolioRepository.save prüft genau diese bereinigte Menge (> 0)
        assertEquals(0, clean.amount.signum())
    }

    @Test
    fun mengeWirdAufGespeicherteStellenGerundet() {
        val clean = cleanForSave(tx("1.0000000000000000005"))
        assertEquals(0, clean.amount.compareTo(BigDecimal.ONE))
        // Was geprüft wurde, wird auch gespeichert
        assertEquals(PortfolioDecimal.text(clean.amount), PortfolioDecimal.text(BigDecimal("1.0000000000000000005")))
    }

    @Test
    fun gebuehrUndErhalteneMengeUnterEinemWeiFallenWeg() {
        val clean = cleanForSave(tx("1", fee = "0.0000000000000000001"))
        assertNull(clean.fee)
        assertNull(clean.feeUnit)
        val swap = runCatching { cleanForSave(tx("1", toAmount = "0.0000000000000000001", type = PortfolioTxType.SWAP)) }
        assertTrue("Tausch mit erhaltener Menge 0 muss ungültig sein", swap.isFailure)
    }

    @Test
    fun eineRundungsregelFuerRechnenUndSpeichern() {
        for (v in listOf("0.0000000000000000015", "0.0000000000000000025", "2.0000000000000000035", "0.0000000000000000005")) {
            val d = BigDecimal(v)
            assertEquals(v, 0, PortfolioDecimal.amount(d).compareTo(BigDecimal(PortfolioDecimal.text(d))))
        }
        // Halbe auf gerade
        assertEquals("0.000000000000000002", PortfolioDecimal.text(BigDecimal("0.0000000000000000015")))
        assertEquals("0.000000000000000002", PortfolioDecimal.text(BigDecimal("0.0000000000000000025")))
        assertEquals("0", PortfolioDecimal.text(BigDecimal("0.0000000000000000005")))
    }
}
