package com.cryptochecker.app.parity

import com.cryptochecker.app.data.portfolio.PortfolioJson
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.util.json.JsonObject
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.TaxEngines
import com.cryptochecker.app.domain.portfolio.csvimport.CsvRestoreState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

/** Gemeinsame Testfälle `portfolio_stored_fields.json` (iOS: `PortfolioStoredFieldsTests.swift`). */
class PortfolioStoredFieldsParityTest {

    private val data = ParityJson.load("portfolio_stored_fields.json")

    /** Fixture-Wert zurück in JSON-Text (für org.json). */
    private fun json(v: Any?): String = when (v) {
        null -> "null"
        is String -> JSONObject.quote(v)
        is Boolean -> v.toString()
        is Double -> if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { (k, x) -> JSONObject.quote(k as String) + ":" + json(x) }
        is List<*> -> v.joinToString(",", "[", "]") { json(it) }
        else -> error("unexpected $v")
    }

    @Test
    fun restoreStateRoundTripsAndRejectsInvalid() {
        data["restoreStates"].list().map { it.obj() }.forEach { c ->
            val e = c["json"]
            val state = CsvRestoreState.fromJson(JsonObject(json(e)))!!
            assertEquals(c["name"] as String, e, ParityJson.parse(state.encode()))
            assertEquals(state, CsvRestoreState.decode(state.encode()))
        }
        data["restoreStateInvalid"].list().map { it.obj() }.forEach { c ->
            assertNull(c["name"] as String, CsvRestoreState.decode(json(c["json"])))
        }
        // Unsinnig langer Text (Sicherung von aussen) wird nicht gelesen
        assertNull(CsvRestoreState.decode(" ".repeat(CsvRestoreState.MAX_TEXT + 1)))
    }

    /**
     * Sicherung: Mengen, Gebühren und Werte gerundet wie gespeichert, eine Menge 0 nach dem Runden ungültig; Preise nach
     * der Regel der Preise («backupAmounts»).
     */
    @Test
    fun backupAmountsRoundLikeStored() {
        val base = PortfolioTxEntity(id = 1, coin = "BTC", type = PortfolioTxType.BUY, amount = BigDecimal.ONE, time = 1L)
        data["backupAmounts"].list().map { it.obj() }.forEach { c ->
            val name = c["name"] as String
            val o = PortfolioJson.txToJson(base)
            c["changes"].obj().forEach { (key, value) -> o.put(key, value) }
            val tx = PortfolioJson.jsonToTx(o)
            val expected = c["expected"]
            if (expected == null) {
                assertNull(name, tx)
            } else {
                val back = PortfolioJson.txToJson(tx!!)
                expected.obj().forEach { (key, value) ->
                    assertEquals("$name $key", value, back.opt(key).takeIf { it != JSONObject.NULL })
                }
            }
        }
    }

    /** Zins nur bei ganzen Wörtern («Verzinsung», «Zinseszins» nicht). */
    @Test
    fun interestRewardMatchesWholeWords() {
        data["interestReward"].list().map { it.obj() }.forEach { c ->
            val text = c["text"] as String?
            assertEquals("interestReward: $text", c["interest"], TaxEngines.interestReward(text))
        }
    }
}
