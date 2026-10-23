package com.cryptochecker.app.domain.portfolio

import org.json.JSONObject
import java.math.BigDecimal

/**
 * Guthaben öffentlicher Adressen ohne Schlüssel und ohne Konto beim Anbieter.
 *
 * Bitcoin: Esplora-REST `GET https://{host}/api/address/{adr}` — zuerst mempool.space, sonst
 * blockstream.info (gleiche Antwort). Guthaben = chain_stats (funded − spent) + mempool_stats
 * (funded − spent), in Satoshi; unbestätigte Eingänge zählen also schon mit.
 * Ethereum: JSON-RPC `eth_getBalance(adr, "latest")` an öffentliche Knoten ohne Schlüssel —
 * zuerst PublicNode, sonst LlamaRPC (beide schon für die Netzwerkgebühren im Einsatz). Wei → ETH exakt.
 * ERC-20-Token ([Erc20Tokens], abschaltbar je Adresse): `eth_call balanceOf` an dieselben Knoten, mit dem
 * ETH-Guthaben in einer Stapel-Anfrage.
 * xpub/ypub/zpub ([Bip32], [XpubScan]): Adressen auf dem Gerät abgeleitet; jede abgeleitete Adresse einzeln wie oben
 * (Esplora, `chain_stats.tx_count` für die Gap-Regel); Guthaben = Summe.
 *
 * Datenschutz: Jede Abfrage nennt dem Anbieter die Adresse zusammen mit der IP des Geräts
 * ([PRIVACY_HOSTS]); nur diese Hosts bekommen Adressen.
 * Abruf höchstens alle [CACHE_MILLIS] je Adresse (manuell frühestens nach [MIN_FORCED_MILLIS]).
 * Reines Kotlin, gleich wie `AddressBalance.swift`.
 */
object AddressBalance {

    /** Guthaben so lange gültig; danach lädt die nächste Portfolio-Aktualisierung neu. */
    const val CACHE_MILLIS = 10 * 60_000L

    /** Manuelles Aktualisieren (Ziehen) frühestens so lange nach dem letzten Versuch. */
    const val MIN_FORCED_MILLIS = 60_000L

    /** Zeitgrenze je Anfrage. */
    const val TIMEOUT_MILLIS = 15_000L

    /** Bitcoin-Hosts (Esplora-API) in dieser Reihenfolge. */
    val BTC_HOSTS: List<String> = listOf("mempool.space", "blockstream.info")

    /** Ethereum-Knoten (JSON-RPC) in dieser Reihenfolge. */
    val ETH_RPCS: List<String> = listOf("https://ethereum-rpc.publicnode.com", "https://eth.llamarpc.com")

    /** Alle Hosts, die eine Adresse zu sehen bekommen (Datenschutzerklärung), Reihenfolge der Netze. */
    val PRIVACY_HOSTS: List<String> = AddressChain.entries.flatMap { ChainBalance.hosts(it) }.distinct()

    fun btcUrl(host: String, address: String): String = "https://$host/api/address/$address"

    /** Anfrage-Text für `eth_getBalance` (Adresse klein; geprüft, also nur Hex). */
    fun ethRequest(address: String): String =
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"eth_getBalance\",\"params\":[\"${address.lowercase()}\",\"latest\"]}"

    /**
     * Satoshi bestätigt und unbestätigt (kann negativ sein: Ausgabe wartet); [txCount] = Transaktionen bestätigt +
     * unbestätigt (xpub: benutzt, wenn > 0).
     */
    data class BtcStats(val confirmedSats: Long, val pendingSats: Long, val txCount: Int = 0) {
        val totalSats: Long get() = (confirmedSats + pendingSats).coerceAtLeast(0)
    }

