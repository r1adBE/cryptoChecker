package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.portfolio.AddressServerRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regeln zum eigenen Bitcoin-Server (`testdata/parity/address_server_ui.json`): Eingabe kürzen, Pausen-Schlüssel,
 * Sperr-Ausnahme, Testergebnis zur Eingabe, Rückfrage vor dem Wechsel auf öffentliche Knoten. Gegenstück:
 * `Tests/AddressServerRulesParityTests.swift`.
 */
class AddressServerRulesParityTest {

    private val fixture = ParityJson.load("address_server_ui.json")

    private fun cases(key: String) = fixture[key].list().map { it.obj() }.also { assertTrue(key, it.isNotEmpty()) }

    @Test
    fun eingabeWirdGekuerzt() {
        for (c in cases("clamp")) {
            assertEquals(c["output"], AddressServerRules.clamp(c["input"] as String))
        }
    }

    @Test
    fun hostSchluesselUndEigenerServer() {
        for (c in cases("hostKey")) {
            assertEquals(c["key"], AddressServerRules.hostKey(c["entry"] as String))
        }
        for (c in cases("own")) {
            @Suppress("UNCHECKED_CAST")
            val entries = c["entries"] as List<String>
            assertEquals("own: $entries", c["own"], AddressServerRules.isOwnServer(entries))
        }
    }

    @Test
    fun testergebnisNurZurEingabe() {
        for (c in cases("testShown")) {
            assertEquals("$c", c["shown"], AddressServerRules.testShown(c["tested"] as String?, c["input"] as String?))
        }
    }

    @Test
    fun rueckfrageVorOeffentlichenKnoten() {
        for (c in cases("confirmPublic")) {
            val ask = AddressServerRules.confirmPublicSwitch(c["current"] as String?, c["next"] as String?, c["btcCount"].num()!!.toInt())
            assertEquals("$c", c["ask"], ask)
        }
    }
}
