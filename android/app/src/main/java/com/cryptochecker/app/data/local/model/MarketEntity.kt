package com.cryptochecker.app.data.local.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Eine Börse mit gespeicherter Paarliste (Tabelle «markets»). Reihenfolge und Art der Felder
 * bestimmen das Schema — nicht verändern (keine Migration).
 */
@Entity(
    tableName = "markets",
    indices = [Index(value = ["marketKey"])],
)
data class MarketEntity(
    /** Laufende Nummer, von Room vergeben (0 beim Einfügen). */
    @PrimaryKey(autoGenerate = true)
    val id: Long,

    /** Kennung der Börse (Klassenname), Vergleich ohne Gross-/Kleinschreibung. */
    @ColumnInfo(collate = ColumnInfo.NOCASE)
    val marketKey: String,

    /** Zeitpunkt des letzten Abgleichs in ms. */
    @ColumnInfo
    val updateDate: Long,
)
