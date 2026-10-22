package com.cryptochecker.app.parity

import com.cryptochecker.app.data.BackupRestoreLimits
import com.cryptochecker.app.data.BackupWatchlistCodec
import com.cryptochecker.app.data.local.model.GROUP_NAME_MAX
import com.cryptochecker.app.data.local.model.NOTE_MAX
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grenzen beim Wiederherstellen der Merkliste (`testdata/parity/backup_limits.json`, seit Build 99): unzulässige
 * Kennungen lassen das Paar wegfallen, Gruppe und Notiz werden wie die Eingabe gesäubert, höchstens so viele Paare
 * und Alarme je Paar wie festgelegt; Weggelassenes wird gezählt (Rückfrage und Erfolgsmeldung). Gegenstück: `Tests/BackupRestoreLimitsTests.swift`.
 */
class BackupRestoreLimitsParityTest {

    private val fixture = ParityJson.load("backup_limits.json")

    @Test
    fun grenzenWieGemeinsameTestfaelle() {
        val limits = fixture["limits"].obj()
        assertEquals(limits["maxMarketKey"].num()!!.toInt(), BackupRestoreLimits.MAX_MARKET_KEY)
        assertEquals(limits["maxMarketName"].num()!!.toInt(), BackupRestoreLimits.MAX_MARKET_NAME)
        assertEquals(limits["maxAsset"].num()!!.toInt(), BackupRestoreLimits.MAX_ASSET)
        assertEquals(limits["maxPairId"].num()!!.toInt(), BackupRestoreLimits.MAX_PAIR_ID)
        assertEquals(limits["maxPairs"].num()!!.toInt(), BackupRestoreLimits.MAX_PAIRS)
        assertEquals(limits["maxAlarmsPerPair"].num()!!.toInt(), BackupRestoreLimits.MAX_ALARMS_PER_PAIR)
        assertEquals(limits["groupNameMax"].num()!!.toInt(), GROUP_NAME_MAX)
        assertEquals(limits["noteMax"].num()!!.toInt(), NOTE_MAX)
    }

    @Test
    fun paareWieGemeinsameTestfaelle() {
        val cases = fixture["watches"].list()
        assertTrue(cases.size >= 20)
        for (raw in cases) {
            val c = raw.obj()
            val name = c["description"] as String
            val json = JSONObject()
            c["watch"].obj().forEach { (key, value) ->
                // Ganze Zahlen als Long (Ids); Bruchzahlen wie ein Bestand 0.5 bleiben Double (seit Build 103)
                json.put(key, if (value is Double && value % 1.0 == 0.0) value.toLong() else value ?: JSONObject.NULL)
            }
            val watch = BackupWatchlistCodec.jsonToWatch(json)
            assertEquals(name, c["kept"] as Boolean, BackupRestoreLimits.acceptsWatch(watch))
            assertEquals(name, c["group"] as String?, watch.groupName)
            assertEquals(name, c["note"] as String?, watch.note)
            // Seit Build 103: Bestand nur endlich und > 0 (wie iOS `Watch.validHoldings`)
            if (c.containsKey("expectedHoldings")) assertEquals(name, c["expectedHoldings"].num(), watch.holdings)
        }
    }

    /** Erste Paare der Datei bleiben; Alarme weggelassener Paare fallen weg, je Paar nur die ersten. */
    @Test
    fun hoechstensSovielePaareUndAlarmeJePaar() {
        val watches = (1L..BackupRestoreLimits.MAX_PAIRS + 5L).toList()
        val alarms = (1..BackupRestoreLimits.MAX_ALARMS_PER_PAIR + 3).map { 1L to it } +
            listOf(2L to 1, (BackupRestoreLimits.MAX_PAIRS + 1L) to 1)
        val limited = BackupRestoreLimits.limit(watches, alarms, watchId = { it }, alarmWatchId = { it.first })
        val (keptWatches, keptAlarms) = limited
        assertEquals(BackupRestoreLimits.MAX_PAIRS, keptWatches.size)
        assertEquals(BackupRestoreLimits.MAX_PAIRS.toLong(), keptWatches.last())
        assertEquals(BackupRestoreLimits.MAX_ALARMS_PER_PAIR, keptAlarms.count { it.first == 1L })
        assertEquals((1..BackupRestoreLimits.MAX_ALARMS_PER_PAIR).toList(), keptAlarms.filter { it.first == 1L }.map { it.second })
        assertEquals(listOf(2L to 1), keptAlarms.filter { it.first == 2L })
        assertTrue(keptAlarms.none { it.first > BackupRestoreLimits.MAX_PAIRS })
        // Gezählt für Rückfrage und Erfolgsmeldung: 5 Paare zu viel, 3 Alarme über der Grenze und 1 eines weggelassenen Paars
        assertEquals(5, limited.droppedPairs)
        assertEquals(4, limited.droppedAlarms)
    }

