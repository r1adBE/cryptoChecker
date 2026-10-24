package com.cryptochecker.app.performance

import com.cryptochecker.app.domain.watch.WatchlistLimits
import org.junit.Assert.assertTrue
import java.util.Locale

/**
 * Zeitmessung der Leistungstests ohne JMH: je Messung zuerst [WARMUP_RUNS] Aufwärmläufe (JIT), dann der Median aus
 * [MEASURED_RUNS] Läufen mit `System.nanoTime`; Eingaben für jeden Lauf frisch und ungemessen (`setup`).
 *
 * Geprüft werden zwei Dinge: eine feste, grosszügige Obergrenze für die grosse Menge (CI-Runner sind langsam und
 * schwanken; etwa das 5- bis 10-Fache des erwarteten Werts) und die Skalierung gegenüber einem Viertel davon —
 * höchstens [SCALE_FACTOR]-mal so lang plus [SCALE_SLACK_MILLIS]. Linear wäre das 4-Fache, n·log n knapp das
 * 5-Fache, quadratisch das 16-Fache; das fällt unabhängig von der Geschwindigkeit des Rechners auf. Grössen: [PAIRS]
 * (Merkliste, Standard) oder [TRANSACTIONS] (Portfolio). Alle Werte stehen mit «[Performance]» im Testprotokoll. Wie
 * `PerformanceTiming` in `PerformanceSupport.swift` (iOS).
 */
internal object PerformanceTiming {

    /** Obergrenze der Merkliste. */
    const val LARGE = WatchlistLimits.MAX_PAIRS

    /** Vergleichsgrösse für die Skalierung. */
    const val SMALL = LARGE / 4

    /** Grössen einer Messung: [small] und [large] (ein Viertel bzw. das Ganze), im Protokoll als [unit]. */
    data class Sizes(val small: Int, val large: Int, val unit: String)

    /** Paare der Merkliste: [SMALL] und [LARGE]. */
    val PAIRS = Sizes(SMALL, LARGE, "Paare")

    /** Transaktionen im Portfolio: 10 000 (sehr grosses Portfolio) und ein Viertel davon. */
    val TRANSACTIONS = Sizes(2_500, 10_000, "Transaktionen")

    const val WARMUP_RUNS = 2
    const val MEASURED_RUNS = 5
    const val SCALE_FACTOR = 5.0

    /** Feste Zugabe zur Skalierungsgrenze: kleine Messwerte schwanken absolut um einige Millisekunden. */
    const val SCALE_SLACK_MILLIS = 5.0

    private const val NANOS_PER_MILLI = 1_000_000.0

    @Volatile
    private var sink = 0

    /** Median der Laufzeit von [block] in Millisekunden; [setup] liefert je Lauf frische Eingaben (nicht gemessen). */
    fun <S> medianMillis(setup: () -> S, block: (S) -> Any?): Double {
        repeat(WARMUP_RUNS) { consume(block(setup())) }
        val times = DoubleArray(MEASURED_RUNS) {
            val input = setup()
            val started = System.nanoTime()
            val result = block(input)
            val elapsed = System.nanoTime() - started
            consume(result)
            elapsed / NANOS_PER_MILLI
        }
        times.sort()
        return times[MEASURED_RUNS / 2]
    }

    /** Wie oben, ohne eigene Eingaben je Lauf. */
    fun medianMillis(block: () -> Any?): Double = medianMillis({ Unit }, { block() })

    /**
     * Misst [measure] (Anzahl → Median in ms) für die kleine und die grosse Menge von [sizes], schreibt beide Werte ins
     * Testprotokoll und prüft [limitMillis] für die grosse sowie die Skalierung.
     */
    fun assertScales(name: String, limitMillis: Double, sizes: Sizes = PAIRS, measure: (Int) -> Double) {
        val small = measure(sizes.small)
        val large = measure(sizes.large)
        val scaleLimit = SCALE_FACTOR * small + SCALE_SLACK_MILLIS
        report(name, sizes, small, large, limitMillis, "Skalierung ≤ ${fmt(scaleLimit)} ms")
        assertWithin(name, sizes, large, limitMillis)
        assertTrue(
            "$name: ${fmt(large)} ms für ${sizes.large} ${sizes.unit} gegenüber ${fmt(small)} ms für " +
                "${sizes.small} — wächst schneller als n·log n (Grenze ${fmt(scaleLimit)} ms)",
            large <= scaleLimit,
        )
    }

    /**
     * Nur die feste Grenze; das Verhältnis der grossen zur kleinen Menge steht bloss im Protokoll. Für Abläufe, die
     * bekanntlich schneller als linear wachsen (im jeweiligen Test begründet).
     */
    fun assertLimitOnly(name: String, limitMillis: Double, sizes: Sizes = PAIRS, measure: (Int) -> Double) {
        val small = measure(sizes.small)
        val large = measure(sizes.large)
        report(name, sizes, small, large, limitMillis, "Skalierung nicht geprüft")
        assertWithin(name, sizes, large, limitMillis)
    }

    /** Grösse (z. B. einer Sicherung) ins Testprotokoll. */
    fun reportSize(name: String, pairs: Int, bytes: Int, limitBytes: Int) {
        println(
            String.format(
                Locale.ROOT, "[Performance] %s: %d Paare %d Bytes (%.0f je Paar) · Grenze %d Bytes",
                name, pairs, bytes, bytes.toDouble() / pairs, limitBytes,
            ),
        )
    }

    private fun assertWithin(name: String, sizes: Sizes, large: Double, limitMillis: Double) {
        assertTrue(
            "$name: ${fmt(large)} ms für ${sizes.large} ${sizes.unit}, Grenze ${fmt(limitMillis)} ms",
            large <= limitMillis,
        )
    }

    private fun report(name: String, sizes: Sizes, small: Double, large: Double, limitMillis: Double, scale: String) {
        val ratio = if (small > 0.0) large / small else Double.NaN
        println(
            String.format(
                Locale.ROOT, "[Performance] %s: %d %s %.2f ms · %d %s %.2f ms (×%.1f) · Grenze %.0f ms · %s",
                name, sizes.small, sizes.unit, small, sizes.large, sizes.unit, large, ratio, limitMillis, scale,
            ),
        )
    }

    private fun consume(result: Any?) {
        // Ergebnis «verbrauchen», damit der JIT die gemessene Arbeit nicht als unbenutzt wegfallen lässt
        sink += System.identityHashCode(result)
    }

    private fun fmt(millis: Double): String = String.format(Locale.ROOT, "%.2f", millis)
}
