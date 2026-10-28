// Pausen je Bild-Server und fehlgeschlagene Coin-Logos (Teil von `LogoImageStore`), gespeichert in den
// Einstellungen der Logos ([CoinLogoStorage.prefs]), damit ein Neustart sie beachtet.
package com.cryptochecker.app.data

import android.content.SharedPreferences
import androidx.core.content.edit
import com.cryptochecker.app.domain.logos.CoinLogoRetry
import com.cryptochecker.app.domain.logos.CoinLogos
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/** Pausen der Bild-Server und fehlgeschlagene Bilder für [LogoImageStore]; [prefs] erst beim ersten Zugriff gelesen. */
class LogoImagePauses(private val prefsProvider: () -> SharedPreferences) {

    private val prefs: SharedPreferences get() = prefsProvider()

    /** Laufende Pausen je Bild-Server (Host → bis, ms); gespeichert, damit ein Neustart sie beachtet. */
    private val imagePauses = ConcurrentHashMap<String, Long>()
    @Volatile private var pausesLoaded = false

    fun isPaused(host: String): Boolean {
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

    fun pauseHost(host: String, retryAfterSeconds: Long?) {
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
    fun loadFailures(now: Long): Map<String, Long> =
        prefs.getString(KEY_FAILED, null).orEmpty().split(';').mapNotNull { part ->
            val name = part.substringBefore(':')
            val at = part.substringAfter(':', "").toLongOrNull() ?: return@mapNotNull null
            if (name.isEmpty() || !CoinLogos.isFresh(at, now, CoinLogos.FAILURE_TTL_MILLIS)) null else name to at
        }.toMap()

    fun saveFailures(failures: Map<String, Long>) {
        prefs.edit { putString(KEY_FAILED, failures.entries.joinToString(";") { "${it.key}:${it.value}" }) }
    }

    /** Kürzeste verbleibende Pause der Server von [jobs] (ms); null = keiner pausiert. */
    fun shortestPauseMillis(jobs: List<Pair<String, String>>): Long? {
        val now = System.currentTimeMillis()
        return jobs.mapNotNull { CoinLogoRetry.hostOf(it.second) }.distinct()
            .mapNotNull { host -> imagePauses[host]?.let { (it - now).coerceAtLeast(0L) } }
            .minOrNull()
    }

    companion object {
        /** Fehlgeschlagene Bilder ([loadFailures]): «Name:Zeitpunkt;…». */
        const val KEY_FAILED = "failed_v2"
        /** Pausen je Bild-Server ([CoinLogoRetry.encodePauses]). */
        const val KEY_IMAGE_PAUSES = "image_pauses_v1"
    }
}
