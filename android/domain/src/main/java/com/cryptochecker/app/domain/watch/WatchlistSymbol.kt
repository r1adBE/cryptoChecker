package com.cryptochecker.app.domain.watch

/**
 * Die kleinen Symbole einer Zeile der Merkliste, in der Reihenfolge der Zeile: der Stern (Favorit), die Art ([AssetKind]:
 * Raute, Bank, «α»), die Kursmitteilung (Glocke an / durchgestrichen aus), die Alarmglocke mit Wellen und der Zahl der
 * aktiven Alarme, zuletzt Notiz und Warn-Dreieck (Kurs veraltet bzw. Börse nicht erreichbar). Das Blatt «Symbole erklärt» zeigt genau diese Liste — wie iOS `WatchlistSymbol`.
 */
enum class WatchlistSymbol {
    /** Stern am Logo bzw. vor dem Paar: Favorit. */
    FAVORITE,
    TOKEN,
    TRADFI,
    ALPHA,
    PRICE_NOTIFICATION_ON,
    PRICE_NOTIFICATION_OFF,
    ACTIVE_ALARMS,

    /** Notiz-Zeile unter dem Paar. */
    NOTE,

    /** Warn-Dreieck vor der Zeitzeile: Kurs veraltet bzw. Börse nicht erreichbar. */
    WARNING;

    /** Art des Paars, für die das Symbol steht; null bei den übrigen (Stern, Glocken, Notiz, Warnung). */
    val kind: AssetKind?
        get() = when (this) {
            TOKEN -> AssetKind.TOKEN
            TRADFI -> AssetKind.TRADFI
            ALPHA -> AssetKind.ALPHA
            FAVORITE, PRICE_NOTIFICATION_ON, PRICE_NOTIFICATION_OFF, ACTIVE_ALARMS, NOTE, WARNING -> null
        }
}
