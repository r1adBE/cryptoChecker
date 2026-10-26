package com.cryptochecker.app.domain.exceptions

/**
 * Fehler mit einer festen Kennung als Text. Die Kennung wird so in der Datenbank abgelegt
 * (WatchEntity.lastError) und erst in der Oberfläche übersetzt (friendlyError, wie NOT_TRADED_MARKER).
 */
class UserFriendlyMarketError(message: String) : MarketError(message) {
    companion object {
        /** Leere Antwort der Börse. */
        const val EMPTY_RESPONSE = "Response data is empty"

        /** Antwort gelesen, aber ohne Kurs (siehe MarketRemoteDataSource). */
        const val NO_TICKER_DATA = "Parsed ticker has no data"

        /** Fehlermeldung der Börse ohne Inhalt. */
        const val UNKNOWN_EMPTY = "Unknown error (empty)"

        /** Diese App-Version kennt die Börse nicht (mehr). */
        const val MARKET_UNAVAILABLE = "Market unavailable"
    }
}
