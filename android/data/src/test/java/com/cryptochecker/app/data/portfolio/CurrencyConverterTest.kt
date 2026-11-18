package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.data.FolderContext
import com.cryptochecker.app.data.TokenIdentityStore
import com.cryptochecker.app.data.fakePortfolioDao
import com.cryptochecker.app.data.fakeWatchDao
import com.cryptochecker.app.data.remote.CandleDataSource
import com.cryptochecker.app.data.remote.Reply
import com.cryptochecker.app.data.remote.RouteHttp
import com.cryptochecker.app.data.unusedExchangeRepository
import com.cryptochecker.app.domain.alarm.DepegReadingStore
import com.cryptochecker.app.domain.convert.CurrencyConversion
import com.cryptochecker.app.domain.convert.CurrencyConversion.QuoteKind
import com.cryptochecker.app.domain.convert.FiatAlarmGuard
import com.cryptochecker.app.domain.convert.FiatAlarmGuard.UsdtState
import com.cryptochecker.app.domain.util.KeyValueStore
import com.cryptochecker.marketdata.util.EcbRates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * «≈ Umrechnung» ([CurrencyConverter]) mit echten Kurs- und Devisenquellen über einen Interceptor: Fiat über die EZB,
 * Krypto und Stablecoins über die Binance-Ticker, USDT × gemessenes USDT/USD, Zwischenspeicher ohne Netz, Alter und
 * Zeitpunkt der Devisenkurse, Stand von USDT/USD je Faktor.
 */
class CurrencyConverterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val tickers = "https://data-api.binance.vision/api/v3/ticker/price?symbols="

    private val ecbDaily = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><gesmes:Envelope " +
        "xmlns:gesmes=\"http://www.gesmes.org/xml/2002-08-01\" xmlns=\"http://www.ecb.int/vocabulary/2002-08-01/eurofxref\">" +
        "<Cube><Cube time='2026-10-23'><Cube currency='USD' rate='1.1267'/><Cube currency='CHF' rate='0.9188'/>" +
        "</Cube></Cube></gesmes:Envelope>"

    private val usdToChf = 0.81548
    private val usdToEur = 0.88755

    private val http = RouteHttp { url ->
        when {
            url == EcbRates.DAILY_URL -> Reply(ecbDaily)
            url.startsWith(tickers) -> Reply("""[{"symbol":"BTCUSDT","price":"60000"},{"symbol":"USDCUSDT","price":"0.9995"}]""")
            else -> null
        }
    }

    private val store = MemoryStore()
    private val depeg = DepegReadingStore { store }
    private lateinit var converter: CurrencyConverter

    @Before
    fun setUp() {
        RouteHttp.releaseChain()
        val context = FolderContext(temp.newFolder())
        val names = TokenIdentityStore(context, fakeWatchDao { emptyList() })
        names.saveBinance(mapOf("BTC" to "Bitcoin", "USDC" to "USD Coin"))
        val fx = FxRateSource(http.client, ioDispatcher = Dispatchers.IO)
        val prices = PortfolioPriceSource(
            context, http.client, CandleDataSource(http.client, names, ioDispatcher = Dispatchers.IO), unusedExchangeRepository(),
            fakeWatchDao { emptyList() }, fakePortfolioDao(), fx, names, depeg, ioDispatcher = Dispatchers.IO,
        )
        converter = CurrencyConverter(fx, prices, depeg)
    }

    @Test
    fun `same currency is one and unusable targets or quotes give no rate`() = runBlocking {
        assertEquals(1.0, converter.rate(" chf", "CHF")!!, 0.0)
        assertEquals(1.0, converter.cachedRate("btc", "BTC")!!, 0.0)
        assertEquals(QuoteKind.FIAT_UNSUPPORTED, converter.classify("NGN"))
        assertNull("Ziel Krypto", converter.rate("USD", "BTC"))
        assertNull("Fiat ohne Devisenkurs", converter.rate("NGN", "CHF"))
        assertNull("ungültig", converter.rate("U-D", "CHF"))
        assertNull(converter.cachedRate("NGN", "CHF"))
        assertNull(converter.cachedRate("USD", "BTC"))
        assertTrue("keine Anfrage: ${http.requested}", http.requested.isEmpty())
    }

    @Test
    fun `fiat, crypto and stablecoins convert over usd`() = runBlocking {
        // Vor dem Abruf nichts im Zwischenspeicher
        assertNull(converter.cachedRate("EUR", "CHF"))
        assertNull(converter.cachedRate("BTC", "CHF"))

        assertEquals(usdToChf / usdToEur, converter.rate("eur", "chf")!!, 1e-12)
        assertEquals(usdToChf, converter.rate("USD", "CHF")!!, 1e-12)
        assertEquals(usdToChf, converter.rate("USDT", "CHF")!!, 1e-12)
        assertEquals(60000 * usdToChf, converter.rate("BTC", "CHF")!!, 1e-6)
        assertEquals(0.9995 * usdToChf, converter.rate("USDC", "CHF")!!, 1e-12)
        assertEquals(60000.0, converter.rate("BTC", "USD")!!, 1e-9)
        assertEquals(1 / usdToChf, converter.rate("CHF", "USDT")!!, 1e-12)
        assertNull("unbekannter Coin ohne Kurs", converter.rate("XYZ", "CHF"))
        assertEquals("eine Abfrage der EZB", 1, http.count("ecb.europa.eu"))

        // Danach dieselben Faktoren ohne Netz
        val before = http.requested.size
        assertEquals(usdToChf / usdToEur, converter.cachedRate("EUR", "CHF")!!, 1e-12)
        assertEquals(60000 * usdToChf, converter.cachedRate("btc", "chf")!!, 1e-6)
        assertEquals(0.9995 * usdToChf, converter.cachedRate("USDC", "CHF")!!, 1e-12)
        assertEquals(usdToChf, converter.cachedRate("USDT", "CHF")!!, 1e-12)
        assertEquals(0.9995, converter.cachedRate("USDC", "USD")!!, 1e-12)
        assertEquals(
            mapOf("EUR" to usdToChf / usdToEur, "USD" to usdToChf),
            converter.cachedRates(listOf("eur", " ", "USD", "EUR", "NGN"), "CHF"),
        )
        assertEquals(before, http.requested.size)
    }

    @Test
    fun `several quotes share one price request and keep only known rates`() = runBlocking {
        val rates = converter.rates(listOf("btc", "USDC", "eur", "", "NGN", "BTC"), "CHF")
        assertEquals(setOf("BTC", "USDC", "EUR"), rates.keys)
        assertEquals(60000 * usdToChf, rates.getValue("BTC"), 1e-6)
        assertEquals("BTC und USDC in einer Sammelabfrage", 1, http.count(tickers))
    }

    @Test
    fun `fx age and time only where a fiat rate is involved`() = runBlocking {
        converter.rate("EUR", "CHF")
        val now = System.currentTimeMillis()
        assertNull("frisch", converter.staleFxTime("EUR", "CHF", now))
        val later = now + CurrencyConversion.FX_STALE_MILLIS + 60_000
        val stale = converter.staleFxTime("EUR", "CHF", later)!!
        assertTrue(stale <= now)
        assertNull("kein Devisenkurs", converter.staleFxTime("BTC", "USD", later))
        assertNull(converter.staleFxTime("CHF", "chf", later))
        // Auch USD, USDT und Krypto in CHF brauchen den Devisenkurs von CHF
        val times = converter.staleFxTimes(listOf("eur", "USD", "USDT ", "BTC", ""), "CHF", later)
        assertEquals(setOf("EUR", "USD", "USDT", "BTC"), times.keys)
        assertTrue(times.values.all { it <= now })
        assertTrue(converter.staleFxTimes(listOf("BTC", "USDT"), "USD", later).isEmpty())
        assertTrue(converter.staleFxTimes(listOf("EUR"), "CHF", now).isEmpty())

        val ecbTime = FiatAlarmGuard.rateTimeOf("2026-10-23")
        assertEquals(ecbTime, converter.fxRateTime("EUR", "CHF"))
        assertEquals(ecbTime, converter.fxRateTime("BTC", "CHF"))
        assertNull(converter.fxRateTime("BTC", "USDT"))
        assertNull(converter.fxRateTime("EUR", "eur"))
    }

    @Test
    fun `usdt state follows the measured usdt rate`() = runBlocking {
        val now = System.currentTimeMillis()
        assertFalse(converter.usdtMeasured().first())
        assertNull(converter.liveUsdtUsd())
        assertEquals(UsdtState.NOT_INVOLVED, converter.usdtState("EUR", "CHF", now))
        assertEquals(UsdtState.NOT_INVOLVED, converter.usdtState("USD", "CHF", now))
        assertEquals(UsdtState.ASSUMED, converter.usdtState("BTC", "CHF", now))
        assertEquals(
            mapOf("BTC" to UsdtState.ASSUMED, "USDT" to UsdtState.ASSUMED),
            converter.usdtNotes(listOf("btc", "EUR", "USD", "usdt", ""), "CHF", now),
        )

        // Gemessen: USDT × USDT/USD, auch als Ziel; der Hinweis entfällt
        depeg.save(0.998, now)
        assertTrue(converter.usdtMeasured().first())
        assertEquals(0.998, converter.liveUsdtUsd()!!, 0.0)
        assertEquals(UsdtState.MEASURED, converter.usdtState("BTC", "CHF", now))
        assertTrue(converter.usdtNotes(listOf("BTC", "USDT"), "CHF", now).isEmpty())
        assertEquals(0.998 * usdToChf, converter.rate("USDT", "CHF")!!, 1e-12)
        assertEquals(1 / 0.998, converter.rate("USD", "USDT")!!, 1e-12)
        assertEquals(1 / 0.998, converter.cachedRate("USD", "USDT")!!, 1e-12)

        // Nur über USDC gemessen und USDC/USDT mehr als 1 % neben 1: unsicher
        depeg.save(0.97, now, viaUsdc = true)
        assertEquals(mapOf("BTC" to UsdtState.UNSURE), converter.usdtNotes(listOf("BTC", "EUR"), "CHF", now))
    }

    /** Ablage im Speicher für die USDT/USD-Messung. */
    private class MemoryStore : KeyValueStore {
        private val values = HashMap<String, Any?>()
        override fun getInt(key: String, default: Int) = values[key] as? Int ?: default
        override fun getLong(key: String, default: Long) = values[key] as? Long ?: default
        override fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
        override fun getString(key: String) = values[key] as? String
        override fun contains(key: String) = key in values
        override fun all(): Map<String, Any?> = HashMap(values)
        override fun edit(block: KeyValueStore.Editor.() -> Unit) {
            object : KeyValueStore.Editor {
                override fun putInt(key: String, value: Int) = values.set(key, value)
                override fun putLong(key: String, value: Long) = values.set(key, value)
                override fun putBoolean(key: String, value: Boolean) = values.set(key, value)
                override fun putString(key: String, value: String?) = values.set(key, value)
                override fun remove(key: String) {
                    values.remove(key)
                }
            }.block()
        }
    }
}
