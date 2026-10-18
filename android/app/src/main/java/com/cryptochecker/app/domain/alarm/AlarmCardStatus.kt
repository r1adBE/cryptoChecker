package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.domain.refresh.OutdatedRule

/**
 * Eine Statuszeile auf der Karte eines eingeschalteten Alarms: Warum meldet er sich gerade nicht?
 * Es zählt nur der wichtigste Grund, damit die Karte ruhig bleibt:
 *  1. [Kind.NOTIFICATIONS_OFF] — Mitteilungen aus (Erlaubnis, App oder Alarm-Kanal): selbst ein
 *     ausgelöster Alarm käme nicht an, darum vor allem anderen.
 *  2. [Kind.LOW_LIQUIDITY] — DEX-Pool unter der Alarm-Liquidität oder unbekannt
 *     ([com.cryptochecker.app.domain.watch.DexLiquidity.blocksAlarms]): der Alarm wird gar nicht ausgewertet.
 *  3. [Kind.WAITING_FOR_PRICE] — Zeit laut Börse älter als 2 Stunden ([AlarmFreshness]) oder letzter
 *     Abruf älter als die Veraltet-Grenze der Merkliste ([OutdatedRule]): es fehlt ein frischer Kurs.
 *  4. [Kind.UNCHECKED] — zuletzt nicht geprüft ([AlarmCheckStatus]); den Text liefert dessen Grund.
 * Sonst null (keine Zeile). Ausgeschaltete Alarme werden nicht geprüft und bekommen nie eine Zeile.
 * Reine Logik ohne Android (AlarmCardStatusTest), Swift-Spiegel AlarmCardStatus.swift.
 */
object AlarmCardStatus {

    enum class Kind { NOTIFICATIONS_OFF, LOW_LIQUIDITY, WAITING_FOR_PRICE, UNCHECKED }

    /**
     * @param notificationsAllowed Alarme dürfen als Mitteilung erscheinen
     * @param dexBlocksAlarms DEX-Pool mit zu wenig oder unbekannter Liquidität
     * @param exchangeTime Zeit des Kurses laut Börse (null/≤ 0 = unbekannt)
     * @param lastUpdate letzter erfolgreicher Abruf (≤ 0 = noch nie, dann kein «Wartet»)
     * @param staleAfterMillis Veraltet-Grenze der abgefragten Paare (wie die Merkliste)
     * @param unchecked zuletzt nicht geprüft
     */
    fun of(
        alarmEnabled: Boolean,
        notificationsAllowed: Boolean,
        dexBlocksAlarms: Boolean,
        exchangeTime: Long?,
        lastUpdate: Long,
        now: Long,
        staleAfterMillis: Long,
        unchecked: Boolean,
    ): Kind? = when {
        !alarmEnabled -> null
        !notificationsAllowed -> Kind.NOTIFICATIONS_OFF
        dexBlocksAlarms -> Kind.LOW_LIQUIDITY
        AlarmFreshness.exchangeTimeTooOld(exchangeTime, now) ||
            OutdatedRule.isOutdated(lastUpdate, now, staleAfterMillis) -> Kind.WAITING_FOR_PRICE
        unchecked -> Kind.UNCHECKED
        else -> null
    }
}
