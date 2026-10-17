package com.cryptochecker.app.domain.portfolio

/** Ein CSV-Importlauf für die Liste «Importe» ([PortfolioImportBatch.summarize]). */
data class PortfolioImportBatchInfo(
    /** Kennung ([PortfolioImportBatch.newId]), so in `importBatch` gespeichert. */
    val id: String,
    /** Format des Imports ([com.cryptochecker.app.domain.portfolio.csvimport.CsvImportFormat]-Name, z. B. «RELAI»); null = unlesbar. */
    val format: String?,
    /** Zeitpunkt des Imports (ms); null = unlesbar. */
    val time: Long?,
    /** Anzahl Transaktionen dieses Laufs, die noch da sind. */
    val count: Int,
)

/**
 * Kennung eines CSV-Importlaufs (reines Kotlin, Spiegel: `PortfolioImportBatch.swift`): `FORMAT:Zeit(ms):Hash`, Hash =
 * FNV-1a (32 Bit, 8 Hex-Zeichen) über die Zeilenschlüssel des Laufs — eindeutig auch bei zwei Importen in derselben
 * Millisekunde. Alle Transaktionen eines Laufs tragen dieselbe Kennung; «Import rückgängig» löscht genau sie.
 */
object PortfolioImportBatch {

    /** Höchstlänge (gespeichert, Sicherung). */
    const val MAX_LENGTH = 64

    fun newId(format: String, time: Long, keys: List<String>): String {
        val f = format.trim().uppercase().filter { it in 'A'..'Z' || it in '0'..'9' || it == '_' }.take(20).ifEmpty { "CSV" }
        return "$f:$time:${hash(keys)}"
    }

    /** FNV-1a 32 Bit über die UTF-8-Bytes der Schlüssel (getrennt durch «\n»), 8 Hex-Zeichen klein. */
    fun hash(keys: List<String>): String {
        var h = 0x811C9DC5L
        keys.joinToString("\n").toByteArray(Charsets.UTF_8).forEach { b ->
            h = h xor (b.toLong() and 0xFF)
            h = (h * 0x01000193L) and 0xFFFFFFFFL
        }
        return h.toString(16).padStart(8, '0')
    }

    /** Gespeicherte Kennung bereinigt: getrimmt, höchstens [MAX_LENGTH] Zeichen (länger = ungültig); leer = null. */
    fun clean(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_LENGTH }

    /** Format und Zeit aus der Kennung; unlesbar = null. */
    fun parse(id: String): Pair<String?, Long?> {
        val parts = id.split(':')
        if (parts.size != 3) return null to null
        return parts[0].takeIf { it.isNotEmpty() } to parts[1].toLongOrNull()
    }

    /** Importläufe aus den Kennungen aller importierten Transaktionen: Anzahl je Lauf, neueste zuerst (dann Kennung). */
    fun summarize(batches: List<String?>): List<PortfolioImportBatchInfo> =
        batches.mapNotNull { clean(it) }
            .groupingBy { it }.eachCount()
            .map { (id, count) -> parse(id).let { (format, time) -> PortfolioImportBatchInfo(id, format, time, count) } }
            .sortedWith(compareByDescending<PortfolioImportBatchInfo> { it.time ?: Long.MIN_VALUE }.thenBy { it.id })
}
