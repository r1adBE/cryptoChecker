package com.cryptochecker.app.performance

import com.cryptochecker.app.domain.watch.WatchlistLimits
import org.junit.Assert.assertTrue
import java.util.Locale

/**
 * Zeitmessung der Leistungstests ohne JMH: je Messung zuerst [WARMUP_RUNS] Aufwärmläufe (JIT), dann der Median aus
 * [MEASURED_RUNS] Läufen mit `System.nanoTime`; Eingaben für jeden Lauf frisch und ungemessen (`setup`).
 *
 * Geprüft werden zwei Dinge: eine feste, grosszügige Obergrenze für [LARGE] Paare (CI-Runner sind langsam und
 * schwanken; etwa das 5- bis 10-Fache des erwarteten Werts) und die Skalierung gegenüber [SMALL] Paaren —
 * höchstens [SCALE_FACTOR]-mal so lang plus [SCALE_SLACK_MILLIS]. Linear wäre das 4-Fache, n·log n knapp das
 * 5-Fache, quadratisch das 16-Fache; das fällt unabhängig von der Geschwindigkeit des Rechners auf. Alle Werte stehen
 * mit «[Performance]» im Testprotokoll. Wie `PerformanceTiming` in `PerformanceSupport.swift` (iOS).
 */
internal object PerformanceTiming {

    /** Obergrenze der Merkliste. */
    const val LARGE = WatchlistLimits.MAX_PAIRS

    /** Vergleichsgrösse für die Skalierung. */
    const val SMALL = LARGE / 4

    const val WARMUP_RUNS = 2
    const val MEASURED_RUNS = 5
    const val SCALE_FACTOR = 5.0

    /** Feste Zugabe zur Skalierungsgrenze: kleine Messwerte schwanken absolut um einige Millisekunden. */
    const val SCALE_SLACK_MILLIS = 15.0

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
     * Misst [measure] (Anzahl Paare → Median in ms) für [SMALL] und [LARGE] Paare, schreibt beide Werte ins
     * Testprotokoll und prüft [limitMillis] für [LARGE] sowie die Skalierung.
     */
    fun assertScales(name: String, limitMillis: Double, measure: (Int) -> Double) {
        val small = measure(SMALL)
        val large = measure(LARGE)
        val scaleLimit = SCALE_FACTOR * small + SCALE_SLACK_MILLIS
        report(name, small, large, limitMillis, "Skalierung ≤ ${fmt(scaleLimit)} ms")
        assertWithin(name, large, limitMillis)
        assertTrue(
            "$name: ${fmt(large)} ms für $LARGE Paare gegenüber ${fmt(small)} ms für $SMALL — wächst schneller als " +
                "n·log n (Grenze ${fmt(scaleLimit)} ms)",
            large <= scaleLimit,
        )
    }

    /**
     * Nur die feste Grenze; das Verhältnis [LARGE] zu [SMALL] steht bloss im Protokoll. Für Abläufe, die bekanntlich
     * schneller als linear wachsen (im jeweiligen Test begründet).
     */
    fun assertLimitOnly(name: String, limitMillis: Double, measure: (Int) -> Double) {
        val small = measure(SMALL)
        val large = measure(LARGE)
        report(name, small, large, limitMillis, "Skalierung nicht geprüft")
        assertWithin(name, large, limitMillis)
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

    private fun assertWithin(name: String, large: Double, limitMillis: Double) {
        assertTrue("$name: ${fmt(large)} ms für $LARGE Paare, Grenze ${fmt(limitMillis)} ms", large <= limitMillis)
    }

    private fun report(name: String, small: Double, large: Double, limitMillis: Double, scale: String) {
        val ratio = if (small > 0.0) large / small else Double.NaN
        println(
            String.format(
                Locale.ROOT, "[Performance] %s: %d Paare %.2f ms · %d Paare %.2f ms (×%.1f) · Grenze %.0f ms · %s",
                name, SMALL, small, LARGE, large, ratio, limitMillis, scale,
            ),
        )
    }

    private fun consume(result: Any?) {
        // Ergebnis «verbrauchen», damit der JIT die gemessene Arbeit nicht als unbenutzt wegfallen lässt
        sink += System.identityHashCode(result)
    }

    private fun fmt(millis: Double): String = String.format(Locale.ROOT, "%.2f", millis)
}
