package com.cryptochecker.app.domain.market

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Phase im vierjährigen Bitcoin-Zyklus, abgeleitet aus früheren Zyklen. */
enum class CyclePhase {
    /** Kurz nach dem Halving: Kurs sammelt sich, Aufwärtsbewegung beginnt. */
    EARLY_BULL,

    /** Historisch der stärkste Anstieg; Hochs lagen 12–18 Monate nach dem Halving. */
    BULL,

    /** Nach dem Zyklushoch; Tiefs lagen rund 12–13 Monate nach dem Hoch. */
    BEAR,

    /** Bodenbildung und Erholung bis zum nächsten Halving. */
    RECOVERY,
}

data class CycleInfo(
    val phase: CyclePhase,
    val monthsSinceHalving: Long,
    val lastHalving: LocalDate,
    val nextHalvingEstimate: LocalDate,
)

/**
 * Bitcoin-Halving-Zyklus. Rein kalenderbasiert, ohne Internet.
 *
 * Grundlage sind die bisherigen Zyklen (Hochs: Dez. 2013, Dez. 2017,
 * Nov. 2021, Okt. 2025 — jeweils 12–18 Monate nach dem Halving; Tiefs
 * rund ein Jahr danach). Das ist ein historisches Muster, keine Prognose.
 */
object BitcoinCycle {

    /** Tatsächliche Halving-Termine. */
    val HALVINGS: List<LocalDate> = listOf(
        LocalDate.of(2012, 11, 28),
        LocalDate.of(2016, 7, 9),
        LocalDate.of(2020, 5, 11),
        LocalDate.of(2024, 4, 20),
    )

    /** Ein Halving alle 210'000 Blöcke, im Schnitt knapp vier Jahre. */
    private const val CYCLE_DAYS = 1_440L

    fun info(today: LocalDate = LocalDate.now()): CycleInfo {
        val last = HALVINGS.lastOrNull { !it.isAfter(today) } ?: HALVINGS.first()

        // Liegt heute schon nach einem geschätzten künftigen Halving, weiterzählen.
        var lastHalving = last
        var next = last.plusDays(CYCLE_DAYS)
        while (!next.isAfter(today)) {
            lastHalving = next
            next = next.plusDays(CYCLE_DAYS)
        }

        val months = ChronoUnit.MONTHS.between(lastHalving, today)
        val phase = when {
            months < 6 -> CyclePhase.EARLY_BULL
            months < 18 -> CyclePhase.BULL
            months < 31 -> CyclePhase.BEAR
            else -> CyclePhase.RECOVERY
        }

        return CycleInfo(phase, months, lastHalving, next)
    }
}
