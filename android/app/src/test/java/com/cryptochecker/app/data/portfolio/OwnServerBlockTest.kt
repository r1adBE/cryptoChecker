package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.data.remote.BlockedSources
import com.cryptochecker.app.domain.exceptions.HttpMarketError
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Eigener Bitcoin-Server: 403/451 sperrt ihn nicht 6 h ([BlockedSources]); öffentliche Hosts weiter. Speichern bzw.
 * erfolgreicher Test ([clearServerPauses]) hebt eine Sperre auf. Gegenstück: `OwnServerBlockTests.swift`.
 */
class OwnServerBlockTest {

    private val source = WatchedAddressSource(OkHttpClient())

    private fun failWith(code: Int, entry: String, own: Boolean) {
        val failed = try {
            runBlocking { source.firstWorking(listOf(entry), null, own) { throw HttpMarketError(code, null) } }
            false
        } catch (e: HttpMarketError) {
            true
        }
        assertTrue(failed)
    }

    @Test
    fun eigenerServerWirdNichtGesperrt() {
        failWith(403, "https://own-block-test.example/api", own = true)
        assertFalse(BlockedSources.isBlocked("own-block-test.example"))
        failWith(451, "https://own-block-test.example/api", own = true)
        assertFalse(BlockedSources.isBlocked("own-block-test.example"))
    }

    @Test
    fun oeffentlicherHostWirdGesperrtUndSpeichernHebtAuf() {
        failWith(403, "public-block-test.example", own = false)
        assertTrue(BlockedSources.isBlocked("public-block-test.example"))
        source.clearServerPauses("https://public-block-test.example:3006/api")
        assertFalse(BlockedSources.isBlocked("public-block-test.example"))
    }

    @Test
    fun eigenerServerNachSperreWiederGefragt() {
        // Alte Sperre desselben Hosts (z. B. vor dem Speichern): der eigene Server wird trotzdem gefragt
        failWith(403, "stale-block-test.example", own = false)
        val (value, _) = runBlocking { source.firstWorking(listOf("https://stale-block-test.example"), null, true) { 7 } }
        assertEquals(7, value)
    }
}
