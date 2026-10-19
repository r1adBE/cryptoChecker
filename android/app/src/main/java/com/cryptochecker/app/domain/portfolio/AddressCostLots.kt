// Eröffnungsposten (Einstand) für Guthaben beobachteter Adressen (gespiegelt in `AddressCostLots.swift`).
package com.cryptochecker.app.domain.portfolio

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/**
 * Eröffnungsposten einer beobachteten Adresse («Einstand für Adressguthaben», Build 84): der Nutzer nennt für einen Teil
 * des Guthabens Kaufdatum, Menge und Einstand. [id] ist innerhalb der Adresse eindeutig (1, 2, …), [coin] normalisiert,
 * [time] = Erwerb (ms), [amount] > 0. Einstand entweder als [priceUsdt] (je Coin) oder als [costUsdt] (gesamt) — so wie
 * eingegeben gespeichert, damit nichts durch Umrechnen rundet.
 */
data class AddressCostLot(
    val id: Long,
    val coin: String,
    val time: Long,
    val amount: BigDecimal,
    val priceUsdt: BigDecimal? = null,
    val costUsdt: BigDecimal? = null,
) {
    /** Preis je Coin: erfasster Preis, sonst Einstand ÷ Menge (34 Stellen); null ohne gültigen Einstand. */
    val price: BigDecimal?
        get() = priceUsdt?.takeIf { it.signum() >= 0 }
            ?: costUsdt?.takeIf { it.signum() >= 0 && amount.signum() > 0 }?.divide(amount, PortfolioDecimal.MC)

    /** Einstand gesamt: erfasster Einstand, sonst Menge × Preis; null ohne gültigen Einstand. */
    val cost: BigDecimal?
        get() = costUsdt?.takeIf { it.signum() >= 0 } ?: priceUsdt?.takeIf { it.signum() >= 0 }?.let { amount * it }
}

/**
 * Regeln der Eröffnungsposten ([AddressCostLot]) — sie speisen die Einstandsrechnung genau wie Käufe:
 *  - Jede beobachtete Adresse ist ihre eigene Wallet ([walletName]; FIFO je Wallet trennt sie von Börsen).
 *  - Die Posten übersteigen nie das beobachtete Guthaben DIESES Coins an DIESER Adresse ([cover]): ist die Summe grösser
 *    (zu viel erfasst oder das Guthaben ist seither gesunken), verbraucht FIFO die ältesten Posten zuerst — wie ein
 *    Abgang aus dieser Adresse (ohne Gewinn, der Preis je Coin der übrigen Posten bleibt). Hinweis in der Anzeige.
 *  - Nur der nicht belegte Rest bleibt Adress-Teil «ohne Einstand» ([AddressBalance.trades]).
 *  - Zählt die Adresse nicht («Nur anzeigen») oder steckt sie schon in einem xpub, zählen auch ihre Posten nicht — gleiche
 *    Regeln wie [AddressBalance.holdings].
 *  - Heute / 7 T / 1 J, Wertverlauf, Stichtag und CH-Vermögenswert lassen die Posten weg (Adress-Einträge,
 *    [AddressBalance.isAddressTrade]): dort zählt das beobachtete Guthaben zum Wert, nie rückwirkend.
 * Gespeichert mit der Adresse (Android Spalte `costLots`, iOS `watched_addresses.json`), in der App-Sicherung unter
 * «costLots» je Adresse: `[{"id":1,"coin":"BTC","t":…,"amount":"0.5","price":"30000"}]` («cost» statt «price», wenn
 * gesamt eingegeben). Ältere Versionen kennen das Feld nicht und lesen den Rest unverändert.
 */
object AddressCostLots {

    /** Höchstens so viele Posten je Adresse. */
    const val MAX_LOTS = 50

    /** Ids der Posten im Rechner: ab hier abwärts (weit unter den Adress-Einzahlungen −1 … −1001 …). */
    const val ID_BASE = -1_000_000_000L

