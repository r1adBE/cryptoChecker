package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.exceptions.ExchangeParseException
import com.cryptochecker.marketdata.model.ReferenceSource
import com.cryptochecker.marketdata.util.EcbRates
import com.cryptochecker.marketdata.util.ReferenceRates

/**
 * Referenzkurse der Polnischen Nationalbank (api.nbp.pl), wöchentlich (Tabelle B, mittwochs), für alle übrigen
 * Währungen (NGN, ARS, KES …), je gegen USD und EUR — Kreuzkurse aus den PLN-Kursen; USD und EUR aus Tabelle A
 * desselben `effectiveDate`. Zwei Abfragen (A, B) zusammen gelesen, gültig bis zur nächsten Tabelle B. Veränderung
 * gegen die Vorwoche. iOS: `NbpReference.swift`.
 */
class NbpReference : BankReference("NBP", "N B P") {
    override val key = KEY
    override val source = ReferenceSource.NBP

    override fun responseAddresses(now: Long): List<String> =
        listOf(ReferenceRates.NBP_TABLE_A_URL, ReferenceRates.NBP_TABLE_B_URL)

    override fun validUntil(bodies: List<String>, now: Long): Long = ReferenceRates.nbpValidUntil(
        bodies.getOrNull(0)?.let(ReferenceRates::parseNbp), bodies.getOrNull(1)?.let(ReferenceRates::parseNbp), now,
    )

    override fun publications(bodies: List<String>): List<Map<String, Double>>? {
        val tableB = bodies.getOrNull(1)?.let(ReferenceRates::parseNbp) ?: return null
        return ReferenceRates.nbpPublications(bodies.getOrNull(0)?.let(ReferenceRates::parseNbp), tableB)
    }

    override val pairListAddresses = listOf(
        EcbRates.DAILY_URL, ReferenceRates.NBU_LIST_URL, ReferenceRates.NBP_LIST_B_URL, ReferenceRates.NBP_LIST_A_URL,
    )

    /** Codes der NBP = Tabelle B samt den Währungen der Tabelle A, die sonst niemand führt (CLP). */
    override fun listedCodes(bodies: List<String>): Triple<Set<String>, Set<String>, Set<String>> {
        val nbu = bodies.getOrNull(1)?.let(ReferenceRates::nbuCodes)
            ?: throw ExchangeParseException(ExchangeParseException.NO_DATA)
        val tableB = bodies.getOrNull(2)?.let(ReferenceRates::parseNbp)
            ?: throw ExchangeParseException(ExchangeParseException.NO_DATA)
        val tableA = bodies.getOrNull(3)?.let(ReferenceRates::parseNbp).orEmpty()
        val nbp = (tableB.values + tableA.values).flatMap { it.keys }.toSet()
        return Triple(ecbCodes(bodies.getOrNull(0)), nbu, nbp)
    }

    companion object {
        const val KEY = "NbpReference"
    }
}
