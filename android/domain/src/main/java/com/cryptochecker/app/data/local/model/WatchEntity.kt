package com.cryptochecker.app.data.local.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.FuturesContractType

/** Höchstlänge einer Notiz je Paar. */
const val NOTE_MAX = 120

/** Höchstlänge eines Gruppennamens in sichtbaren Zeichen — Eingabe und Sicherung (wie iOS `WatchGroupNames.maxLength`). */
const val GROUP_NAME_MAX = 24

/**
 * Ein beobachtetes Handelspaar ("Checker" in der Original-App).
 * Aus diesen Einträgen speisen sich Watchlist, Benachrichtigungen, Widgets,
 * Sprachansagen und Alarme.
 */
@Entity(
    tableName = "watches",
    // Eindeutig je Paar; `pairId` gehört dazu, damit zwei DexScreener-Pools mit gleichem Symbol auf derselben
    // Chain zwei Einträge sein können (WatchIdentity). Bei den Börsen prüft die App vor dem Einfügen
    // ohne Kennung (WatchDao.findWatchId); SQLite zählt NULL-Kennungen ohnehin als verschieden.
    indices = [Index(value = ["exchangeKey", "baseAsset", "quoteAsset", "contractType", "pairId"], unique = true)]
)
data class WatchEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo val exchangeKey: String,
    @ColumnInfo val exchangeName: String,
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

    /** Gruppe (Merkliste) des Paars. null = keine Gruppe. */
    @ColumnInfo val groupName: String? = null,

    /** Eigene Notiz, steht in der Merkliste unter dem Paar (z. B. «Einstieg bei 0.42»). */
    @ColumnInfo val note: String? = null,

    /**
     * Veränderung über 24 Stunden in Prozent (Änderung, Puls-Zeile, Widgets), bei jeder
     * Aktualisierung neu berechnet (siehe `DayChange`). null = kein 24-h-Bezug
     * verfügbar, Anzeige «—». Abgeleiteter Marktwert, nicht in der Sicherung.
     */
    @ColumnInfo val change24h: Double? = null,

    /**
     * Deaktiviert: keine Kursabfragen (REST, Live, Hintergrund, Widgets), keine Meldungen, keine
     * Alarme (bleiben unverändert gespeichert). Steht grau mit dem letzten Kurs in der Merkliste.
     * Standardwert wie die Migration (`DEFAULT 0`), damit Room das Schema als gleich erkennt.
     */
    @ColumnInfo(defaultValue = "0") val disabled: Boolean = false,

    /**
     * Zeitpunkt, an dem sich [lastPrice] zuletzt WIRKLICH geändert hat (bleibt bei gleichem Kurs
     * stehen, anders als [lastUpdate]). 0 = unbekannt. Grundlage für «Kurs unverändert
     * seit …» bei Börsen ohne eigene Zeit (`PriceFreshness`). Abgeleitet, nicht in der Sicherung.
     */
    @ColumnInfo(defaultValue = "0") val priceChangedAt: Long = 0,

    /**
     * Zeit des letzten Kurses laut Börse (`Ticker.exchangeTime`); null = die Börse liefert keine
     * (dann ist [lastUpdate] der Abrufzeitpunkt). Abgeleitet, nicht in der Sicherung.
     */
    @ColumnInfo val exchangeTime: Long? = null,
) {
    val displayPair: String get() = "$baseAsset/$quoteAsset"

    val displayName: String
        get() {
            val contract = FuturesContractType.getShortName(contractType)
            return if (contract == null) displayPair else "$displayPair $contract"
        }

    fun toPairInfo(): ExchangePair =
        ExchangePair(baseAsset, quoteAsset, pairId, contractType)
}
