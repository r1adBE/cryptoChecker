package com.cryptochecker.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptochecker.app.data.logos.LogoImageStore
import com.cryptochecker.app.data.logos.LogoTestInterceptor
import com.cryptochecker.app.data.util.ConnectivityMonitor
import com.cryptochecker.app.domain.logos.LogoLoadingHint
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * Hinweis «Logos werden geladen» am echten Bild-Abruf (Audit A-01, früher Quelltext-Wächter): Geladene, nicht
 * vorhandene (404) und nach der letzten Runde noch gestörte Bilder zählen als erledigt (100 %); ein bremsender Server
 * nimmt die Prozentzahl weg; ohne fehlende Bilder kein Hinweis. Das erste Laden wird nur nach einem vollständigen
 * Durchgang im Logo-Ordner gemerkt. Antworten aus einem [LogoTestInterceptor] (kein Netz).
 */
@HiltAndroidTest
class LogoLoadingHintDeviceTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val environment = AppTestEnvironmentRule()

    @Inject lateinit var connectivity: ConnectivityMonitor

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val imageDir = File(context.noBackupFilesDir, "coin_logos")

    private val png: ByteArray = ByteArrayOutputStream().also { out ->
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            .compress(Bitmap.CompressFormat.PNG, 100, out)
    }.toByteArray()

    @Before
    fun setUp() {
        hiltRule.inject()
        imageDir.deleteRecursively()
        imageDir.mkdirs()
    }

    @After
    fun tearDown() {
        imageDir.deleteRecursively()
    }

    /** Server je Verhalten: «ok» liefert, «gone» 404, «slow» antwortet nie, «busy» bremst (429). */
    private fun store(): LogoImageStore {
        val images = object : LogoTestInterceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val host = chain.request().url.host
                if (host.startsWith("slow")) throw IOException("keine Antwort (Test)")
                val code = when {
                    host.startsWith("gone") -> 404
                    host.startsWith("busy") -> 429
                    else -> 200
                }
                return Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Test")
                    .body((if (code == 200) png else ByteArray(0)).toResponseBody("image/png".toMediaType())).build()
            }
        }
        return LogoImageStore(context, OkHttpClient.Builder().addInterceptor(images).build(), connectivity, "cryptoChecker-test")
    }

    private fun url(host: String, key: String) = "https://$host.coingecko.com/coins/images/1/large/$key.png"

    @Test
    fun loadedMissingAndStillDisturbedImagesAllCountAsDone() = runBlocking {
        val store = store()
        val wanted = mapOf(
            "OKA" to url("ok1", "OKA"), "OKB" to url("ok2", "OKB"),
            "GONEA" to url("gone1", "GONEA"),
            "SLOWA" to url("slow1", "SLOWA"),
        )
        var shown: Int? = -1
        store.loadingHint.during(complete = { _: Any -> false }) {
            store.downloadMissing(wanted, lastStage = true).also {
                assertTrue("Hinweis während des ersten Ladens", store.loadingHint.visible.value)
                shown = store.loadingHint.currentPercent
            }
        }
        assertEquals("alles erledigt (auch 404 und noch gestört)", 100, shown)
        assertFalse(store.loadingHint.visible.value)
    }

    @Test
    fun aBrakingServerRemovesThePercentage() = runBlocking {
        val store = store()
        var shown: Int? = -1
        store.loadingHint.during(complete = { _: Any -> false }) {
            store.downloadMissing(mapOf("OKC" to url("ok3", "OKC"), "BUSYA" to url("busy1", "BUSYA")), lastStage = true)
            shown = store.loadingHint.currentPercent
        }
        assertNull("gebremst: ohne Prozent", shown)
    }

    @Test
    fun nothingToLoadShowsNoHint() = runBlocking {
        val store = store()
        store.loadingHint.during(complete = { _: Any -> true }) {
            store.downloadMissing(emptyMap(), lastStage = true)
            assertFalse(store.loadingHint.visible.value)
        }
        // Ohne Hinweis kein «erstes Laden erledigt» (es lud ja nichts)
        assertFalse(File(imageDir, LogoLoadingHint.DONE_MARKER).isFile)
    }

    @Test
    fun onlyACompletePassRemembersTheFirstLoad() = runBlocking {
        val store = store()
        store.loadingHint.during(complete = { d: com.cryptochecker.app.data.logos.LogoDownloads -> d.complete }) {
            store.downloadMissing(mapOf("SLOWB" to url("slow2", "SLOWB"), "OKD" to url("ok4", "OKD")), lastStage = true)
        }
        assertFalse("gestört: nicht vollständig, nicht gemerkt", File(imageDir, LogoLoadingHint.DONE_MARKER).isFile)
        store.loadingHint.during(complete = { d: com.cryptochecker.app.data.logos.LogoDownloads -> d.complete }) {
            store.downloadMissing(mapOf("OKE" to url("ok5", "OKE")), lastStage = true)
        }
        assertTrue("vollständig: gemerkt", File(imageDir, LogoLoadingHint.DONE_MARKER).isFile)
    }
}
