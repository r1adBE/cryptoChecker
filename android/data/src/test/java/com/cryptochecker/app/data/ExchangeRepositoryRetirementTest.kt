package com.cryptochecker.app.data

import com.cryptochecker.app.data.local.ExchangeLocalDataSource
import com.cryptochecker.app.data.remote.ExchangeRemoteDataSource
import com.cryptochecker.app.data.settings.SettingsRepository
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.marketdata.config.ExchangeRetirement
import com.cryptochecker.marketdata.model.ExchangePair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * [ExchangeRepositoryImpl] nach dem Ende einer Börse ([ExchangeRetirement], Bitpanda ab 1. Februar 2027): keine einzige
 * Anfrage — weder Einzelkurs noch Sammelabfrage noch Paarliste. Der HTTP-Client ist ein Stub, der jede Anfrage zählt
 * und ohne Netz scheitert; vor dem Ende zählt er die Anfrage des Einzelkurses (der Stub greift also).
 */
class ExchangeRepositoryRetirementTest {

    private val calls = AtomicInteger()
    private val http = OkHttpClient.Builder()
        .addInterceptor { calls.incrementAndGet(); throw IOException("kein Netz im Test") }
        .build()
    private val bitpanda = ExchangeInfo(key = ExchangeRetirement.BITPANDA, name = ExchangeRetirement.BITPANDA)
    private val pair = ExchangePair("BTC", "EUR", "BTC_EUR")

    @After
    fun resetClock() = ExchangeRetirement.resetClock()

    @Test
    fun noRequestAfterTheEnd() = runBlocking {
        ExchangeRetirement.clock = { ExchangeRetirement.BITPANDA_END_MILLIS }
        val repo = repository()

        val ticker = repo.getExchangeTicker(bitpanda, pair)
        val bulk = repo.getBulkTickers(bitpanda, listOf("BTC_EUR", "ETH_EUR"))
        repo.updateExchangeCurrencyPairs(bitpanda)

        assertEquals(0, calls.get())
        assertEquals("Exchange retired: Bitpanda", ticker.error)
        assertEquals("Exchange retired: Bitpanda", bulk.error)
        assertFalse(repo.isExchangeSupportsBulkTickers(bitpanda))
    }

    @Test
    fun requestsBeforeTheEnd() = runBlocking {
        ExchangeRetirement.clock = { ExchangeRetirement.BITPANDA_END_MILLIS - 1 }

        val ticker = repository().getExchangeTicker(bitpanda, pair)

        assertTrue(calls.get() > 0)
        assertFalse(ExchangeRetirement.isMarker(ticker.error))
    }

    /** Nur die Netzschicht ist echt; die übrigen Teile braucht kein Kursweg (ohne Konstruktor angelegt, nie benutzt). */
    private fun repository() = ExchangeRepositoryImpl(
        local = unusedInstance(ExchangeLocalDataSource::class.java),
        remote = ExchangeRemoteDataSource(http),
        settings = unusedInstance(SettingsRepository::class.java),
        tokenIdentityStore = unusedInstance(TokenIdentityStore::class.java),
        coinLogos = unusedInstance(CoinLogoRepository::class.java),
        ioDispatcher = Dispatchers.Unconfined,
    )

    /** Objekt ohne Konstruktor-Aufruf (JVM-Test): für Abhängigkeiten, die der geprüfte Weg nie anfasst. */
    private fun <T> unusedInstance(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return type.cast(unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type))
    }
}
