package com.cryptochecker.marketdata.util

/** Anzeigezeichen von Währungskürzeln («USD» → «$»). Krakens eigene Kürzel: KrakenAssetCodes. */
object CurrencyText {

    // Kürzel → Zeichen. Nur diese Kürzel bekommen ein Zeichen, alle anderen (auch Krypto) bleiben als Kürzel stehen.
    // CHF bleibt bewusst «CHF»: «₣» war der französische Franc, für den Franken schreibt man das Kürzel.
    private val SYMBOLS: Map<String, String> = HashMap<String, String>().apply {
        fun sign(symbol: String, vararg codes: String) = codes.forEach { put(it, symbol) }

        // Zeichen, die mehrere Währungen teilen
        sign("¥", "CNY", "JPY")
        sign("kr", "SEK", "DKK", "ISK")
        sign("₽", "RUB")
        sign("L", "ALL", "RON")
        sign("Dh", "MAD", "AED")

        // Eigene Währungszeichen
        sign("$", "USD")
        sign("€", "EUR")
        sign("£", "GBP")
        sign("₩", "KRW")
        sign("₹", "INR")
        sign("฿", "THB")
        sign("₱", "PHP")
        sign("₦", "NGN")
        sign("₮", "MNT")
        sign("₸", "KZT")
        sign("₾", "GEL")
        sign("₡", "CRC")
        sign("৳", "BDT")
        sign("֏", "AMD")
        sign("؋", "AFN")
        sign("ƒ", "AWG")

        // Abkürzungen in lateinischer Schrift
        sign("zł", "PLN")
        sign("Kč", "CZK")
        sign("R$", "BRL")
        sign("R", "ZAR")
        sign("Rp", "IDR")
        sign("RM", "MYR")
        sign("Ft", "HUF")
        sign("TL", "TRY")
        sign("Fr", "BIF")
        sign("Br", "BYR")
        sign("Kz", "AOA")
        sign("P", "BWP")
        sign("m", "AZN")
        sign("JD", "JOD")
        sign("SR", "SAR")
        sign("KSH", "KES")
        sign("TSh", "TZS")
        sign("S/", "PEN")
        sign("Nu.", "BTN")
        sign("Bs.", "BOB")
        sign("din.", "RSD")

        // Andere Schriften
        sign("лв", "BGN")
        sign("КМ", "BAM")
        sign("د.ج", "DZD")
        sign("ب.د", "BHD")
    }

    /** Anzeigezeichen eines Kürzels («USD» → «$»); ohne eigenes Zeichen das Kürzel selbst. */
    fun symbol(code: String): String = SYMBOLS[code] ?: code
}
