package com.cryptochecker.app.parity

import com.cryptochecker.app.data.AlarmJson
import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.alarm.AlarmReadiness
import com.cryptochecker.app.domain.alarm.AlarmReliability
import com.cryptochecker.app.domain.alarm.AlarmSnooze
import com.cryptochecker.app.domain.alarm.OemBattery
import com.cryptochecker.app.domain.refresh.CheckRunLog
import com.cryptochecker.app.ui.features.alarms.alarmPairChoices
import com.cryptochecker.app.ui.features.alarms.alarmPickerRows
import com.cryptochecker.app.ui.features.alarms.alarmReliabilityLineShown
import com.cryptochecker.app.ui.features.explorer.NameMatch
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Kleine Testklassen dieses Themas in einer Datei; jede Klasse unverändert.

// ---- aus AlarmPairPickerParityTest

/**
 * Review UX-02: Paarwahl für einen neuen Alarm — Suche wie in der Merkliste, Favoriten zuerst
 * (`testdata/parity/alarm_pair_picker.json`). Gegenstück: `Tests/AlarmPairPickerTests`. Über die
 * vorberechneten Zeilen ([alarmPickerRows]) mit gefalteten Coin-Namen.
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
        val names = fixture["names"].obj().map { (id, name) -> id.toLong() to NameMatch.fold(name as String) }.toMap()
        val rows = alarmPickerRows(watches)
        val cases = fixture["cases"].list()
        assertTrue(cases.size >= 8)
        for (raw in cases) {
            val c = raw.obj()
            val expected = c["expected"].list().map { it.num()!!.toLong() }
            assertEquals(c["name"] as String, expected, alarmPairChoices(rows, c["query"] as String, names).map { it.id })
        }
        // Ein neuer Kurs ändert die Zeilen nicht (distinctUntilChanged lässt ihn durch), ein Favorit schon
        assertEquals(rows, alarmPickerRows(watches.map { it.copy(lastPrice = 42.0, lastUpdate = 1L) }))
        assertTrue(rows != alarmPickerRows(watches.map { it.copy(favorite = !it.favorite) }))
    }
}

// ---- aus AlarmReliabilityParityTest

/**
 * Zeile über der Merkliste (`testdata/parity/alarm_reliability.json`): gelesener Zustand → ein Satz und Farbe.
 * Gegenstück: `Tests/AlarmReliabilityTests`.
 */
class AlarmReliabilityParityTest {

    private val data = ParityJson.load("alarm_reliability.json")

    private fun bool(m: Map<String, Any?>, key: String, default: Boolean): Boolean = m[key] as? Boolean ?: default

    /** Gespeicherte Läufe («at;trigger;result;dauer»), falls der Fall sie nennt. */
    private fun runs(m: Map<String, Any?>) =
        (m["runs"] as? List<*>)?.let { lines -> CheckRunLog.decode(lines.joinToString("\n")) }

    private fun input(m: Map<String, Any?>) = AlarmReadiness.Input(
        platform = if (m["platform"] == "ios") AlarmReadiness.Platform.IOS else AlarmReadiness.Platform.ANDROID,
        notifications = AlarmReadiness.Access.entries.first { it.code == m["notifications"] },
        now = m["now"].num()!!.toLong(),
        minuteOfDay = m["minuteOfDay"].num()!!.toInt(),
        lastBackgroundCheck = runs(m)?.let { CheckRunLog.lastBackgroundCheck(it) } ?: m["lastBackgroundCheck"].num()?.toLong(),
        lastFailedBackgroundCheck = runs(m)?.let { CheckRunLog.lastFailedBackgroundCheck(it) },
        storageNoKey = bool(m, "storageNoKey", false),
        hasAlarms = bool(m, "hasAlarms", false),
        channel = (m["channel"] as String?)?.let { code -> AlarmReadiness.Channel.entries.first { it.code == code } },
        batteryUnrestricted = bool(m, "batteryUnrestricted", false),
        backgroundRestricted = bool(m, "backgroundRestricted", false),
        oemVendor = if (bool(m, "oem", false)) OemBattery.Vendor.XIAOMI else null,
        backgroundRefresh = bool(m, "backgroundRefresh", true),
    )

    @Test
    fun staleLimitMatches() {
        assertEquals(data["staleMillis"].num()!!.toLong(), AlarmReliability.STALE_MILLIS)
        assertEquals(data["staleMillisIos"].num()!!.toLong(), AlarmReliability.STALE_MILLIS_IOS)
        assertEquals(AlarmReliability.STALE_MILLIS, AlarmReliability.staleMillis(AlarmReadiness.Platform.ANDROID))
        assertEquals(AlarmReadiness.STALE_CHECK_MILLIS, AlarmReliability.staleMillis(AlarmReadiness.Platform.IOS))
    }

    @Test
    fun status() {
        val cases = data["cases"].list().map { it.obj() }
        assertTrue(cases.size >= 20)
        // Jeder Satz kommt im gemeinsamen Testfall vor
        assertEquals(AlarmReliability.Kind.entries.map { it.code }.toSet(), cases.map { it["kind"] as String }.toSet())
        for (c in cases) {
            val actual = AlarmReliability.status(input(c["input"].obj()))
            assertEquals(c["name"] as String, c["kind"], actual.kind.code)
            assertEquals(c["name"] as String, c["warn"], actual.warn)
        }
    }

