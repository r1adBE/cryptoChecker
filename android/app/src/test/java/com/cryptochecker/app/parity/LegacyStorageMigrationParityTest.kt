package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.portfolio.LegacyStorageMigration
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.TaxEngines
import com.cryptochecker.app.domain.portfolio.csvimport.CsvRestoreState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Gemeinsame Testfälle `legacy_storage_migration.json` (iOS: `LegacyStorageMigrationTests.swift`). */
class LegacyStorageMigrationParityTest {

    private val data = ParityJson.load("legacy_storage_migration.json")

    private fun fields(o: Map<String, Any?>) = LegacyStorageMigration.Fields(
        transferTo = o["transferTo"] as String?,
        giftToSpouse = o["giftToSpouse"] == true,
        importKey = o["importKey"] as String?,
        linkedImportKey = o["linkedImportKey"] as String?,
        importRestoreState = o["importRestoreState"]?.let { CsvRestoreState.fromJson(JSONObject(json(it))) },
    )

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

    /** Vergleichbare Form der Felder: Zustand als JSON-Struktur (Reihenfolge der Schlüssel egal). */
    private fun comparable(f: LegacyStorageMigration.Fields) = listOf(
        f.transferTo, f.giftToSpouse, f.importKey, f.linkedImportKey, f.importRestoreState?.let { ParityJson.parse(it.encode()) },
    )

    @Test
    fun legacyFormsBecomeFields() {
        data["cases"].list().map { it.obj() }.forEach { c ->
            val before = c["before"].obj()
            val after = c["after"].obj()
            val expected = listOf(
                after["transferTo"], after["giftToSpouse"] == true, after["importKey"], after["linkedImportKey"],
                after["importRestoreState"],
            )
            val converted = LegacyStorageMigration.convert(PortfolioTxType.fromName(c["type"] as String), fields(before))
            assertEquals(c["name"] as String, expected, comparable(converted))
            // Beliebig oft anwendbar: ein zweiter Lauf ändert nichts mehr
            assertEquals("${c["name"]} idempotent", converted,
                LegacyStorageMigration.convert(PortfolioTxType.fromName(c["type"] as String), converted))
        }
    }

    @Test
    fun restoreStateRoundTripsAndRejectsInvalid() {
        data["cases"].list().mapNotNull { it.obj()["after"].obj()["importRestoreState"] }.forEach { e ->
            val state = CsvRestoreState.fromJson(JSONObject(json(e)))!!
            assertEquals(e, ParityJson.parse(state.encode()))
            assertEquals(state, CsvRestoreState.decode(state.encode()))
        }
        data["restoreStateInvalid"].list().map { it.obj() }.forEach { c ->
            assertNull(c["name"] as String, CsvRestoreState.decode(json(c["json"])))
        }
        // Unsinnig langer Text (Sicherung von aussen) wird nicht gelesen
        assertNull(CsvRestoreState.decode(" ".repeat(CsvRestoreState.MAX_TEXT + 1)))
    }

    /**
     * Neue Eingaben: Zins nur bei ganzen Wörtern («Verzinsung», «Zinseszins» nicht). Altbestand (Migration, Sicherungen
     * ohne Feld): eingefrorene Teilstring-Regel, damit sich alte Daten nicht nachträglich ändern.
     */
    @Test
    fun interestRewardMatchesWholeWords() {
        data["interestReward"].list().map { it.obj() }.forEach { c ->
            val text = c["text"] as String?
            assertEquals("interestReward: $text", c["interest"], TaxEngines.interestReward(text))
            assertEquals("interestRewardLegacy: $text", c["legacyInterest"], LegacyStorageMigration.interestRewardLegacy(text))
            assertEquals("legacyInterest: $text", c["legacyInterest"], LegacyStorageMigration.legacyInterest(PortfolioTxType.REWARD, text))
            assertEquals("legacyInterest BUY: $text", false, LegacyStorageMigration.legacyInterest(PortfolioTxType.BUY, text))
        }
    }
}
