package com.cryptochecker.app.notification

import com.cryptochecker.app.R

/**
 * Inhalt der Mitteilung eines Portfolio-Alarms (rein, testbar) — wie iOS `PortfolioAlarmNotice.content()`: immer nur
 * Titel und neutraler Satz, nie Schwelle, Betrag oder Prozent. Bewusst ohne Alarm, Messwert und Einstellungen als
 * Eingabe: was hier nicht hineinkommt, kann auf keinem Sperrbildschirm stehen (Android zeigt den vollen Text einer
 * privaten Mitteilung, solange das System «sensible Inhalte» nicht verbirgt). Einzelheiten: Alarmliste/Protokoll.
 */
data class PortfolioAlarmNotice(val title: String, val text: String) {

    /** Alle Textfelder der Mitteilung (Titel, Text, grosser Text, öffentliche Fassung) — für den Test. */
    val fields: List<String> get() = listOf(title, text)

    companion object {
        val TITLE = R.string.notification_portfolio_alarm_title
        val TEXT = R.string.notification_portfolio_alarm_locked

        fun content(string: (Int) -> String): PortfolioAlarmNotice = PortfolioAlarmNotice(string(TITLE), string(TEXT))
    }
}
