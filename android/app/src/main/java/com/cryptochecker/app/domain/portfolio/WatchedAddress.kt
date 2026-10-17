package com.cryptochecker.app.domain.portfolio

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/**
 * Netz einer beobachteten Adresse; [coin] = Coin im Portfolio, [decimals] = kleinste Einheit. ETH steht für die 0x-Adresse
 * (Ethereum und die gewählten weiteren EVM-Netze, [ChainAssets.EVM]). Reihenfolge = Reihenfolge im Bestand.
 */
enum class AddressChain(val coin: String, val decimals: Int) {
    BTC("BTC", 8),
    ETH("ETH", 18),
    SOL("SOL", 9),
    TRX("TRX", 6),
    XRP("XRP", 6),
    LTC("LTC", 8),
    TON("TON", 9),
    ADA("ADA", 6);

    companion object {
        fun fromName(name: String?): AddressChain? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Öffentliche Adresse, deren Guthaben als Bestand im Portfolio erscheint (nur lesen: kein Seed,
 * kein Schlüssel). [address] ist normalisiert ([AddressValidation.check]): Bech32 klein,
 * Ethereum mit EIP-55-Prüfsumme — oder ein xpub/ypub/zpub ([isXpub], Guthaben = Summe der abgeleiteten Adressen).
 * [lastBalance] exakt als Text («0.00012345», [AddressBalance]), null = noch nie geladen. Zeiten in ms; 0 = nie.
 * Reines Kotlin, gleich wie `WatchedAddress.swift`.
 */
data class WatchedAddress(
    val id: Long = 0,
    val chain: AddressChain,
    val address: String,
    val label: String? = null,
    val lastBalance: String? = null,
    /** Letzter erfolgreicher Abruf. */
    val lastUpdated: Long = 0,
    /** Letzter Versuch (Erfolg oder Fehler) — bestimmt mit [AddressBalance.isDue] den nächsten. */
    val lastAttempt: Long = 0,
    /** Technischer Fehlertext des letzten Versuchs; null nach Erfolg. */
    val lastError: String? = null,
    /** Anbieter des letzten Guthabens («mempool.space», «publicnode.com» …) für die Herkunftsangabe. */
    val lastProvider: String? = null,
    val addedAt: Long = 0,
    /**
     * Beobachtete Guthaben ([AddressBalanceHistory.append] je erfolgreichem Abruf): ab der ersten zählt die
     * Adresse in Heute / 7 T / 1 J und im Wertverlauf (nie rückwirkend). Gehen mit der Adresse weg.
     */
    val observations: List<AddressObservation> = emptyList(),
    /**
     * Weitere Guthaben ([ChainAssets.resolve]): ETH = ERC-20 auf Ethereum (Schlüssel Symbol) und Guthaben auf weiteren
     * EVM-Netzen («NETZ:SYMBOL»), TRON = TRC-20, Solana = SPL; exakter Text wie zuletzt gelesen (auch «0»).
     */
    val tokenBalances: Map<String, String> = emptyMap(),
    /** Beobachtete Guthaben je Schlüssel von [tokenBalances] (Regeln wie [observations]: ab der ersten Beobachtung). */
    val tokenObservations: Map<String, List<AddressObservation>> = emptyMap(),
    /** «Tokens mitzählen» (ETH, TRON, Solana), Standard an; aus = keine Token-Abfrage, kein Token-Bestand. */
    val countTokens: Boolean = true,
    /** Nur xpub: abgeleitete und benutzte Adressen ([XpubScan]). */
    val xpub: XpubCache? = null,
    /**
     * Nur ETH: gewählte weitere EVM-Netze ([ChainAssets.EVM], Ethereum zählt immer); null = noch nie gesucht — die erste
     * Abfrage fragt alle und wählt die mit Guthaben.
     */
    val networks: List<String>? = null,
    /** false = «Nur anzeigen, nicht zum Portfolio zählen» (z. B. dieselben Coins wie eigene Transaktionen). */
    val countInPortfolio: Boolean = true,
    /** Solana: Token mit Guthaben, deren Mint nicht in der Liste steht (nicht gezählt, nur angezeigt). */
    val unknownTokens: Int = 0,
    /**
     * Überschneidung mit eigenen Transaktionen schon entschieden (beim Hinzufügen gefragt, von Hand umgeschaltet oder
     * einmal automatisch auf «Nur anzeigen», [WatchedAddressRules.overlapAutoShowOnly]) — danach nie mehr automatisch.
     */
    val overlapChecked: Boolean = false,
    /**
     * Entscheid [overlapChecked] stammt aus der Zeit, als «Nur anzeigen» schon bei gleichem Coin automatisch kam (ohne
     * Mengenvergleich; gespeichert ohne «ovl2»). Beim Laden einmal geprüft ([WatchedAddressRules.overlapLegacyRevert]).
     */
    val overlapLegacy: Boolean = false,
) {
    /** Erweiterter öffentlicher Schlüssel statt einzelner Adresse. */
    val isXpub: Boolean get() = chain == AddressChain.BTC && AddressValidation.isExtendedKey(address)

    /** Ist dieses EVM-Netz gewählt? (Vor der ersten Suche: alle.) */
    fun networkSelected(id: String): Boolean = networks?.contains(id) ?: true

    /**
     * Weitere Guthaben > 0, die zählen: Token nur mit «Tokens mitzählen», Netz-Guthaben weiterer EVM-Netze immer (wenn
     * das Netz gewählt ist); Reihenfolge Ethereum-Token, dann Netze in der Reihenfolge von [ChainAssets.EVM].
     */
    fun tokenHoldings(): List<TokenHolding> {
        if (!ChainAssets.hasTokens(chain)) return emptyList()
        return tokenBalances.entries.mapNotNull { (key, text) ->
            val asset = ChainAssets.resolve(chain, key) ?: return@mapNotNull null
            if (!asset.native && !countTokens) return@mapNotNull null
            if (asset.network != null && !networkSelected(asset.network)) return@mapNotNull null
            if ((PortfolioDecimal.parse(text)?.signum() ?: 0) <= 0) return@mapNotNull null
            asset to text
        }.sortedBy { it.first.order }.map { (a, text) -> TokenHolding(a.key, a.label, a.coin, text, a.network) }
    }
}

/** Ein weiteres Guthaben einer Adresse: [label] («USDC.e»), [coin] für Kurs/Bestand, [network] = EVM-Netz oder null. */
data class TokenHolding(val key: String, val label: String, val coin: String, val amount: String, val network: String?)

/**
 * Eintrag in der App-Sicherung: was der Nutzer eingegeben hat, dazu die beobachteten Guthaben (sie bestimmen
 * den Verlauf ab der ersten Beobachtung). Das aktuelle Guthaben wird neu geladen. Ältere Sicherungen ohne
 * Beobachtungen: leer.
 */
data class WatchedAddressBackupEntry(
    val chain: AddressChain,
    val address: String,
    val label: String? = null,
    val addedAt: Long = 0,
    val observations: List<AddressObservation> = emptyList(),
    /** «Tokens mitzählen»; in der Sicherung nur, wenn aus. */
    val countTokens: Boolean = true,
    /** Beobachtete Guthaben je Schlüssel ([WatchedAddress.tokenObservations]). */
    val tokenObservations: Map<String, List<AddressObservation>> = emptyMap(),
    /** Gewählte EVM-Netze (nur ETH); null = noch nie gesucht. */
    val networks: List<String>? = null,
    /** «Nur anzeigen» = false; in der Sicherung nur, wenn aus. */
    val countInPortfolio: Boolean = true,
)

/** Regeln der beobachteten Adressen (Anzahl, Bezeichnung, Anzeige, Sicherung). */
object WatchedAddressRules {

    /** Höchstens so viele Adressen (jede kostet eine Anfrage je Aktualisierung). */
    const val MAX_ADDRESSES = 20

    /** Längste Bezeichnung in Unicode-Zeichen (Codepoints). */
    const val MAX_LABEL = 40

    /** Schlüssel der Liste in der App-Sicherung (JSON). */
    const val BACKUP_KEY = "watchedAddresses"

    /** Bezeichnung zuschneiden; leer = null. Gleiche Zählung wie iOS (Unicode-Skalare). */
    fun cleanLabel(raw: String?): String? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return null
        val count = t.codePointCount(0, t.length)
        val cut = if (count <= MAX_LABEL) t else t.substring(0, t.offsetByCodePoints(0, MAX_LABEL)).trimEnd()
        return cut.ifEmpty { null }
    }

    /** «bc1qxy…0wlh» — Adressen sind ASCII. */
    fun shortAddress(address: String): String =
        if (address.length <= 14) address else address.take(6) + "…" + address.takeLast(4)

    /** Gleiche Adresse schon vorhanden? (Vergleich der normalisierten Form, Ethereum ohne Gross/Klein.) */
    fun contains(existing: List<WatchedAddress>, chain: AddressChain, address: String): Boolean =
        existing.any { it.chain == chain && sameAddress(chain, it.address, address) }

    fun sameAddress(chain: AddressChain, a: String, b: String): Boolean =
        if (chain == AddressChain.ETH) a.equals(b, ignoreCase = true) else a == b

    fun canAdd(existing: List<WatchedAddress>): Boolean = existing.size < MAX_ADDRESSES

    /**
     * Wiederherstellen: neue Adressen aus der Sicherung (geprüft und normalisiert), ohne Doppelte,
     * höchstens bis [MAX_ADDRESSES]. Vorhandene bleiben unverändert (auch ihre Bezeichnung).
     * Ungültige Einträge fallen weg.
     */
    fun merge(existing: List<WatchedAddress>, incoming: List<WatchedAddressBackupEntry>): List<WatchedAddress> {
        val result = ArrayList<WatchedAddress>()
        for (entry in incoming) {
            if (existing.size + result.size >= MAX_ADDRESSES) break
            val check = AddressValidation.check(entry.address, entry.chain)
            val normalized = check.normalized ?: continue
            if (contains(existing, entry.chain, normalized) || contains(result, entry.chain, normalized)) continue
            val networks = if (entry.chain == AddressChain.ETH) cleanNetworks(entry.networks) else null
            var a = WatchedAddress(
                chain = entry.chain,
                address = normalized,
                label = cleanLabel(entry.label),
                addedAt = entry.addedAt.coerceAtLeast(0),
                observations = AddressBalanceHistory.clean(entry.observations),
                countTokens = entry.countTokens,
                tokenObservations = cleanTokens(entry.chain, entry.tokenObservations),
                networks = networks,
                countInPortfolio = entry.countInPortfolio,
            )
            // Reihen nur, wo sie heute zählen würden (Token aus, Netz abgewählt: weg)
            a = a.copy(tokenObservations = a.tokenObservations.filterKeys { counts(a, it) })
            result += a
        }
        return result
    }

    /** Zählt dieser Schlüssel bei dieser Adresse (bekannt, Token nur mit «Tokens mitzählen», Netz gewählt)? */
    fun counts(a: WatchedAddress, key: String): Boolean {
        val asset = ChainAssets.resolve(a.chain, key) ?: return false
        if (!asset.native && !a.countTokens) return false
        return asset.network == null || a.networkSelected(asset.network)
    }

    /** Nur bekannte Schlüssel ([ChainAssets.resolve]) mit gültigen Beobachtungen. */
    fun cleanTokens(chain: AddressChain, map: Map<String, List<AddressObservation>>): Map<String, List<AddressObservation>> =
        map.filterKeys { ChainAssets.resolve(chain, it) != null }
            .mapValues { AddressBalanceHistory.clean(it.value) }
            .filterValues { it.isNotEmpty() }

    /** Nur bekannte EVM-Netze, Reihenfolge von [ChainAssets.EVM]; null bleibt null. */
    fun cleanNetworks(list: List<String>?): List<String>? =
        list?.let { l -> ChainAssets.EVM.map { it.id }.filter { it in l } }

    fun backupEntries(list: List<WatchedAddress>): List<WatchedAddressBackupEntry> =
        list.map {
            WatchedAddressBackupEntry(
                it.chain, it.address, it.label, it.addedAt, it.observations, it.countTokens, it.tokenObservations,
                it.networks, it.countInPortfolio,
            )
        }

    /**
     * Einzelne Bitcoin-Adressen, die schon in einem beobachteten xpub stecken (abgeleitete Adressen im
     * Zwischenspeicher) — zählen nur einmal (über den xpub).
     */
    fun coveredByXpub(list: List<WatchedAddress>): Set<String> {
        val out = HashSet<String>()
        for (a in list) {
            val cache = a.xpub ?: continue
            if (!a.isXpub) continue
            out.addAll(cache.receive)
            out.addAll(cache.change)
        }
        return out
    }

    /**
     * Erfolgreicher Abruf: Guthaben, Zeit, Anbieter, Fehler gelöscht; Beobachtung nur bei geändertem Guthaben
     * ([AddressBalanceHistory.record]). [tokens] (null = nicht gelesen): gelesene Schlüssel ersetzen ihren Wert, nicht
     * gelesene behalten ihn; Token nur mit «Tokens mitzählen», Netze nur, wenn gewählt; Beobachtung je Schlüssel (ein
     * Guthaben 0 ohne frühere Beobachtung wird nicht gespeichert). [xpub]: neuer Zwischenspeicher (null = unverändert).
     * [networks]: Wahl nach der ersten Suche (null = unverändert). [unknownTokens]: Solana, null = unverändert.
     */
    fun applyFetched(
        a: WatchedAddress,
        time: Long,
        balance: String,
        provider: String?,
        tokens: Map<String, String>? = null,
        xpub: XpubCache? = null,
        networks: List<String>? = null,
        unknownTokens: Int? = null,
    ): WatchedAddress {
        var out = a.copy(lastBalance = balance, lastUpdated = time, lastAttempt = time, lastError = null, lastProvider = provider)
        PortfolioDecimal.parse(balance)?.let { out = out.copy(observations = AddressBalanceHistory.record(a.observations, time, it)) }
        if (networks != null && a.chain == AddressChain.ETH && a.networks == null) out = out.copy(networks = cleanNetworks(networks))
        if (tokens != null && ChainAssets.hasTokens(a.chain)) {
            val balances = LinkedHashMap(out.tokenBalances)
            val observed = LinkedHashMap(out.tokenObservations)
            for ((key, text) in tokens) {
                if (!counts(out, key)) continue
                // Einheitliche Form («3.0» → 3), damit gleiche Guthaben gleich bleiben
                val clean = PortfolioDecimal.parse(text)?.takeIf { it.signum() >= 0 }?.let { PortfolioDecimal.text(it) } ?: continue
                val value = PortfolioDecimal.parse(clean) ?: continue
                balances[key] = clean
                val before = observed[key].orEmpty()
                if (value.signum() == 0 && before.isEmpty()) continue
                observed[key] = AddressBalanceHistory.record(before, time, value)
            }
            out = out.copy(tokenBalances = balances, tokenObservations = observed)
        }
        if (xpub != null) out = out.copy(xpub = xpub)
        if (unknownTokens != null && a.chain == AddressChain.SOL) out = out.copy(unknownTokens = unknownTokens.coerceAtLeast(0))
        return out
    }

    /** Fehlgeschlagener Abruf: letzter Stand bleibt. */
    fun applyError(a: WatchedAddress, time: Long, error: String): WatchedAddress = a.copy(lastAttempt = time, lastError = error)

    /**
     * «Tokens mitzählen»: aus löscht Token-Guthaben und -Beobachtungen (ohne Abfrage keine ehrliche Reihe; Netz-Guthaben
     * weiterer EVM-Netze bleiben); an macht die Adresse sofort fällig (Token werden gleich gelesen und zählen ab dann).
     */
    fun withCountTokens(a: WatchedAddress, on: Boolean): WatchedAddress = when {
        !ChainAssets.hasTokens(a.chain) || a.countTokens == on -> a
        on -> a.copy(countTokens = true, lastAttempt = 0)
        else -> {
            val off = a.copy(countTokens = false)
            off.copy(
                tokenBalances = a.tokenBalances.filterKeys { counts(off, it) },
                tokenObservations = a.tokenObservations.filterKeys { counts(off, it) },
                unknownTokens = 0,
            )
        }
    }

    /**
     * Gewählte EVM-Netze (nur ETH): abgewählte verlieren Guthaben und Reihen (ohne Abfrage keine ehrliche Reihe); ein neu
     * gewähltes macht die Adresse sofort fällig.
     */
    fun withNetworks(a: WatchedAddress, networks: List<String>): WatchedAddress {
        if (a.chain != AddressChain.ETH) return a
        val clean = cleanNetworks(networks) ?: emptyList()
        if (clean == a.networks) return a
        val added = clean.any { !a.networkSelected(it) } || a.networks == null
        val next = a.copy(networks = clean)
        return next.copy(
            tokenBalances = a.tokenBalances.filterKeys { counts(next, it) },
            tokenObservations = a.tokenObservations.filterKeys { counts(next, it) },
            lastAttempt = if (added) 0 else a.lastAttempt,
        )
    }

    /** «Nur anzeigen, nicht zum Portfolio zählen» (false) bzw. zählen (true); die Wahl gilt als entschieden. */
    fun withCountInPortfolio(a: WatchedAddress, on: Boolean): WatchedAddress =
        a.copy(countInPortfolio = on, overlapChecked = true, overlapLegacy = false)

    /**
     * Zählende Adressen, deren Guthaben MENGENMÄSSIG zu eigenen Transaktionen passt ([overlapMatched]) und die noch nie
     * entschieden wurden ([WatchedAddress.overlapChecked]): einmal automatisch «Nur anzeigen» (mit Hinweis und
     * «Rückgängig») — kein stilles Doppelzählen. Nur gleicher Coin ohne passende Menge: zählt weiter, der Hinweis
     * «Auch in eigenen Transaktionen» ([overlap]) bleibt.
     */
    fun overlapAutoShowOnly(addresses: List<WatchedAddress>, trades: List<PortfolioTrade>): List<WatchedAddress> =
        addresses.filter { it.countInPortfolio && !it.overlapChecked && overlapMatched(it, trades).isNotEmpty() }

    /** Einmal automatisch «Nur anzeigen» ([overlapAutoShowOnly]); schon entschieden bzw. nicht zählend: unverändert. */
    fun withOverlapShowOnly(a: WatchedAddress): WatchedAddress =
        if (a.overlapChecked || !a.countInPortfolio) a else a.copy(countInPortfolio = false, overlapChecked = true, overlapLegacy = false)

    /**
     * Coins der Adresse ([overlap]), deren Menge zu eigenen Transaktionen passt: Guthaben ≈ eigener Bestand des Coins
     * ([PortfolioCalculator.holdingsAt], ± 5 % — [OVERLAP_TOLERANCE]) oder eine eigene Einzahlung/Umbuchung wie ein
     * beobachteter Zugang ([transferMatches], ± 1 %). Reihenfolge wie [AddressBalance.holdings].
     */
    fun overlapMatched(a: WatchedAddress, trades: List<PortfolioTrade>): List<String> {
        val real = trades.filter { !AddressBalance.isAddressTrade(it.id) }
        val own = PortfolioCalculator.holdingsAt(real)
        val transferred = transferMatches(a, real).map { PortfolioCalculator.normalizeCoin(it.coin) }.toSet()
        return AddressBalance.holdings(listOf(a.copy(countInPortfolio = true))).mapNotNull { (coin, text) ->
            val balance = PortfolioDecimal.parse(text)?.takeIf { it.signum() > 0 } ?: return@mapNotNull null
            val mine = own[coin]
            coin.takeIf { (mine != null && nearlyEqual(mine, balance, OVERLAP_TOLERANCE)) || coin in transferred }
        }
    }

    /**
     * Alte Entscheide ([WatchedAddress.overlapLegacy]), die wieder zählen sollen: «Nur anzeigen» ohne passende Menge
     * ([overlapMatched]) — früher genügte derselbe Coin. Einmal beim Laden, mit Hinweis und «Rückgängig».
     */
    fun overlapLegacyRevert(a: WatchedAddress, trades: List<PortfolioTrade>): Boolean =
        a.overlapLegacy && !a.countInPortfolio && overlapMatched(a, trades).isEmpty()

    /**
     * Alten Entscheid übernehmen ([WatchedAddress.overlapLegacy] weg): [revert] = zählt wieder und gilt als nicht
     * entschieden (die neue Regel darf später bei passender Menge umstellen); sonst bleibt die Wahl.
     */
    fun withOverlapMigrated(a: WatchedAddress, revert: Boolean): WatchedAddress = when {
        !a.overlapLegacy -> a
        revert -> a.copy(countInPortfolio = true, overlapChecked = false, overlapLegacy = false)
        else -> a.copy(overlapLegacy = false)
    }

    /**
     * Coins dieser Adresse (Guthaben > 0, wie sie zählen würde), die auch in eigenen Transaktionen vorkommen — dann
     * würden dieselben Coins doppelt zählen, wenn es dieselben sind (Hinweis, Schalter «Nur anzeigen»).
     */
    fun overlap(a: WatchedAddress, ownCoins: Set<String>): List<String> =
        AddressBalance.holdings(listOf(a.copy(countInPortfolio = true)))
            .filter { (coin, amount) -> coin in ownCoins && (PortfolioDecimal.parse(amount)?.signum() ?: 0) > 0 }
            .map { it.first }

    /** Beim Hinzufügen (Guthaben noch unbekannt): schon eigene Transaktionen im Coin des Netzes? */
    fun overlapAtAdd(chain: AddressChain, ownCoins: Set<String>): Boolean = chain.coin in ownCoins

    /** Toleranz für [transferMatches]: ± 1 % des Guthabens bzw. der Änderung. */
    private val MATCH_TOLERANCE = BigDecimal("0.01")

    /**
     * Toleranz für «Guthaben ≈ eigener Bestand» ([overlapMatched], einmal automatisch «Nur anzeigen»): ± 5 % des Guthabens.
     * Gebühren, Staub und kleine nicht gebuchte Zugänge trennen Buchung und Kette oft um mehr als 1 % (1.02 auf der Kette
     * gegen 1.00 gebucht) — dann zählten sonst beide (Bestand zu hoch). Innerhalb von 5 % gilt die Buchung, die Adresse
     * wird nur angezeigt (Hinweis, «Rückgängig»); weiter auseinander zählt sie weiter, der Hinweis bleibt.
     */
    private val OVERLAP_TOLERANCE = BigDecimal("0.05")

    /**
     * Eigene Einzahlungen ([PortfolioTxType.DEPOSIT]) und Umbuchungen ([PortfolioTxType.TRANSFER]) im Coin der Adresse,
     * deren Menge (oder erhaltene Menge nach Coin-Gebühr) dem aktuellen Guthaben oder einem beobachteten Zugang
     * (erste Beobachtung, Zunahme zwischen zwei Beobachtungen) auf ± 1 % entspricht — dann liegt derselbe Betrag sehr
     * wahrscheinlich doppelt im Portfolio (Zeilen-Warnung, «Zählen» / «Nur anzeigen»). Zeitlich aufsteigend, je
     * Transaktion einmal (Reihenfolge bei gleicher Zeit wie die Eingabe). Adress-Einzahlungen (negative Id) zählen nicht.
     */
    fun transferMatches(a: WatchedAddress, trades: List<PortfolioTrade>): List<PortfolioTrade> {
        val counted = a.copy(countInPortfolio = true)
        val targets = HashMap<String, MutableList<BigDecimal>>()
        fun target(coin: String, value: BigDecimal?) {
            if (value == null || value.signum() <= 0) return
            targets.getOrPut(PortfolioCalculator.normalizeCoin(coin)) { ArrayList() } += value
        }
        for ((coin, text) in AddressBalance.holdings(listOf(counted))) target(coin, PortfolioDecimal.parse(text))
        for (h in AddressBalanceHistory.of(listOf(counted))) {
            var before = BigDecimal.ZERO
            for (o in h.observations.sortedBy { it.time }) {
                target(h.coin, o.balance - before)
                before = o.balance
            }
        }
        if (targets.isEmpty()) return emptyList()
        return trades.filter { t ->
            if (AddressBalance.isAddressTrade(t.id)) return@filter false
            if (t.type != PortfolioTxType.DEPOSIT && t.type != PortfolioTxType.TRANSFER) return@filter false
            val list = targets[PortfolioCalculator.normalizeCoin(t.coin)] ?: return@filter false
            list.any { goal -> nearlyEqual(t.amount, goal) || nearlyEqual(t.received, goal) }
        }.sortedBy { it.time }
    }

    /**
     * Coin-Detail: Treffer ([transferMatches]) aller ZÄHLENDEN Adressen im Coin [coin] — je Transaktion einmal, zeitlich
     * aufsteigend (gleiche Zeit: Reihenfolge der Adressen). «Nur anzeigen» zählt nicht doppelt, also keine Warnung.
     */
    fun transferMatchesFor(coin: String, addresses: List<WatchedAddress>, trades: List<PortfolioTrade>): List<PortfolioTrade> {
        val c = PortfolioCalculator.normalizeCoin(coin)
        return addresses.filter { it.countInPortfolio }
            .flatMap { transferMatches(it, trades) }
            .filter { PortfolioCalculator.normalizeCoin(it.coin) == c }
            .distinctBy { it.id }
            .sortedBy { it.time }
    }

    /** |[x] − [goal]| ≤ [tolerance] von [goal] (goal > 0; Standard 1 %). */
    private fun nearlyEqual(x: BigDecimal, goal: BigDecimal, tolerance: BigDecimal = MATCH_TOLERANCE): Boolean =
        x.signum() > 0 && (x - goal).abs() <= goal * tolerance

    /**
     * Für die App-Sicherung: `[{"chain":"BTC","address":"…","label":"…","addedAt":…,"observations":[{"t":…,"b":"…"}]}]`
     * («observations» nur, wenn vorhanden; dazu «countTokens»:false und «countInPortfolio»:false nur, wenn aus,
     * «tokenObservations»:{"USDT":[…]} nur, wenn vorhanden, «networks»:[…] nur ETH nach der ersten Suche).
     */
    fun toJson(entries: List<WatchedAddressBackupEntry>): JSONArray {
        val array = JSONArray()
        for (e in entries) {
            val o = JSONObject()
            o.put("chain", e.chain.name)
            o.put("address", e.address)
            e.label?.let { o.put("label", it) }
            o.put("addedAt", e.addedAt)
            if (e.observations.isNotEmpty()) o.put("observations", AddressBalanceHistory.toJson(e.observations))
            if (!e.countTokens) o.put("countTokens", false)
            if (e.tokenObservations.isNotEmpty()) o.put("tokenObservations", WatchedAddressExtras.tokensToJson(e.tokenObservations))
            e.networks?.let { o.put("networks", JSONArray(it)) }
            if (!e.countInPortfolio) o.put("countInPortfolio", false)
            array.put(o)
        }
        return array
    }

    /** Lesen; unlesbare Einträge (unbekanntes Netz, keine Adresse) fallen weg — geprüft wird in [merge]. */
    fun fromJson(array: JSONArray?): List<WatchedAddressBackupEntry> {
        if (array == null) return emptyList()
        val out = ArrayList<WatchedAddressBackupEntry>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val chain = AddressChain.fromName(o.optString("chain")) ?: continue
            val address = o.optString("address").takeIf { it.isNotEmpty() } ?: continue
            val label = if (o.isNull("label")) null else o.optString("label")
            val addedAt = o.optLong("addedAt", 0L)
            val observations = AddressBalanceHistory.fromJson(o.optJSONArray("observations"))
            val countTokens = o.opt("countTokens") as? Boolean ?: true
            val tokens = WatchedAddressExtras.tokensFromJson(chain, o.optJSONObject("tokenObservations"))
            val networks = WatchedAddressExtras.networksFromJson(o.optJSONArray("networks"))
            val countInPortfolio = o.opt("countInPortfolio") as? Boolean ?: true
            out += WatchedAddressBackupEntry(chain, address, label, addedAt, observations, countTokens, tokens, networks, countInPortfolio)
        }
        return out
    }
}