    /** Rechner-Eintrag eines Postens (Kauf mit negativer Id, [tradeId])? */
    fun isLotTrade(id: Long): Boolean = id <= ID_BASE

    /** Id des Postens Nr. [index] (Reihenfolge von [clean]) der Adresse [addressId]: eindeutig, nie gespeichert. */
    fun tradeId(addressId: Long, index: Int): Long = ID_BASE - addressId * 1000 - index

    /** Wallet der Adresse für FIFO je Wallet: Bezeichnung, sonst gekürzte Adresse. */
    fun walletName(a: WatchedAddress): String = a.label ?: WatchedAddressRules.shortAddress(a.address)

    /** Gültige Posten (Coin, Menge > 0, Einstand ≥ 0, Zeit > 0) nach Zeit, dann Id; höchstens [MAX_LOTS]. */
    fun clean(lots: List<AddressCostLot>): List<AddressCostLot> =
        lots.mapNotNull { l ->
            val coin = PortfolioCalculator.normalizeCoin(l.coin)
            val valid = coin.isNotEmpty() && coin.length <= 15 && coin.all { it.isLetterOrDigit() || it == '.' } &&
                l.time > 0 && l.amount.signum() > 0 && PortfolioDecimal.withinLimits(l.amount) && l.price != null
            if (valid) l.copy(coin = coin) else null
        }.sortedWith(compareBy<AddressCostLot> { it.time }.thenBy { it.id }).take(MAX_LOTS)

    /** Nächste freie Id einer Adresse. */
    fun nextId(lots: List<AddressCostLot>): Long = (lots.maxOfOrNull { it.id } ?: 0L) + 1

    /**
     * Guthaben je Coin EINER Adresse, wie [AddressBalance.holdings] es zählt: Netz-Coin aus dem letzten Guthaben (auch 0),
     * dazu die weiteren Guthaben > 0 ([WatchedAddress.tokenHoldings]), derselbe Coin addiert. Ohne geladenes Guthaben fehlt
     * der Coin.
     */
    fun balances(a: WatchedAddress): Map<String, BigDecimal> {
        val out = LinkedHashMap<String, BigDecimal>()
        PortfolioDecimal.parse(a.lastBalance)?.takeIf { it.signum() >= 0 }?.let { out[a.chain.coin] = it }
        for (h in a.tokenHoldings()) {
            val v = PortfolioDecimal.parse(h.amount) ?: continue
            out[h.coin] = (out[h.coin] ?: BigDecimal.ZERO) + v
        }
        return out
    }

    /**
     * Adressen, deren Guthaben zählt, mit ihren Guthaben je Coin ([balances]) — gleiche Auswahl wie
     * [AddressBalance.holdings]: nur «zum Portfolio zählen», gleiche Adresse einmal, eine Bitcoin-Adresse in einem
     * beobachteten xpub zählt über den xpub (ihre Posten dann nicht).
     */
    fun counted(addresses: List<WatchedAddress>): List<Pair<WatchedAddress, Map<String, BigDecimal>>> {
        val list = addresses.filter { it.countInPortfolio }
        val covered = WatchedAddressRules.coveredByXpub(list)
        val seen = ArrayList<WatchedAddress>()
        val out = ArrayList<Pair<WatchedAddress, Map<String, BigDecimal>>>()
        for (a in list) {
            if (a.chain == AddressChain.BTC && !a.isXpub && a.address in covered) continue
            if (seen.any { it.chain == a.chain && WatchedAddressRules.sameAddress(a.chain, it.address, a.address) }) continue
            seen += a
            out += a to balances(a)
        }
        return out
    }

    /** Ergebnis von [cover] für einen Coin einer Adresse. */
    data class Coverage(
        val coin: String,
        /** Wirksame Posten (gekürzt, Preis je Coin unverändert), Reihenfolge von [clean]. */
        val lots: List<AddressCostLot>,
        /** Belegte Menge = min(Summe der Posten, Guthaben). */
        val lotted: BigDecimal,
        val balance: BigDecimal,
        /** Posten über dem Guthaben (verbraucht); 0 = keine. */
        val excess: BigDecimal,
    ) {
        val capped: Boolean get() = excess.signum() > 0

        /** Guthaben ohne Posten («ohne Einstand»). */
        val unlotted: BigDecimal get() = (balance - lotted).max(BigDecimal.ZERO)
    }

