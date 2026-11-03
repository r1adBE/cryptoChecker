package com.cryptochecker.marketdata.watch

/**
 * Paar eines Favoriten des Handys für «Wie Handy»: Basis, Gegenwährung, Börse (Name wie auf dem Handy, z. B. «Binance
 * Futures») und Kontrakt ([contract]: leer = Spot, sonst die Kurzform des Handys «Perp», «1Q» …; [POOL] = Pool mit
 * Kennung, z. B. DexScreener). [key] ist der Schlüssel in Liste, Kursen und Speicher der Uhr; Coins der eigenen Liste
 * behalten ihr Kürzel («BTC» = gegen USDT, Spot, erste Börse, die liefert). Die Uhr zeigt den Kurs dieses Paars: vom
 * Handy oder selbst abgefragt zuerst bei derselben Börse und Kontraktart ([selfFetchable]). Wie iOS `WatchPair`
 * (Paritäts-Fixtures `watch_settings.json`, `watch_link.json`, `watch_quotes.json`).
 */
data class WatchPair(val base: String, val quote: String, val exchange: String, val contract: String) {

    /** Schlüssel «BASE/QUOTE|Börse|Kontrakt», z. B. «BANK/USDT|Binance Futures|Perp». */
    val key: String get() = "$base/$quote|$exchange|$contract"

    /** Anzeige wie auf dem Handy: «BANK Perp», «BTC/EUR», «ETH» (Gegenwährung nur, wenn nicht USDT). */
    val label: String
        get() = buildString {
            append(base)
            if (quote != USDT) append('/').append(quote)
            if (contract.isNotEmpty() && contract != POOL) append(' ').append(contract)
        }

    /** Lineares Perpetual (Kurzform «Perp» des Handys). */
    val perp: Boolean get() = contract == PERP

    /** Kann die Uhr den Kurs selbst abfragen? Spot und Perpetuals; andere Kontrakte und Pools nur vom Handy. */
    val selfFetchable: Boolean get() = contract.isEmpty() || perp

    companion object {
        const val USDT = "USDT"
        const val PERP = "Perp"
        const val POOL = "Pool"
        private const val MAX_EXCHANGE = 40
        private val SYMBOL = Regex("[A-Z0-9]{2,12}")
        private val CONTRACT = Regex("[A-Za-z0-9]{0,8}")

        /** Kontrakt-Marke aus der Kurzform des Handys ([short], null = Spot) und ob das Paar eine Kennung hat (Pool). */
        fun contractOf(short: String?, hasPairId: Boolean): String = short ?: if (hasPairId) POOL else ""

        /** Bereinigt (gross, ohne Leerzeichen, Börse ohne «|», höchstens 40 Zeichen); ungültig → null. */
        fun of(base: String, quote: String, exchange: String, contract: String): WatchPair? {
            val b = base.trim().uppercase()
            val q = quote.trim().uppercase()
            val x = exchange.replace("|", "").trim().take(MAX_EXCHANGE)
            val c = contract.trim()
            if (!SYMBOL.matches(b) || !SYMBOL.matches(q) || x.isEmpty() || !CONTRACT.matches(c)) return null
            return WatchPair(b, q, x, c)
        }

        /** Schlüssel ([key]) → Paar; ein Kürzel der eigenen Liste oder Unlesbares → null. */
        fun parse(key: String): WatchPair? {
            val parts = key.split('|')
            if (parts.size != 3) return null
            val legs = parts[0].split('/')
            if (legs.size != 2) return null
            return of(legs[0], legs[1], parts[1], parts[2])?.takeIf { it.key == key }
        }

        /** Anzeige eines Schlüssels: Paar wie auf dem Handy, sonst das Kürzel. */
        fun label(key: String): String = parse(key)?.label ?: key
    }
}