/**
 * Zusatzdaten einer beobachteten Adresse in EINER Text-Spalte (Android: `observations` der Tabelle
 * `watched_addresses`, ohne neue Spalte). Ohne Zusatzdaten genau wie bisher das Array der Beobachtungen
 * (`[{"t":…,"b":"…"}]`, [AddressBalanceHistory.encode]); mit Zusatzdaten ein Objekt:
 * `{"o":[…],"tb":{"USDT":"12.5"},"to":{"USDT":[…]},"tokens":false,"x":{…},"n":["BSC"],"show":false,"uk":2}` —
 * «tokens»/«show» nur, wenn aus; «x» = [XpubCache]; «n» = EVM-Netze; «uk» = unbekannte Solana-Token; «ovl»: true =
 * Überschneidung entschieden ([WatchedAddress.overlapChecked]), «ovl2»: true = nach der Mengenregel (sonst
 * [WatchedAddress.overlapLegacy]).
 * Lesen versteht beide Formen; unlesbar = leer. iOS speichert dieselben Felder in `watched_addresses.json`.
 */
object WatchedAddressExtras {

    data class Decoded(
        val observations: List<AddressObservation> = emptyList(),
        val tokenBalances: Map<String, String> = emptyMap(),
        val tokenObservations: Map<String, List<AddressObservation>> = emptyMap(),
        val countTokens: Boolean = true,
        val xpub: XpubCache? = null,
        val networks: List<String>? = null,
        val countInPortfolio: Boolean = true,
        val unknownTokens: Int = 0,
        val overlapChecked: Boolean = false,
        val overlapLegacy: Boolean = false,
    )

