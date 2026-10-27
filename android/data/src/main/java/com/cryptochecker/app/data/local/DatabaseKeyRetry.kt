package com.cryptochecker.app.data.local

import java.io.File

/**
 * Vorübergehende Keystore-Fehler nicht als endgültig werten ([DatabaseKeyStore]). Auf manchen Geräten
 * liefert der Keystore kurz nach dem Start oder einem System-Update `UnrecoverableKeyException` oder gar keinen Schlüssel
 * (`getKey` = null), obwohl der Eintrag besteht. Früher legte die App die Datenbank dann sofort beiseite und
 * ersetzte den Keystore-Eintrag — die früheren Schlüsseldateien waren danach nie mehr zu öffnen.
 *
 * Darum: [run] wiederholt «verdächtige» Fehler ([Failure.SUSPECT]) wie gewöhnliche mehrmals; erst wenn ein verdächtiger
 * Fehler auch in einem späteren Lauf wieder auftritt ([SuspectMarker]), gilt der Schlüssel als verloren. Nur wirklich
 * eindeutige Fehler ([Failure.PERMANENT]: Schlüssel ungültig, falsches Chiffrat) gelten sofort. Reines Kotlin, testbar.
 */
object DatabaseKeyRetry {

    /** Art eines Fehlers beim Lesen des Schlüssels. */
    enum class Failure {
        /** Dienst beschäftigt o. Ä.: wiederholen, sonst «gerade nicht verfügbar». */
        TRANSIENT,

        /** Sieht endgültig aus, ist es auf manchen Geräten aber nicht (`UnrecoverableKeyException`, kein Schlüssel). */
        SUSPECT,

        /** Eindeutig endgültig (Schlüssel ungültig gemacht, Chiffrat passt nicht zum Schlüssel). */
        PERMANENT,
    }

    /** Ergebnis von [run]. */
    sealed interface Outcome<out T> {
        class Value<T>(val value: T) : Outcome<T>

        /** Nach allen Versuchen verdächtig; [error] null = kein Schlüssel geliefert. */
        class Suspect(val error: Throwable?) : Outcome<Nothing>

        class Permanent(val error: Throwable) : Outcome<Nothing>

        class Transient(val error: Throwable) : Outcome<Nothing>
    }

    /**
     * [block] bis zu [attempts]-mal; null aus [block] = kein Schlüssel (verdächtig). Zwischen den Versuchen [sleep] mit
     * [delayMillis]. Ein eindeutig endgültiger Fehler bricht sofort ab.
     */
    fun <T : Any> run(
        attempts: Int,
        delayMillis: Long,
        sleep: (Long) -> Unit,
        classify: (Throwable) -> Failure,
        block: () -> T?,
    ): Outcome<T> {
        var last: Outcome<T> = Outcome.Suspect(null)
        for (attempt in 0 until attempts) {
            last = try {
                block()?.let { return Outcome.Value(it) } ?: Outcome.Suspect(null)
            } catch (e: Exception) {
                when (classify(e)) {
                    Failure.PERMANENT -> return Outcome.Permanent(e)
                    Failure.SUSPECT -> Outcome.Suspect(e)
                    Failure.TRANSIENT -> Outcome.Transient(e)
                }
            }
            if (attempt < attempts - 1) sleep(delayMillis)
        }
        return last
    }

    /**
     * Merker «Schlüssel von [database] verdächtig» als kleine Datei je Datenbank in [directory] (neben den
     * Schlüsseldateien, nie in einer Sicherung). [seenThisRun]: in diesem Prozess angelegte Merker — sie zählen erst im
     * nächsten Lauf. So wird ein Schlüssel nie im selben Lauf als verloren gewertet (und ersetzt), in dem er zum ersten
     * Mal fehlte; ein vorübergehender Fehler ist bis zum nächsten Start meist vorbei.
     */
    class SuspectMarker(
        private val directory: File,
        private val seenThisRun: MutableSet<String> = PROCESS_SEEN,
    ) {
        private fun file(database: String) = File(directory, "$database$SUFFIX")

        /**
         * Verdacht für [database] melden. true = schon in einem früheren Lauf vermerkt: jetzt als verloren werten.
         * false = erster Verdacht (Merker angelegt, gilt erst ab dem nächsten Lauf) — oder Merker nicht schreibbar.
         */
        fun escalate(database: String): Boolean {
            val marker = file(database)
            synchronized(seenThisRun) {
                if (marker.exists() && database !in seenThisRun) return true
                seenThisRun += database
            }
            if (!marker.exists()) {
                runCatching {
                    directory.mkdirs()
                    marker.writeText(System.currentTimeMillis().toString())
                }
            }
            return false
        }

        /** Schlüssel wieder lesbar oder ersetzt: Verdacht erledigt. */
        fun clear(database: String) {
            synchronized(seenThisRun) { seenThisRun -= database }
            file(database).delete()
        }

        companion object {
            /** Endung des Merkers; passt nicht zu den Plätzen früherer Schlüssel (`.previous`, `.replaced`). */
            const val SUFFIX = ".key-suspect"

            /** In diesem Prozess angelegte Merker, über alle Instanzen (App und Sicherungs-Agent). */
            private val PROCESS_SEEN = mutableSetOf<String>()
        }
    }
}
