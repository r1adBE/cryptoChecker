package com.cryptochecker.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/** Absturz im Fehlerprotokoll ([CrashRecord]): Stelle im eigenen Code, Typ der Ursache, nie der Text. */
class CrashRecordTest {

    @Test
    fun areaIsTheOwnFrameOfTheCause() {
        val cause = IllegalStateException("geheim 1.2 BTC").apply {
            stackTrace = arrayOf(
                StackTraceElement("androidx.fragment.app.FragmentManager", "checkStateLoss", "F.kt", 1),
                StackTraceElement("com.cryptochecker.app.ui.Settings", "pick", "S.kt", 42),
            )
        }
        val error = RuntimeException("Hülle", cause).apply {
            stackTrace = arrayOf(StackTraceElement("com.cryptochecker.app.Main", "run", "M.kt", 7))
        }
        assertEquals("crash:Settings.pick:42", CrashRecord.area(error))
        assertSame(cause, CrashRecord.rootCause(error))
        // Ohne Text: nur Klasse, Methode, Zeile
        assertFalse(CrashRecord.area(error).contains("BTC"))
    }

    @Test
    fun withoutOwnFrameTheTopFrame() {
        val error = IllegalArgumentException().apply {
            stackTrace = arrayOf(StackTraceElement("android.os.Handler", "dispatch", "H.java", 9))
        }
        assertEquals("crash:Handler.dispatch:9", CrashRecord.area(error))
        assertEquals("crash", CrashRecord.area(IllegalArgumentException().apply { stackTrace = emptyArray() }))
    }
}