    /**
     * Esplora-Antwort lesen; wirft bei fehlenden/negativen Feldern oder wenn die Antwort zu einer
     * anderen Adresse gehört. Plausibilität je Netz ([maxSats], Litecoin hat 84 Mio. Coins): geprüft wird das Guthaben,
     * nicht die Summen über alle Zeit (funded_txo_sum einer Börsen-Adresse übersteigt die Geldmenge leicht).
     */
    fun parseBtc(body: String, address: String, chain: AddressChain = AddressChain.BTC): BtcStats {
        val json = JSONObject(body)
        if (json.has("address") && !json.isNull("address") &&
            (json.opt("address") as? String)?.equals(address, ignoreCase = true) != true
        ) {
            throw IllegalStateException("Antwort für andere Adresse")
        }
        val max = maxSats(chain)
        val stats = json.optJSONObject("chain_stats") ?: throw IllegalStateException("chain_stats fehlt")
        val confirmed = sats(stats, "funded_txo_sum") - sats(stats, "spent_txo_sum")
        if (confirmed < 0) throw IllegalStateException("negatives Guthaben")
        if (confirmed > max) throw IllegalStateException("Guthaben ungültig")
        val mempool = json.optJSONObject("mempool_stats")
        val pending = mempool?.let { sats(it, "funded_txo_sum") - sats(it, "spent_txo_sum") } ?: 0L
        if (pending > max || pending < -max) throw IllegalStateException("Guthaben ungültig")
        val txCount = txCountOf(stats) + (mempool?.let { txCountOf(it) } ?: 0)
        return BtcStats(confirmed, pending, txCount)
    }

    /** Mehr als alle je möglichen Coins des Netzes in kleinsten Einheiten ist unplausibel (BTC 21 Mio., LTC 84 Mio. × 10⁸). */
    fun maxSats(chain: AddressChain): Long = when (chain) {
        AddressChain.LTC -> 8_400_000_000_000_000L
        else -> 2_100_000_000_000_000L
    }

    /** `tx_count` (fehlt oder ungültig = 0: dann zählt nur das Guthaben). */
    private fun txCountOf(o: JSONObject): Int {
        val v = (o.opt("tx_count") as? Number)?.toDouble() ?: return 0
        return if (v.isFinite() && v >= 0 && v <= Int.MAX_VALUE) v.toInt() else 0
    }

    /** Ganze, nicht negative Zahl im Long-Bereich (Summen über alle Zeit); sonst Fehler. */
    private fun sats(o: JSONObject, key: String): Long {
        val v = o.opt(key) as? Number ?: throw IllegalStateException("$key fehlt")
        val d = v.toDouble()
        if (!d.isFinite() || d < 0 || d != Math.floor(d) || d > MAX_CUMULATIVE_SATS) throw IllegalStateException("$key ungültig")
        return v.toLong()
    }

    /** Obergrenze der Summen funded/spent (unter Long.MAX_VALUE, die Differenz läuft nie über). */
    private const val MAX_CUMULATIVE_SATS = 9.0e18

    /** `eth_getBalance`-Antwort → Wei als Dezimalziffern; wirft bei Fehlerantwort oder unlesbarem Wert. */
    fun parseEthWei(body: String): String {
        val json = JSONObject(body)
        json.optJSONObject("error")?.let { throw IllegalStateException("RPC: " + it.optString("message")) }
        val result = json.opt("result") as? String ?: throw IllegalStateException("result fehlt")
        if (!result.startsWith("0x") || result.length < 3 || result.length > 66) throw IllegalStateException("result ungültig")
        return hexToDecimal(result.substring(2)) ?: throw IllegalStateException("result ungültig")
    }

