package com.cryptochecker.app.domain.exceptions

/**
 * Die Antwort überschreitet die erlaubte Grösse (HttpBodyLimit). Der Text ist fest, da Fehler
 * als Zeichenkette gespeichert und erst später ausgewertet werden.
 */
class ResponseTooLargeError : MarketError(MESSAGE) {
    companion object {
        const val MESSAGE = "Response too large"
    }
}
