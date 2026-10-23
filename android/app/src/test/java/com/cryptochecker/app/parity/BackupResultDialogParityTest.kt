package com.cryptochecker.app.parity

import com.cryptochecker.app.R
import com.cryptochecker.app.data.BackupRestorePolicy
import com.cryptochecker.app.ui.features.settings.BackupMessage
import com.cryptochecker.app.ui.features.settings.BackupResultPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ergebnis von Sichern/Wiederherstellen als Dialog oder Toast (`testdata/parity/backup_result_dialog.json`).
 * Gegenstück: `Tests/BackupResultDialogParityTests.swift`.
 */
class BackupResultDialogParityTest {

    private val fixture = ParityJson.load("backup_result_dialog.json")

    @Test
    fun entscheidetWieGemeinsameTestfaelle() {
        val cases = fixture["cases"].list()
        assertTrue(cases.size >= 35)
        for (raw in cases) {
            val c = raw.obj()
            val name = c["description"] as String
            val note = (c["addressServerNote"] as String?)?.let { BackupRestorePolicy.AddressServerNote.valueOf(it) }
            val actual = BackupResultPresentation.asDialog(
                key = c["key"] as String,
                text = c["text"] as String,
                droppedPairs = c["droppedPairs"].num()!!.toInt(),
                droppedAlarms = c["droppedAlarms"].num()!!.toInt(),
                appLockKept = c["appLockKept"] as Boolean,
                addressServerNote = note,
                fontScale = (c["fontScale"].num() ?: 1.0).toFloat(),
            )
            assertEquals(name, c["expected"] as String, if (actual) "dialog" else "toast")
        }
    }

    @Test
    fun grenzeUndSchluesselWieGemeinsameTestfaelle() {
        assertEquals(fixture["toastMaxChars"].num()!!.toInt(), BackupResultPresentation.TOAST_MAX_CHARS)
        assertEquals(fixture["longKeys"].list().map { it as String }.toSet(), BackupResultPresentation.LONG_KEYS)
        val ranges = fixture["wideRanges"].list().map { r -> r.list().map { (it as String).toInt(16) } }
        assertEquals(ranges, BackupResultPresentation.WIDE_RANGES.map { listOf(it.first, it.last) })
    }

    /** Breite: schmal 1, breit 2 (auch ausserhalb der BMP); Grenze sinkt mit grösserer Schrift, steigt nie über 80. */
    @Test
    fun breiteUndGrenze() {
        assertEquals(3, BackupResultPresentation.displayWidth("abc"))
        assertEquals(4, BackupResultPresentation.displayWidth("日本"))
        assertEquals(2, BackupResultPresentation.displayWidth("\uD840\uDC00"))
        assertEquals(1, BackupResultPresentation.displayWidth("\uFF71"))
        assertEquals(80.0, BackupResultPresentation.toastLimit(0.85f), 0.0)
        assertEquals(40.0, BackupResultPresentation.toastLimit(2f), 0.0)
    }

    /** Die langen Fehlermeldungen zeigen genau die Texte, deren Namen in LONG_KEYS stehen. */
    @Test
    fun langeMeldungenNutzenDieGenanntenTexte() {
        assertEquals(R.string.backup_not_enough_memory, BackupResultPresentation.messageRes(BackupMessage.NotEnoughMemory))
        assertEquals(R.string.backup_storage_kept, BackupResultPresentation.messageRes(BackupMessage.StorageKept))
        assertEquals(R.string.backup_restore_storage_in_memory, BackupResultPresentation.messageRes(BackupMessage.StorageInMemory))
        assertEquals(
            R.string.backup_restore_journal_unavailable,
            BackupResultPresentation.messageRes(BackupMessage.JournalUnavailable),
        )
        assertEquals(R.string.backup_restored, BackupResultPresentation.messageRes(BackupMessage.Restored(1, 0)))
    }
}
