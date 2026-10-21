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

    @Test
    fun `Bearbeiten bleibt ungebremst`() = runBlocking {
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
