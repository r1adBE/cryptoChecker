package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.domain.portfolio.csvimport.CsvAbsorbedRow
import com.cryptochecker.app.domain.portfolio.csvimport.CsvRestoreState
import java.math.BigDecimal

/**
 * Einmalige Umstellung der Speicher-Notlösungen aus Build 56/57 auf eigene Felder (Entscheid des Inhabers, 17. Okt. 2026;
 * reines Kotlin, iOS `LegacyStorageMigration.swift`, gemeinsame Fälle `legacy_storage_migration.json`). Läuft in
 * [com.cryptochecker.app.data.portfolio.PortfolioDatabase.MIGRATION_3_4], beim Wiederherstellen alter Sicherungen
 * ([com.cryptochecker.app.data.portfolio.PortfolioJson.jsonToTx]) und auf iOS beim Lesen der Datei. Nur hier stehen die
 * alten Formen noch — alles andere kennt nur die Felder.
 *
 * Alte Formen:
 *  - Schenkung an Ehe-/Lebenspartner: Merker [SPOUSE_MARKER] im Feld «nach» (`transferTo`) einer [PortfolioTxType.GIFT]
 *    → `giftToSpouse` = true, «nach» leer.
 *  - Verknüpfte Umbuchung: beide Herkunftsschlüssel in `importKey`, getrennt durch [LINK] (Auszahlung links, Einzahlung
 *    rechts) → `importKey` + `linkedImportKey`.
 *  - Dahinter, durch [RESTORE] getrennt, der Zustand der gespeicherten Seite vor der Verknüpfung (Felder mit «|», Text mit
 *    %-Ersatz): Fassung «1» (Build 56, nur die gespeicherte Seite) bzw. «2» (Build 57, dazu ein dritter Abschnitt mit der
 *    aufgegangenen neuen Zeile) → `importRestoreState` ([CsvRestoreState]). Unlesbarer Zustand → keiner (die Schlüssel
 *    werden trotzdem getrennt; «Import rückgängig» behandelt die Umbuchung dann wie eine eigene Zeile ihres Laufs).
 *
 * Werte ohne alte Form kommen unverändert zurück — die Umstellung ist beliebig oft anwendbar.
 */
object LegacyStorageMigration {

    /** Merker im Feld «nach» einer Schenkung (Build 56/57). */
    const val SPOUSE_MARKER = "@spouse"

    /** Trenner der beiden Herkunftsschlüssel im alten `importKey`. */
    const val LINK = " ⇄ "

    /** Trenner vor dem alten Zustand im `importKey`. */
    const val RESTORE = " ⟲ "

    private const val VERSION_WITH_ABSORBED = "2"

    /**
     * Gebühr-Zeile nach der Regel bis Portfolio-Datenbank v4 (Build 75): ein Verkauf zum Preis 0 MIT Import-Herkunft. Seit
     * v5 steht das im eigenen Feld `feeRow`; diese Regel leitet es nur noch einmal ab — in
     * [com.cryptochecker.app.data.portfolio.PortfolioDatabase.MIGRATION_4_5] und für Sicherungen ohne das Feld
     * ([com.cryptochecker.app.data.portfolio.PortfolioJson.jsonToTx]). Wie iOS.
     */
    fun legacyFeeRow(type: PortfolioTxType?, importKey: String?, price: BigDecimal?): Boolean =
        type == PortfolioTxType.SELL && !importKey.isNullOrBlank() && price != null && price.signum() == 0

    /**
     * Zins (Lending) nach der Regel bis v4: Belohnung, deren Notiz «interest», «lending», «Zins» … nennt
     * ([interestRewardLegacy]). Seit v5 eigenes Feld `interest`; diese Regel nur noch beim einmaligen Ableiten.
     */
    fun legacyInterest(type: PortfolioTxType?, note: String?): Boolean =
        type == PortfolioTxType.REWARD && interestRewardLegacy(note)

    /**
     * Zins-Erkennung, wie sie bis Build 87 galt (Teilstring, auch «Verzinsung»): eingefroren für Altbestand — Migration v4 →
     * v5 und Sicherungen ohne Feld `interest` werden so abgeleitet, wie die App sie damals rechnete. Die Wort-Regel
     * ([TaxEngines.interestReward]) gilt nur für neue Eingaben und Importe. Wie iOS.
     */
    fun interestRewardLegacy(text: String?): Boolean {
        val t = text?.lowercase() ?: return false
        return LEGACY_INTEREST_WORDS.any { it in t }
    }

    private val LEGACY_INTEREST_WORDS = listOf("interest", "lending", "zins", "interet", "intérêt")
    private const val VERSION_STORED_ONLY = "1"

