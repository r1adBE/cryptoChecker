package com.cryptochecker.app.domain.portfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/**
 * [PortfolioCostPartPool] gegen die frühere Liste der Herkunftsteile ([FormerParts]: jeder Abgang schreibt alle Teile
 * neu): zufällige Folgen mit Seed aus Zugängen, Teil- und Ganz-Abgängen und Zugängen zu früheren Tagen. Gleiche Zahl
 * und Zeitpunkte der Teile, Summen exakt, Beträge bis auf die Rundung der 34. Stelle; kurze Folgen mit runden Zahlen
 * exakt gleich (wie die Paritäts-Fixtures). Dazu die Umrechnung grosser Ansichten ohne Auslesen ([weightedSum],
 * [anyTime], [timeBounds]) gegen die Rechnung Teil für Teil. iOS: PortfolioCostPartPoolTests.swift.
 */
class PortfolioCostPartPoolTest {

    private val mc = PortfolioDecimal.MC
    private val utc: ZoneId = ZoneId.of("UTC")

    /** Frühere Rechnung (Referenz): Liste, die jeder Abgang Teil für Teil neu schreibt; mit [zone] je Tag ein Teil. */
    private class FormerParts(private val zone: ZoneId?) {
        val parts = ArrayList<PortfolioCostPart>()
        private val dayIndex = HashMap<LocalDate, Int>()

        fun add(time: Long, usdt: BigDecimal) {
            if (usdt.signum() == 0) return
            val z = zone ?: run {
                parts += PortfolioCostPart(time, usdt)
                return
            }
            val day = Instant.ofEpochMilli(time).atZone(z).toLocalDate()
            val i = dayIndex[day]
            if (i != null) {
                parts[i] = PortfolioCostPart(parts[i].time, parts[i].usdt + usdt)
            } else {
                dayIndex[day] = parts.size
                parts += PortfolioCostPart(time, usdt)
            }
        }

        fun take(removed: BigDecimal, total: BigDecimal): List<PortfolioCostPart> {
            if (removed.signum() == 0 || parts.isEmpty()) return emptyList()
            if (removed >= total) {
                val all = parts.toList()
                parts.clear()
                dayIndex.clear()
                return all
            }
            val out = ArrayList<PortfolioCostPart>(parts.size)
            var left = removed
            for (i in parts.indices) {
                val p = parts[i]
                val r = if (i == parts.size - 1) left else p.usdt.multiply(removed).divide(total, PortfolioDecimal.MC)
                left -= r
                out += PortfolioCostPart(p.time, r)
                parts[i] = PortfolioCostPart(p.time, p.usdt - r)
            }
            return out
        }
    }

    /** Neue gegen frühere Rechnung über [steps] zufällige Schritte; [nice]: runde Beträge und Anteile. */
    private fun compare(seed: Int, steps: Int, zone: ZoneId?, nice: Boolean, exact: Boolean) {
        val rnd = Random(seed)
        val former = FormerParts(zone)
        val pool = PortfolioCostPartPool(zone)
        var cost = BigDecimal.ZERO
        var time = 1_700_000_000_000L
        val label = "Seed $seed"
        repeat(steps) { step ->
            time += rnd.nextLong(HOUR, 2 * DAY)
            val roll = rnd.nextInt(100)
            when {
                roll < 45 || cost.signum() == 0 -> {
                    // Zugang; mit Zeitzone ab und zu zu einem früheren Tag (zusammengefasster Teil)
                    val usdt = if (nice) BigDecimal(50 * (1 + rnd.nextInt(100))) else randomAmount(rnd)
                    val at = if (zone != null && rnd.nextInt(5) == 0) time - rnd.nextLong(0, 20 * DAY) else time
                    cost += usdt
                    former.add(at, usdt)
                    pool.add(at, usdt)
                }
                else -> {
                    val fraction = if (nice) NICE[rnd.nextInt(NICE.size)] else randomFraction(rnd)
                    val removed = if (rnd.nextInt(30) == 0) cost else cost.multiply(fraction, mc)
                    val expected = former.take(removed, cost)
                    val actual = pool.take(removed, cost)
                    assertParts("$label Schritt $step", expected, actual, exact)
                    cost = if (removed >= cost) BigDecimal.ZERO else cost - removed
                }
            }
        }
        val open = pool.toList(cost)
        assertParts("$label offen", former.parts, open, exact)
        if (open.isNotEmpty()) assertEquals("$label Summe offen", 0, open.sumOf { it.usdt }.compareTo(cost))
    }

