package com.cryptochecker.app.domain.portfolio

/**
 * Portfolio ausgeschaltet (ab Werk seit Build 44, `AppSettings.portfolioEnabled`): was trotzdem geht
 * und was das Portfolio-Widget dann zeigt. Reines Kotlin, testbar — gespiegelt in
 * `Shared/Portfolio/PortfolioOff.swift`.
 */
object PortfolioOff {

    /**
     * Schaltet das Speichern einer Transaktion das Portfolio ein? Nur eine neue ([isNew], id 0),
     * solange es aus ist — «Zum Portfolio hinzufügen» steht auch mit ausgeschaltetem Portfolio im
     * Aktionsblatt. Abbrechen speichert nichts und schaltet nichts ein.
     */
    fun enableOnSave(isNew: Boolean, portfolioEnabled: Boolean): Boolean = isNew && !portfolioEnabled

    /**
     * Build 46: Nach dem Speichern der ERSTEN Transaktion (vorher keine, [hadTransactions] false) mit
     * ausgeschaltetem Portfolio fragen «Portfolio-Tab einschalten?» statt still einzuschalten
     * (Merkliste, Markt). «Später» behält die Transaktion, der Tab bleibt aus; danach wird nicht mehr gefragt.
     */
    fun askEnableOnSave(isNew: Boolean, portfolioEnabled: Boolean, hadTransactions: Boolean): Boolean =
        enableOnSave(isNew, portfolioEnabled) && !hadTransactions

    /** «Zum Portfolio hinzufügen» im Aktionsblatt: immer, auch mit Portfolio aus — nur nicht in der Vorschau. */
    fun offerAddToPortfolio(preview: Boolean): Boolean = !preview

    /** Was ein abgelegtes Portfolio-Widget zeigt. */
    enum class WidgetState {
        /** Portfolio aus: «Portfolio ist aus» und wo man es einschaltet. */
        OFF,

        /** Portfolio-Sperre an: Schloss und «Gesperrt – in der App entsperren». */
        LOCKED,

        /** Noch keine Aufnahme bzw. keine Bestände. */
        EMPTY,

        /** Werte. */
        VALUES,
    }

    /** Zustand des Widgets; «aus» geht vor der Sperre (zeigt ohnehin keine Werte). */
    fun widgetState(portfolioEnabled: Boolean, locked: Boolean, empty: Boolean): WidgetState = when {
        !portfolioEnabled -> WidgetState.OFF
        locked -> WidgetState.LOCKED
        empty -> WidgetState.EMPTY
        else -> WidgetState.VALUES
    }

    /** Wohin das Tippen auf das Portfolio-Widget führt. */
    enum class WidgetTarget {
        /** Portfolio-Tab. */
        PORTFOLIO_TAB,

        /** Einstellungen › Portfolio (dort der Schalter). */
        PORTFOLIO_SETTINGS,
    }

    /** Ziel beim Öffnen aus dem Widget — entschieden beim Tippen (das Widget kann einen alten Stand zeigen). */
    fun widgetTarget(portfolioEnabled: Boolean): WidgetTarget =
        if (portfolioEnabled) WidgetTarget.PORTFOLIO_TAB else WidgetTarget.PORTFOLIO_SETTINGS
}