    /** Die betroffenen Felder einer Transaktion (vorher bzw. nachher). */
    data class Fields(
        val transferTo: String?,
        val giftToSpouse: Boolean,
        val importKey: String?,
        val linkedImportKey: String?,
        val importRestoreState: CsvRestoreState?,
    )

    /** Alte Formen in [fields] einer Transaktion der Art [type] (null = unbekannte Art) in die Felder überführen. */
    fun convert(type: PortfolioTxType?, fields: Fields): Fields {
        var out = fields
        if (type == PortfolioTxType.GIFT && fields.transferTo?.trim() == SPOUSE_MARKER) {
            out = out.copy(transferTo = null, giftToSpouse = true)
        }
        val key = fields.importKey
        if (key != null && LINK in key && fields.linkedImportKey == null) {
            val segments = key.split(RESTORE)
            val parts = segments[0].split(LINK)
            if (parts.size == 2) {
                out = out.copy(
                    importKey = parts[0],
                    linkedImportKey = parts[1],
                    importRestoreState = fields.importRestoreState ?: restoreState(segments, parts),
                )
            }
        }
        return out
    }

    /**
     * Kurzform eines langen Teilschlüssels («~» + FNV-1a wie [PortfolioImportBatch.hash]): Vor Build 58 kürzte die
     * Verknüpfung Teile über 98 Zeichen so, damit beide in 200 Zeichen passten. Solche Teile bleiben nach der Umstellung
     * stehen ([com.cryptochecker.app.domain.portfolio.csvimport.CsvImport.splitDuplicates] erkennt die Zeile darüber).
     */
    fun shortKey(key: String): String = "~" + PortfolioImportBatch.hash(listOf(key))

    /** Zustand aus den Abschnitten nach [RESTORE]; null = keiner, unbekannte Fassung oder unlesbar. */
    private fun restoreState(segments: List<String>, parts: List<String>): CsvRestoreState? {
        if (segments.size !in 2..3) return null
        val f = segments[1].split('|').map { unescape(it) }
        val withAbsorbed = segments.size == 3
        if (f.size != 15 || f[0] != (if (withAbsorbed) VERSION_WITH_ABSORBED else VERSION_STORED_ONLY)) return null
        fun text(i: Int) = f[i].ifEmpty { null }
        fun dec(i: Int) = text(i)?.let { runCatching { BigDecimal(it) }.getOrNull() }
        val type = PortfolioTxType.fromName(f[2]) ?: return null
        // Eigene Seite der Verknüpfung: Einzahlung rechts, sonst links; die aufgegangene neue Zeile ist die andere
        val ownKey = if (type == PortfolioTxType.DEPOSIT) parts[1] else parts[0]
        val otherKey = if (type == PortfolioTxType.DEPOSIT) parts[0] else parts[1]
        val absorbed = if (withAbsorbed) absorbedRow(segments[2], otherKey) ?: return null else null
        return CsvRestoreState(
            type = type,
            amount = dec(3) ?: return null,
            priceUsdt = dec(4),
            time = f[5].toLongOrNull() ?: return null,
            fee = dec(6),
            feeUnit = text(7)?.let { u -> PortfolioFeeUnit.entries.firstOrNull { it.name == u } },
            feeCoin = text(8),
            feeCoinAmount = dec(9),
            feeCoinPrice = dec(10),
            transferFrom = text(11),
            transferTo = text(12),
            acquiredTime = text(13)?.toLongOrNull(),
            exchange = text(14),
            importKey = ownKey,
            importBatch = text(1),
            absorbed = absorbed,
        )
    }

    /** Aufgegangene neue Zeile aus dem dritten Abschnitt (Fassung «2»); null = unlesbar. */
    private fun absorbedRow(segment: String, importKey: String): CsvAbsorbedRow? {
        val f = segment.split('|').map { unescape(it) }
        if (f.size != 8) return null
        fun text(i: Int) = f[i].ifEmpty { null }
        fun dec(i: Int) = text(i)?.let { runCatching { BigDecimal(it) }.getOrNull() }
        return CsvAbsorbedRow(
            type = PortfolioTxType.fromName(f[0]) ?: return null,
            amount = dec(1) ?: return null,
            priceUsdt = dec(2),
            time = f[3].toLongOrNull() ?: return null,
            fee = dec(4),
            feeUnit = text(5)?.let { u -> PortfolioFeeUnit.entries.firstOrNull { it.name == u } },
            feeCoin = text(6),
            feeCoinAmount = dec(7),
            importKey = importKey,
        )
    }

    /** %-Ersatz der alten Felder rückgängig («%7C» = «|», «%21C4» = «⇄», «%27F2» = «⟲», «%25» = «%»). */
    private fun unescape(t: String) = t.replace("%27F2", "⟲").replace("%21C4", "⇄").replace("%7C", "|").replace("%25", "%")
}
