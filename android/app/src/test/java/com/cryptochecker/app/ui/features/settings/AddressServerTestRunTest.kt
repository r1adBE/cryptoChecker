package com.cryptochecker.app.ui.features.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * «Testen» des eigenen Servers ([AddressServerTestRun]): Test A abbrechen (Eingabe geändert), erneut A testen — das
 * späte Ergebnis des ersten Abrufs (der den Abbruch nicht bemerkt) überschreibt Running(A) des zweiten nicht.
 * Gegenstück: `AddressServerTestRunTests`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddressServerTestRunTest {

    private val url = "https://node.example.org"

    @Test
    fun lateResultOfCancelledTestDoesNotOverwriteNewerTest() = runTest {
        val answers = listOf(CompletableDeferred<Long?>(), CompletableDeferred())
        var calls = 0
        // Abruf, der den Abbruch nicht bemerkt (liefert trotzdem spät)
        val run = AddressServerTestRun { withContext(NonCancellable) { answers[calls++].await() } }

        run.start(backgroundScope, url)
        runCurrent()
        assertEquals(AddressServerTest.Running(url), run.state.value)
        run.reset()
        assertEquals(AddressServerTest.Idle, run.state.value)
        run.start(backgroundScope, url)
        runCurrent()
        assertEquals(2, calls)

        answers[0].complete(111)
        runCurrent()
        assertEquals(AddressServerTest.Running(url), run.state.value)

        answers[1].complete(222)
        runCurrent()
        assertEquals(AddressServerTest.Ok(url, 222), run.state.value)
    }

    @Test
    fun lateResultOfOldUrlDoesNotAppearAfterResetAndNewUrl() = runTest {
        val other = "https://other.example"
        val answers = listOf(CompletableDeferred<Long?>(), CompletableDeferred())
        var calls = 0
        val run = AddressServerTestRun { withContext(NonCancellable) { answers[calls++].await() } }

        run.start(backgroundScope, url)
        runCurrent()
        run.reset()
        run.start(backgroundScope, other)
        runCurrent()
        assertEquals(2, calls)

        // Spätes Ok der alten URL: weder Ok(url) noch eine Änderung an Running(other)
        answers[0].complete(111)
        runCurrent()
        assertEquals(AddressServerTest.Running(other), run.state.value)

        answers[1].complete(222)
        runCurrent()
        assertEquals(AddressServerTest.Ok(other, 222), run.state.value)
    }

    @Test
    fun resultOfCurrentTestIsShown() = runTest {
        val run = AddressServerTestRun { null }
        run.start(backgroundScope, url)
        runCurrent()
        assertEquals(AddressServerTest.Failed(url), run.state.value)
    }

    @Test
    fun secondStartWhileRunningIsIgnored() = runTest {
        val answer = CompletableDeferred<Long?>()
        var calls = 0
        val run = AddressServerTestRun { calls++; answer.await() }
        run.start(backgroundScope, url)
        run.start(backgroundScope, "https://other.example")
        runCurrent()
        assertEquals(1, calls)
        answer.complete(5)
        runCurrent()
        assertEquals(AddressServerTest.Ok(url, 5), run.state.value)
    }
}
