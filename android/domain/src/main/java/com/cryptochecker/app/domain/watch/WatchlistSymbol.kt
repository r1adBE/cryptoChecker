package com.cryptochecker.app.domain.watch

/**
 * Die kleinen Symbole unter einem Paar der Merkliste, in der Reihenfolge der Zeile: zuerst die Art ([AssetKind]:
 * Raute, Bank, «α»), dann die Kursmitteilung (Glocke an / durchgestrichen aus), zuletzt die Alarmglocke mit Wellen und
 * der Zahl der aktiven Alarme. Das Blatt «Symbole erklärt» zeigt genau diese Liste — wie iOS `WatchlistSymbol`.
 */
enum class WatchlistSymbol {
    TOKEN,
    TRADFI,
    ALPHA,
    PRICE_NOTIFICATION_ON,
    PRICE_NOTIFICATION_OFF,
    ACTIVE_ALARMS;

    /** Art des Paars, für die das Symbol steht; null bei den Glocken. */
    val kind: AssetKind?
        get() = when (this) {
            TOKEN -> AssetKind.TOKEN
            TRADFI -> AssetKind.TRADFI
            ALPHA -> AssetKind.ALPHA
            PRICE_NOTIFICATION_ON, PRICE_NOTIFICATION_OFF, ACTIVE_ALARMS -> null
        }
}
