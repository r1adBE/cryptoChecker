package com.cryptochecker.marketdata.exceptions

/** Antwort einer Börse passt nicht zum erwarteten Format; [message] ist der angezeigte Grund. */
class MarketParseException(message: String) : Exception(message) {
    companion object {
        /** Die Antwort ist gültig, enthält aber keinen Eintrag für das Paar (leere Liste). */
        const val NO_DATA = "No data"
    }
}
