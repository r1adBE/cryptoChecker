package com.cryptochecker.app.parity

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.ui.features.alarms.alarmPairChoices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seit Build 103 (Review UX-02): Paarwahl für einen neuen Alarm — Suche wie in der Merkliste, Favoriten zuerst
 * (`testdata/parity/alarm_pair_picker.json`). Gegenstück: `Tests/AlarmPairPickerTests.swift`.
 */
class AlarmPairPickerParityTest {

    private val fixture = ParityJson.load("alarm_pair_picker.json")

    @Test
    fun auswahlWieGemeinsameTestfaelle() {
        val watches = fixture["watches"].list().map { raw ->
            val w = raw.obj()
            WatchEntity(
                id = w["id"].num()!!.toLong(),
                marketKey = (w["marketName"] as String).lowercase(),
                marketName = w["marketName"] as String,
                baseAsset = w["base"] as String,
                quoteAsset = w["quote"] as String,
                favorite = w["favorite"] as Boolean,
                note = w["note"] as String?,
            )
        }
        val names = fixture["names"].obj().map { (id, name) -> id.toLong() to name as String }.toMap()
        val cases = fixture["cases"].list()
        assertTrue(cases.size >= 8)
        for (raw in cases) {
            val c = raw.obj()
            val expected = c["expected"].list().map { it.num()!!.toLong() }
            assertEquals(c["name"] as String, expected, alarmPairChoices(watches, c["query"] as String, names).map { it.id })
        }
    }
}
