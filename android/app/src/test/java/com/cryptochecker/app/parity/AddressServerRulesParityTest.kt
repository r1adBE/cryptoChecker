package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.portfolio.AddressChain
import com.cryptochecker.app.domain.portfolio.AddressServer
import com.cryptochecker.app.domain.portfolio.AddressServerRules
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
            val output = AddressServerRules.clamp(c["input"] as String)
            assertEquals(c["output"], output)
            // Gekürzte Eingabe gilt nie still als gültig: 201/250 Zeichen → INVALID, genau 200 → gültig (auch nach führendem Leerraum)
            assertEquals("Länge ${output.length}", c["problem"], AddressServer.check(output).problem?.name)
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
            // «chains» statt «btcCount»: Zahl aus der Liste (null = nicht lesbar → fragen)
            val count = if (c.containsKey("btcCount")) c["btcCount"].num()!!.toInt() else AddressServerRules.btcCount(addresses(c["chains"]))
            val ask = AddressServerRules.confirmPublicSwitch(c["current"] as String?, c["next"] as String?, count)
            assertEquals("$c", c["ask"], ask)
        }
    }

    @Test
    fun bitcoinAnzahlListeNichtLesbarFragt() {
        for (c in cases("btcCount")) {
            assertEquals("$c", c["count"].num()!!.toInt(), AddressServerRules.btcCount(addresses(c["chains"])))
        }
    }

    /** Eigener Bitcoin-Server immer ausdrücklich: kein Standardwert, der still öffentliche Knoten wählt. */
    @Test
    fun keinStandardwertFuerBitcoinServer() {
        val path = "src/main/java/com/cryptochecker/app/data/portfolio/WatchedAddressSource.kt"
        val text = listOf(File(path), File("app/$path")).first { it.exists() }.readText()
        assertFalse(text.contains("btcServer: String? = null"))
    }

    private fun addresses(chains: Any?): List<WatchedAddress>? = (chains as List<*>?)?.mapIndexed { i, chain ->
        WatchedAddress(id = i + 1L, chain = AddressChain.valueOf(chain as String), address = "a$i")
    }
}