    /** Hex (ohne «0x», höchstens 64 Stellen) → Dezimalziffern ohne führende Nullen; ungültig = null. */
    fun hexToDecimal(hex: String): String? {
        if (hex.isEmpty() || hex.length > 64) return null
        val digits = ArrayList<Int>() // Basis 10, niedrigste Stelle zuerst
        digits.add(0)
        for (c in hex) {
            var carry = Character.digit(c, 16)
            if (carry < 0 || !(c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F')) return null
            for (i in digits.indices) {
                val v = digits[i] * 16 + carry
                digits[i] = v % 10
                carry = v / 10
            }
            while (carry > 0) {
                digits.add(carry % 10)
                carry /= 10
            }
        }
        while (digits.size > 1 && digits.last() == 0) digits.removeAt(digits.size - 1)
        return digits.asReversed().joinToString("")
    }

    /** Ganze kleinste Einheiten (Ziffern) → Text mit [decimals] Stellen, ohne Nullen am Ende («0.0001»). */
    fun unitsText(digits: String, decimals: Int): String {
        val d = digits.trimStart('0')
        if (d.isEmpty()) return "0"
        val padded = d.padStart(decimals + 1, '0')
        val intPart = padded.substring(0, padded.length - decimals)
        val frac = padded.substring(padded.length - decimals).trimEnd('0')
        return if (frac.isEmpty()) intPart else "$intPart.$frac"
    }

    fun btcText(stats: BtcStats): String = unitsText(stats.totalSats.toString(), AddressChain.BTC.decimals)

    fun ethText(weiDigits: String): String = unitsText(weiDigits, AddressChain.ETH.decimals)

    /** Ist diese Adresse wieder dran? Nie versucht oder Uhr zurückgestellt: ja. */
    fun isDue(lastAttempt: Long, now: Long, force: Boolean): Boolean {
        if (lastAttempt <= 0) return true
        val elapsed = now - lastAttempt
        if (elapsed < 0) return true
        return elapsed >= if (force) MIN_FORCED_MILLIS else CACHE_MILLIS
    }

    /**
     * Fällige Adressen ([isDue]) in Abruf-Reihenfolge: Vordergrund wie gespeichert; Hintergrund die am längsten nicht
     * versuchten zuerst (nie versucht = 0 vorne, bei Gleichstand wie gespeichert) — das knappe Zeitbudget käme sonst
     * immer nur den zuerst erfassten Adressen zugute.
     */
    fun dueOrder(addresses: List<WatchedAddress>, now: Long, force: Boolean, background: Boolean): List<WatchedAddress> {
        val due = addresses.filter { isDue(it.lastAttempt, now, force) }
        return if (background) due.sortedBy { it.lastAttempt.coerceAtLeast(0) } else due
    }

    /**
     * Zählende Adressen ([WatchedAddress.countInPortfolio]), deren Guthaben noch nie geladen wurde (z. B. frisch
     * wiederhergestellt, xpub ohne Zwischenspeicher im Hintergrund) und die noch «laden» ([AddressAlarmState.isPending]):
     * sie fehlen in [holdings], der Gesamtwert ist dann zu tief — Portfolio-Alarme prüfen nicht
     * ([com.cryptochecker.app.domain.alarm.PortfolioReading.addressesPending]). Eine Adresse, die seit
     * über 24 Stunden scheitert, zählt nicht ([AddressAlarmState.failing]). Regeln wie [AddressAlarmState.of].
     */
    fun pendingCount(addresses: List<WatchedAddress>, now: Long): Int = AddressAlarmState.of(addresses, now).pending

    /** Anbieter für die Herkunftsangabe: «mempool.space», «publicnode.com», «llamarpc.com». */
    fun providerName(hostOrUrl: String): String {
        val host = hostOrUrl.removePrefix("https://").substringBefore('/')
        val parts = host.split('.')
        // IPv4 (eigener Server) ganz, sonst die letzten beiden Teile des Namens
        if (host.all { it in '0'..'9' || it == '.' }) return host
        return if (parts.size > 2) parts.takeLast(2).joinToString(".") else host
    }

    /**
     * Bestand je Coin aus allen Adressen, die zum Portfolio zählen ([WatchedAddress.countInPortfolio]), mit geladenem
     * Guthaben (exakt summiert, gleiche Adresse nur einmal; eine einzelne Bitcoin-Adresse, die schon in einem beobachteten
     * xpub steckt, zählt über den xpub). Reihenfolge: Netz-Guthaben in der Reihenfolge von [AddressChain], dann weitere
     * Guthaben > 0 ([WatchedAddress.tokenHoldings]) nach Netz und Liste; derselbe Coin aus mehreren Quellen (z. B. ETH auf
     * Ethereum und Arbitrum, USDC und USDC.e) wird addiert. Coins ohne geladenes Guthaben fehlen.
     */
    fun holdings(addresses: List<WatchedAddress>): List<Pair<String, String>> {
        val counted = addresses.filter { it.countInPortfolio }
        val sums = LinkedHashMap<String, BigDecimal>()
        val covered = WatchedAddressRules.coveredByXpub(counted)
        for (chain in AddressChain.entries) {
            val seen = ArrayList<String>()
            var sum: BigDecimal? = null
            for (a in counted) {
                if (a.chain != chain) continue
                if (chain == AddressChain.BTC && !a.isXpub && a.address in covered) continue
                val value = PortfolioDecimal.parse(a.lastBalance) ?: continue
                if (value.signum() < 0) continue
                if (seen.any { WatchedAddressRules.sameAddress(chain, it, a.address) }) continue
                seen += a.address
                sum = (sum ?: BigDecimal.ZERO) + value
            }
            sum?.let { sums[chain.coin] = (sums[chain.coin] ?: BigDecimal.ZERO) + it }
        }
        // Weitere Guthaben: nach Netz der Adresse, dann Reihenfolge der Liste
        data class Entry(val chain: Int, val order: Int, val coin: String, val value: BigDecimal)
        val entries = ArrayList<Entry>()
        for (chain in AddressChain.entries) {
            if (!ChainAssets.hasTokens(chain)) continue
            val seen = ArrayList<String>()
            for (a in counted) {
                if (a.chain != chain) continue
                if (seen.any { WatchedAddressRules.sameAddress(chain, it, a.address) }) continue
                seen += a.address
                for (h in a.tokenHoldings()) {
                    val value = PortfolioDecimal.parse(h.amount) ?: continue
                    val order = ChainAssets.resolve(chain, h.key)?.order ?: Int.MAX_VALUE
                    entries += Entry(chain.ordinal, order, h.coin, value)
                }
            }
        }
        for (e in entries.sortedWith(compareBy({ it.chain }, { it.order }))) sums[e.coin] = (sums[e.coin] ?: BigDecimal.ZERO) + e.value
        return sums.map { (coin, sum) -> coin to PortfolioDecimal.text(sum) }
    }

    /**
     * Coins aus [holdings], deren Summe gebrückte Stablecoins enthält (USDC.e, USDbC → USDC, USDT.e → USDT;
     * [ChainAssets.isBridgedStable]) → Symbole dieser Varianten (Reihenfolge wie [holdings], je Symbol einmal). Eine
     * Brücke kann abkoppeln: die Menge gilt dann nur ungefähr — die Zeile markiert sie mit «≈» wie WBTC
     * ([ChainAssets.priceViaBase]) statt sie als exakten Bestand des Basis-Stablecoins auszugeben.
     */
    fun bridgedStables(addresses: List<WatchedAddress>): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        for (chain in AddressChain.entries) {
            if (!ChainAssets.hasTokens(chain)) continue
            val seen = ArrayList<String>()
            for (a in addresses) {
                if (!a.countInPortfolio || a.chain != chain) continue
                if (seen.any { WatchedAddressRules.sameAddress(chain, it, a.address) }) continue
                seen += a.address
                for (h in a.tokenHoldings()) {
                    if (!ChainAssets.isBridgedStable(h.label, h.coin)) continue
                    val list = out.getOrPut(h.coin) { ArrayList() }
                    if (h.label !in list) list += h.label
                }
            }
        }
        return out
    }

    // --- Adress-Bestände im Portfolio-Rechner ---
    //
    // Ehrliche Regel: Das Guthaben beobachteter Adressen ist ein EIGENER Teil der Position,
    // kein Posten der Buchhaltung. Die Eingabe bleibt kompatibel: [withAddresses] hängt je Coin eine Einzahlung
    // ohne Kaufpreis mit negativer Id an; [split] trennt sie wieder ab. Daraus folgt:
    //  - Gesamtwert, Positionen, Widget und Portfolio-Alarme zählen das Guthaben × aktueller Kurs mit
    //    ([CoinPosition.addressHoldingsExact]).
    //  - Einstand, Ø, «Seit Kauf», realisiert und Steuer nur aus den eigenen Transaktionen — ein Adress-Guthaben
    //    macht den Einstand gekaufter Coins nicht «unvollständig».
    //  - Heute / 7 T / 1 J und Wertverlauf: Adress-Einträge zählen NICHT rückwirkend. Nur eine beobachtete
    //    Guthaben-Reihe ([AddressBalanceHistory]) zählt, ab ihrer ersten Beobachtung; Änderungen zwischen
    //    Beobachtungen sind Zu-/Abflüsse zum Marktkurs (kein Gewinn).
    // Die echten Transaktionen bleiben unverändert (Liste, Export, Sicherung kennen diese Einträge nicht).
    // Eröffnungsposten ([AddressCostLots]) belegen einen Teil des Guthabens mit Einstand — sie rechnen wie Käufe
    // (Wallet = Adresse), nur der Rest bleibt Adress-Teil ohne Einstand ([isAddressPart]).

    private const val DAY_MILLIS = 86_400_000L

    /** Id der Adress-Einzahlung je Netz (negativ, nie eine gespeicherte Transaktion). */
    fun tradeId(chain: AddressChain): Long = -(chain.ordinal + 1L)

    /** Id der Adress-Einzahlung eines Tokens: −101, −102 … nach [Erc20Tokens.LIST]. */
    fun tokenTradeId(symbol: String): Long = -(101L + Erc20Tokens.order(symbol))

    /**
     * Eindeutige negative Ids je Coin: Netz-Coins [tradeId], Coins der Ethereum-Liste [tokenTradeId], alle übrigen
     * −1001, −1002 … in der Reihenfolge von [coins].
     */
    fun tradeIds(coins: List<String>): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        var next = -1001L
        for (coin in coins) {
            if (coin in out) continue
            val chain = AddressChain.entries.firstOrNull { it.coin == coin }
            out[coin] = when {
                chain != null -> tradeId(chain)
                Erc20Tokens.LIST.any { it.symbol == coin } -> tokenTradeId(coin)
                else -> next--
            }
        }
        return out
    }

