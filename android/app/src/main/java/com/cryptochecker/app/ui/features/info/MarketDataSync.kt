// Gemeinsamer Ablauf jeder Markt-Karte (Teil von `InfoViewModel`): erst den Zwischenspeicher zeigen, dann
// mit Zeitlimit frisch laden, Stand und Quelle merken, Fehler abfangen.
// Eigene Datei, weil alle Lader denselben Ablauf nutzen und er für sich lesbar sein soll.
package com.cryptochecker.app.ui.features.info

import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.CacheCodec
import com.cryptochecker.app.domain.market.CycleCachePolicy
import com.cryptochecker.app.domain.market.CycleSource
import com.cryptochecker.app.domain.market.DataStamp
import com.cryptochecker.app.domain.market.Sourced
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Ein Bereich: zeigt sofort, was da ist (Speicher der Ansicht, sonst Gerät), und
 * lädt nur neu, wenn das älter als die Gültigkeit ist oder [force]. Ohne irgendeinen
 * Wert erscheint der Ladezustand bzw. bei Fehler «Failed»; mit Wert bleibt dieser
 * bei einem Fehler einfach stehen. Höchstens ein Abruf je Bereich; ein Wechsel des
 * Eintrags (anderer Coin) bricht den alten ab.
 *
 * @param fetch holt den Wert samt Anbieter ([Sourced.provider], gespeichert und in [stamps])
 * @param name Eintrag im Zwischenspeicher (Standard: Schlüssel des Bereichs)
 * @param timeOf Zeitpunkt der Daten selbst; sonst gilt der Abrufzeitpunkt
 * @param isUsable unbrauchbare Werte weder zeigen noch speichern
 */
internal fun <T> InfoViewModel.sync(
    source: CycleSource,
    force: Boolean,
    codec: CacheCodec<T>,
    show: (T) -> Unit,
    loading: () -> Unit,
    failed: () -> Unit,
    fetch: suspend () -> Sourced<T>,
    name: String = source.key,
    timeOf: ((T) -> Long)? = null,
    isUsable: (T) -> Boolean = { true },
): Job {
    jobs[source]?.let { (runningName, job) ->
        if (job.isActive) {
            // Gleicher Eintrag läuft schon: nicht doppelt laden
            if (runningName == name) return job
            job.cancel()
            running.update { it - source }
        }
    }
    val job = viewModelScope.launch {
        val self = coroutineContext.job
        var savedAt = shownEntry[source]?.takeIf { it.first == name }?.second
        if (savedAt == null) {
            // Noch nichts (oder anderer Coin) angezeigt: Gerät, sonst Ladezustand
            val cached = cacheStore.read(name, codec)?.takeIf { isUsable(it.value) }
            if (cached != null && showSafely(source, show, cached.value)) {
                savedAt = cached.savedAt
                markShown(source, name, cached.savedAt, cached.provider)
            } else {
                shownEntry.remove(source)
                shownAt.update { it - source }
                _stamps.update { it - source }
                loading()
            }
        }
        cacheChecked.update { it + source }
        // Ohne Netz: Gezeigtes stehen lassen (Pille «Offline · Stand …»), kein vergeblicher Abruf
        if (savedAt != null && !connectivity.isOnline()) return@launch
        // Langsame Bereiche (z. B. Altcoin-Saison) von Hand frühestens alle 5 Min. neu
        val minForce = CycleCachePolicy.manualMinInterval(source)
        if (!CycleCachePolicy.needsRefresh(savedAt, System.currentTimeMillis(), source.ttlMillis, force, minForce)) {
            return@launch
        }
        running.update { it + source }
        try {
            val sourced = fetchWithin(source, fetch)?.getOrNull()?.takeIf { isUsable(it.value) }
            if (sourced != null && showSafely(source, show, sourced.value)) {
                val fresh = sourced.value
                val at = timeOf?.invoke(fresh)?.takeIf { it > 0L } ?: System.currentTimeMillis()
                markShown(source, name, at, sourced.provider)
                cacheStore.write(name, fresh, at, codec, sourced.provider)
            } else if (savedAt == null) {
                failed()
            }
        } finally {
            // Ein abgelöster Abruf (anderer Coin) räumt nicht für den neuen auf
            if (jobs[source]?.second === self) running.update { it - source }
        }
    }
    jobs[source] = name to job
    return job
}

/**
 * Anzeigen (samt Auswertung, z. B. Zyklus-Modell); false, wenn das an unerwarteten
 * Werten scheitert — dann gilt der Wert als unbrauchbar statt die App zu beenden.
 */
internal fun <T> InfoViewModel.showSafely(source: CycleSource, show: (T) -> Unit, value: T): Boolean = try {
    show(value)
    true
} catch (e: Exception) {
    Timber.w(e, "Zyklus-Daten %s nicht auswertbar", source.key)
    false
}

internal fun InfoViewModel.markShown(source: CycleSource, name: String, at: Long, provider: String?) {
    shownEntry[source] = name to at
    shownAt.update { it + (source to at) }
    _stamps.update { it + (source to DataStamp(provider, at, source.ttlMillis)) }
}

/**
 * Abruf auf IO mit harter Zeitgrenze des Bereichs. null bei Fehler oder
 * Zeitüberschreitung (der Wert steckt in [Result], damit null nie «Zeit um» heisst).
 */
internal suspend fun <T> InfoViewModel.fetchWithin(source: CycleSource, fetch: suspend () -> T): Result<T>? = try {
    withTimeoutOrNull(source.timeoutMillis) {
        Result.success(withContext(Dispatchers.IO) { fetch() })
    }.also { if (it == null) Timber.w("Zyklus-Daten %s: Zeitüberschreitung", source.key) }
} catch (e: kotlinx.coroutines.TimeoutCancellationException) {
    // Innere Zeitgrenze einer Abfrage: wie ein Fehler der Quelle, kein Abbruch des Bereichs
    Timber.w(e, "Zyklus-Daten %s: Zeitüberschreitung", source.key)
    null
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    Timber.w(e, "Zyklus-Daten %s nicht verfügbar", source.key)
    null
}
