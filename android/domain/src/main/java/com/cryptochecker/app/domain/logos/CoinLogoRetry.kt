package com.cryptochecker.app.domain.logos

/**
 * Nachholen des Logo-Abgleichs nach einem Netzfehler (reines Kotlin, getestet in CoinLogoRetryTest;
 * gleiche Regeln wie `CoinLogoRetry` auf iOS).
 *
 * Fehlt in den ersten Sekunden nach der Installation das Internet, darf das nicht als «erledigt»
 * gelten: Ein Netzfehler (keine Verbindung, Name nicht auflösbar, Verbindung abgebrochen) sperrt
 * weder die Stunde bis zum nächsten Abruf der Liste noch einzelne Bilder für einen Tag. Sobald wieder
 * Netz da ist (oder die App in den Vordergrund kommt), läuft der Abgleich erneut — mit wachsendem
 * Abstand ([retryDelayMillis]), falls das Netz zwar «da», aber noch nicht nutzbar ist. Die übrigen
 * Grenzen bleiben: 6 h bei unvollständiger und eine Woche bei vollständiger Liste,
 * eine Stunde nach einem Server-Fehler.
 */
object CoinLogoRetry {

    /** Ab so vielen Bildern gilt «alle fehlgeschlagen» als Netzfehler (nicht als kaputte Bilder). */
    const val ALL_FAILED_MIN = 3

    /**
     * Fehlgeschlagener Abruf der Liste: als Versuch speichern (dann eine Stunde Pause)? Nur bei einem
     * Server-Fehler — ein Netzfehler zählt nicht, sonst kämen nach einer Installation ohne Netz
     * eine Stunde lang keine Logos.
     */
    fun recordsAttempt(networkFailure: Boolean): Boolean = !networkFailure

    /**
     * Fehlgeschlagene Bild-Abrufe ([failed] von [attempted]) am fehlenden Netz? Dann nicht für einen Tag
     * sperren. Ohne Netz immer; mit Netz nur, wenn wirklich alle (mindestens [ALL_FAILED_MIN]) scheiterten.
     */
    fun downloadsFailedForNetwork(online: Boolean, failed: Int, attempted: Int): Boolean =
        failed > 0 && (!online || (failed == attempted && attempted >= ALL_FAILED_MIN))

    // ---- Bild-Server bremst

    /**
     * Antwort eines Bild-Servers, die sofort eine Pause des ganzen Servers verlangt statt «Bild kaputt»: zu viele
     * Anfragen (429), überlastet (503) oder gesperrt (403) mit Angabe «Retry-After» ([retryAfter]). Ein 403 ohne
     * Angabe ist zuerst nur ein fehlgeschlagenes Bild: CloudFront/S3 antwortet so auch auf eine fehlende Datei, und
     * ein einziges fehlendes Bild sperrte sonst den ganzen Server bei jedem Lauf an derselben Stelle. Erst
     * [FORBIDDEN_PAUSE_STREAK] 403 in Folge pausieren den Server ([forbiddenStreakPauses]).
     */
    fun pausesImages(httpCode: Int, retryAfter: Boolean): Boolean =
        httpCode == 429 || httpCode == 503 || (httpCode == 403 && retryAfter)

    /** So viele Bilder eines Servers in Folge mit 403 (ohne «Retry-After») in einem Durchgang: Server pausieren. */
    const val FORBIDDEN_PAUSE_STREAK = 3

    /**
     * Neue Zahl der 403 in Folge eines Servers nach einem fertigen Bild: [forbidden] = seine letzte Antwort war 403
     * (ohne Pause) → +1; jedes andere Ergebnis (geladen, 404, unlesbar) beendet die Folge → 0.
     */
    fun nextForbiddenStreak(streak: Int, forbidden: Boolean): Int = if (forbidden) streak + 1 else 0

    /** Pausiert diese Folge von 403 den Server ([FORBIDDEN_PAUSE_STREAK])? Dann ohne Angabe [IMAGE_PAUSE_MILLIS]. */
    fun forbiddenStreakPauses(streak: Int): Boolean = streak >= FORBIDDEN_PAUSE_STREAK

    /** Pause ohne Angabe des Servers. */
    const val IMAGE_PAUSE_MILLIS = 15 * 60_000L

    /** Höchstens so lange (auch bei längerer «Retry-After»-Angabe). */
    const val IMAGE_PAUSE_MAX_MILLIS = 6 * 60 * 60_000L

