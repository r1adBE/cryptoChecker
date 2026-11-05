package com.cryptochecker.app.contract

import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.exchange.NbpReference
import com.cryptochecker.marketdata.model.exchange.NbuReference
import org.junit.Assert.assertEquals
import org.junit.Test

/** Gemeinsame Paarliste der Notenbanken: jede Währung aus genau einer Quelle. Hier mit echtem org.json (nicht in :marketdata). */
class BankReferencePairListTest {

    @Test
    fun eachCurrencyComesFromOneSource() {
        val nbu = NbuReference()
        val nbp = NbpReference()
        // Paarliste: AED kommt von der NBU (die EZB führt es nicht), nie zusätzlich von der NBP
        val ecbDaily = "<Cube><Cube time='2026-11-04'><Cube currency='USD' rate='1.16'/><Cube currency='PLN' rate='4.24'/></Cube></Cube>"
        val nbuList = "[{\"cc\":\"AED\",\"rate\":12.4},{\"cc\":\"USD\",\"rate\":45.5},{\"cc\":\"XAU\",\"rate\":170000}]"
        val tableB = "[{\"effectiveDate\":\"2026-11-04\",\"rates\":[{\"code\":\"AED\",\"mid\":1.06},{\"code\":\"NGN\",\"mid\":0.0029}]}]"
        val nbuPairs = ArrayList<ExchangePair>()
        nbu.readPairListCombined(listOf(ecbDaily, nbuList), nbuPairs)
        assertEquals(listOf("AEDEUR", "AEDUSD", "UAHEUR", "UAHUSD"), nbuPairs.map { it.pairId }.sortedBy { it })
        val nbpPairs = ArrayList<ExchangePair>()
        nbp.readPairListCombined(listOf(ecbDaily, nbuList, tableB, "[]"), nbpPairs)
        assertEquals(listOf("NGNEUR", "NGNUSD"), nbpPairs.map { it.pairId }.sortedBy { it })
    }
}
