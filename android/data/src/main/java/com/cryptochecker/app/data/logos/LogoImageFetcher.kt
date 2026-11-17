// Ein Logo-Bild holen und speichern (Teil von `LogoImageStore`): Grenzen je Server und gesamt, Ersatz-Adressen,
// Pausen gebremster Server, Einordnung der Antwort ([LogoImageFetcher.Fetched]).
package com.cryptochecker.app.data.logos

import com.cryptochecker.app.data.remote.util.CallStartInterceptor
import com.cryptochecker.app.domain.logos.CoinLogoQueue
import com.cryptochecker.app.domain.logos.CoinLogoRetry
import com.cryptochecker.app.domain.logos.CoinLogos
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import timber.log.Timber

/**
 * Bild-Abrufe von [LogoImageStore]: [imageDir] = Ablage der Bilder, [pauses] = Pausen je Server (dieselben wie im Store).
 * Sperren: nur die Semaphoren (je Server, dann gesamt — nie gesamt blockieren).
 */
internal class LogoImageFetcher(
    httpClient: OkHttpClient,
    private val userAgent: String,
    private val imageDir: () -> File,
    private val pauses: LogoImagePauses,
) {
    /**
     * Gleiche Verbindungen, aber ohne Mitschnitt (sonst landen Bild-Bytes im Debug-Protokoll); das Startsignal der
     * Wache ([CallStartInterceptor]) bleibt.
     */
    private val client: OkHttpClient = httpClient.newBuilder()
        .apply {
            interceptors().removeAll { it !is LogoTestInterceptor }
            addInterceptor(CallStartInterceptor)
        }.build()

    /** Harte Grenzen auch für Ersatz-Adressen und Start-Logos ([CoinLogoQueue] ordnet nur nach dem ersten Server). */
    private val downloads = Semaphore(CoinLogoQueue.MAX_PARALLEL)

    /** Gleichzeitige Bild-Abrufe je Server ([CoinLogoQueue.PER_HOST_PARALLEL]). */
    private val hostPermits = ConcurrentHashMap<String, Semaphore>()

    /** Erst [url], scheitert es, die Ersatz-Adressen der Reihe nach; bremst dabei ein Server, gilt das Bild als gebremst. */
    suspend fun fetchWithAlternates(
        name: String,
        url: String,
        alternates: List<String>,
        streaks: LogoImagePauses.ForbiddenStreaks
    ): Fetched {
        var result = fetchLimited(name, url, streaks)
        if (result != Fetched.FAILED && result != Fetched.OPEN) return result
        var paused = false
        var transient = false
        var open = result == Fetched.OPEN
        for (alt in alternates) {
            if (alt == url) continue
            result = fetchLimited(name, alt, streaks)
            if (result == Fetched.OK || result == Fetched.UNSAVED) return result
            if (result == Fetched.PAUSED) paused = true
            if (result == Fetched.TRANSIENT) transient = true
            if (result == Fetched.OPEN) open = true
        }
        return when {
            paused -> Fetched.PAUSED
            transient -> Fetched.TRANSIENT
            open -> Fetched.OPEN
            else -> Fetched.FAILED
        }
    }

    /** Ein Bild mit den Grenzen je Server und insgesamt (erst Server, dann gesamt — nie gesamt blockieren). */
    suspend fun fetchLimited(name: String, url: String, streaks: LogoImagePauses.ForbiddenStreaks): Fetched {
        val host = CoinLogoRetry.hostOf(url).orEmpty()
        val perHost = hostPermits.getOrPut(host) { Semaphore(CoinLogoQueue.PER_HOST_PARALLEL) }
        return perHost.withPermit { downloads.withPermit { fetchTo(name, url, imageDir(), streaks) } }
    }

    /**
     * OK; FAILED = gibt es nicht bzw. unlesbar (Tagessperre); PAUSED = Server bremst; TRANSIENT = kurze Störung;
     * UNSAVED = geladen, aber nicht gespeichert (Speicher voll): keine Sperre, kein Netzfehler, Durchgang lädt nichts mehr;
     * OPEN = 403 ohne «Retry-After» ([CoinLogoRetry.leavesOpen]): bleibt offen bis zum nächsten Abgleich, erst nach
     * [CoinLogoRetry.OPEN_PASSES_FAIL] Durchgängen in Folge gesperrt — Bild-Server (CloudFront) antworten so auch beim
     * Bremsen, und ein gebremstes Logo galt sonst einen Tag lang als «kein Logo».
     */
    enum class Fetched { OK, FAILED, PAUSED, TRANSIENT, UNSAVED, OPEN }

    /**
     * Wie [downloadTo], aber mit Pause des Bild-Servers ([CoinLogoRetry.pausesImages]): dann
     * [Fetched.PAUSED] (gespeichert in [LogoImagePauses]), das Bild gilt nicht als fehlgeschlagen.
     * Ein 403 ohne «Retry-After» ist nur dieses Bild (erst die kleine, dann die grosse Fassung); erst das dritte
     * Bild des Servers in Folge mit 403 ([streaks]) pausiert den Server. Abbruch der Coroutine bricht auch die
     * laufende Anfrage ab ([download] wartet ohne zu blockieren).
     */
    private suspend fun fetchTo(name: String, url: String, dir: File, streaks: LogoImagePauses.ForbiddenStreaks): Fetched {
        // Speicher voll: nichts mehr herunterladen, das ohnehin verworfen würde
        if (streaks.unsaved) return Fetched.UNSAVED
        val host = CoinLogoRetry.hostOf(url)
        if (host != null && pauses.isPaused(host)) return Fetched.PAUSED
        val file = File(dir, name)
        // Letzte Antwort war 403 (für die Folge je Server)
        var forbidden = false
        // Eine Fassung scheiterte an einer kurzen Störung ([CoinLogoRetry.isTransient]): später erneut, keine Tagessperre
        var transient = false
        for (candidate in listOf(CoinLogos.smallUrl(url), url).distinct()) {
            try {
                val png = LogoImageDownload.png(client, candidate, userAgent)
                forbidden = false
                if (png == null) continue
                // Nicht gespeichert (Speicher voll, Ordner weg): eigene Art — keine Tagessperre, kein Netzfehler (sonst
                // lüde das Nachholen alle 15 Min. alles neu und verwürfe es); der Durchgang lädt nichts mehr (wie iOS)
                if (LogoFiles.write(file, png)) {
                    host?.let {
                        streaks.note(it, forbidden = false)
                        streaks.noteTransient(it, false)
                    }
                    return Fetched.OK
                }
                Timber.d("Logo %s: nicht gespeichert", name)
                streaks.unsaved = true
                return Fetched.UNSAVED
            } catch (e: CancellationException) {
                throw e
            } catch (e: LogoImageDownload.Pause) {
                pauses.pauseHost(e.host, e.retryAfterSeconds)
                return Fetched.PAUSED
            } catch (e: LogoImageDownload.HttpError) {
                forbidden = e.code == HTTP_FORBIDDEN
                if (CoinLogoRetry.isTransient(e.code)) transient = true
                Timber.d("Logo %s: HTTP %d", name, e.code)
            } catch (e: IOException) {
                // Keine Antwort: Zeitüberschreitung, Verbindung abgebrochen
                forbidden = false
                transient = true
                Timber.d("Logo %s: %s", name, e.message)
            } catch (e: Exception) {
                forbidden = false
                Timber.d("Logo %s: %s", name, e.message)
            }
        }
        if (host != null) {
            // Dritte 403 bzw. vierte Störung des Servers in Folge: Server pausieren ([CoinLogoRetry.transientStreakPauses])
            val forbiddenPause = streaks.note(host, forbidden)
            if (streaks.noteTransient(host, transient) || forbiddenPause) {
                pauses.pauseHost(host, null)
                return Fetched.PAUSED
            }
        }
        return when {
            transient -> Fetched.TRANSIENT
            forbidden && CoinLogoRetry.leavesOpen(HTTP_FORBIDDEN) -> Fetched.OPEN
            else -> Fetched.FAILED
        }
    }

    private companion object {
        const val HTTP_FORBIDDEN = 403
    }
}

/**
 * Nur Gerätetests: ein Interceptor dieser Art bleibt beim Bild-Abruf erhalten (alle anderen fallen weg, siehe
 * [LogoImageFetcher.client]) — so beantwortet der Test die Bild-Anfragen ohne Netz.
 */
interface LogoTestInterceptor : okhttp3.Interceptor