    private fun assertParts(
        label: String,
        expected: List<PortfolioCostPart>,
        actual: List<PortfolioCostPart>,
        exact: Boolean,
    ) {
        assertEquals("$label Anzahl", expected.size, actual.size)
        expected.zip(actual).forEachIndexed { i, (e, a) ->
            assertEquals("$label Teil $i Zeitpunkt", e.time, a.time)
            val diff = (e.usdt - a.usdt).abs()
            if (exact) assertEquals("$label Teil $i ${e.usdt} / ${a.usdt}", 0, diff.signum())
            else assertTrue("$label Teil $i ${e.usdt} / ${a.usdt}", diff <= TOLERANCE)
        }
        if (expected.isNotEmpty()) {
            assertEquals("$label Summe", 0, expected.sumOf { it.usdt }.compareTo(actual.sumOf { it.usdt }))
        }
    }

    @Test
    fun matchesFormerListOnRandomSequences() {
        for (seed in 1..24) compare(seed, steps = 400, zone = null, nice = false, exact = false)
        for (seed in 25..48) compare(seed, steps = 400, zone = utc, nice = false, exact = false)
    }

    @Test
    fun shortSequencesWithRoundNumbersAreExact() {
        // Wie die Paritäts-Fixtures: wenige Schritte, runde Beträge und Anteile → Teil für Teil dieselbe Zahl
        for (seed in 100..399) compare(seed, steps = 12, zone = if (seed % 2 == 0) utc else null, nice = true, exact = true)
    }

    @Test
    fun aPartWithoutLaterTakeKeepsItsExactAmount() {
        val pool = PortfolioCostPartPool()
        pool.add(1L, BigDecimal("100"))
        pool.add(2L, BigDecimal("200"))
        // Ein Drittel ab: 33.33…33 und der Rest 66.66…67 (Summe exakt 100)
        val taken = pool.take(BigDecimal("100"), BigDecimal("300"))
        assertEquals(0, taken.sumOf { it.usdt }.compareTo(BigDecimal("100")))
        assertEquals(0, taken[0].usdt.compareTo(BigDecimal("33.33333333333333333333333333333333")))
        // Neue Teile ohne Abgang danach: exakt ihr Betrag (der letzte Teil ist der Rest zum Einstand)
        pool.add(3L, BigDecimal("0.000000012345678901234567"))
        pool.add(4L, BigDecimal("5"))
        val total = BigDecimal("205.000000012345678901234567")
        val open = pool.toList(total)
        assertEquals(0, open[0].usdt.compareTo(BigDecimal("66.66666666666666666666666666666667")))
        assertEquals(0, open[2].usdt.compareTo(BigDecimal("0.000000012345678901234567")))
        assertEquals(0, open.sumOf { it.usdt }.compareTo(total))
        // Ganz ab: alle Teile, danach leer
        assertEquals(4, pool.take(BigDecimal("500"), total).size)
        assertTrue(pool.isEmpty)
    }

