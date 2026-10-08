package com.cryptochecker.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.core.content.edit
import com.cryptochecker.app.data.remote.callMarket
import com.cryptochecker.app.domain.logos.CoinLogos
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Coin-Logos von CoinGecko (Regeln in [CoinLogos]). Nichts ist mitgeliefert, und nichts wird
 * einzeln geholt — so erfährt CoinGecko nie, welche Coins in einer Merkliste stehen:
 *
 * 1. Rangliste der grössten 1000 Coins (`/coins/markets`), Lücken (TradFi wie Gold, Silber,
 *    Aktien) aus der Symbolliste der Binance-Website; höchstens einmal pro Woche, für alle gleich.
 * 2. [startSync] lädt die Logos **aller** Coins dieser Liste (kleine Fassung, auf
 *    [CoinLogos.STORED_PX] begrenzt) und legt sie als PNG im Cache-Ordner ab. Später fehlen nur
 *    neu dazugekommene Coins; ohne Neues geht kein Bild-Abruf ins Netz.
 * 3. Angezeigt wird nur aus Speicher und Datei ([logo]); Coins ausserhalb der Liste, DEX-Pools
 *    und Fehler → Initialen. Ein fehlgeschlagenes Bild wird einen Tag lang nicht wiederholt.
 *
 * Alle drei Schalter «Coin-Logos» aus → [startSync] wird nicht aufgerufen, also nichts geladen.
 */
