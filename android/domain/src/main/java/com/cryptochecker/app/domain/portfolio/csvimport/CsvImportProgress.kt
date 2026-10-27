package com.cryptochecker.app.domain.portfolio.csvimport

// Fortschritt der langen Schritte des CSV-Imports (Vorschau umrechnen, Speichern): gedrosselt melden und abbrechbar
// anlegen. Reines Kotlin, gespiegelt in `CsvImportProgress.swift`.

/** Langer Schritt des CSV-Imports mit Fortschrittsbalken. */
enum class CsvImportStep { PREVIEW, SAVE }

/** Stand eines langen Schritts: [done] von [total] Zeilen. */
data class CsvImportProgress(val step: CsvImportStep, val done: Int, val total: Int) {
    /** Anteil 0…1 (ohne Zeilen 0). */
    val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/** «Abbrechen» während des Speicherns: nichts angelegt (die umschliessende Transaktion rollt alles zurück). */
class CsvImportCancelledException : Exception("CSV-Import abgebrochen")

/**
 * Meldet den Fortschritt gedrosselt an [report]: höchstens alle [minRows] Zeilen und frühestens nach [minMillis] ms
 * (die Ansicht zeichnet nicht für jede Zeile neu); die letzte Zeile ([total]) immer, genau einmal. Nicht threadsicher —
 * aus einem einzigen Ablauf rufen.
 */
class CsvProgressThrottle(
    private val step: CsvImportStep,
    private val total: Int,
    private val minRows: Int = MIN_ROWS,
    private val minMillis: Long = MIN_MILLIS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val report: (CsvImportProgress) -> Unit,
) {
    private var lastDone = 0
    private var lastTime = clock()

    /** «0 von [total]»: der Balken erscheint gleich zu Beginn des Schritts. */
    fun start() = report(CsvImportProgress(step, 0, total))

    /** [done] Zeilen fertig. */
    fun row(done: Int) {
        if (done <= lastDone) return
        val now = clock()
        if (done < total && (done - lastDone < minRows || now - lastTime < minMillis)) return
        lastDone = done
        lastTime = now
        report(CsvImportProgress(step, done.coerceAtMost(total), total))
    }

    companion object {
        const val MIN_ROWS = 100
        const val MIN_MILLIS = 50L
    }
}

/** Abbrechbares Anlegen importierter Zeilen — gleiche Regel auf Android und iOS (`CsvImportInsert`). */
object CsvImportInsert {
    /**
     * Zeilen der Reihe nach anlegen; der Aufrufer umschliesst alles mit EINER Transaktion. Zeilen mit einem schon
     * bekannten Schlüssel ([keys], in [known]) werden übersprungen, die angelegten kommen zu [known]; [insert] = true
     * bei angelegt. Vor jeder Zeile wird [cancelled] geprüft — dann [CsvImportCancelledException] (die Transaktion rollt
     * zurück, nichts ist importiert); nach jeder Zeile [onRow] mit der Zahl fertiger Zeilen.
     * @return Anzahl angelegter Zeilen
     */
    inline fun <T> run(
        items: List<T>,
        known: MutableSet<String>,
        keys: (T) -> List<String>,
        cancelled: () -> Boolean,
        onRow: (Int) -> Unit,
        insert: (T) -> Boolean,
    ): Int {
        var count = 0
        items.forEachIndexed { i, item ->
            if (cancelled()) throw CsvImportCancelledException()
            val k = keys(item)
            if (k.none { it in known }) {
                known += k
                if (insert(item)) count++
            }
            onRow(i + 1)
        }
        return count
    }
}
