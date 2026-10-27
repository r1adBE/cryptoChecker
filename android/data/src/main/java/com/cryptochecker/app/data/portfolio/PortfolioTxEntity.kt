package com.cryptochecker.app.data.portfolio

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioTrade
import com.cryptochecker.app.domain.portfolio.PortfolioDecimal
import com.cryptochecker.app.domain.portfolio.PortfolioExchangeRoute
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.csvimport.CsvRestoreState
import java.math.BigDecimal

/**
 * Transaktion im Portfolio ([PortfolioTxType]: Kauf, Verkauf, Umbuchung, Belohnung, Ein-/Auszahlung) —
 * unabhängig von der Merkliste.
 * Preise in USDT; [priceUsdt] null = unbekannt (z. B. übernommener Bestand). Menge, Preis und Gebühr
 * sind exakt ([BigDecimal], seit Portfolio-Datenbank v2 als Text gespeichert): 1 Satoshi =
 * 0.00000001 BTC bleibt genau 1 Satoshi.
 *
 * Spalten bis `note` müssen exakt zu [com.cryptochecker.app.data.local.AppDatabase.MIGRATION_6_7] passen
 * (Altbestand der Hauptdatenbank); `fee`, `feeUnit`, `exchange` kamen mit
 * [PortfolioDatabase.MIGRATION_1_2] dazu (alle optional); `transferFrom`, `transferTo`, `feeCoin`,
 * `feeCoinAmount`, `feeCoinPriceUsdt`, `importKey` (mit Index), `acquiredTime`, `toCoin`, `toAmount`,
 * `swapValueUsdt` (Tausch) und `importBatch` (CSV-Importlauf) mit [PortfolioDatabase.MIGRATION_2_3] (alle optional);
 * `giftToSpouse`, `linkedImportKey` und `importRestoreState` mit [PortfolioDatabase.MIGRATION_3_4]; `feeRow` und `interest`
 * mit [PortfolioDatabase.MIGRATION_4_5].
 */
