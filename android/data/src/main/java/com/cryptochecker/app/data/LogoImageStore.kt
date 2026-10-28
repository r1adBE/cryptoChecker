package com.cryptochecker.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.core.content.edit
import com.cryptochecker.app.data.remote.AppUserAgent
import com.cryptochecker.app.data.remote.HttpUserAgent
import com.cryptochecker.app.data.remote.util.HttpBodyLimit
import com.cryptochecker.app.data.remote.util.await
import com.cryptochecker.app.domain.logos.CoinLogoRetry
import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.refresh.ExchangeBackoff
import com.cryptochecker.app.util.ConnectivityMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import com.cryptochecker.app.domain.logos.bundledIconName
import com.cryptochecker.app.domain.logos.parseIconPack
import com.cryptochecker.app.data.LogoImageCodec.decodeFile
import com.cryptochecker.app.data.LogoImageCodec.pngBytes
import com.cryptochecker.app.data.LogoImageCodec.writeBytes

/**
 * Coin-Logos auf dem Gerät: Bilder im Speicher ([memory]) und in Dateien, das mitgelieferte Icon-Paket
 * ([CoinLogos.ICON_PACK_FILE], CC0) und das Laden fehlender Bilder von den Bildservern ([downloadMissing]) —
 * mehrere gleichzeitig je Server ([PER_HOST_PARALLEL]); bremst ein Server (429/403/503), pausiert nur dieser.
 * Welche Bilder es gibt, sagt die Zuordnung von [CoinListSync]. Teil von [CoinLogoRepository].
 *
 * Sperren: nur die Semaphoren der Abrufe (je Server, dann gesamt — nie gesamt blockieren); keine Mutex. Wird aus
 * [CoinListSync] auch unter dessen Sperre der Zuordnung aufgerufen ([dropChangedImages]) — das ist sicher, weil
 * hier nichts auf [CoinListSync] wartet.
 */
