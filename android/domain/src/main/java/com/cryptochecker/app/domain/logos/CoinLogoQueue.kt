package com.cryptochecker.app.domain.logos

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Reihenfolge und Grenzen des Sammel-Abrufs der Logos (gleich in iOS `CoinLogoQueue.swift`, gemeinsame Testfälle
 * `testdata/parity/coin_logos.json` › `queue`):
 *
 * - Reihenfolge: die der Liste ([run] startet in ihrer Reihenfolge) — für alle gleich, nie nach Merkliste oder
 *   Portfolio (sonst verrieten die Abrufe, was jemand beobachtet): bei Binance gelistete Kürzel in der Reihenfolge der
 *   Symbolliste, dann die Rangliste von CoinGecko ([CoinMeta.merge]). Geholt wird immer die ganze Liste.
 * - [nextStartable] / [run]: höchstens [MAX_PARALLEL] Abrufe gleichzeitig, je Bild-Server höchstens
 *   [PER_HOST_PARALLEL] — kein Ansturm auf einen Server; die Pausen gebremster Server ([CoinLogoRetry]) gelten
 *   unverändert.
 */
object CoinLogoQueue {
    /** Bild-Abrufe gleichzeitig insgesamt. */
    const val MAX_PARALLEL = 6

    /** Bild-Abrufe gleichzeitig je Server (CoinGecko, Binance). */
    const val PER_HOST_PARALLEL = 4

    /**
     * Nächster startbarer Abruf: Stelle des ersten in [hosts] (Server der wartenden Abrufe, in ihrer Reihenfolge),
     * dessen Server weniger als [PER_HOST_PARALLEL] laufende Abrufe hat ([running]); -1, wenn insgesamt schon
     * [MAX_PARALLEL] laufen oder jeder wartende Server voll ist.
     */
    fun nextStartable(hosts: List<String>, running: Map<String, Int>): Int {
        if (running.values.sum() >= MAX_PARALLEL) return -1
        return hosts.indexOfFirst { (running[it] ?: 0) < PER_HOST_PARALLEL }
    }

    /**
     * Führt [work] für alle [jobs] aus — in ihrer Reihenfolge, so früh es die Grenzen ([nextStartable]) erlauben;
     * [done] läuft nach jedem Abruf nacheinander (nie gleichzeitig) in der aufrufenden Coroutine.
     */
    suspend fun <T, R> run(jobs: List<T>, hostOf: (T) -> String, work: suspend (T) -> R, done: (T, R) -> Unit) {
        if (jobs.isEmpty()) return
        coroutineScope {
            val waiting = ArrayList(jobs)
            val hosts = jobs.mapTo(ArrayList(jobs.size), hostOf)
            val running = HashMap<String, Int>()
            val finished = Channel<Triple<T, String, R>>(Channel.UNLIMITED)
            var active = 0
            while (waiting.isNotEmpty() || active > 0) {
                while (true) {
                    val i = nextStartable(hosts, running)
                    if (i < 0) break
                    val job = waiting.removeAt(i)
                    val host = hosts.removeAt(i)
                    running[host] = (running[host] ?: 0) + 1
                    active++
                    launch { finished.send(Triple(job, host, work(job))) }
                }
                val (job, host, result) = finished.receive()
                running[host] = (running[host] ?: 1) - 1
                active--
                done(job, result)
            }
        }
    }
}
