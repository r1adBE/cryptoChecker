package com.cryptochecker.app.data.local.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Bedingung, die einen Alarm auslöst. */
enum class AlarmCondition {
    /** Kurs erreicht oder übersteigt den Schwellwert. */
    PRICE_ABOVE,

    /** Kurs erreicht oder unterschreitet den Schwellwert. */
    PRICE_BELOW,

    /** Kurs steigt gegenüber dem Referenzkurs um mindestens x Prozent. */
    CHANGE_PERCENT_UP,

    /** Kurs fällt gegenüber dem Referenzkurs um mindestens x Prozent. */
    CHANGE_PERCENT_DOWN,

    /** Kurs bewegt sich (rauf oder runter) um mindestens x Prozent innerhalb von y Stunden. */
    MOVE_PERCENT_WINDOW,

    /**
     * Handelsvolumen der letzten abgeschlossenen Stunde ist mindestens x-mal so hoch
     * wie der Durchschnitt der 24 Stunden davor (Binance-Stundenkerzen).
     * [AlarmEntity.threshold] = Faktor, [AlarmEntity.referenceAt] = Startzeit der
     * zuletzt gemeldeten Kerze. Gespeichert wird der Name — keine Migration nötig.
     */
    VOLUME_SPIKE,

    /**
     * Kurs liegt höchstens x % unter dem Hoch der letzten 30/90/365 Tage — oder
     * macht ein neues (Tageskerzen, siehe NearExtreme). [AlarmEntity.threshold] = Abstand
     * in %, [AlarmEntity.windowHours] = Zeitraum in TAGEN, [AlarmEntity.referenceAt] = 0
     * scharf / > 0 gemeldet, [AlarmEntity.referencePrice] = zuletzt gemeldete Marke.
     * Gespeichert wird der Name — keine Migration nötig.
     */
    NEAR_HIGH,

    /** Wie [NEAR_HIGH], aber höchstens x % über dem Tief des Zeitraums (oder ein neues Tief). */
    NEAR_LOW,

    /**
     * Nur Perpetual-Futures (siehe DerivativesAlarm.supports): Funding Rate je Intervall erreicht
     * oder übersteigt x % ([AlarmEntity.threshold] in Prozent, darf negativ sein).
     * [AlarmEntity.referenceAt] 0 = scharf / > 0 = gemeldet (wie Kursmarken). Gespeichert
     * wird der Name — keine Migration nötig.
     */
    FUNDING_ABOVE,

    /** Wie [FUNDING_ABOVE], aber Funding erreicht oder unterschreitet x %. */
    FUNDING_BELOW,

    /**
     * Nur Perpetual-Futures: Open Interest (in Coins) liegt mindestens x % über der gespeicherten
     * Messung von vor [AlarmEntity.windowHours] Stunden (1, 4 oder 24). [AlarmEntity.referenceAt]
     * 0 = scharf / > 0 = gemeldet. Gespeichert wird der Name — keine Migration nötig.
     */
    OI_UP,

    /** Wie [OI_UP], aber Open Interest mindestens x % darunter. */
    OI_DOWN,
    ;

    /** «Nahe am Hoch / Tief»: Schwellwert ist ein Abstand in Prozent, Fenster in Tagen. */
    val isNearExtreme: Boolean
        get() = this == NEAR_HIGH || this == NEAR_LOW

    /** «Funding über/unter»: Schwellwert ist eine Funding Rate in Prozent (mit Vorzeichen). */
    val isFunding: Boolean
        get() = this == FUNDING_ABOVE || this == FUNDING_BELOW

    /** «Open Interest steigt/fällt um x % in N Stunden». */
    val isOpenInterest: Boolean
        get() = this == OI_UP || this == OI_DOWN

    /** Braucht Funding/Open Interest eines Perpetual-Kontrakts (nur Futures-Paare). */
    val isDerivatives: Boolean
        get() = isFunding || isOpenInterest

    val isPercent: Boolean
        get() = this == CHANGE_PERCENT_UP || this == CHANGE_PERCENT_DOWN || this == MOVE_PERCENT_WINDOW

    /** Schwellwert ist ein Kurs (in der Quote-Währung oder in [AlarmEntity.currency]). */
    val isPriceThreshold: Boolean
        get() = this == PRICE_ABOVE || this == PRICE_BELOW
}

@Entity(
    tableName = "alarms",
    foreignKeys = [
        ForeignKey(
            entity = WatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["watchId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("watchId")]
)
data class AlarmEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo val watchId: Long,
    @ColumnInfo val condition: AlarmCondition,

    /** Kurs (bei PRICE_*), Prozentwert (bei CHANGE_PERCENT_*) oder Faktor (bei VOLUME_SPIKE). */
    @ColumnInfo val threshold: Double,

    @ColumnInfo val enabled: Boolean = true,

    /** false = Alarm schaltet sich nach dem Auslösen ab. */
    @ColumnInfo val repeating: Boolean = false,

    @ColumnInfo val sound: Boolean = true,
    @ColumnInfo val vibrate: Boolean = true,

    /** Alarm zusätzlich vorlesen. */
    @ColumnInfo val speak: Boolean = false,

    /** Bezugskurs für prozentuale Alarme; wird beim Auslösen neu gesetzt. */
    @ColumnInfo val referencePrice: Double? = null,

    @ColumnInfo val lastTriggeredAt: Long = 0,
    @ColumnInfo val lastTriggeredPrice: Double? = null,

    /**
     * Zeitfenster in Stunden für MOVE_PERCENT_WINDOW und OI_UP/OI_DOWN (1, 4, 24);
     * bei NEAR_HIGH/NEAR_LOW der Zeitraum in Tagen.
     */
    @ColumnInfo(defaultValue = "1") val windowHours: Int = 1,

    /**
     * Seit wann der Bezugskurs gilt (Beginn des Zeitfensters). Bei PRICE_ABOVE/PRICE_BELOW:
     * 0 = scharf, > 0 = gemeldet, bis der Kurs auf die andere Seite der Marke zurückkehrt.
     */
    @ColumnInfo(defaultValue = "0") val referenceAt: Long = 0,

    /**
     * Währung des Schwellwerts bei PRICE_ABOVE/PRICE_BELOW, z. B. «CHF».
     * null = Quote-Währung des Paars (wie bisher). Bei einer anderen Währung
     * wird der Kurs vor dem Vergleich umgerechnet.
     */
    @ColumnInfo val currency: String? = null,
)

/**
 * Währung, in der der Schwellwert umgerechnet verglichen wird; null = keine
 * Umrechnung. Als Erweiterung, damit Room sie nicht als Spalte betrachtet.
 */
val AlarmEntity.convertCurrency: String?
    get() = currency?.takeIf { condition.isPriceThreshold && it.isNotBlank() }