@Singleton
class LogoImageStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    httpClient: OkHttpClient,
    private val connectivity: ConnectivityMonitor,
    /** Wie die Börsen-Abfragen und iOS («cryptoChecker-iOS/16.2.2»): mit der vollen App-Version ([AppUserAgent]). */
    @param:AppUserAgent private val userAgent: String,
) {
    /** Gleiche Verbindungen, aber ohne Mitschnitt (sonst landen Bild-Bytes im Debug-Protokoll). */
    private val client: OkHttpClient = httpClient.newBuilder().apply { interceptors().clear() }.build()

    private val prefs by lazy { CoinLogoStorage.prefs(context) }
    private val imageDir by lazy { File(context.cacheDir, CoinLogoStorage.IMAGE_DIR).apply { mkdirs() } }

    /** Zuletzt benutzte Bilder, höchstens ~4 MB. */
    private val memory = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val downloads = Semaphore(MAX_PARALLEL_DOWNLOADS)

    /** Gleichzeitige Bild-Abrufe je Server ([PER_HOST_PARALLEL]). */
    private val hostPermits = ConcurrentHashMap<String, Semaphore>()

    /** Pausen je Bild-Server und fehlgeschlagene Bilder (gespeichert in [prefs]). */
    private val pauses = LogoImagePauses { prefs }

    private val _revision = MutableStateFlow(0)

    /** Steigt, sobald neue Logos auf dem Gerät liegen (Anzeigen laden dann neu). */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** Sofort, ohne Datei und Netz: Bild aus dem Speicher oder null (mitgeliefertes Icon, falls nur das da ist). */
    fun cached(symbol: String): Bitmap? = CoinLogos.fileName(symbol)?.let { name ->
        memory.get(name) ?: CoinLogos.bundledIconName(symbol)?.let { memory.get(BUNDLED_MEMORY_PREFIX + it) }
    }

    /**
     * Logo für [symbol] aus Speicher oder Datei — nie aus dem Netz. Vorrang hat das geladene Logo der
     * Listen (CoinGecko, Binance, Alpha), sonst das mitgelieferte Icon ([bundled]). null → Initialen.
     */
    suspend fun logo(symbol: String): Bitmap? {
        val name = CoinLogos.fileName(symbol) ?: return null
        memory.get(name)?.let { return it }
        return withContext(Dispatchers.IO) {
            readImage(File(imageDir, name), name) ?: bundled(symbol)
        }
    }

    /** Bild aus Datei in den Speicher ([key]); kaputte Datei wird gelöscht. */
    private fun readImage(file: File, key: String): Bitmap? {
        memory.get(key)?.let { return it }
        if (!file.isFile) return null
        val bitmap = decodeFile(file)
        if (bitmap == null) file.delete() else memory.put(key, bitmap)
        return bitmap
    }

    /** Mitgeliefertes Icon-Paket ([CoinLogos.ICON_PACK_FILE]): Bytes und Inhaltsverzeichnis, einmal gelesen. */
    private val iconPack: Pair<ByteArray, Map<String, CoinLogos.IconSlice>>? by lazy {
        val bytes = runCatching { context.assets.open(CoinLogos.ICON_PACK_FILE).use { it.readBytes() } }
            .onFailure { Timber.w("Icon-Paket nicht lesbar: %s", it.message) }
            .getOrNull()
        bytes?.let { b -> CoinLogos.parseIconPack(b)?.let { b to it } }
    }

    /** Mitgeliefertes Icon (CC0) für [symbol]; null, wenn das Paket es nicht kennt. */
    private fun bundled(symbol: String): Bitmap? {
        val name = CoinLogos.bundledIconName(symbol) ?: return null
        val key = BUNDLED_MEMORY_PREFIX + name
        memory.get(key)?.let { return it }
        val (bytes, index) = iconPack ?: return null
        val slice = index[name] ?: return null
        val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, slice.offset, slice.length) }.getOrNull()
            ?: return null
        memory.put(key, bitmap)
        return bitmap
    }

    /**
     * Erster Start: die Logos der Start-Coins ([symbols]) holen — Adressen aus der Zuordnung [current]
     * ([CoinListSync]). Siehe [CoinLogoRepository.ensureStarterLogos].
     * @return true, wenn danach alle Logos da sind (fehlt eines, zeigt die Zeile Initialen)
     */
    suspend fun ensureStarterLogos(symbols: Collection<String>, current: Map<String, String>): Boolean {
        var added = 0
        val complete = withContext(Dispatchers.IO) {
            symbols.map { symbol ->
                val name = CoinLogos.fileName(symbol) ?: return@map true
                if (File(imageDir, name).isFile) return@map true
                val url = current[symbol.uppercase()] ?: return@map false
                downloadTo(name, url).also { if (it) added++ }
            }.all { it }
        }
        // Gleich in den Speicher, damit die Zeilen das Bild ohne Dateizugriff zeigen
        symbols.forEach { logo(it) }
        if (added > 0) bumpRevision(force = true)
        return complete
    }

    /** Ergebnis eines Bild-Durchgangs; [network] = am fehlenden Netz gescheitert. */
    data class Downloads(val added: Int = 0, val network: Boolean = false) {
        operator fun plus(o: Downloads) = Downloads(added + o.added, network || o.network)
    }

    /**
     * Lädt alle Logos aus [all], die noch fehlen (nicht fehlgeschlagen, keine Datei): mehrere
     * gleichzeitig je Server ([PER_HOST_PARALLEL], insgesamt [MAX_PARALLEL_DOWNLOADS]). Bremst ein Server
     * nur kurz (Pause bis [SHORT_PAUSE_MAX_MILLIS]), werden seine Bilder nach der Pause gleich nachgeholt.
     */
    suspend fun downloadMissing(all: Map<String, String>): Downloads {
        // Mobile Daten: Logos, die nur die Zusatzseiten der Rangliste bringen, erst im WLAN
        val wanted = CoinLogos.downloadable(all, loadExtraOnly(), CoinLogoStorage.isUnmetered(context))
        if (wanted.isEmpty()) return Downloads()
        val now = System.currentTimeMillis()
        val failed = pauses.loadFailures(now)
        var pending = withContext(Dispatchers.IO) {
            wanted.entries.mapNotNull { (symbol, url) ->
                val name = CoinLogos.fileName(symbol) ?: return@mapNotNull null
                if (name in failed || File(imageDir, name).isFile) null else name to url
            }.distinctBy { it.first }
        }
        if (pending.isEmpty()) return Downloads()
        val attempted = pending.size
        val added = AtomicInteger()
        val newFailures = ConcurrentHashMap<String, Long>()
        var paused: List<Pair<String, String>> = emptyList()
        for (round in 1..PAUSE_ROUNDS) {
            val pausedNow = ConcurrentLinkedQueue<Pair<String, String>>()
            coroutineScope {
                pending.map { job ->
                    async(Dispatchers.IO) {
                        when (fetchLimited(job.first, job.second)) {
                            Fetched.OK -> {
                                added.incrementAndGet()
                                bumpRevision()
                            }
                            Fetched.FAILED -> newFailures[job.first] = now
                            // Server bremst: nicht als Fehler
                            Fetched.PAUSED -> pausedNow.add(job)
                        }
                        Unit
                    }
                }.awaitAll()
            }
            paused = pausedNow.toList()
            if (paused.isEmpty() || round == PAUSE_ROUNDS) break
            val wait = pauses.shortestPauseMillis(paused) ?: break
            if (wait > SHORT_PAUSE_MAX_MILLIS) break
            delay(wait)
            pending = paused
        }
        // Am fehlenden Netz gescheitert: nicht einen Tag sperren, sondern mit dem Netz nachholen
        // (gebremste Bilder zählen nicht als Versuch — sonst galt «alle gebremst» als Netzfehler)
        val network = CoinLogoRetry.downloadsFailedForNetwork(connectivity.isOnline(), newFailures.size, attempted - paused.size)
        if (newFailures.isNotEmpty() && !network) pauses.saveFailures(pauses.loadFailures(System.currentTimeMillis()) + newFailures)
        if (added.get() > 0) bumpRevision(force = true)
        Timber.i("Coin-Logos: %d neu, %d fehlgeschlagen, %d gebremst", added.get(), newFailures.size, paused.size)
        return Downloads(added.get(), network)
    }

    /** Ein Bild mit den Grenzen je Server und insgesamt (erst Server, dann gesamt — nie gesamt blockieren). */
    private suspend fun fetchLimited(name: String, url: String): Fetched {
        val host = CoinLogoRetry.hostOf(url).orEmpty()
        val perHost = hostPermits.getOrPut(host) { Semaphore(PER_HOST_PARALLEL) }
        return perHost.withPermit { downloads.withPermit { fetchTo(name, url, imageDir) } }
    }

    private fun loadExtraOnly(): Set<String> =
        runCatching { prefs.getStringSet(KEY_EXTRA_ONLY, null)?.toSet() }.getOrNull() ?: emptySet()

    /** Kürzel, die nur die Zusatzseiten der Rangliste bringen ([CoinLogos.extraOnlyKeys]); ihre Logos nur im WLAN. */
    fun saveExtraOnly(keys: Set<String>) {
        runCatching { prefs.edit { putStringSet(KEY_EXTRA_ONLY, keys) } }
    }

    /**
     * Gespeicherte Bilder der Kürzel löschen, deren Bild-Adresse sich zwischen [old] und [new]
     * geändert hat (ausser [skip]); Ergebnis: diese Kürzel.
     */
    suspend fun dropChangedImages(old: Map<String, String>, new: Map<String, String>, skip: Set<String> = emptySet()): Set<String> {
        val changed = CoinLogos.changedKeys(old, new) - skip
        if (changed.isEmpty()) return changed
        withContext(Dispatchers.IO) {
            for (key in changed) {
                val name = CoinLogos.fileName(key) ?: continue
                memory.remove(name)
                File(imageDir, name).delete()
            }
        }
        prefs.edit { remove(LogoImagePauses.KEY_FAILED) }
        Timber.i("Coin-Logos: %d geänderte Bilder werden neu geladen", changed.size)
        return changed
    }

    /** Anzeigen auffrischen: höchstens alle [REVISION_INTERVAL_MILLIS] (sonst bei jedem Bild), [force] sofort. */
    private val lastRevisionAt = AtomicLong(0L)

    private fun bumpRevision(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force) {
            lastRevisionAt.set(now)
            _revision.update { it + 1 }
            return
        }
        val last = lastRevisionAt.get()
        if (now - last >= REVISION_INTERVAL_MILLIS && lastRevisionAt.compareAndSet(last, now)) _revision.update { it + 1 }
    }

    /** Erst die kleine Fassung, sonst das Bild aus der Rangliste. */
    private suspend fun downloadTo(name: String, url: String, dir: File = imageDir): Boolean = fetchTo(name, url, dir) == Fetched.OK

    private enum class Fetched { OK, FAILED, PAUSED }

    /**
     * Wie [downloadTo], aber mit Pause des Bild-Servers ([CoinLogoRetry.pausesImages]): dann
     * [Fetched.PAUSED] (gespeichert in [LogoImagePauses]), das Bild gilt nicht als fehlgeschlagen.
     * Abbruch der Coroutine bricht auch die laufende Anfrage ab ([download] wartet ohne zu blockieren).
     */
    private suspend fun fetchTo(name: String, url: String, dir: File): Fetched {
        val host = CoinLogoRetry.hostOf(url)
        if (host != null && pauses.isPaused(host)) return Fetched.PAUSED
        val file = File(dir, name)
        for (candidate in listOf(CoinLogos.smallUrl(url), url).distinct()) {
            try {
                val png = download(candidate) ?: continue
                writeBytes(file, png)
                return Fetched.OK
            } catch (e: CancellationException) {
                throw e
            } catch (e: ImagePause) {
                pauses.pauseHost(e.host, e.retryAfterSeconds)
                return Fetched.PAUSED
            } catch (e: Exception) {
                Timber.d("Logo %s: %s", name, e.message)
            }
        }
        return Fetched.FAILED
    }

    /** Bild-Server verlangt eine Pause ([CoinLogoRetry.pausesImages]). */
    private class ImagePause(val host: String, val retryAfterSeconds: Long?) : Exception("pause $host")

    /**
     * Bild laden → PNG-Bytes zum Speichern; null = kein lesbares Bild. Wartet auf die Antwort ohne einen Thread
     * zu blockieren ([await]): wird die Coroutine abgebrochen (Abgleich beendet), bricht auch die Anfrage ab.
     */
    private suspend fun download(url: String): ByteArray? {
        val request = Request.Builder().url(url).header(HttpUserAgent.HEADER, userAgent).build()
        client.newCall(request).await().use { response ->
            if (CoinLogoRetry.pausesImages(response.code)) {
                throw ImagePause(
                    CoinLogoRetry.hostOf(url) ?: "",
                    ExchangeBackoff.parseRetryAfterSeconds(response.header("Retry-After"), System.currentTimeMillis()),
                )
            }
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body
            // Auch ohne Content-Length nicht mehr als die Höchstgrösse lesen
            val bytes = HttpBodyLimit.read(body.byteStream(), body.contentLength(), MAX_IMAGE_BYTES)
                ?: error("too large")
            return pngBytes(bytes)
        }
    }

    private companion object {
        /** Kürzel, die nur die Zusatzseiten der Rangliste bringen ([CoinLogos.extraOnlyKeys]). */
        const val KEY_EXTRA_ONLY = "extra_only"
        /** Mitgelieferte Icons im Speicher (eigene Vorsilbe: Vorrang haben die geladenen Logos). */
        const val BUNDLED_MEMORY_PREFIX = "B_"
        const val MEMORY_BYTES = 4 * 1024 * 1024
        /** Bild-Abrufe gleichzeitig: insgesamt und je Server (CoinGecko, Binance). */
        const val MAX_PARALLEL_DOWNLOADS = 12
        const val PER_HOST_PARALLEL = 6
        /**
         * Höchstgrösse eines Quellbilds (wie iOS `CoinLogoStore.maxImageBytes`): 4 MB — Binance lädt Logos seit 2025
         * in hoher Auflösung hoch (über 512 KB, dann standen Initialen). Gespeichert wird nur das verkleinerte Bild
         * ([CoinLogos.STORED_PX]); die Pixel-Grenze [CoinLogos.MAX_SOURCE_PIXELS] bleibt.
         */
        const val MAX_IMAGE_BYTES = 4L * 1024 * 1024

        /** Bremst ein Server höchstens so kurz, werden seine Bilder nach der Pause gleich nachgeholt. */
        const val SHORT_PAUSE_MAX_MILLIS = 2 * 60_000L
        const val PAUSE_ROUNDS = 3

        /** Anzeigen während des Ladens höchstens so oft auffrischen. */
        const val REVISION_INTERVAL_MILLIS = 1_000L
    }
}
