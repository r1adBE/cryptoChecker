package com.cryptochecker.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.core.content.edit
import com.cryptochecker.app.data.remote.AppUserAgent
import com.cryptochecker.app.domain.logos.CoinLogoQueue
import com.cryptochecker.app.domain.logos.CoinLogoRetry
import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.logos.LogoDiagnostics
import com.cryptochecker.app.domain.logos.LogoLoadingHint
import com.cryptochecker.app.domain.logos.LogoPassSummary
import com.cryptochecker.app.data.util.ConnectivityMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import com.cryptochecker.app.domain.logos.bundledIconName
import com.cryptochecker.app.domain.logos.parseIconPack
import com.cryptochecker.app.data.LogoImageCodec.decodeFile
import com.cryptochecker.app.data.LogoImageFetcher.Fetched

/**
 * Coin-Logos auf dem Gerät: Bilder im Speicher ([memory]) und in Dateien, das mitgelieferte Icon-Paket
 * ([CoinLogos.ICON_PACK_FILE], die 100 grössten Coins) und das Laden fehlender Bilder von den Bildservern ([downloadMissing]) —
 * für alle in derselben Reihenfolge, höchstens [CoinLogoQueue.MAX_PARALLEL] gleichzeitig, je Server
 * [CoinLogoQueue.PER_HOST_PARALLEL]; bremst ein Server (429/403/503), pausiert nur dieser.
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
    @AppUserAgent userAgent: String,
) {

    private val prefs by lazy { CoinLogoStorage.prefs(context) }
    /** Dauerhaft wie iOS (App-Gruppe), nicht im Cache: den darf Android bei Platzmangel leeren, dann fehlten Logos. */
    private val imageDir by lazy { File(context.noBackupFilesDir, CoinLogoStorage.IMAGE_DIR).apply { mkdirs(); LogoFiles.removeStaleTemps(this, System.currentTimeMillis()) } }

    /** Zuletzt benutzte Bilder, höchstens ~4 MB. */
    private val memory = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    /** Pausen je Bild-Server und fehlgeschlagene Bilder (gespeichert in [prefs]). */
    private val pauses = LogoImagePauses { prefs }

    /** Bild-Abrufe ([LogoImageFetcher]): Grenzen je Server und gesamt, Pausen, Einordnung der Antwort. */
    private val fetcher = LogoImageFetcher(httpClient, userAgent, { imageDir }, pauses)

    private val _revision = MutableStateFlow(0)

    /** Steigt, sobald neue Logos auf dem Gerät liegen (Anzeigen laden dann neu). */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** «Logos werden geladen · 40 %» (nur erstes Laden, gemerkt im Logo-Ordner): Durchgänge von [CoinListSync], [downloadMissing]. */
    val loadingHint = LogoLoadingHint(doneMarker = { File(imageDir, LogoLoadingHint.DONE_MARKER) })

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

    /**
     * Bild aus Datei in den Speicher ([key]); kaputte Datei wird gelöscht. Nur bei wirklich unlesbarer Datei — bei
     * Speichermangel bleibt sie (diesmal Initialen, beim nächsten Zeichnen wieder das Logo); früher löschte ein solcher
     * Moment gute Logos, die dann bis zum nächsten Abgleich fehlten.
     */
    private fun readImage(file: File, key: String): Bitmap? {
        memory.get(key)?.let { return it }
        if (!file.isFile) return null
        val bitmap = try {
            decodeFile(file)
        } catch (e: OutOfMemoryError) {
            Timber.w("Logo %s: zu wenig Speicher zum Lesen, Datei bleibt", key)
            return null
        }
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

    /** Mitgeliefertes Logo (die 100 grössten Coins) für [symbol]; null, wenn das Paket es nicht kennt. */
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
     * Erster Start: die Logos der Start-Coins ([symbols]) holen — Adressen aus [current] ([CoinLogoRepository.ensureStarterLogos]).
     * @return true, wenn danach alle Logos da sind (fehlt eines, zeigt die Zeile Initialen)
     */
    suspend fun ensureStarterLogos(symbols: Collection<String>, current: Map<String, String>): Boolean {
        var added = 0
        val complete = withContext(Dispatchers.IO) {
            val missing = symbols.filter { symbol -> CoinLogos.fileName(symbol)?.let { !File(imageDir, it).isFile } == true }
            // Adresse wie beim Zusammenführen («1000CAT» → «CAT»); je Datei ein Abruf
            val wanted = missing.mapNotNull { symbol -> CoinLogos.urlFor(current, symbol)?.let { symbol to it } }
            val streaks = LogoImagePauses.ForbiddenStreaks()
            // Gleichzeitig wie iOS, mit den Grenzen je Server und insgesamt ([fetchLimited])
            val results = coroutineScope {
                CoinLogos.downloadJobs(wanted) { File(imageDir, it).isFile }
                    .map { (name, url) -> async { fetcher.fetchLimited(name, url, streaks) == Fetched.OK } }.awaitAll()
            }
            added = results.count { it }
            wanted.size == missing.size && results.all { it }
        }
        // Gleich in den Speicher, damit die Zeilen das Bild ohne Dateizugriff zeigen
        symbols.forEach { logo(it) }
        if (added > 0) bumpRevision(force = true)
        return complete
    }

    /**
     * Lädt alle Logos aus [all], die noch fehlen (nicht fehlgeschlagen, keine Datei), in der Reihenfolge von [all] — für
     * alle gleich, nie nach der Merkliste —, mehrere gleichzeitig ([CoinLogoQueue.run]). Bremst ein Server nur kurz (bis
     * [SHORT_PAUSE_MAX_MILLIS]), gleich nachgeholt; scheitert ein Bild, die Ersatz-Adressen ([alternates],
     * [com.cryptochecker.app.domain.logos.CoinMeta.alternates]). [lastStage]: letzte Stufe des Durchgangs ([loadingHint]).
     */
    suspend fun downloadMissing(all: Map<String, String>, alternates: Map<String, List<String>> = emptyMap(),
                                lastStage: Boolean = false): LogoDownloads {
        // Mobile Daten: Logos, die nur die Zusatzseiten der Rangliste bringen, erst im WLAN
        val wanted = CoinLogos.downloadable(all, loadExtraOnly(), CoinLogoStorage.isUnmetered(context))
        if (wanted.isEmpty()) return LogoDownloads()
        val now = System.currentTimeMillis()
        val failed = pauses.loadFailures(now)
        // Je Datei höchstens ein Abruf (zwei Kürzel, eine Datei: nie gleichzeitig dieselbe Datei schreiben)
        var pending = withContext(Dispatchers.IO) {
            CoinLogos.downloadJobs(wanted.toList()) { name -> name in failed || File(imageDir, name).isFile }
        }
        if (pending.isEmpty()) return LogoDownloads()
        loadingHint.imagesPending(pending.size, last = lastStage)
        val altByFile = alternates.entries.mapNotNull { (key, urls) -> CoinLogos.fileName(key)?.let { it to urls } }.toMap()
        val names = pending.map { it.first }
        val added = AtomicInteger()
        val unsaved = AtomicInteger()
        val newFailures = ConcurrentHashMap<String, Long>()
        // 403 in Folge je Server in diesem Durchgang (erst die dritte pausiert den Server)
        val streaks = LogoImagePauses.ForbiddenStreaks()
        // Länger gebremste Bilder (Pause über [SHORT_PAUSE_MAX_MILLIS]): erst nach der Pause ([LogoDownloads.pausedUntil])
        var parked: List<Pair<String, String>> = emptyList()
        // Nach der letzten Runde noch gestört ([Fetched.TRANSIENT]): kein Fehlschlag, nächster Abgleich holt nach
        var transientLeft: List<Pair<String, String>> = emptyList()
        // 403 ohne «Retry-After» ([Fetched.OPEN]): nicht in diesem Durchgang wiederholen, offen bis zum nächsten
        val openLeft = ConcurrentHashMap.newKeySet<String>()
        for (round in 1..PAUSE_ROUNDS) {
            val pausedNow = ArrayList<Pair<String, String>>()
            val transientNow = ArrayList<Pair<String, String>>()
            CoinLogoQueue.run(
                pending,
                hostOf = { CoinLogoRetry.hostOf(it.second).orEmpty() },
                work = { job ->
                    withContext(Dispatchers.IO) { fetcher.fetchWithAlternates(job.first, job.second, altByFile[job.first].orEmpty(), streaks) }
                },
            ) { job, result ->
                when (result) {
                    Fetched.OK -> {
                        loadingHint.imageDone()
                        added.incrementAndGet()
                        bumpRevision()
                    }
                    Fetched.FAILED -> newFailures[job.first] = now.also { loadingHint.imageDone() }
                    // Speicher voll: weder Fehlschlag (Tagessperre) noch Netz — der nächste Abgleich versucht es wieder
                    Fetched.UNSAVED -> unsaved.incrementAndGet().also { loadingHint.imageDone() }
                    // Server bremst: nicht als Fehler (und kein stehender Prozentwert im Hinweis)
                    Fetched.PAUSED -> pausedNow.add(job).also { loadingHint.imagesPaused() }
                    // Kurze Störung: in der nächsten Runde noch einmal
                    Fetched.TRANSIENT -> transientNow.add(job)
                    // 403: offen bis zum nächsten Abgleich (kein Tagessperre, keine Zählung als Netzfehler)
                    Fetched.OPEN -> openLeft.add(job.first).also { loadingHint.imageDone() }
                }
            }
            transientLeft = transientNow
            // Pause schon vorbei (null): gleich die nächste Runde
            val pauseWait = if (pausedNow.isEmpty()) 0L else pauses.shortestPauseMillis(pausedNow) ?: 0L
            val waitForPaused = pausedNow.isNotEmpty() && pauseWait <= SHORT_PAUSE_MAX_MILLIS && round < PAUSE_ROUNDS
            if (!waitForPaused) parked = parked + pausedNow
            val next = (if (waitForPaused) pausedNow else emptyList()) + transientNow
            if (next.isEmpty() || round == PAUSE_ROUNDS) break
            delay(maxOf(if (waitForPaused) pauseWait else 0L, if (transientNow.isEmpty()) 0L else TRANSIENT_RETRY_MILLIS))
            pending = next
        }
        val paused = parked
        transientLeft.forEach { _ -> loadingHint.imageDone() } // Fortschritt schliessen (kommen mit dem nächsten Abgleich)
        // Am fehlenden Netz gescheitert: nicht einen Tag sperren, sondern mit dem Netz nachholen
        // (gebremste Bilder zählen nicht als Versuch — sonst galt «alle gebremst» als Netzfehler)
        val network = CoinLogoRetry.downloadsFailedForNetwork(
            connectivity.isOnline(), newFailures.size + transientLeft.size,
            names.size - paused.size - unsaved.get() - openLeft.size,
            transient = transientLeft.size,
        )
        // Drei Durchgänge in Folge offen (gestört, gebremst, 403) zählt wie gescheitert, sonst bliebe das erste Laden offen
        val stillOpen = (transientLeft + paused).map { it.first } + openLeft
        val capped = (if (network) emptySet() else pauses.noteOpenPasses(names, stillOpen)).onEach { newFailures[it] = now }
        if (newFailures.isNotEmpty() && !network) pauses.saveFailures(pauses.loadFailures(System.currentTimeMillis()) + newFailures)
        if (added.get() > 0) bumpRevision(force = true)
        Timber.i("Coin-Logos: %d neu, %d fehlgeschlagen, %d gebremst, %d gestört, %d offen (403), %d nicht gespeichert",
            added.get(), newFailures.size, paused.size, transientLeft.size, openLeft.size, unsaved.get())
        recordPass(LogoPassSummary(System.currentTimeMillis(), added.get(), newFailures.size, paused.size,
            transientLeft.size + openLeft.count { it !in capped }, unsaved.get()))
        // Gebremste Bilder übrig, Pause aber schon vorbei: gleich nachholen (Ende = jetzt)
        val pausedUntil = paused.filter { it.first !in capped }.takeIf { it.isNotEmpty() }
            ?.let { pauses.earliestPauseEnd(it) ?: System.currentTimeMillis() }
        return LogoDownloads(added.get(), network, pausedUntil, incomplete = transientLeft.any { it.first !in capped } || unsaved.get() > 0)
    }

    /** Letzten Durchgang für den Diagnosebericht merken ([LogoPassSummary]). */
    private fun recordPass(summary: LogoPassSummary) {
        runCatching { prefs.edit { putString(KEY_LAST_PASS, summary.encode()) } }
    }

    /** Für den Diagnosebericht: Bilder auf dem Gerät, gerade gesperrte, letzter Durchgang. Nie Netz; liest Dateien (IO). */
    fun diagnostics(): LogoDiagnostics = LogoDiagnostics(
        onDevice = imageDir.list()?.count { it.endsWith(".png") } ?: 0,
        locked = pauses.loadFailures(System.currentTimeMillis()).size,
        lastPass = LogoPassSummary.decode(prefs.getString(KEY_LAST_PASS, null)),
    )

    /**
     * Logo fehlt, kommt aber noch ([com.cryptochecker.app.domain.logos.MissingLogoNotice]): Adresse bekannt — auch nach
     * einem gescheiterten Abruf, der nach seiner Sperre wieder versucht wird. «Kein Logo verfügbar» nur, wenn keine Quelle
     * eines kennt; vorher hiess jeder gescheiterte Abruf (oft nur ein gebremster Bild-Server) «kein Logo».
     */
    fun notYetLoaded(symbol: String, map: Map<String, String>): Boolean = CoinLogos.urlFor(map, symbol) != null

    private fun loadExtraOnly(): Set<String> =
        runCatching { prefs.getStringSet(KEY_EXTRA_ONLY, null)?.toSet() }.getOrNull() ?: emptySet()

    /** Kürzel, die nur die Zusatzseiten der Rangliste bringen ([CoinLogos.extraOnlyKeys]); ihre Logos nur im WLAN. */
    fun saveExtraOnly(keys: Set<String>) {
        runCatching { prefs.edit { putStringSet(KEY_EXTRA_ONLY, keys) } }
    }

    /** Bilder der Kürzel löschen, deren Adresse sich zwischen [old] und [new] geändert hat (ausser [skip]); Ergebnis: diese. */
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

    private companion object {
        /** Kürzel, die nur die Zusatzseiten der Rangliste bringen ([CoinLogos.extraOnlyKeys]). */
        const val KEY_EXTRA_ONLY = "extra_only"
        /** Letzter Durchgang der Bilder ([LogoPassSummary]). */
        const val KEY_LAST_PASS = "last_pass"
        /** Mitgelieferte Icons im Speicher (eigene Vorsilbe: Vorrang haben die geladenen Logos). */
        const val BUNDLED_MEMORY_PREFIX = "B_"
        const val MEMORY_BYTES = 4 * 1024 * 1024

        /** Bremst ein Server höchstens so kurz, werden seine Bilder nach der Pause gleich nachgeholt. */
        const val SHORT_PAUSE_MAX_MILLIS = 2 * 60_000L
        const val PAUSE_ROUNDS = 3

        /** Kurze Störung: so lange warten, bevor die gestörten Bilder in der nächsten Runde erneut drankommen. */
        const val TRANSIENT_RETRY_MILLIS = 5_000L

        /** Anzeigen während des Ladens höchstens so oft auffrischen. */
        const val REVISION_INTERVAL_MILLIS = 1_000L
    }
}