    /**
     * Adress-Eintrag statt echter Transaktion (Adress-Einzahlung oder Eröffnungsposten, [AddressCostLots])? Zeiträume,
     * Verlauf, Stichtag und Listen lassen beide weg.
     */
    fun isAddressTrade(id: Long): Boolean = id < 0

    /**
     * Adress-Teil OHNE Einstand (Adress-Einzahlung, kein Eröffnungsposten) — nur er steht neben der Buchhaltung; die
     * Posten ([AddressCostLots.isLotTrade]) rechnen Einstand und Steuer wie Käufe.
     */
    fun isAddressPart(id: Long): Boolean = isAddressTrade(id) && !AddressCostLots.isLotTrade(id)

    /**
     * Echte Transaktionen samt Eröffnungsposten und Adress-Guthaben ohne Einstand je Coin (Summe der Adress-Einzahlungen,
     * [withAddresses]), Coin in Grossschreibung.
     */
    fun split(trades: List<PortfolioTrade>): Pair<List<PortfolioTrade>, Map<String, BigDecimal>> {
        if (trades.none { isAddressPart(it.id) }) return trades to emptyMap()
        val real = ArrayList<PortfolioTrade>(trades.size)
        val address = LinkedHashMap<String, BigDecimal>()
        for (t in trades) {
            if (!isAddressPart(t.id)) {
                real += t
            } else if (t.type == PortfolioTxType.DEPOSIT && t.amount.signum() > 0) {
                val coin = PortfolioCalculator.normalizeCoin(t.coin)
                address[coin] = (address[coin] ?: BigDecimal.ZERO) + t.amount
            }
        }
        return real to address
    }