    @Test
    fun lineShownOnlyWhenSomethingIsWrong() {
        val expected = data["lineShownKinds"].list().map { it as String }.toSet()
        val actual = AlarmReliability.Kind.entries.filter { alarmReliabilityLineShown(it) }.map { it.code }.toSet()
        assertEquals(expected, actual)
    }
}

// ---- aus AlarmSnoozeParityTest

/** Gemeinsame Testfälle `alarm_snooze.json` — «1 h später» (iOS: `AlarmSnoozeTests.swift`). */
class AlarmSnoozeParityTest {

    private val data = ParityJson.load("alarm_snooze.json")

    @Test
    fun snoozeLastsOneHour() {
        assertEquals(data["snoozeMillis"].num()!!.toLong(), AlarmSnooze.SNOOZE_MILLIS)
        data["until"].list().map { it.obj() }.forEach { c ->
            assertEquals("until: $c", c["until"].num()!!.toLong(), AlarmSnooze.snoozeUntil(c["now"].num()!!.toLong()))
        }
    }

    @Test
    fun isSnoozed() {
        data["isSnoozed"].list().map { it.obj() }.forEach { c ->
            assertEquals(
                c["name"] as String,
                c["snoozed"],
                AlarmSnooze.isSnoozed(c["until"].num()?.toLong(), c["now"].num()!!.toLong()),
            )
        }
    }

    @Test
    fun pruneKeepsRunningPauses() {
        val c = data["prune"].obj()
        val all = c["all"].obj().entries.associate { it.key.toLong() to it.value.num()!!.toLong() }
        val kept = AlarmSnooze.prune(all, c["now"].num()!!.toLong())
        assertEquals(c["kept"].list().map { it.num()!!.toLong() }.toSet(), kept.keys)
    }

    @Test
    fun offeredOnlyWherePauseWorks() {
        val cases = data["offeredFor"].list().map { it.obj() }
        // Jede Alarm-Art muss im gemeinsamen Testfall vorkommen, damit eine neue Art nicht unbemerkt bleibt
        assertEquals(AlarmCondition.entries.map { it.name }.toSet(), cases.map { it["condition"] as String }.toSet())
        cases.forEach { c ->
            assertEquals(
                "${c["name"]} (${c["condition"]}, repeating=${c["repeating"]})",
                c["offered"],
                AlarmSnooze.offeredFor(AlarmCondition.valueOf(c["condition"] as String), c["repeating"] as Boolean),
            )
        }
    }

    @Test
    fun rearmsOnlyPriceLevels() {
        val cases = data["rearmsOnSnooze"].list().map { it.obj() }
        assertEquals(AlarmCondition.entries.map { it.name }.toSet(), cases.map { it["condition"] as String }.toSet())
        cases.forEach { c ->
            val condition = AlarmCondition.valueOf(c["condition"] as String)
            assertEquals(c["condition"] as String, c["rearms"], AlarmSnooze.rearmsOnSnooze(condition))
            // Wer wieder scharf gestellt wird, bekommt den Knopf auch bei «einmal»
            if (c["rearms"] == true) assertEquals(true, AlarmSnooze.offeredFor(condition, repeating = false))
        }
    }
}

// ---- aus AlarmToggleParityTest

/**
 * Review FUN3-02/UX3-06: «fällt um ≥ 100 %» lässt sich per Schalter nicht einschalten
 * (`testdata/parity/alarm_toggle.json`); Gegenstück: `AlarmToggleParityTests`.
 */
class AlarmToggleParityTest {

    private val data = ParityJson.load("alarm_toggle.json")

    @Test
    fun toggleMatchesFixture() {
        val cases = data["cases"].list()
        assertTrue(cases.size >= 5)
        cases.forEach { raw ->
            val c = raw.obj()
            assertEquals(
                c["name"] as String, c["allowed"],
                AlarmJson.toggleAllowed(
                    AlarmCondition.valueOf(c["condition"] as String), (c["threshold"] as Number).toDouble(),
                    enable = c["enable"] as Boolean,
                ),
            )
        }
    }

    /** Schalter (Karte, Übersicht) und Schreibweg nutzen dieselbe Regel. */
    @Test
    fun switchesAndWritePathUseTheRule() {
        fun source(path: String) = listOf(File(path), File("app/$path"), File("../data/$path"), File("data/$path"), File("../domain/$path"), File("domain/$path")).first { it.exists() }.readText()
        val base = "src/main/java/com/cryptochecker/app"
        assertTrue(source("$base/ui/features/alarms/AlarmCard.kt").contains("enabled = AlarmJson.toggleAllowed("))
        val overview = source("$base/ui/features/alarms/AlarmsOverviewScreen.kt")
        assertTrue(overview.contains("AlarmJson.toggleAllowed(") && overview.contains("enabled = canToggle"))
        val repo = source("$base/data/WatchRepository.kt").substringAfter("suspend fun setAlarmEnabled(")
        assertTrue(repo.substringBefore("watchDao.setAlarmEnabled(").contains("AlarmJson.toggleAllowed("))
    }
}
