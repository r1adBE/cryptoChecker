package com.cryptochecker.app.domain.portfolio

import org.json.JSONArray
import org.json.JSONObject

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
