package com.cryptochecker.marketdata.watch

/**
 * Paar eines Favoriten des Handys für «Wie Handy»: Basis, Gegenwährung, Börse (Name wie auf dem Handy, z. B. «Binance
 * Futures») und Kontrakt ([contract]: leer = Spot, sonst die Kurzform des Handys «Perp», «1Q» …; [POOL] + 8 Hex-Stellen
 * = DexScreener-Pool, die Stellen aus seiner Kennung, damit zwei Pools mit gleicher Basis/Quote getrennt bleiben; eine
 * Börsen-Kennung wie «BTCUSDT» macht kein Pool). [key] ist der Schlüssel in Liste, Kursen und Speicher der Uhr; Coins
 * der eigenen Liste behalten ihr Kürzel («BTC» = gegen USDT, Spot, erste Börse, die liefert). Die Uhr zeigt den Kurs dieses Paars: vom
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
            if (contract.isNotEmpty() && !pool) append(' ').append(contract)
        }

    /** DexScreener-Pool ([POOL] + Kennungs-Stellen, [contractOf]). */
    val pool: Boolean get() = contract.startsWith(POOL)

    /** Lineares Perpetual (Kurzform «Perp» des Handys). */
    val perp: Boolean get() = contract == PERP

    /**
     * Kann die Uhr den Kurs selbst abfragen? Spot und Perpetuals der Börsen, die sie kennt ([PERP_EXCHANGES]); ein
     * Perp anderer Börsen (Bitget, Hyperliquid …), andere Kontrakte und Pools nur vom Handy — nie ein fremder Kurs
     * unter dem Namen dieses Paars.
     */
    val selfFetchable: Boolean
        get() = contract.isEmpty() || (perp && PERP_EXCHANGES.any { it.equals(exchange, ignoreCase = true) })

    companion object {
        const val USDT = "USDT"
        const val PERP = "Perp"
        const val POOL = "Pool"

        /** Perpetual-Börsen, die die Uhr selbst abfragt (Namen wie auf dem Handy; Wear `WatchPairQuotes.Venue`). */
        val PERP_EXCHANGES = listOf("Binance Futures", "Bybit Futures", "OKX Futures")
        private const val MAX_EXCHANGE = 40
        private val SYMBOL = Regex("[A-Z0-9]{2,12}")
        private val CONTRACT = Regex("[A-Za-z0-9]{0,12}")

        /**
         * Kontrakt-Marke aus der Kurzform des Handys ([short], null = Spot) und der Pool-Kennung ([pool] =
         * `WatchIdentity.pool`: nur DexScreener, sonst null — die Kennung einer Börse wie «BTCUSDT» zählt nicht):
         * Kurzform, sonst [POOL] + [poolTag], sonst leer (Spot).
         */
        fun contractOf(short: String?, pool: String?): String =
            short ?: pool?.takeIf { it.isNotEmpty() }?.let { POOL + poolTag(it) } ?: ""

        /** 8 Hex-Stellen (FNV-1a, 32 Bit, über UTF-8) der Pool-Kennung — gleich wie iOS `WatchPair.poolTag`. */
        fun poolTag(pool: String): String {
            var h = 0x811c9dc5L
            for (b in pool.toByteArray(Charsets.UTF_8)) {
                h = ((h xor (b.toLong() and 0xff)) * 0x01000193L) and 0xffffffffL
            }
            return h.toString(16).padStart(8, '0')
        }

        /** Bereinigt (gross, ohne Leerzeichen, Börse ohne «|», höchstens 40 Zeichen, Kontrakt höchstens 12); ungültig → null. */
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