    /**
     * Posten [lots] EINES Coins (Reihenfolge von [clean]) gegen das Guthaben [balance]: was darüber liegt, verbraucht
     * FIFO — die ältesten Posten zuerst, ein angebrochener Posten behält seinen Preis je Coin.
     */
    fun cover(coin: String, lots: List<AddressCostLot>, balance: BigDecimal): Coverage {
        val held = balance.max(BigDecimal.ZERO)
        val total = lots.fold(BigDecimal.ZERO) { a, l -> a + l.amount }
        val excess = (total - held).max(BigDecimal.ZERO)
        var left = excess
        val out = ArrayList<AddressCostLot>(lots.size)
        for (l in lots) {
            if (left >= l.amount) {
                left -= l.amount
                continue
            }
            val rest = l.amount - left
            left = BigDecimal.ZERO
            // Angebrochen: Preis je Coin bleibt (ein Gesamt-Einstand wird zum Preis, sonst passte er nicht zur Menge)
            out += if (rest.compareTo(l.amount) == 0) l else l.copy(amount = rest, priceUsdt = l.price, costUsdt = null)
        }
        return Coverage(coin, out, total - excess, held, excess)
    }

    /** Je Coin mit Posten die Abdeckung dieser Adresse (Guthaben fehlt = 0), Reihenfolge der Coins wie die Posten. */
    fun coverage(a: WatchedAddress): List<Coverage> {
        val lots = clean(a.costLots)
        val balances = balances(a)
        return lots.map { it.coin }.distinct().map { coin -> cover(coin, lots.filter { it.coin == coin }, balances[coin] ?: BigDecimal.ZERO) }
    }

    /**
     * Rechner-Einträge der Posten aller zählenden Adressen ([counted]): je wirksamem Posten ein Kauf mit negativer Id
     * ([tradeId]), Preis je Coin, Wallet = die Adresse ([walletName]). Reihenfolge der Adressen, dann der Posten.
     */
    fun trades(addresses: List<WatchedAddress>): List<PortfolioTrade> {
        val out = ArrayList<PortfolioTrade>()
        for ((a, balances) in counted(addresses)) {
            val lots = clean(a.costLots)
            if (lots.isEmpty()) continue
            for (coin in lots.map { it.coin }.distinct()) {
                val c = cover(coin, lots.filter { it.coin == coin }, balances[coin] ?: BigDecimal.ZERO)
                for (l in c.lots) {
                    out += PortfolioTrade(
                        id = tradeId(a.id, lots.indexOfFirst { it.id == l.id }), coin = coin, type = PortfolioTxType.BUY,
                        amount = l.amount, priceUsdt = l.price, time = l.time, exchange = walletName(a),
                    )
                }
            }
        }
        return out
    }

    /** Belegte Menge je Coin über alle zählenden Adressen (für den Rest «ohne Einstand» in [AddressBalance.trades]). */
    fun lotted(addresses: List<WatchedAddress>): Map<String, BigDecimal> {
        val out = LinkedHashMap<String, BigDecimal>()
        for ((a, balances) in counted(addresses)) {
            val lots = clean(a.costLots)
            for (coin in lots.map { it.coin }.distinct()) {
                val c = cover(coin, lots.filter { it.coin == coin }, balances[coin] ?: BigDecimal.ZERO)
                if (c.lotted.signum() > 0) out[coin] = (out[coin] ?: BigDecimal.ZERO) + c.lotted
            }
        }
        return out
    }