    /**
     * Datum der Adress-Einzahlung: Beginn des UTC-Tags [todayUtcDay] − 366, oder die erste echte Transaktion,
     * wenn sie älter ist. Nur noch Sortierung — Zeiträume und Verlauf lassen Adress-Einzahlungen weg.
     */
    fun heldSince(trades: List<PortfolioTrade>, todayUtcDay: Long): Long {
        val yearAgo = (todayUtcDay - PortfolioPeriodChange.YEAR_DAYS - 1) * DAY_MILLIS
        val first = trades.filter { it.amount.signum() > 0 && !isAddressTrade(it.id) }.minOfOrNull { it.time }
        return if (first != null && first < yearAgo) first else yearAgo
    }

    /**
     * Je Coin mit geladenem Guthaben > 0 eine Einzahlung ohne Kaufpreis ([holdings]) für den Teil OHNE Eröffnungsposten,
     * Reihenfolge BTC, ETH, Token; danach die Eröffnungsposten als Käufe ([AddressCostLots.trades]). Erfasste Bewegungen
     * der Adress-Wallets in [realTrades] stecken schon im Guthaben ([AddressCostLots.accounted]) — nie doppelt.
     */
    fun trades(addresses: List<WatchedAddress>, realTrades: List<PortfolioTrade>, todayUtcDay: Long): List<PortfolioTrade> {
        val held = holdings(addresses)
        if (held.isEmpty()) return emptyList()
        val time = heldSince(realTrades, todayUtcDay)
        val ids = tradeIds(held.map { it.first })
        val accounted = AddressCostLots.accounted(addresses, realTrades)
        return held.mapNotNull { (coin, text) ->
            val amount = PortfolioDecimal.parse(text)?.let { it - (accounted[coin] ?: BigDecimal.ZERO) }?.takeIf { it.signum() > 0 }
                ?: return@mapNotNull null
            PortfolioTrade(id = ids.getValue(coin), coin = coin, type = PortfolioTxType.DEPOSIT, amount = amount, priceUsdt = null, time = time)
        } + AddressCostLots.trades(addresses, realTrades)
    }

