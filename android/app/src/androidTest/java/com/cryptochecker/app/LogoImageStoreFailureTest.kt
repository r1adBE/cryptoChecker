package com.cryptochecker.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptochecker.app.data.logos.LogoImageStore
import com.cryptochecker.app.data.util.ConnectivityMonitor
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import com.cryptochecker.app.data.logos.LogoTestInterceptor
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * Bilder der Coin-Logos bei Fehlern: Speicher voll (Ordner nicht beschreibbar) ist
 * eine eigene Art — keine Tagessperre, kein Netzfehler, der Durchgang lädt nichts mehr, das ohnehin verworfen würde, und
 * der nächste Durchgang holt alles nach. Start-Logos laden gleichzeitig (nicht eins nach dem anderen).
 * Antworten aus einem Interceptor (kein Netz).
 */
@HiltAndroidTest
class LogoImageStoreFailureTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val environment = AppTestEnvironmentRule()

    @Inject lateinit var connectivity: ConnectivityMonitor

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val imageDir = File(context.noBackupFilesDir, "coin_logos")
    private val requests = AtomicInteger()
    private var delayMillis = 0L

    private val png: ByteArray = ByteArrayOutputStream().also { out ->
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
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
        imageDir.setWritable(true)
        imageDir.deleteRecursively()
    }

    private fun store(): LogoImageStore {
        // Bleibt beim Bild-Abruf erhalten (LogoTestInterceptor), alle anderen Interceptors fallen dort weg
        val images = object : LogoTestInterceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                requests.incrementAndGet()
                if (delayMillis > 0) Thread.sleep(delayMillis)
                return Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(png.toResponseBody("image/png".toMediaType())).build()
            }
        }
        val client = OkHttpClient.Builder().addInterceptor(images).build()
        return LogoImageStore(context, client, connectivity, "cryptoChecker-test")
    }

    private fun map(count: Int, prefix: String) =
        (0 until count).associate { "$prefix$it" to "https://coin-images.coingecko.com/coins/images/$it/large/$prefix$it.png" }

    @Test
    fun storageFullIsNeitherFailureNorNetworkAndTheNextPassCatchesUp() = runBlocking {
        val store = store()
        val wanted = map(30, "FULL")
        // Kein Platz: Ordner nicht beschreibbar
        assertTrue(imageDir.setWritable(false))
        val first = store.downloadMissing(wanted)
        assertEquals(0, first.added)
        assertFalse("Speicher voll ist kein Netzfehler", first.network)
        assertTrue("unvollständig: der nächste Abgleich versucht es wieder", first.incomplete)
        assertTrue("nach dem ersten nicht gespeicherten Bild lädt der Durchgang nichts mehr (${requests.get()} Abrufe)",
            requests.get() < wanted.size)
        // Platz wieder da: alles nachgeholt (keine Tagessperre)
        assertTrue(imageDir.setWritable(true))
        val second = store.downloadMissing(wanted)
        assertEquals(wanted.size, second.added)
        assertFalse(second.incomplete)
    }

    @Test
    fun starterLogosLoadAtTheSameTime() = runBlocking {
        val store = store()
        val symbols = (0 until 6).map { "START$it" }
        delayMillis = 600
        val started = System.nanoTime()
        assertTrue(store.ensureStarterLogos(symbols, map(6, "START")))
        val elapsed = (System.nanoTime() - started) / 1_000_000
        // Nacheinander wären es mindestens 6 × 600 ms
        assertTrue("Start-Logos nacheinander geladen ($elapsed ms)", elapsed < 6 * 600 - 600)
        symbols.forEach { assertTrue(it, File(imageDir, "$it.png").isFile) }
    }
}