    /**
     * Steuer-Export: eigene Transaktionen + Eröffnungsposten als Käufe ([trades]); FR dazu die beobachteten Guthaben-Reihen
     * ([AddressBalanceHistory.trades]) für den Gesamtwert — ohne den belegten Teil ([withoutLotted]), der schon als Posten
     * zählt. CH-Vermögenswert und Stichtag lassen beides weg (Adress-Einträge).
     */
    fun exportTrades(real: List<PortfolioTrade>, addresses: List<WatchedAddress>, frTotalValue: Boolean): List<PortfolioTrade> {
        val lots = trades(addresses)
        if (!frTotalValue) return real + lots
        return real + lots + withoutLotted(AddressBalanceHistory.trades(AddressBalanceHistory.of(addresses)), lotted(addresses))
    }

    /**
     * Beobachtete Guthaben-Reihen ohne den belegten Teil [lotted] (je Coin): die ältesten Zugänge werden gekürzt (bzw.
     * fallen weg), Abgänge bleiben. Reihenfolge der Eingabe.
     */
    fun withoutLotted(history: List<PortfolioTrade>, lotted: Map<String, BigDecimal>): List<PortfolioTrade> {
        if (lotted.isEmpty()) return history
        val left = HashMap(lotted)
        val cut = HashMap<Int, BigDecimal>()
        history.withIndex().sortedWith(compareBy({ it.value.time }, { it.index })).forEach { (i, t) ->
            val coin = PortfolioCalculator.normalizeCoin(t.coin)
            val open = left[coin] ?: return@forEach
            if (t.type != PortfolioTxType.DEPOSIT || open.signum() <= 0) return@forEach
            val take = open.min(t.amount)
            left[coin] = open - take
            cut[i] = t.amount - take
        }
        return history.withIndex().mapNotNull { (i, t) ->
            val rest = cut[i] ?: return@mapNotNull t
            if (rest.signum() > 0) t.copy(amount = rest) else null
        }
    }

    /** Vorschlag Einstand gesamt = Menge × Tagesschluss (> 0); sonst null. */
    fun suggestion(amount: BigDecimal, close: BigDecimal?): BigDecimal? =
        close?.takeIf { it.signum() > 0 && amount.signum() > 0 }?.let { amount * it }

    // ---- Speicherform (Spalte, Datei, Sicherung) ----

    fun toJson(lots: List<AddressCostLot>): JSONArray {
        val array = JSONArray()
        for (l in lots) {
            val o = JSONObject()
            o.put("id", l.id)
            o.put("coin", l.coin)
            o.put("t", l.time)
            o.put("amount", PortfolioDecimal.text(l.amount))
            l.priceUsdt?.let { o.put("price", PortfolioDecimal.text(it)) }
            if (l.priceUsdt == null) l.costUsdt?.let { o.put("cost", PortfolioDecimal.text(it)) }
            array.put(o)
        }
        return array
    }

    /** Lesen; unlesbare Einträge fallen weg ([clean]). */
    fun fromJson(array: JSONArray?): List<AddressCostLot> {
        if (array == null) return emptyList()
        val out = ArrayList<AddressCostLot>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val amount = PortfolioDecimal.parse(o.opt("amount") as? String) ?: continue
            out += AddressCostLot(
                id = (o.opt("id") as? Number)?.toLong() ?: (i + 1L),
                coin = o.opt("coin") as? String ?: continue,
                time = (o.opt("t") as? Number)?.toLong() ?: continue,
                amount = amount,
                priceUsdt = PortfolioDecimal.parse(o.opt("price") as? String),
                costUsdt = PortfolioDecimal.parse(o.opt("cost") as? String),
            )
        }
        return clean(out)
    }

    /** Text der Spalte `costLots`; keine Posten = null. */
    fun encode(lots: List<AddressCostLot>): String? = lots.takeIf { it.isNotEmpty() }?.let { toJson(it).toString() }

    fun decode(text: String?): List<AddressCostLot> {
        val t = text?.trim().orEmpty()
        if (!t.startsWith("[")) return emptyList()
        return runCatching { fromJson(JSONArray(t)) }.getOrDefault(emptyList())
    }
}
