package com.cryptochecker.app.domain.usage

import com.cryptochecker.app.domain.refresh.CheckResult
import com.cryptochecker.app.domain.refresh.CheckRun
import com.cryptochecker.app.domain.refresh.CheckRunStore
import com.cryptochecker.app.domain.refresh.CheckTrigger
import com.cryptochecker.marketdata.usage.UsageLog
import com.cryptochecker.marketdata.usage.UsageMeter
import com.cryptochecker.marketdata.usage.UsageTotals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Zähler von «Akku & Aktivität» ([UsageMeter]): bis zur Datei im Speicher, höchstens jede Minute geschrieben, sofort
 * mit `flush`; ein zweiter Prozess (neuer Zähler auf derselben Datei) sieht das Geschriebene. Läufe zählen über
 * «Letzte Prüfungen» ([CheckRunStore.add]), die offene App nicht. Wie iOS `UsageMeterTests`.
 */
class UsageMeterTest {

    private val dir: File = Files.createTempDirectory("usage").toFile()
    private var clock = 1_700_000_000_000L

    @Test
    fun zaehltImSpeicherUndSchreibtHoechstensJedeMinute() {
        val file = File(dir, UsageMeter.FILE_NAME)
        val meter = UsageMeter { clock }
        meter.countRequest(100)
        // Ohne Datei: nur im Speicher
        assertEquals(UsageTotals(0, 1, 100), meter.totals())
        meter.attach(file)
        // Erster Zähler nach dem Anhängen: gleich geschrieben (noch nie geschrieben)
        meter.countRun()
        assertTrue(file.isFile)
        assertEquals(UsageTotals(1, 1, 100), UsageMeter { clock }.apply { attach(file) }.totals())
        // Innerhalb der Minute: nur im Speicher, dieser Zähler sieht es trotzdem
        clock += 30_000
        meter.countBytes(50)
        assertEquals(UsageTotals(1, 1, 100), UsageMeter { clock }.apply { attach(file) }.totals())
        assertEquals(UsageTotals(1, 1, 150), meter.totals())
        meter.flush()
        assertEquals(UsageTotals(1, 1, 150), UsageMeter { clock }.apply { attach(file) }.totals())
        // Nach 25 h ist alles aus dem Fenster, die Datei behält es (7 Tage)
        clock += 25 * UsageLog.HOUR_MILLIS
        assertEquals(UsageTotals(0, 0, 0), meter.totals())
        assertFalse(UsageLog.decode(file.readText()).isEmpty())
    }

    @Test
    fun laeufeZaehlenUeberLetztePruefungenOhneOffeneApp() {
        val meter = UsageMeter { clock }
        meter.attach(File(dir, UsageMeter.FILE_NAME))
        val store = CheckRunStore(File(dir, CheckRunStore.FILE_NAME), meter)
        for (trigger in CheckTrigger.entries) store.add(CheckRun(clock, trigger, CheckResult.OK, 1_000))
        assertEquals(CheckTrigger.entries.size - 1, meter.totals().runs)
    }
}
