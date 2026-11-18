package com.cryptochecker.app.domain.watch

/** Gültigkeit der Mini-Chart-Kurve: Zeilen laden danach neu (`SparklineRepository.TTL_MILLIS` verweist hierher). */
object SparklineTiming {
    const val TTL_MILLIS = 15 * 60_000L
}