    fun encode(a: WatchedAddress): String? {
        val plain = a.tokenBalances.isEmpty() && a.tokenObservations.isEmpty() && a.countTokens && a.xpub == null &&
            a.networks == null && a.countInPortfolio && a.unknownTokens == 0 && !a.overlapChecked
        if (plain) return AddressBalanceHistory.encode(a.observations)
        val o = JSONObject()
        o.put("o", AddressBalanceHistory.toJson(a.observations))
        if (a.tokenBalances.isNotEmpty()) {
            val tb = JSONObject()
            for ((k, v) in a.tokenBalances) tb.put(k, v)
            o.put("tb", tb)
        }
        if (a.tokenObservations.isNotEmpty()) o.put("to", tokensToJson(a.tokenObservations))
        if (!a.countTokens) o.put("tokens", false)
        a.xpub?.let { o.put("x", XpubCache.toJson(it)) }
        a.networks?.let { o.put("n", JSONArray(it)) }
        if (!a.countInPortfolio) o.put("show", false)
        if (a.unknownTokens > 0) o.put("uk", a.unknownTokens)
        if (a.overlapChecked) o.put("ovl", true)
        // Entscheid nach der Mengenregel (ohne «ovl2» = alter Entscheid, [WatchedAddress.overlapLegacy])
        if (a.overlapChecked && !a.overlapLegacy) o.put("ovl2", true)
        return o.toString()
    }