@Singleton
class CoinLogoRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    httpClient: OkHttpClient,
) {
    /** Gleiche Verbindungen, aber ohne Mitschnitt (sonst landen Bild-Bytes im Debug-Protokoll). */
    private val client: OkHttpClient = httpClient.newBuilder().apply { interceptors().clear() }.build()
    private val apiClient: OkHttpClient = httpClient

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val mapFile by lazy { File(context.noBackupFilesDir, MAP_FILE) }
    private val imageDir by lazy { File(context.cacheDir, IMAGE_DIR).apply { mkdirs() } }

    private val mapMutex = Mutex()
    @Volatile private var map: Map<String, String>? = null

    /** Zuletzt benutzte Bilder, höchstens ~4 MB. */
    private val memory = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val downloads = Semaphore(MAX_PARALLEL_DOWNLOADS)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncLock = Any()
    private var syncJob: Job? = null

    private val _revision = MutableStateFlow(0)

    /** Steigt, sobald neue Logos auf dem Gerät liegen (Anzeigen laden dann neu). */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** Sofort, ohne Datei und Netz: Bild aus dem Speicher oder null. */
    fun cached(symbol: String): Bitmap? = CoinLogos.fileName(symbol)?.let { memory.get(it) }

    /** Logo für [symbol] aus Speicher oder Datei — nie aus dem Netz. null → Initialen. */
    suspend fun logo(symbol: String): Bitmap? {
        val name = CoinLogos.fileName(symbol) ?: return null
        memory.get(name)?.let { return it }
        return withContext(Dispatchers.IO) {
            val file = File(imageDir, name)
            if (!file.isFile) return@withContext null
            val bitmap = decodeFile(file)
            if (bitmap == null) file.delete() else memory.put(name, bitmap)
            bitmap
        }
    }

    /**
     * Lädt im Hintergrund die Logos aller Coins der Rangliste, die noch fehlen. Läuft nur einmal
     * gleichzeitig; danach [onDone] mit der Zahl neuer Logos (z. B. um Widgets neu zu zeichnen).
     */
    fun startSync(onDone: suspend (added: Int) -> Unit = {}) {
        synchronized(syncLock) {
            if (syncJob?.isActive == true) return
            syncJob = scope.launch {
                val added = try {
                    syncAll()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Coin-Logos: Abgleich fehlgeschlagen")
                    0
                }
                onDone(added)
            }
        }
    }

    private suspend fun syncAll(): Int {
        val wanted = symbolMap()
        if (wanted.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val failed = loadFailures(now)
        val missing = wanted.entries.mapNotNull { (symbol, url) ->
            val name = CoinLogos.fileName(symbol) ?: return@mapNotNull null
            if (name in failed || File(imageDir, name).isFile) null else name to url
        }
        if (missing.isEmpty()) return 0
        val added = AtomicInteger()
        val newFailures = ConcurrentHashMap<String, Long>()
        coroutineScope {
            missing.map { (name, url) ->
                async {
                    if (downloads.withPermit { downloadTo(name, url) }) {
                        // In Schüben melden, damit die Zeilen nicht bei jedem Bild neu laden
                        if (added.incrementAndGet() % REVISION_STEP == 0) _revision.value++
                    } else {
                        newFailures[name] = now
                    }
                }
            }.awaitAll()
        }
        if (newFailures.isNotEmpty()) saveFailures(failed + newFailures)
        if (added.get() % REVISION_STEP != 0) _revision.value++
        Timber.i("Coin-Logos: %d neu, %d fehlgeschlagen", added.get(), newFailures.size)
        return added.get()
    }

    /** Erst die kleine Fassung, sonst das Bild aus der Rangliste. */
    private fun downloadTo(name: String, url: String): Boolean {
        val file = File(imageDir, name)
        for (candidate in listOf(CoinLogos.smallUrl(url), url).distinct()) {
            try {
                val bitmap = download(candidate) ?: continue
                writePng(file, bitmap)
                bitmap.recycle()
                return true
            } catch (e: Exception) {
                Timber.d("Logo %s: %s", name, e.message)
            }
        }
        return false
    }

    /** Zuordnung aus Datei bzw. frisch von CoinGecko; bei Fehlern die alte (auch abgelaufen). */
    private suspend fun symbolMap(): Map<String, String> = mapMutex.withLock { lockedSymbolMap() }

    private suspend fun lockedSymbolMap(): Map<String, String> {
        val now = System.currentTimeMillis()
        val known = map ?: withContext(Dispatchers.IO) {
            CoinLogos.decode(runCatching { mapFile.readText() }.getOrNull())
        }.also { map = it }
        if (CoinLogos.isFresh(prefs.getLong(KEY_MAP_TIME, 0L), now) && known.isNotEmpty()) return known
        // Letzter Versuch scheiterte vor Kurzem: nicht bei jedem Start erneut fragen
        if (CoinLogos.isFresh(prefs.getLong(KEY_MAP_ATTEMPT, 0L), now, MAP_RETRY_MILLIS)) return known
        prefs.edit { putLong(KEY_MAP_ATTEMPT, now) }

        val fresh = withContext(Dispatchers.IO) { fetchMap() }
        if (fresh.isEmpty()) return known
        withContext(Dispatchers.IO) {
            runCatching {
                mapFile.parentFile?.mkdirs()
                mapFile.writeText(CoinLogos.encode(fresh))
            }.onFailure { Timber.w(it, "Logo-Zuordnung nicht gespeichert") }
        }
        prefs.edit { putLong(KEY_MAP_TIME, now) }
        map = fresh
        return fresh
    }

    /**
     * CoinGecko Seite für Seite (bricht bei einem Fehler ab und behält, was da ist), danach füllt
     * die Binance-Symbolliste die Lücken (TradFi wie Gold, Silber, Aktien).
     */
    private suspend fun fetchMap(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fetchCoinGecko(out)
        fetchBinance(out)
        return out
    }

    private suspend fun fetchCoinGecko(out: LinkedHashMap<String, String>) {
        for (page in 1..CoinLogos.PAGES) {
            try {
                val array = JSONArray(apiClient.callMarket(CoinLogos.marketsUrl(page), null))
                val ranked = (0 until array.length()).mapNotNull { i ->
                    val o = array.optJSONObject(i) ?: return@mapNotNull null
                    o.optString("symbol") to (if (o.isNull("image")) null else o.optString("image"))
                }
                CoinLogos.pick(ranked, out)
                if (array.length() < CoinLogos.PER_PAGE) break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Logo-Rangliste Seite %d nicht verfügbar: %s", page, e.message)
                break
            }
        }
    }

    /** Inoffizielle Liste der Binance-Website; fehlt sie, bleiben die Lücken (Initialen). */
    private suspend fun fetchBinance(out: LinkedHashMap<String, String>) {
        try {
            val array = JSONObject(apiClient.callMarket(CoinLogos.BINANCE_LIST_URL, null)).optJSONArray("data") ?: return
            val entries = (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val logo = if (o.isNull("logo")) null else o.optString("logo")
                o.optString("name").ifEmpty { o.optString("baseAsset") } to logo
            }
            CoinLogos.pick(entries, out)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Binance-Symbolliste nicht verfügbar: %s", e.message)
        }
    }

    /** Fehlgeschlagene Bilder (Name → Zeitpunkt) der letzten [CoinLogos.FAILURE_TTL_MILLIS]. */
    private fun loadFailures(now: Long): Map<String, Long> =
        prefs.getString(KEY_FAILED, null).orEmpty().split(';').mapNotNull { part ->
            val name = part.substringBefore(':')
            val at = part.substringAfter(':', "").toLongOrNull() ?: return@mapNotNull null
            if (name.isEmpty() || !CoinLogos.isFresh(at, now, CoinLogos.FAILURE_TTL_MILLIS)) null else name to at
        }.toMap()

    private fun saveFailures(failures: Map<String, Long>) {
        prefs.edit { putString(KEY_FAILED, failures.entries.joinToString(";") { "${it.key}:${it.value}" }) }
    }

    private fun download(url: String): Bitmap? {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body
            if (body.contentLength() > MAX_IMAGE_BYTES) error("too large")
            val bytes = body.bytes()
            if (bytes.size > MAX_IMAGE_BYTES) error("too large")
            return decodeScaled(bytes)
        }
    }

    /** Verkleinert beim Dekodieren (inSampleSize) und danach genau auf [CoinLogos.STORED_PX]. */
    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= CoinLogos.STORED_PX) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val longest = max(decoded.width, decoded.height)
        if (longest <= CoinLogos.STORED_PX) return decoded
        val scale = CoinLogos.STORED_PX.toFloat() / longest
        return Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        ).also { if (it !== decoded) decoded.recycle() }
    }

    private fun decodeFile(file: File): Bitmap? = runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()

    private fun writePng(file: File, bitmap: Bitmap) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        runCatching {
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!tmp.renameTo(file)) tmp.delete()
        }.onFailure { tmp.delete() }
    }

    private companion object {
        const val PREFS = "coin_logos"
        // «v2»: Zuordnung mit Binance-Lücken; ältere Testinstallationen holen sie einmal neu
        const val KEY_MAP_TIME = "map_time_v2"
        const val KEY_MAP_ATTEMPT = "map_attempt_v2"
        const val KEY_FAILED = "failed"
        const val MAP_FILE = "coin_logos/map2.txt"
        const val IMAGE_DIR = "coin_logos"
        const val MEMORY_BYTES = 4 * 1024 * 1024
        const val MAX_PARALLEL_DOWNLOADS = 4
        const val MAX_IMAGE_BYTES = 512L * 1024

        /** Anzeigen alle so viele neue Logos auffrischen. */
        const val REVISION_STEP = 25

        /** Rangliste nicht erreichbar: frühestens nach einer Stunde erneut. */
        const val MAP_RETRY_MILLIS = 60L * 60 * 1000
    }
}
