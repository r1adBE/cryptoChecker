package com.cryptochecker.app.testing

import com.cryptochecker.app.data.HttpLogger
import com.cryptochecker.app.di.RemoteDataModule
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import javax.inject.Singleton

/**
 * Feste Kurse für die instrumentierten Tests. `MarketRemoteDataSource` ist eine Klasse mit
 * @Inject-Konstruktor (kein Interface, kein Modul) — ersetzt wird deshalb eine Ebene tiefer
 * der OkHttpClient aus [RemoteDataModule]: Alle Abfragen über ihn beantwortet
 * [FakeMarketInterceptor] ohne Netz. Die App selbst bleibt unverändert.
 */
object FakeMarket {
    const val START_PRICE = 95_000.0

    /** Aktueller BTC-Kurs (Binance BTC/USDT bzw. Coinbase BTC-USD); der Test setzt ihn neu. */
    @Volatile
    var btcPrice: Double = START_PRICE

    /** Die übrigen Start-Coins mit festen Kursen. */
    private val fixed = mapOf("ETH" to 3_500.0, "XRP" to 2.5, "BNB" to 900.0, "SOL" to 200.0)

    val coins: List<String> get() = listOf("BTC") + fixed.keys

    fun price(base: String): Double? = if (base == "BTC") btcPrice else fixed[base]

    fun reset() {
        btcPrice = START_PRICE
    }
}

/** Beantwortet Binance- und Coinbase-Abfragen aus [FakeMarket]; alles andere: 404 (die App zeigt dann «—»). */
class FakeMarketInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = answer(request.url)
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(if (body != null) 200 else 404)
            .message(if (body != null) "OK" else "Not Found")
            .body((body ?: """{"message":"not found"}""").toResponseBody(JSON))
            .build()
    }

    private fun answer(url: HttpUrl): String? {
        val path = url.encodedPath
        return when (url.host) {
            "api.binance.com" -> when (path) {
                "/api/v3/exchangeInfo" -> binanceExchangeInfo()
                "/api/v3/ticker/24hr" -> binanceTickers(url)
                else -> null
            }
            "api.exchange.coinbase.com" -> when {
                path == "/products" -> coinbaseProducts()
                path.startsWith("/products/") && path.endsWith("/stats") -> coinbaseStats(url.pathSegments[1])
                else -> null
            }
            else -> null
        }
    }

    private fun binanceExchangeInfo(): String = FakeMarket.coins.joinToString(",", """{"symbols":[""", "]}") {
        """{"symbol":"${it}USDT","status":"TRADING","baseAsset":"$it","quoteAsset":"USDT"}"""
    }

    private fun binanceTicker(base: String): String? {
        val price = FakeMarket.price(base) ?: return null
        val now = System.currentTimeMillis()
        return """{"symbol":"${base}USDT","bidPrice":"$price","askPrice":"$price","volume":"1000.0",""" +
            """"quoteVolume":"${price * 1000}","highPrice":"${price * 1.02}","lowPrice":"${price * 0.98}",""" +
            """"lastPrice":"$price","closeTime":$now,"priceChangePercent":"1.25"}"""
    }

    private fun binanceTickers(url: HttpUrl): String? {
        url.queryParameter("symbol")?.let { symbol -> return binanceTicker(symbol.removeSuffix("USDT")) }
        val wanted = url.queryParameter("symbols")
            ?.let { Regex("\"([A-Z0-9]+)USDT\"").findAll(it).map { m -> m.groupValues[1] }.toList() }
            ?: FakeMarket.coins
        return wanted.mapNotNull { binanceTicker(it) }.joinToString(",", "[", "]")
    }

    private fun coinbaseProducts(): String = FakeMarket.coins.joinToString(",", "[", "]") {
        """{"id":"$it-USD","base_currency":"$it","quote_currency":"USD","status":"online"}"""
    }

    private fun coinbaseStats(productId: String): String? {
        val price = FakeMarket.price(productId.substringBefore('-')) ?: return null
        return """{"open":"${price / 1.0125}","high":"${price * 1.02}","low":"${price * 0.98}",""" +
            """"last":"$price","volume":"1000.0"}"""
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

/** Ersetzt [RemoteDataModule] in allen instrumentierten Tests: gleiche Bindungen, kein Netz. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [RemoteDataModule::class])
object FakeRemoteDataModule {

    @Singleton
    @Provides
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(FakeMarketInterceptor()).build()

    @Singleton
    @Provides
    fun provideHttpLoggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.NONE }

    @Singleton
    @Provides
    fun provideHttpLoggerFlow(): HttpLogger = object : HttpLogger {
        private val flow = MutableSharedFlow<String>(1)
        override val messageFlow: SharedFlow<String> get() = flow
    }
}
