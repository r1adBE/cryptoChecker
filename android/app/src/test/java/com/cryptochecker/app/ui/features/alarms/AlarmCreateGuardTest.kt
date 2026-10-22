package com.cryptochecker.app.ui.features.alarms

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Doppeltipp beim Anlegen eines Alarms: nur ein INSERT. */
class AlarmCreateGuardTest {

    @Test
    fun `zweiter Tipp waehrend das Anlegen laeuft wird ignoriert`() = runBlocking {
        val guard = AlarmCreateGuard()
        val release = CompletableDeferred<Unit>()
        var inserts = 0
        val first = guard.launch(this, guarded = true) { release.await(); inserts++ }
        val second = guard.launch(this, guarded = true) { inserts++ }
        assertNotNull(first)
        assertNull(second)
        release.complete(Unit)
        first!!.join()
        assertEquals(1, inserts)
        assertFalse(guard.busy)
        // Danach geht ein neues Anlegen wieder
        guard.launch(this, guarded = true) { inserts++ }!!.join()
        assertEquals(2, inserts)
    }

    /** Der Alarm-Editor sperrt auch beim Bearbeiten (IOS6-01, wie iOS `AlarmCreateGuard`): kein ungesperrter Start mehr. */
    @Test
    fun `Alarm-Editor sperrt auch beim Bearbeiten`() {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        var source: java.io.File? = null
        while (dir != null && source == null) {
            source = listOf("", "app/", "android/app/")
                .map { java.io.File(dir, it + "src/main/java/com/cryptochecker/app/ui/features/alarms/AlarmsViewModel.kt") }
                .firstOrNull { it.isFile }
            dir = dir.parentFile
        }
        assertNotNull("AlarmsViewModel.kt nicht gefunden (Arbeitsverzeichnis?)", source)
        val text = source!!.readText()
        val save = text.substringAfter("fun save(draft: AlarmDraft) {").substringBefore("\n    fun ")
        assertTrue(save.contains("createGuard.launch(viewModelScope, guarded = true)"))
        assertFalse(text.contains("guarded = draft.id == 0L"))
    }

    @Test
    fun `ungesperrter Start laeuft neben einem Anlegen`() = runBlocking {
        val guard = AlarmCreateGuard()
        val release = CompletableDeferred<Unit>()
        var saves = 0
        val creating = guard.launch(this, guarded = true) { release.await() }
        val edit = guard.launch(this, guarded = false) { saves++ }
        assertNotNull(edit)
        edit!!.join()
        assertEquals(1, saves)
        release.complete(Unit)
        creating!!.join()
    }

    @Test
    fun `Abbruch gibt den Waechter frei`() = runBlocking {
        val scope = CoroutineScope(Job())
        val guard = AlarmCreateGuard()
        val job = guard.launch(scope, guarded = true) { CompletableDeferred<Unit>().await() }
        yield()
        scope.cancel()
        job!!.join()
        assertFalse(guard.busy)
    }
}
