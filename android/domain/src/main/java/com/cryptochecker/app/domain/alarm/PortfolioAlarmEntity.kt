package com.cryptochecker.app.domain.alarm

/**
 * Alarm «Portfolio-Wert»: Gesamtwert über/unter einem Betrag oder Veränderung
 * «heute» ±x %. Eigene Tabelle `portfolio_alarms` statt der Tabelle
 * `alarms`: Die gehört über einen Fremdschlüssel zu einem Paar der Merkliste — ein
 * Portfolio-Alarm hat keins, ein Schein-Paar wäre in Merkliste, Sicherung und Widgets sichtbar.
 * Fachmodell ohne Room; die Tabellenzeile ist `PortfolioAlarmRow` in :data. Regeln: [PortfolioAlarmLogic].
 */
data class PortfolioAlarmEntity(
    val id: Long = 0,
    val kind: PortfolioAlarmKind,
    val threshold: Double,
    val currency: String? = null,
    val enabled: Boolean = true,
    val repeating: Boolean = false,
    val referenceAt: Long = 0,
    val lastTriggeredAt: Long = 0,
    val lastTriggeredValue: Double? = null,
)

fun PortfolioAlarmEntity.toInput() = PortfolioAlarmInput(
    kind = kind,
    threshold = threshold,
    currency = currency,
    enabled = enabled,
    referenceAt = referenceAt,
    lastTriggeredAt = lastTriggeredAt,
)
