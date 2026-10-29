package com.cryptochecker.app.parity

import com.cryptochecker.app.data.local.AsideRetention
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Frist beiseitegelegter und ersetzter Dateien (`testdata/parity/aside_retention.json`): gleiche Auswahl wie iOS
 * (`AsideRetentionTests.swift`), damit beide Plattformen Kopien der Nutzerdaten gleich lange behalten.
 */
class AsideRetentionParityTest {

    private val fixture = ParityJson.load("aside_retention.json")

    @Test
    fun fristWieGemeinsameFaelle() {
        assertEquals(AsideRetention.MAX_AGE_MILLIS, fixture["maxAgeMillis"].num()?.toLong())
        for (raw in fixture["cases"].list()) {
            val c = raw.obj()
            val name = c["name"] as String
            val seen = c["seen"].obj().mapValues { (it.value.num() ?: error(name)).toLong() }
            val selection = AsideRetention.select(
                copies = c["copies"].list().map { it as String },
                seen = seen,
                now = (c["now"].num() ?: error(name)).toLong(),
                allowed = c["allowed"] as Boolean,
            )
            assertEquals(name, c["delete"].list().map { it as String }, selection.delete)
            assertEquals(name, c["seenAfter"].obj().mapValues { (it.value.num() ?: error(name)).toLong() }, selection.seen)
        }
    }
}
