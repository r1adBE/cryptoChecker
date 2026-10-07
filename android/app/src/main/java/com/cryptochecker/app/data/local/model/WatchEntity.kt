package com.cryptochecker.app.data.local.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.marketdata.model.FuturesContractType

/** Höchstlänge einer Notiz je Paar. */
const val NOTE_MAX = 120

/**
 * Ein beobachtetes Handelspaar ("Checker" in der Original-App).
 * Aus diesen Einträgen speisen sich Watchlist, Benachrichtigungen, Widgets,
 * Sprachansagen und Alarme.
 */
@Entity(
    tableName = "watches",
    indices = [Index(value = ["marketKey", "baseAsset", "quoteAsset", "contractType"], unique = true)]
)
data class WatchEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo val marketKey: String,
    @ColumnInfo val marketName: String,
    @ColumnInfo val baseAsset: String,
    @ColumnInfo val quoteAsset: String,
    @ColumnInfo val contractType: FuturesContractType = FuturesContractType.NONE,
    @ColumnInfo val pairId: String? = null,

    @ColumnInfo val sortOrder: Int = 0,

    /** Dauerhafte Benachrichtigung mit dem aktuellen Kurs. */
    @ColumnInfo val notificationEnabled: Boolean = true,

    /** Kurs bei jeder Aktualisierung vorlesen. */
    @ColumnInfo val ttsEnabled: Boolean = false,

    @ColumnInfo val lastPrice: Double? = null,
    @ColumnInfo val previousPrice: Double? = null,
    @ColumnInfo val lastUpdate: Long = 0,

    /** Kurs, bei dem zuletzt eine Benachrichtigung gezeigt wurde. */
    @ColumnInfo val notifiedPrice: Double? = null,

    /** Zeitpunkt jener Meldung — Grundlage für „seit der letzten Meldung". */
    @ColumnInfo val notifiedAt: Long = 0,
    @ColumnInfo val lastError: String? = null,

    /** Favorit: steht in der Watchlist ganz oben (langes Drücken auf die Karte). */
    @ColumnInfo(defaultValue = "0") val favorite: Boolean = false,

    /** Bestand: gehaltene Menge der Basiswährung (z. B. 0.25 BTC). null = kein Bestand. */
    @ColumnInfo val holdings: Double? = null,

    /** Gruppe (Merkliste) des Paars. null = keine Gruppe. */
    @ColumnInfo val groupName: String? = null,

    /** Eigene Notiz, steht in der Merkliste unter dem Paar (z. B. «Einstieg bei 0.42»). */
    @ColumnInfo val note: String? = null,

    /**
     * Veränderung über 24 Stunden in Prozent (Pille, Puls-Zeile, Widgets), bei jeder
     * Aktualisierung neu berechnet (siehe `DayChange`). null = kein 24-h-Bezug
     * verfügbar, Pille «—». Abgeleiteter Marktwert, nicht in der Sicherung.
     */
    @ColumnInfo val change24h: Double? = null,
) {
    val displayPair: String get() = "$baseAsset/$quoteAsset"

    val displayName: String
        get() {
            val contract = FuturesContractType.getShortName(contractType)
            return if (contract == null) displayPair else "$displayPair $contract"
        }

    /** Wert des Bestands in der Quote-Währung; null ohne Bestand oder Kurs. */
    val holdingsValue: Double?
        get() {
            val amount = holdings?.takeIf { it > 0.0 } ?: return null
            val price = lastPrice?.takeIf { it > 0.0 } ?: return null
            return amount * price
        }

    fun toPairInfo(): CurrencyPairInfo =
        CurrencyPairInfo(baseAsset, quoteAsset, pairId, contractType)
}