@Entity(tableName = "portfolio_tx", indices = [Index(value = ["importKey"])])
data class PortfolioTxEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Basis-Symbol in Grossbuchstaben, z. B. «BTC». */
    @ColumnInfo val coin: String,

    @ColumnInfo val type: PortfolioTxType,

    /** Menge, immer > 0 — exakt (Text in der Datenbank, [PortfolioDecimalConverters]). */
    @ColumnInfo val amount: BigDecimal,

    @ColumnInfo val priceUsdt: BigDecimal? = null,

    /** Zeitpunkt des Handels (ms). */
    @ColumnInfo val time: Long,

    @ColumnInfo val note: String? = null,

    /** Gebühr (> 0) in [feeUnit]; null = keine erfasst. */
    @ColumnInfo val fee: BigDecimal? = null,

    /** Name von [PortfolioFeeUnit] («QUOTE» = USDT, «COIN»); null mit Gebühr = USDT. */
    @ColumnInfo val feeUnit: String? = null,

    /**
     * Börse des Handels; alle Transaktionen eines Coins mit derselben Börse → Kurs dieser Börse
     * ([PortfolioExchangeRoute]). null = keine Angabe. Bei einer Umbuchung null (dort [transferFrom]/[transferTo]).
     */
    @ColumnInfo val exchange: String? = null,

    /** Umbuchung: woher (Börse oder Wallet, freier Text); null = keine Angabe. */
    @ColumnInfo val transferFrom: String? = null,

    /** Umbuchung: wohin (Börse oder Wallet, freier Text) — dort liegt der Bestand danach; null = keine Angabe. */
    @ColumnInfo val transferTo: String? = null,

    /** Gebühr in einem dritten Coin (z. B. «BNB»); null = keine. Nur zusammen mit [feeCoinAmount]. */
    @ColumnInfo val feeCoin: String? = null,

    /** Menge der Gebühr in [feeCoin] (> 0) — exakt. */
    @ColumnInfo val feeCoinAmount: BigDecimal? = null,

    /** USDT je [feeCoin] zum Zeitpunkt (≥ 0); null = unbekannt. */
    @ColumnInfo val feeCoinPriceUsdt: BigDecimal? = null,

    /**
     * Herkunft aus einem CSV-Import (Quelle + Zeilenschlüssel, [com.cryptochecker.app.domain.portfolio.csvimport.CsvImportRow]);
     * erkennt dieselbe Zeile beim nächsten Import als doppelt. null = von Hand erfasst.
     */
    @ColumnInfo val importKey: String? = null,

    /**
     * Einzahlung: ursprüngliches Kaufdatum (ms) — FIFO-Reihenfolge und Haltedauer ab diesem Datum
     * ([PortfolioTrade.acquiredTime]); null = Datum der Einzahlung. Nur bei [PortfolioTxType.DEPOSIT].
     */
    @ColumnInfo val acquiredTime: Long? = null,

    /** Tausch ([PortfolioTxType.SWAP]): erhaltener Coin (Grossbuchstaben, ≠ [coin]); sonst null. */
    @ColumnInfo val toCoin: String? = null,

    /** Tausch: erhaltene Menge (> 0) — exakt (Text). */
    @ColumnInfo val toAmount: BigDecimal? = null,

    /** Tausch: Wert in USDT (gesamt, ≥ 0); null = unbekannt (Erlös und Einstand des neuen Postens unbekannt). */
    @ColumnInfo val swapValueUsdt: BigDecimal? = null,

    /**
     * CSV-Importlauf ([com.cryptochecker.app.domain.portfolio.PortfolioImportBatch]): alle Transaktionen eines Imports
     * haben dieselbe Kennung — «Import rückgängig» löscht genau sie. null = von Hand erfasst (oder früher importiert).
     */
    @ColumnInfo val importBatch: String? = null,

    /**
     * Schenkung ([PortfolioTxType.GIFT]) an Ehe-/Lebenspartner — UK/CA ohne Gewinn und Verlust
     * ([com.cryptochecker.app.domain.portfolio.TaxTemplate.spouseGiftNeutral]); bei anderen Arten immer false.
     */
    @ColumnInfo(defaultValue = "0") val giftToSpouse: Boolean = false,

    /**
     * Verknüpfte Umbuchung ([com.cryptochecker.app.domain.portfolio.csvimport.CsvTransferMatch]): Herkunft der Einzahlung
     * ([importKey] = die der Auszahlung) — erkennt beim nächsten Import auch diese Zeile als doppelt. Sonst null.
     */
    @ColumnInfo val linkedImportKey: String? = null,

    /**
     * Verknüpfte Umbuchung aus einer gespeicherten Transaktion: deren Zustand davor samt der darin aufgegangenen neuen Zeile
     * ([CsvRestoreState], JSON-Text über [PortfolioRestoreStateConverters]) für «Import rückgängig». Sonst null.
     */
    @ColumnInfo val importRestoreState: CsvRestoreState? = null,

    /**
     * Gebühr-Zeile (CSV «Fee»): eine Veräusserung zum Tageskurs ([com.cryptochecker.app.domain.portfolio.PortfolioLedger.isFeeDisposal]),
     * gespeichert als Verkauf. Eigenes Feld seit Portfolio-Datenbank v5 — bis dahin aus «Verkauf zum Preis 0 mit
     * Import-Herkunft» geschlossen ([com.cryptochecker.app.domain.portfolio.LegacyStorageMigration.legacyFeeRow]). Nur bei
     * [PortfolioTxType.SELL].
     */
    @ColumnInfo(defaultValue = "0") val feeRow: Boolean = false,

    /**
     * Belohnung ist ein Zins (Lending) — AT: Einkunft beim Zufluss, Anschaffungskosten = Wert. Eigenes Feld seit v5 (CSV-Import
     * setzt es, wo die Börse Zinsen ausweist; im Erfassen-Blatt umschaltbar) — bis dahin aus der Notiz geschlossen
     * ([com.cryptochecker.app.domain.portfolio.LegacyStorageMigration.legacyInterest]). Nur bei [PortfolioTxType.REWARD].
     */
    @ColumnInfo(defaultValue = "0") val interest: Boolean = false,
) {
    fun toTrade(): PortfolioTrade = PortfolioTrade(
        id = id,
        coin = coin,
        type = type,
        amount = amount,
        priceUsdt = priceUsdt,
        time = time,
        fee = fee,
        feeUnit = fee?.let { PortfolioFeeUnit.fromName(feeUnit) },
        feeCoin = feeCoin,
        feeCoinAmount = feeCoinAmount,
        feeCoinPrice = feeCoinPriceUsdt,
        acquiredTime = acquiredTime?.takeIf { type == PortfolioTxType.DEPOSIT },
        exchange = exchange,
        transferFrom = transferFrom,
        transferTo = transferTo,
        toCoin = toCoin?.takeIf { type == PortfolioTxType.SWAP },
        toAmount = toAmount?.takeIf { type == PortfolioTxType.SWAP },
        swapValueUsdt = swapValueUsdt?.takeIf { type == PortfolioTxType.SWAP },
        giftToSpouse = giftToSpouse && type == PortfolioTxType.GIFT,
        imported = !importKey.isNullOrBlank(),
        // Gebühr-Zeile ([com.cryptochecker.app.domain.portfolio.PortfolioLedger.isFeeDisposal]) und Zins: eigene Felder
        feeRow = feeRow && type == PortfolioTxType.SELL,
        interestIncome = interest && type == PortfolioTxType.REWARD,
    )

    /** Beteiligte Coins: [coin] und beim Tausch [toCoin] (für Kursabfragen und Zählungen). */
    fun coins(): List<String> = listOfNotNull(coin, toCoin?.takeIf { type == PortfolioTxType.SWAP && it.isNotBlank() })

    /** Eintrag für den Börsenweg ([PortfolioExchangeRoute.exchangeByLedger]). */
    fun routeEntry(): PortfolioExchangeRoute.RouteEntry =
        PortfolioExchangeRoute.RouteEntry(coin, exchange, type, transferTo, time, id)

    /** Einträge für den Börsenweg: beim Tausch auch für den erhaltenen Coin (gleiche Börse). */
    fun routeEntries(): List<PortfolioExchangeRoute.RouteEntry> {
        val to = toCoin?.takeIf { type == PortfolioTxType.SWAP && it.isNotBlank() }
            ?: return listOf(routeEntry())
        return listOf(routeEntry(), PortfolioExchangeRoute.RouteEntry(to, exchange, type, null, time, id))
    }
}

/**
 * Room: exakte Dezimalzahl ↔ Text («0.00000001», ohne Exponent). Gelesen wird auch, was die
 * Migration v1 → v2 aus den früheren REAL-Werten macht («1.0e-08»). Unlesbar → 0 bzw. null
 * (eine Menge 0 überspringt der Rechner).
 */
class PortfolioDecimalConverters {
    @TypeConverter
    fun fromText(text: String?): BigDecimal? = PortfolioDecimal.parse(text)

    @TypeConverter
    fun toText(value: BigDecimal?): String? = value?.let { PortfolioDecimal.text(it) }
}

/**
 * Room: Zustand vor einer Verknüpfung ↔ JSON-Text (Format in [CsvRestoreState]). Unlesbar oder unbekannte Fassung → null
 * (die Umbuchung wird beim «Import rückgängig» dann wie eine eigene Zeile ihres Laufs behandelt).
 */
class PortfolioRestoreStateConverters {
    @TypeConverter
    fun fromText(text: String?): CsvRestoreState? = CsvRestoreState.decode(text)

    @TypeConverter
    fun toText(value: CsvRestoreState?): String? = value?.encode()
}
