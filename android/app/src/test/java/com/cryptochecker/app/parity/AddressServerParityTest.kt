package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.portfolio.AddressBalance
import com.cryptochecker.app.domain.portfolio.AddressServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Eigener Bitcoin-Server für Adressabfragen (`testdata/parity/address_server.json`): Prüfung der Eingabe, Adress-URL,
 * «Testen», Anbieter-Name, Zustimmung vor dem ersten xpub und Übernahme aus der Sicherung. Gegenstück:
 * `Tests/AddressServerParityTests.swift`.
 */
class AddressServerParityTest {

    private val fixture = ParityJson.load("address_server.json")

    @Test
    fun pruefungWieGemeinsameFaelle() {
        val cases = fixture["check"].list().map { it.obj() }
        assertTrue(cases.size > 30)
        for (c in cases) {
            val input = c["input"] as String
            val result = AddressServer.check(input)
            assertEquals("url: $input", c["url"], result.url)
            assertEquals("problem: $input", c["problem"], result.problem?.name)
        }
    }

    @Test
    fun urlsUndTest() {
        for (c in fixture["urls"].list().map { it.obj() }) {
            assertEquals(c["url"], AddressServer.btcUrl(c["entry"] as String, c["address"] as String))
        }
        val test = fixture["testUrl"].obj()
        assertEquals(test["url"], AddressServer.testUrl(test["server"] as String))
        for (c in fixture["tip"].list().map { it.obj() }) {
            assertEquals("tip: ${c["body"]}", c["height"].num()?.toLong(), AddressServer.parseTipHeight(c["body"] as String))
        }
    }

    @Test
    fun anbieterName() {
        for (c in fixture["providers"].list().map { it.obj() }) {
            assertEquals(c["name"], AddressBalance.providerName(c["host"] as String))
        }
    }

    @Test
    fun zustimmungVorDemErstenXpub() {
        for (c in fixture["consent"].list().map { it.obj() }) {
            val needed = AddressServer.consentNeeded(
                c["addresses"].num()!!.toInt(), c["xpubs"].num()!!.toInt(), c["newIsKey"] as Boolean,
            )
            assertEquals(c.toString(), c["needed"], needed)
        }
    }

    @Test
    fun uebernahmeAusDerSicherung() {
        for (c in fixture["restore"].list().map { it.obj() }) {
            val result = AddressServer.restored(
                c["present"] as Boolean, c["value"] as String?, c["current"] as String?, c["trusted"] as Boolean,
            )
            assertEquals(c.toString(), c["result"], result)
        }
    }

    /** Gesetzt: nur der eigene Server, kein Rückfall auf öffentliche Hosts. */
    @Test
    fun eigenerServerOhneRueckfall() {
        assertEquals(listOf("https://node.example.org/api"), AddressServer.btcEntries("https://node.example.org/api"))
        assertEquals(AddressBalance.BTC_HOSTS, AddressServer.btcEntries(null))
        assertNull(AddressServer.check("http://node.example.org").url)
    }
}
