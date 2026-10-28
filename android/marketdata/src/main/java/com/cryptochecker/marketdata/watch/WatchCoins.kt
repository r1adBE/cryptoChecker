package com.cryptochecker.marketdata.watch

/**
 * Coins der Uhr-Apps: Auswahl der gängigen Coins gegen USDT, höchstens [MAX] je Liste. Gemeinsam für die
 * Handy-App (Einstellungen › Uhr) und die Wear-OS-App. Wie iOS `WatchCoins` (Paritäts-Fixture `watch_settings.json`).
 */
object WatchCoins {
    const val MAX = 8

    val TOP = listOf(
        "BTC", "ETH", "BNB", "SOL", "XRP", "DOGE", "ADA", "TRX", "TON", "AVAX",
        "SHIB", "DOT", "LINK", "BCH", "NEAR", "LTC", "UNI", "APT", "ICP", "ETC",
        "XLM", "FIL", "HBAR", "ATOM", "ARB", "OP", "SUI", "PEPE", "INJ", "RENDER",
    )

    val DEFAULT_OWN = listOf("BTC", "ETH")

    private val SYMBOL = Regex("[A-Z0-9]{2,12}")

    /** Eintrag umschalten: hinzufügen (am Ende, solange unter [MAX]) oder entfernen. */
    fun toggle(list: List<String>, coin: String): List<String> = when {
        coin in list -> list - coin
        list.size >= MAX -> list
        else -> list + coin
    }

    /** Eigene Liste bereinigen: nur Coins aus [TOP], ohne Doppelte, höchstens [MAX]. */
    fun cleanOwn(stored: List<String>): List<String> = stored.filter { it in TOP }.distinct().take(MAX)

    /**
     * «Wie Handy»: Basis-Coins der Favoriten der Merkliste in ihrer Reihenfolge — gross geschrieben, nur gültige Kürzel,
     * ohne Doppelte (BTC/USDT und BTC/CHF = BTC), höchstens [MAX]; die Uhr zeigt sie gegen USDT.
     */
    fun fromFavorites(bases: List<String>): List<String> =
        bases.map { it.trim().uppercase() }.filter { SYMBOL.matches(it) }.distinct().take(MAX)
}