    /** Paare mit unzulässiger Kennung fallen samt ihren Alarmen weg und werden gezählt; Alarme ohne Paar nicht. */
    @Test
    fun unzulaessigePaareSamtAlarmenGezaehlt() {
        val accepted = BackupRestoreLimits.accept(
            watches = listOf(1L, 2L, 3L),
            alarms = listOf(1L to 1, 2L to 1, 2L to 2, 9L to 1),
            accepts = { it != 2L },
            watchId = { it },
            alarmWatchId = { it.first },
        )
        assertEquals(listOf(1L, 3L), accepted.watches)
        assertEquals(listOf(1L to 1, 9L to 1), accepted.alarms)
        assertEquals(1, accepted.droppedPairs)
        assertEquals(2, accepted.droppedAlarms)
    }

    /**
     * Ganze Merkliste wie beim Wiederherstellen (lesen, bereinigen, begrenzen): behaltene und wegen der Grenzen
     * weggelassene Paare und Alarme wie in den gemeinsamen Testfällen «limitCases».
     */
    @Test
    fun weggelasseneWieGemeinsameTestfaelle() {
        val cases = fixture["limitCases"].list()
        assertTrue(cases.size >= 5)
        for (raw in cases) {
            val c = raw.obj()
            val name = c["description"] as String
            val watchlist = BackupWatchlistCodec.readWatchlist(limitCaseRoot(c))
            assertEquals(name, c["keptPairs"].num()!!.toInt(), watchlist.watches.size)
            assertEquals(name, c["keptAlarms"].num()!!.toInt(), watchlist.alarms.size)
            assertEquals(name, c["droppedPairs"].num()!!.toInt(), watchlist.droppedPairs)
            assertEquals(name, c["droppedAlarms"].num()!!.toInt(), watchlist.droppedAlarms)
            assertEquals(name, 0, watchlist.invalidAlarms)
        }
    }

    /** Sicherung zu einem Fall aus «limitCases» (Aufbau siehe «description» der Testfälle). */
    private fun limitCaseRoot(c: Map<String, Any?>): JSONObject {
        val invalid = c["invalidPairIds"].list().map { it.num()!!.toLong() }.toSet()
        val sameAs = c["duplicates"].list().associate { d -> d.list().let { it[0].num()!!.toLong() to it[1].num()!!.toLong() } }
        val watches = JSONArray()
        for (id in 1L..c["pairs"].num()!!.toLong()) {
            val base = "B${sameAs[id] ?: id}"
            watches.put(
                JSONObject()
                    .put("id", id)
                    .put("marketKey", "Binance")
                    .put("marketName", "Binance")
                    .put("baseAsset", base)
                    .put("quoteAsset", "USDT")
                    .put("contractType", "NONE")
                    .put("pairId", if (id in invalid) "$base USDT" else "${base}USDT")
            )
        }
        val alarms = JSONArray()
        var alarmId = 0L
        for (spec in c["alarms"].list()) {
            val (watchId, count) = spec.list().map { it.num()!!.toLong() }
            repeat(count.toInt()) {
                alarms.put(JSONObject().put("id", ++alarmId).put("watchId", watchId).put("condition", "PRICE_ABOVE").put("threshold", 1.0))
            }
        }
        return JSONObject().put("watches", watches).put("alarms", alarms)
    }
}
