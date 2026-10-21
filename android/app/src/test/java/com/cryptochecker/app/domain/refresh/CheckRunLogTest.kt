package com.cryptochecker.app.domain.refresh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wie `CheckRunLogTests` (iOS). */
class CheckRunLogTest {

    private fun run(at: Long, trigger: CheckTrigger = CheckTrigger.WORKER, result: CheckResult = CheckResult.OK) =
        CheckRun(at, trigger, result, 1_200)

    @Test
    fun ringBufferKeepsNewestTwenty() {
        var runs = emptyList<CheckRun>()
        for (i in 1..25) runs = CheckRunLog.add(runs, run(i.toLong()))
        assertEquals(CheckRunLog.MAX, runs.size)
        assertEquals(25L, runs.first().at)
        assertEquals(6L, runs.last().at)
    }

    @Test
    fun addPrepends() {
        val runs = CheckRunLog.add(listOf(run(1)), run(2, CheckTrigger.EXACT))
        assertEquals(listOf(2L, 1L), runs.map { it.at })
        assertEquals(CheckTrigger.EXACT, runs.first().trigger)
    }

    @Test
    fun resultFromCounts() {
        assertEquals(CheckResult.OK, CheckRunLog.result(checked = 0, failed = 0))
        assertEquals(CheckResult.OK, CheckRunLog.result(checked = 5, failed = 0))
        assertEquals(CheckResult.PARTIAL, CheckRunLog.result(checked = 5, failed = 2))
        assertEquals(CheckResult.FAILED, CheckRunLog.result(checked = 5, failed = 5))
    }

    /** «Letzte Prüfung»: nur geplante Läufe, die nicht gescheitert sind; übersprungene zählen. Wie iOS. */
    @Test
    fun lastBackgroundCheckSkipsFailedAndUnscheduledRuns() {
        val runs = listOf(
            run(50, CheckTrigger.WORKER, CheckResult.FAILED),
            run(40, CheckTrigger.WIDGET),
            run(30, CheckTrigger.FOREGROUND),
            run(20, CheckTrigger.EXACT, CheckResult.SKIPPED),
            run(10),
        )
        assertEquals(20L, CheckRunLog.lastBackgroundCheck(runs))
        val onlyFailed = listOf(run(5, CheckTrigger.WORKER, CheckResult.FAILED), run(4, CheckTrigger.WIDGET))
        assertEquals(null, CheckRunLog.lastBackgroundCheck(onlyFailed))
        assertEquals(null, CheckRunLog.lastBackgroundCheck(emptyList()))
        assertEquals(7L, CheckRunLog.lastBackgroundCheck(listOf(run(7, CheckTrigger.WORKER, CheckResult.PARTIAL))))
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val runs = listOf(
            CheckRun(1_700_000_000_000, CheckTrigger.EXACT, CheckResult.PARTIAL, 2_345),
            CheckRun(1_699_999_000_000, CheckTrigger.BG_TASK, CheckResult.SKIPPED, 0),
            CheckRun(1_699_998_000_000, CheckTrigger.WIDGET, CheckResult.FAILED, 15_000),
            CheckRun(1_699_997_000_000, CheckTrigger.FOREGROUND, CheckResult.OK, 800),
        )
        val text = CheckRunLog.encode(runs)
        assertEquals("1700000000000;exact;partial;2345", text.lines().first())
        assertEquals(runs, CheckRunLog.decode(text))
    }

    @Test
    fun decodeSkipsBadLines() {
        val text = "1;worker;ok;5\nkaputt\n2;unknown;ok;5\n3;worker;meh;5\n4;worker;ok\n\n5;exact;failed;-3"
        val runs = CheckRunLog.decode(text)
        assertEquals(listOf(1L, 5L), runs.map { it.at })
        assertEquals(0L, runs.last().durationMillis)
        assertTrue(CheckRunLog.decode(null).isEmpty())
        assertTrue(CheckRunLog.decode("").isEmpty())
    }

    @Test
    fun decodeCapsAtMax() {
        val text = (1..30).joinToString("\n") { "$it;worker;ok;1" }
        assertEquals(CheckRunLog.MAX, CheckRunLog.decode(text).size)
    }
}