    fun decode(text: String?, chain: AddressChain): Decoded {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return Decoded()
        if (t.startsWith("[")) return Decoded(observations = AddressBalanceHistory.decode(t))
        val o = runCatching { JSONObject(t) }.getOrNull() ?: return Decoded()
        val balances = LinkedHashMap<String, String>()
        o.optJSONObject("tb")?.let { tb ->
            for (k in tb.keys()) {
                val v = tb.opt(k) as? String ?: continue
                val value = PortfolioDecimal.parse(v)?.takeIf { it.signum() >= 0 } ?: continue
                if (ChainAssets.resolve(chain, k) != null) balances[k] = PortfolioDecimal.text(value)
            }
        }
        return Decoded(
            observations = AddressBalanceHistory.fromJson(o.optJSONArray("o")),
            tokenBalances = balances,
            tokenObservations = tokensFromJson(chain, o.optJSONObject("to")),
            countTokens = o.opt("tokens") as? Boolean ?: true,
            xpub = XpubCache.fromJson(o.optJSONObject("x")),
            networks = if (chain == AddressChain.ETH) networksFromJson(o.optJSONArray("n")) else null,
            countInPortfolio = o.opt("show") as? Boolean ?: true,
            unknownTokens = ((o.opt("uk") as? Number)?.toInt() ?: 0).coerceAtLeast(0),
            overlapChecked = o.opt("ovl") as? Boolean ?: false,
            overlapLegacy = (o.opt("ovl") as? Boolean ?: false) && (o.opt("ovl2") as? Boolean) != true,
        )
    }

    /** `{"USDT":[{"t":…,"b":"…"}]}`. */
    fun tokensToJson(map: Map<String, List<AddressObservation>>): JSONObject {
        val o = JSONObject()
        for ((k, v) in map) if (v.isNotEmpty()) o.put(k, AddressBalanceHistory.toJson(v))
        return o
    }

    /** Lesen; nur bekannte Schlüssel mit gültigen Beobachtungen. */
    fun tokensFromJson(chain: AddressChain, o: JSONObject?): Map<String, List<AddressObservation>> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<String, List<AddressObservation>>()
        for (k in o.keys()) out[k] = AddressBalanceHistory.fromJson(o.optJSONArray(k))
        return WatchedAddressRules.cleanTokens(chain, out)
    }

    /** `["BSC","BASE"]` → bekannte Netze in Listen-Reihenfolge; fehlt = null. */
    fun networksFromJson(a: JSONArray?): List<String>? {
        if (a == null) return null
        return WatchedAddressRules.cleanNetworks((0 until a.length()).map { a.optString(it) })
    }
}
