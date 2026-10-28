package com.cryptochecker.wear

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SerialPublisher]: Sendungen laufen nacheinander in der Reihenfolge der Aufrufe, und jede liest den Stand erst beim
 * Senden — die letzte Sendung trägt immer den aktuellen Stand. Gleicher Test auf Uhr und Handy.
 */
class SerialPublisherTest {

    @Test
    fun sendsOneAfterAnotherWithTheStateAtSendTime() {
        runBlocking {
            val publisher = SerialPublisher()
            var state = 3
            val sent = ArrayList<Int>()
            val firstSending = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val first = launch {
                publisher.publish({ state }) {
                    firstSending.complete(Unit)
                    release.await()
                    sent += it
                }
            }
            firstSending.await()
            // Zweite Änderung, während die erste noch sendet
            state = 4
            val second = launch { publisher.publish({ state }) { sent += it } }
            yield()
            assertEquals(emptyList<Int>(), sent)
            // Dritte Änderung, bevor die zweite an der Reihe ist: sie sendet den Stand von jetzt
            state = 5
            release.complete(Unit)
            joinAll(first, second)

            assertEquals(listOf(3, 5), sent)
        }
    }
}
