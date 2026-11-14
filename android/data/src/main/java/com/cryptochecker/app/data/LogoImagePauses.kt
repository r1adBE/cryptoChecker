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

    /**
     * Nach einem Durchgang, der nicht am Netz scheiterte: offene Bilder ([open], gestört bzw. gebremst) zählen, die übrigen
     * versuchten ([attempted]) vergessen ([CoinLogoRetry.nextOpenPasses]). Ergebnis: die Bilder, die jetzt
     * [CoinLogoRetry.OPEN_PASSES_FAIL] Durchgänge in Folge offen sind — sie gelten als gescheitert (Tagessperre).
     */
    fun noteOpenPasses(attempted: Collection<String>, open: Collection<String>): Set<String> {
        val next = CoinLogoRetry.nextOpenPasses(CoinLogoRetry.decodeOpenPasses(prefs.getString(KEY_OPEN_PASSES, null)), attempted, open)
        val failed = next.filterValues { CoinLogoRetry.openPassesFail(it) }.keys
        prefs.edit { putString(KEY_OPEN_PASSES, CoinLogoRetry.encodeOpenPasses(next - failed)) }
        return failed
    }

    /** Kürzeste verbleibende Pause der Server von [jobs] (ms); null = keiner (mehr) pausiert — wie iOS `remainingMillis`. */
    fun shortestPauseMillis(jobs: List<Pair<String, String>>): Long? {
        val now = System.currentTimeMillis()
        return jobs.mapNotNull { CoinLogoRetry.hostOf(it.second) }.distinct()
            .mapNotNull { host -> imagePauses[host]?.takeIf { it > now }?.let { it - now } }
            .minOrNull()
    }

    /** Frühestes Ende (ms) der laufenden Pausen der Server von [jobs]; null = keiner pausiert. */
    fun earliestPauseEnd(jobs: List<Pair<String, String>>): Long? =
        shortestPauseMillis(jobs)?.let { System.currentTimeMillis() + it }

    /**
     * Bilder je Server in Folge mit 403 in einem Durchgang ([CoinLogoRetry.nextForbiddenStreak]); ein Objekt je
     * Durchgang. [note] nach jedem fertigen Bild: true = Server jetzt pausieren ([CoinLogoRetry.forbiddenStreakPauses]).
     */
    class ForbiddenStreaks {
        private val streaks = ConcurrentHashMap<String, Int>()
        private val transient = ConcurrentHashMap<String, Int>()

        /** Speicher voll: ein Bild liess sich nicht schreiben — der Durchgang lädt danach keines mehr (bis zum nächsten). */
        @Volatile
        var unsaved = false

        fun note(host: String, forbidden: Boolean): Boolean {
            val streak = streaks.compute(host) { _, old -> CoinLogoRetry.nextForbiddenStreak(old ?: 0, forbidden) } ?: 0
            return CoinLogoRetry.forbiddenStreakPauses(streak)
        }

        /** Gestörte Bilder je Server in Folge ([CoinLogoRetry.nextTransientStreak]): true = Server jetzt pausieren. */
        fun noteTransient(host: String, gotTransient: Boolean): Boolean {
            val streak = transient.compute(host) { _, old -> CoinLogoRetry.nextTransientStreak(old ?: 0, gotTransient) } ?: 0
            return CoinLogoRetry.transientStreakPauses(streak)
        }
    }

    companion object {
        /** Fehlgeschlagene Bilder ([loadFailures]): «Name:Zeitpunkt;…». */
        const val KEY_FAILED = "failed"
        /** Pausen je Bild-Server ([CoinLogoRetry.encodePauses]). */
        const val KEY_IMAGE_PAUSES = "image_pauses"
        /** Offene Durchgänge je Bild ([noteOpenPasses], [CoinLogoRetry.encodeOpenPasses]). */
        const val KEY_OPEN_PASSES = "open_passes"
    }
}