    /** Pause für einen Bild-Server nach [pausesImages] bzw. [forbiddenStreakPauses] («Retry-After» in Sekunden), 1 Min. … 6 h. */
    fun imagePauseMillis(retryAfterSeconds: Long?): Long =
        (retryAfterSeconds?.coerceIn(0L, IMAGE_PAUSE_MAX_MILLIS / 1000)?.times(1000) ?: IMAGE_PAUSE_MILLIS)
            .coerceIn(60_000L, IMAGE_PAUSE_MAX_MILLIS)

    /** Nach dem Ende einer Pause so viel länger warten (Uhren und Zeitgeber gehen nicht genau gleich). */
    const val PAUSE_RETRY_MARGIN_MILLIS = 1_000L

    /**
     * Wartezeit bis zum Nachholen der gebremsten Bilder ([pausedUntil] = frühestes Ende der Pausen, ms): bis
     * zum Ende (höchstens [IMAGE_PAUSE_MAX_MILLIS], vorbei → sofort) plus [PAUSE_RETRY_MARGIN_MILLIS];
     * null = nichts gebremst. So läuft der Abgleich nach der Pause von selbst weiter, solange die App lebt.
     */
    fun pauseRetryDelayMillis(pausedUntil: Long?, now: Long): Long? =
        pausedUntil?.let { (it - now).coerceIn(0L, IMAGE_PAUSE_MAX_MILLIS) + PAUSE_RETRY_MARGIN_MILLIS }

    /** Host einer HTTPS-Adresse, klein; null, wenn keine. */
    fun hostOf(url: String): String? {
        if (!url.startsWith("https://")) return null
        val host = url.removePrefix("https://").substringBefore('/').substringBefore('?').substringBefore(':').lowercase()
        return host.ifEmpty { null }
    }

    /** Pausen je Host speichern: «host=bis;host=bis». */
    fun encodePauses(pauses: Map<String, Long>): String =
        pauses.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }

    /** Gespeicherte Pausen, nur noch laufende ([now] < bis, höchstens [IMAGE_PAUSE_MAX_MILLIS] voraus). */
    fun decodePauses(text: String?, now: Long): Map<String, Long> {
        if (text.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, Long>()
        for (part in text.split(';')) {
            val host = part.substringBefore('=').trim()
            val until = part.substringAfter('=', "").trim().toLongOrNull() ?: continue
            if (host.isEmpty() || until <= now || until - now > IMAGE_PAUSE_MAX_MILLIS) continue
            out[host] = until
        }
        return out
    }

    /**
     * Abgleich jetzt nachholen? Nur, wenn der letzte am Netz scheiterte ([pendingAfterNetworkFailure])
     * und jetzt Netz da ist. Ein erfolgreicher Abgleich setzt [pendingAfterNetworkFailure] zurück.
     */
    fun syncNeeded(pendingAfterNetworkFailure: Boolean, online: Boolean): Boolean =
        pendingAfterNetworkFailure && online

    /** Wartezeit nach dem ersten am Netz gescheiterten Abgleich. */
    const val FIRST_RETRY_MILLIS = 5_000L

    /** Ab dem zweiten Fehlschlag: 15 s, verdoppelt je weiterem Fehlschlag … */
    const val RETRY_BASE_MILLIS = 15_000L

    /** … höchstens 15 Minuten — so entsteht nie eine Flut von Abrufen. */
    const val RETRY_MAX_MILLIS = 15 * 60_000L

    /**
     * Mindestabstand nach [failures] aufeinanderfolgenden, am Netz gescheiterten Abgleichen:
     * 0 → sofort, 1 → 5 s, 2 → 15 s, 3 → 30 s, 4 → 60 s … höchstens [RETRY_MAX_MILLIS].
     */
    fun backoffMillis(failures: Int): Long = when {
        failures <= 0 -> 0L
        failures == 1 -> FIRST_RETRY_MILLIS
        else -> minOf(RETRY_BASE_MILLIS shl minOf(failures - 2, 10), RETRY_MAX_MILLIS)
    }

    /**
     * Wie lange vor dem nächsten Nachholen noch warten ([now] und [lastFailureAt] in ms)? 0 = jetzt.
     * Kam das Netz erst lange nach dem Fehlschlag zurück, ist die Wartezeit schon um — dann sofort.
     * Geht die Uhr zurück (vor [lastFailureAt]), die volle Wartezeit statt einer Flut.
     */
    fun retryDelayMillis(failures: Int, lastFailureAt: Long, now: Long): Long {
        val backoff = backoffMillis(failures)
        if (backoff == 0L) return 0L
        if (now < lastFailureAt) return backoff
        return (lastFailureAt + backoff - now).coerceIn(0L, backoff)
    }
}
