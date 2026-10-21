package com.cryptochecker.app.parity

import com.cryptochecker.app.data.BackupRestoreLimits
import com.cryptochecker.app.data.BackupWatchlistCodec
import com.cryptochecker.app.data.local.model.GROUP_NAME_MAX
import com.cryptochecker.app.data.local.model.NOTE_MAX
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grenzen beim Wiederherstellen der Merkliste (`testdata/parity/backup_limits.json`, seit Build 99): unzulässige
 * Kennungen lassen das Paar wegfallen, Gruppe und Notiz werden wie die Eingabe gesäubert, höchstens so viele Paare
 * und Alarme je Paar wie festgelegt. Gegenstück: `Tests/BackupRestoreLimitsTests.swift`.
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
                json.put(key, if (value is Double) value.toLong() else value ?: JSONObject.NULL)
            }
            val watch = BackupWatchlistCodec.jsonToWatch(json)
            assertEquals(name, c["kept"] as Boolean, BackupRestoreLimits.acceptsWatch(watch))
            assertEquals(name, c["group"] as String?, watch.groupName)
            assertEquals(name, c["note"] as String?, watch.note)
        }
    }

    /** Erste Paare der Datei bleiben; Alarme weggelassener Paare fallen weg, je Paar nur die ersten. */
    @Test
    fun hoechstensSovielePaareUndAlarmeJePaar() {
        val watches = (1L..BackupRestoreLimits.MAX_PAIRS + 5L).toList()
        val alarms = (1..BackupRestoreLimits.MAX_ALARMS_PER_PAIR + 3).map { 1L to it } +
            listOf(2L to 1, (BackupRestoreLimits.MAX_PAIRS + 1L) to 1)
        val (keptWatches, keptAlarms) = BackupRestoreLimits.limit(watches, alarms, watchId = { it }, alarmWatchId = { it.first })
        assertEquals(BackupRestoreLimits.MAX_PAIRS, keptWatches.size)
        assertEquals(BackupRestoreLimits.MAX_PAIRS.toLong(), keptWatches.last())
        assertEquals(BackupRestoreLimits.MAX_ALARMS_PER_PAIR, keptAlarms.count { it.first == 1L })
        assertEquals((1..BackupRestoreLimits.MAX_ALARMS_PER_PAIR).toList(), keptAlarms.filter { it.first == 1L }.map { it.second })
        assertEquals(listOf(2L to 1), keptAlarms.filter { it.first == 2L })
        assertTrue(keptAlarms.none { it.first > BackupRestoreLimits.MAX_PAIRS })
    }
}