    @Test
    fun largeViewsConvertWithoutReadingLikePartByPart() {
        val rnd = Random(7)
        val weight = PortfolioPartWeight { t -> rate(t) }
        // Kurs mit Lücke (fehlender Tag): ab dem ersten Teil dieses Tags fehlt die Umrechnung jeder Ansicht
        val gap = PortfolioPartWeight { t -> if ((t / DAY) % 97 == 5L) null else rate(t) }
        val test = PortfolioPartTest { t -> t > START + 400 * DAY }
        for (zone in listOf(null, utc)) {
            val pool = PortfolioCostPartPool(zone)
            var cost = BigDecimal.ZERO
            var time = START
            val views = ArrayList<List<PortfolioCostPart>>()
            val early = HashMap<Int, BigDecimal?>()
            repeat(1_500) {
                time += rnd.nextLong(HOUR, DAY)
                if (rnd.nextInt(100) < 55 || cost.signum() == 0) {
                    val usdt = randomAmount(rnd)
                    cost += usdt
                    pool.add(time, usdt)
                } else {
                    val removed = cost.multiply(randomFraction(rnd), mc)
                    val view = pool.take(removed, cost)
                    cost -= removed
                    // Teils sofort umrechnen (der Pool läuft weiter), teils erst am Ende: dasselbe Ergebnis
                    if (view.size > PortfolioScaledParts.EXACT_MAX && rnd.nextBoolean()) {
                        early[views.size] = view.weightedSum(weight)
                    }
                    views += view
                }
            }
            val large = views.filter { it.size > PortfolioScaledParts.EXACT_MAX }
            assertTrue("grosse Ansichten: ${large.size}", large.size > 100)
            views.forEachIndexed { i, view ->
                val parts = ArrayList(view)
                val fast = view.weightedSum(weight)
                val slow = parts.weightedSum(weight)
                if (i in early) assertEquals("früh gelesen $i", early[i], fast)
                val rel = (slow!! - fast!!).abs().divide(slow.abs(), mc)
                assertTrue("Ansicht $i: $slow / $fast", rel <= RELATIVE)
                assertEquals("Prüfung $i", parts.any { test.of(it.time) }, view.anyTime(test))
                val bounds = listOf(parts.minOf { it.time }, parts.maxOf { it.time })
                assertEquals("Zeitpunkte $i", bounds, view.timeBounds())
            }
            // Zweiter Durchgang mit dem anderen Gewicht (die laufende Summe gilt je Gewicht)
            var missing = 0
            views.forEachIndexed { i, view ->
                val gapSlow = ArrayList(view).weightedSum(gap)
                assertEquals("fehlender Kurs $i", gapSlow == null, view.weightedSum(gap) == null)
                if (gapSlow == null) missing++
            }
            assertTrue("auch fehlende Kurse geprüft: $missing", missing > 0 && missing < views.size)
            assertTrue("Prüfung beide Ausgänge", views.any { it.anyTime(test) } && !views.all { it.anyTime(test) })
        }
    }

    private fun rate(t: Long): BigDecimal = BigDecimal("0.9").add(BigDecimal.valueOf((t / DAY) % 13, 3))

    private fun randomAmount(rnd: Random): BigDecimal =
        BigDecimal.valueOf(1L + rnd.nextLong(100_000_000L), rnd.nextInt(9)).add(BigDecimal("0.001"))

    private fun randomFraction(rnd: Random): BigDecimal =
        BigDecimal(1 + rnd.nextInt(999)).divide(BigDecimal(1_000 + rnd.nextInt(7)), PortfolioDecimal.MC)

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 86_400_000L
        const val START = 1_600_000_000_000L

        /** Teile weichen nur in der Rundung ab (Beträge bis 10⁸ USDT, gemessen ≈ 10⁻²⁶). */
        val TOLERANCE = BigDecimal("1E-18")

        /** Umrechnung grosser Ansichten gegen Teil für Teil (gemessen ≈ 10⁻³³). */
        val RELATIVE = BigDecimal("1E-28")

        val NICE = listOf("0.5", "0.25", "0.1", "0.2", "0.75", "0.125").map(::BigDecimal)
    }
}
