package com.cryptochecker.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.core.content.edit
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

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
        val failed = loadFailures(now)
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
            val wait = shortestPauseMillis(paused) ?: break
            if (wait > SHORT_PAUSE_MAX_MILLIS) break
            delay(wait)
            pending = paused
        }
        // Am fehlenden Netz gescheitert: nicht einen Tag sperren, sondern mit dem Netz nachholen
        // (gebremste Bilder zählen nicht als Versuch — sonst galt «alle gebremst» als Netzfehler)
        val network = CoinLogoRetry.downloadsFailedForNetwork(connectivity.isOnline(), newFailures.size, attempted - paused.size)
        if (newFailures.isNotEmpty() && !network) saveFailures(loadFailures(System.currentTimeMillis()) + newFailures)
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

    /** Kürzeste verbleibende Pause der Server von [jobs] (ms); null = keiner pausiert. */
    private fun shortestPauseMillis(jobs: List<Pair<String, String>>): Long? {
        val now = System.currentTimeMillis()
        return jobs.mapNotNull { CoinLogoRetry.hostOf(it.second) }.distinct()
            .mapNotNull { host -> imagePauses[host]?.let { (it - now).coerceAtLeast(0L) } }
            .minOrNull()
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
        prefs.edit { remove(KEY_FAILED) }
        Timber.i("Coin-Logos: %d geänderte Bilder werden neu geladen", changed.size)
        return changed
    }

    /** Ablage der früheren Ersatz-Logos (jsDelivr) einmal wegräumen. */
    suspend fun removeOldFallback() {
        withContext(Dispatchers.IO) {
            val oldDir = File(context.cacheDir, OLD_FALLBACK_DIR)
            if (oldDir.exists()) {
                oldDir.deleteRecursively()
                File(context.noBackupFilesDir, OLD_FALLBACK_MANIFEST_FILE).delete()
                prefs.edit { remove(OLD_KEY_FALLBACK_ATTEMPT) }
            }
        }
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
     * [Fetched.PAUSED] (gespeichert in [imagePauses]), das Bild gilt nicht als fehlgeschlagen.
     * Abbruch der Coroutine bricht auch die laufende Anfrage ab ([download] wartet ohne zu blockieren).
     */
    private suspend fun fetchTo(name: String, url: String, dir: File): Fetched {
        val host = CoinLogoRetry.hostOf(url)
        if (host != null && isPaused(host)) return Fetched.PAUSED
        val file = File(dir, name)
        for (candidate in listOf(CoinLogos.smallUrl(url), url).distinct()) {
            try {
                val png = download(candidate) ?: continue
                writeBytes(file, png)
                return Fetched.OK
            } catch (e: CancellationException) {
                throw e
            } catch (e: ImagePause) {
                pauseHost(e.host, e.retryAfterSeconds)
                return Fetched.PAUSED
            } catch (e: Exception) {
                Timber.d("Logo %s: %s", name, e.message)
            }
        }
        return Fetched.FAILED
    }

    /** Laufende Pausen je Bild-Server (Host → bis, ms); gespeichert, damit ein Neustart sie beachtet. */
    private val imagePauses = ConcurrentHashMap<String, Long>()
    @Volatile private var pausesLoaded = false

    private fun isPaused(host: String): Boolean {
        val now = System.currentTimeMillis()
        if (!pausesLoaded) {
            imagePauses.putAll(CoinLogoRetry.decodePauses(prefs.getString(KEY_IMAGE_PAUSES, null), now))
            pausesLoaded = true
        }
        val until = imagePauses[host] ?: return false
        if (until > now) return true
        imagePauses.remove(host)
        return false
    }

    private fun pauseHost(host: String, retryAfterSeconds: Long?) {
        if (host.isEmpty()) return
        val now = System.currentTimeMillis()
        val until = now + CoinLogoRetry.imagePauseMillis(retryAfterSeconds)
        // Schon pausiert (parallele Abrufe): Pause nicht verlängern
        if ((imagePauses[host] ?: 0L) > now) return
        imagePauses[host] = until
        prefs.edit { putString(KEY_IMAGE_PAUSES, CoinLogoRetry.encodePauses(imagePauses.filterValues { it > now })) }
        Timber.i("Coin-Logos: %s bremst, Pause bis %d", host, until)
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

    /** Bild-Server verlangt eine Pause ([CoinLogoRetry.pausesImages]). */
    private class ImagePause(val host: String, val retryAfterSeconds: Long?) : Exception("pause $host")

    /**
     * Bild laden → PNG-Bytes zum Speichern; null = kein lesbares Bild. Wartet auf die Antwort ohne einen Thread
     * zu blockieren ([await]): wird die Coroutine abgebrochen (Abgleich beendet), bricht auch die Anfrage ab.
     */
    private suspend fun download(url: String): ByteArray? {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
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

    /**
     * Zu speichernde PNG-Bytes: schon ein PNG in passender Grösse (CoinGecko «small», viele
     * Binance-Logos) → unverändert, ohne Umrechnen (spart Zeit beim Laden aller Logos); sonst
     * verkleinert auf [CoinLogos.STORED_PX] und als PNG.
     */
    private fun pngBytes(bytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (isPng(bytes) && max(bounds.outWidth, bounds.outHeight) <= CoinLogos.STORED_PX) return bytes
        val bitmap = decodeScaled(bytes, bounds) ?: return null
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size > PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }

    /** Verkleinert beim Dekodieren (inSampleSize) und danach genau auf [CoinLogos.STORED_PX]. */
    private fun decodeScaled(bytes: ByteArray, bounds: BitmapFactory.Options): Bitmap? {
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

    /** Erst in eine Hilfsdatei, dann umbenennen (nie ein halbes Bild). */
    private fun writeBytes(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        runCatching {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) tmp.delete()
        }.onFailure { tmp.delete() }
    }

    private companion object {
        const val KEY_FAILED = "failed"
        /** Kürzel, die nur die Zusatzseiten der Rangliste bringen ([CoinLogos.extraOnlyKeys]). */
        const val KEY_EXTRA_ONLY = "extra_only_v1"
        /** Pausen je Bild-Server ([CoinLogoRetry.encodePauses]). */
        const val KEY_IMAGE_PAUSES = "image_pauses_v1"
        /** Mitgelieferte Icons im Speicher (eigene Vorsilbe: Vorrang haben die geladenen Logos). */
        const val BUNDLED_MEMORY_PREFIX = "B_"
        /** Ablage der früheren Ersatz-Logos über jsDelivr (wird einmal gelöscht). */
        const val OLD_FALLBACK_DIR = "coin_logos_fallback"
        const val OLD_FALLBACK_MANIFEST_FILE = "coin_logos/fallback_manifest_0_18_1.json"
        const val OLD_KEY_FALLBACK_ATTEMPT = "fallback_attempt_v1"
        /** Wie iOS («cryptoChecker-iOS/16»); ohne Angabe lehnen manche Bild-Server Abrufe ab. */
        const val USER_AGENT = "cryptoChecker-Android/16"
        const val MEMORY_BYTES = 4 * 1024 * 1024
        /** Bild-Abrufe gleichzeitig: insgesamt und je Server (CoinGecko, Binance). */
        const val MAX_PARALLEL_DOWNLOADS = 12
        const val PER_HOST_PARALLEL = 6
        const val MAX_IMAGE_BYTES = 512L * 1024

        /** Bremst ein Server höchstens so kurz, werden seine Bilder nach der Pause gleich nachgeholt. */
        const val SHORT_PAUSE_MAX_MILLIS = 2 * 60_000L
        const val PAUSE_ROUNDS = 3

        /** Anfang jeder PNG-Datei. */
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        /** Anzeigen während des Ladens höchstens so oft auffrischen. */
        const val REVISION_INTERVAL_MILLIS = 1_000L
    }
}