    /**
     * Echte Transaktionen + Adress-Einzahlungen — Eingabe für Rechner, Verlauf, Zeiträume, Widget, Alarme
     * (der Rechner zeigt sie als Adress-Teil, Zeiträume/Verlauf lassen sie weg, siehe oben).
     * Schon zusammengeführt (Einträge mit negativer Id): unverändert — nie doppelt.
     */
    fun withAddresses(realTrades: List<PortfolioTrade>, addresses: List<WatchedAddress>, todayUtcDay: Long): List<PortfolioTrade> {
        if (addresses.isEmpty() || realTrades.any { isAddressTrade(it.id) }) return realTrades
        return realTrades + trades(addresses, realTrades, todayUtcDay)
    }

    /**
     * Guthaben aller Adressen für [coin] OHNE Eröffnungsposten (Detailansicht «Aus Adresse · ohne Kaufpreis»), mit den
     * echten Transaktionen [realTrades] wie im Rechner ([trades]); null = keins geladen.
     */
    fun amountFor(coin: String, addresses: List<WatchedAddress>, realTrades: List<PortfolioTrade> = emptyList()): BigDecimal? {
        val key = PortfolioCalculator.normalizeCoin(coin)
        val accounted = AddressCostLots.accounted(addresses, realTrades)[key] ?: BigDecimal.ZERO
        return holdings(addresses).firstOrNull { it.first == key }?.let { PortfolioDecimal.parse(it.second) }
            ?.let { (it - accounted).max(BigDecimal.ZERO) }
    }
}
