package com.cryptochecker.app

import android.content.Context
import androidx.core.content.edit
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptochecker.app.data.AlphaTokens
import com.cryptochecker.app.data.CoinListSync
import com.cryptochecker.app.data.CoinNames
import com.cryptochecker.app.data.TokenIdentityStore
import com.cryptochecker.app.data.logos.LogoImageStore
import com.cryptochecker.app.data.util.ConnectivityMonitor
import com.cryptochecker.app.data.symbolMap
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import javax.inject.Inject

/**
 * Rangliste der Coin-Logos ([CoinListSync.symbolMap]) bei einem Netzfehler auf einer Folgeseite (Audit A-01, früher
 * Quelltext-Wächter): das Teil-Ergebnis wird genutzt, aber weder der Stand noch ein Versuch gemerkt — so fragt die App
 * bald erneut statt Stunden «unvollständig» zu bleiben ([CoinListSync.mapNetworkFailed]). Gegenprobe: ganze Liste →
 * Stand gemerkt. Antworten kommen aus einem Interceptor (kein Netz).
 */
@HiltAndroidTest
class LogoRankingNetworkCutTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val environment = AppTestEnvironmentRule()

    @Inject lateinit var connectivity: ConnectivityMonitor
    @Inject lateinit var tokenIdentityStore: TokenIdentityStore
    @Inject lateinit var imageStore: LogoImageStore
    @Inject lateinit var names: CoinNames
    @Inject lateinit var alpha: AlphaTokens

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun inject() = hiltRule.inject()

    /** Rangliste mit [firstPageRows] Zeilen auf Seite 1; Seite 2 bricht ab, wenn [cutSecondPage]. */
    private fun sync(firstPageRows: Int, cutSecondPage: Boolean): CoinListSync {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            val page = url.queryParameter("page")?.toIntOrNull()
            if (url.host != "api.coingecko.com" || page == null) return@addInterceptor respond(chain.request(), 404, "")
            if (page == 2 && cutSecondPage) throw IOException("Verbindung abgebrochen (Test)")
            val rows = if (page == 1) firstPageRows else 0
            respond(chain.request(), 200, (0 until rows).joinToString(",", "[", "]") { i ->
                """{"symbol":"c$page$i","name":"Coin $page $i","image":"https://coin-images.coingecko.com/coins/images/$page$i/large/c.png"}"""
            })
        }.build()
        val sync = CoinListSync(context, client, connectivity, tokenIdentityStore, imageStore, names, alpha)
        // Kein gemerkter Stand und kein Versuch: die Rangliste wird abgefragt
        sync.prefs.edit { remove(CoinListSync.KEY_MAP_TIME); remove(CoinListSync.KEY_MAP_ATTEMPT) }
        return sync
    }

    private fun respond(request: okhttp3.Request, code: Int, body: String): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message(if (code == 200) "OK" else "Not Found")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    @Test
    fun networkCutOnALaterPageKeepsNoTimeAndRetriesSoon() = runBlocking {
        val sync = sync(firstPageRows = 250, cutSecondPage = true)
        val map = sync.symbolMap()
        assertTrue("Teil-Ergebnis der ersten Seite genutzt", map.keys.any { it.uppercase().startsWith("C1") })
        assertTrue(sync.mapNetworkFailed)
        assertEquals(0L, sync.prefs.getLong(CoinListSync.KEY_MAP_TIME, 0L))
        assertEquals("kein Versuch gemerkt (bald erneut)", 0L, sync.prefs.getLong(CoinListSync.KEY_MAP_ATTEMPT, 0L))
    }

    @Test
    fun completeListKeepsItsTime() = runBlocking {
        val sync = sync(firstPageRows = 3, cutSecondPage = false)
        sync.symbolMap()
        assertFalse(sync.mapNetworkFailed)
        assertTrue(sync.prefs.getLong(CoinListSync.KEY_MAP_TIME, 0L) > 0L)
    }
}
