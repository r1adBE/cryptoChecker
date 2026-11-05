package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.exceptions.ExchangeParseException
import com.cryptochecker.marketdata.model.ReferenceSource
import com.cryptochecker.marketdata.util.EcbRates
import com.cryptochecker.marketdata.util.ReferenceRates

/**
 * Referenzkurse der Nationalbank der Ukraine (bank.gov.ua), täglich, für die Währungen, die die EZB nicht führt (AED,
 * EGP, KZT, UAH …), je gegen USD und EUR — Kreuzkurse aus den UAH-Kursen derselben Veröffentlichung. Eine Abfrage über
 * die letzten Tage ([ReferenceRates.nbuRangeUrl], Kiewer Datum), gültig bis Mitternacht in Kiew. iOS: `NbuReference.swift`.
 */
class NbuReference : BankReference("NBU", "N B U") {
    override val key = KEY
    override val source = ReferenceSource.NBU

    override fun responseAddresses(now: Long): List<String> =
        listOf(ReferenceRates.nbuRangeUrl(ReferenceRates.dateIn(now, ReferenceRates.KYIV)))

    override fun validUntil(bodies: List<String>, now: Long): Long =
        ReferenceRates.nbuValidUntil(bodies.firstOrNull()?.let(ReferenceRates::parseNbuRange), now)

    override fun publications(bodies: List<String>): List<Map<String, Double>>? =
        bodies.firstOrNull()?.let(ReferenceRates::parseNbuRange)?.let(ReferenceRates::nbuPublications)

    override val pairListAddresses = listOf(EcbRates.DAILY_URL, ReferenceRates.NBU_LIST_URL)

    override fun listedCodes(bodies: List<String>): Triple<Set<String>, Set<String>, Set<String>> {
        val nbu = bodies.getOrNull(1)?.let(ReferenceRates::nbuCodes)
            ?: throw ExchangeParseException(ExchangeParseException.NO_DATA)
        return Triple(ecbCodes(bodies.getOrNull(0)), nbu, emptySet())
    }

    companion object {
        const val KEY = "NbuReference"
    }
}
