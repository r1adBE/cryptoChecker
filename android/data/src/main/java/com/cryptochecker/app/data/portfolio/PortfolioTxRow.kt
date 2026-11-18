package com.cryptochecker.app.data.portfolio

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.cryptochecker.app.domain.portfolio.PortfolioDecimal
import com.cryptochecker.app.domain.portfolio.PortfolioTxEntity
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.csvimport.CsvRestoreState
import java.math.BigDecimal

/**
 * Zeile der Tabelle «portfolio_tx» (Room). Das Fachmodell ohne Room ist [PortfolioTxEntity] in :domain; Spalten, Reihenfolge,
 * Standardwerte und Umwandler hier bestimmen das exportierte Schema und die gespeicherte Form (Preise mit der Regel der
 * Preise, [PortfolioPriceConverters]) und bleiben darum unverändert; Bedeutung der Felder dort. Umwandlung: [toEntity] und
 * [toRow], Feld für Feld — ohne Standardwerte, damit [toRow] kein Feld vergessen kann.
 */
@Entity(tableName = "portfolio_tx", indices = [Index(value = ["importKey"]), Index(value = ["importBatch"]), Index(value = ["time", "id"])])
data class PortfolioTxRow(
    @PrimaryKey(autoGenerate = true)
    val id: Long,
    @ColumnInfo val coin: String,
    @ColumnInfo val type: PortfolioTxType,
    @ColumnInfo val amount: BigDecimal,
    @field:TypeConverters(PortfolioPriceConverters::class)
    @ColumnInfo val priceUsdt: BigDecimal?,
    @ColumnInfo val time: Long,
    @ColumnInfo val note: String?,
    @ColumnInfo val fee: BigDecimal?,
    @ColumnInfo val feeUnit: String?,
    @ColumnInfo val exchange: String?,
    @ColumnInfo val transferFrom: String?,
    @ColumnInfo val transferTo: String?,
    @ColumnInfo val feeCoin: String?,
    @ColumnInfo val feeCoinAmount: BigDecimal?,
    @field:TypeConverters(PortfolioPriceConverters::class)
    @ColumnInfo val feeCoinPriceUsdt: BigDecimal?,
    @ColumnInfo val importKey: String?,
    @ColumnInfo val acquiredTime: Long?,
    @ColumnInfo val toCoin: String?,
    @ColumnInfo val toAmount: BigDecimal?,
    @ColumnInfo val swapValueUsdt: BigDecimal?,
    @ColumnInfo val importBatch: String?,
    @ColumnInfo(defaultValue = "0") val giftToSpouse: Boolean,
    @ColumnInfo val linkedImportKey: String?,
    @ColumnInfo val importRestoreState: CsvRestoreState?,
    @ColumnInfo(defaultValue = "0") val feeRow: Boolean,
    @ColumnInfo(defaultValue = "0") val interest: Boolean,
    @ColumnInfo(defaultValue = "0") val stakingMove: Boolean,
)

/** Tabellenzeile → Fachmodell, alle Felder (PortfolioRowMappingTest prüft Vollständigkeit und Hin- und Rückweg). */
fun PortfolioTxRow.toEntity(): PortfolioTxEntity = PortfolioTxEntity(
    id = id,
    coin = coin,
    type = type,
    amount = amount,
    priceUsdt = priceUsdt,
    time = time,
    note = note,
    fee = fee,
    feeUnit = feeUnit,
    exchange = exchange,
    transferFrom = transferFrom,
    transferTo = transferTo,
    feeCoin = feeCoin,
    feeCoinAmount = feeCoinAmount,
    feeCoinPriceUsdt = feeCoinPriceUsdt,
    importKey = importKey,
    acquiredTime = acquiredTime,
    toCoin = toCoin,
    toAmount = toAmount,
    swapValueUsdt = swapValueUsdt,
    importBatch = importBatch,
    giftToSpouse = giftToSpouse,
    linkedImportKey = linkedImportKey,
    importRestoreState = importRestoreState,
    feeRow = feeRow,
    interest = interest,
    stakingMove = stakingMove,
)

/** Fachmodell → Tabellenzeile, alle Felder. */
fun PortfolioTxEntity.toRow(): PortfolioTxRow = PortfolioTxRow(
    id = id,
    coin = coin,
    type = type,
    amount = amount,
    priceUsdt = priceUsdt,
    time = time,
    note = note,
    fee = fee,
    feeUnit = feeUnit,
    exchange = exchange,
    transferFrom = transferFrom,
    transferTo = transferTo,
    feeCoin = feeCoin,
    feeCoinAmount = feeCoinAmount,
    feeCoinPriceUsdt = feeCoinPriceUsdt,
    importKey = importKey,
    acquiredTime = acquiredTime,
    toCoin = toCoin,
    toAmount = toAmount,
    swapValueUsdt = swapValueUsdt,
    importBatch = importBatch,
    giftToSpouse = giftToSpouse,
    linkedImportKey = linkedImportKey,
    importRestoreState = importRestoreState,
    feeRow = feeRow,
    interest = interest,
    stakingMove = stakingMove,
)

/**
 * Room: exakte Dezimalzahl ↔ Text («0.00000001», ohne Exponent). Unlesbar → 0 bzw. null
 * (eine Menge 0 überspringt der Rechner). Geschrieben mit der Regel der Mengen ([PortfolioDecimal.text]: höchstens 18
 * Nachkommastellen, halbe auf gerade) — für Menge, Gebühren und Werte; die Preisspalten schreibt
 * [PortfolioPriceConverters]. So speichert keine Menge mehr als 18 Nachkommastellen, woher sie auch kommt.
 */
class PortfolioDecimalConverters {
    @TypeConverter
    fun fromText(text: String?): BigDecimal? = PortfolioDecimal.parse(text)

    @TypeConverter
    fun toText(value: BigDecimal?): String? = value?.let { PortfolioDecimal.text(it) }
}

/**
 * Room: Preisspalte ([PortfolioTxRow.priceUsdt], [PortfolioTxRow.feeCoinPriceUsdt]) ↔ Text mit der Regel der Preise
 * ([PortfolioDecimal.priceText]: 34 gültige Stellen, mindestens 18 und höchstens 40 Nachkommastellen) — der Preis
 * winziger Coins behält so seine Stellen. Lesen wie [PortfolioDecimalConverters].
 */
class PortfolioPriceConverters {
    @TypeConverter
    fun fromText(text: String?): BigDecimal? = PortfolioDecimal.parse(text)

    @TypeConverter
    fun toText(value: BigDecimal?): String? = value?.let { PortfolioDecimal.priceText(it) }
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
