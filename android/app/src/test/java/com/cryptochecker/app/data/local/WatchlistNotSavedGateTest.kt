package com.cryptochecker.app.data.local

import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.obj
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Seit Build 104 (F-08): Frage vor der ersten Änderung je Lauf — gleiche Fälle wie `WatchlistNotSavedGateTests.swift`. */
class WatchlistNotSavedGateTest {

    private val data = ParityJson.load("watchlist_not_saved_gate.json")

    @Before
    fun setUp() = WatchlistNotSavedGate.resetForTest()

    @After
    fun tearDown() = WatchlistNotSavedGate.resetForTest()

    @Test
    fun sharedShouldAsk() {
        data["shouldAsk"].list().forEach { raw ->
            val c = raw.obj()
            assertEquals(
                c.toString(), c["ask"],
                WatchlistNotSavedGate.shouldAsk(
                    notSaved = c["notSaved"] as Boolean, userWrite = c["userWrite"] as Boolean,
                    confirmed = c["confirmed"] as Boolean, canAsk = c["canAsk"] as Boolean,
                ),
            )
        }
    }

    @Test
    fun sharedConfirmedAfter() {
        data["confirmedAfter"].list().forEach { raw ->
            val c = raw.obj()
            assertEquals(
                c.toString(), c["result"],
                WatchlistNotSavedGate.confirmedAfter(c["confirmed"] as Boolean, c["proceed"] as Boolean),
            )
        }
    }

    @Test
    fun sharedSequence() = runBlocking {
        data["sequence"].obj()["steps"].list().forEachIndexed { i, raw ->
            val step = raw.obj()
            val write = async(Dispatchers.Default) { WatchlistNotSavedGate.confirm(notSaved = true, canAsk = true) }
            val answer = step["answer"] as Boolean?
            if (answer != null) {
                withTimeout(5_000) { WatchlistNotSavedGate.request.filterNotNull().first() }
                WatchlistNotSavedGate.answer(answer)
            }
            assertEquals("Schritt $i", step["written"], withTimeout(5_000) { write.await() })
            assertNull("Schritt $i", WatchlistNotSavedGate.request.value)
        }
    }

    @Test
    fun withoutVisibleUiNeverAsks() = runBlocking {
        // Kein Beobachter (Hintergrund): schreibt, ohne zu fragen
        assertEquals(true, WatchlistNotSavedGate.confirm(notSaved = true, canAsk = false))
        assertNull(WatchlistNotSavedGate.request.value)
    }
}
