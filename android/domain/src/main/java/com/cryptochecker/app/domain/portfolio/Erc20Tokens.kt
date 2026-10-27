package com.cryptochecker.app.domain.portfolio

import org.json.JSONArray
import org.json.JSONObject

/**
 * ERC-20-Guthaben beobachteter Ethereum-Adressen: feste, kuratierte Liste (nur Ethereum-Mainnet-Verträge), Abfrage
 * `eth_call balanceOf(adr)` an dieselben öffentlichen Knoten wie das ETH-Guthaben ([AddressBalance.ETH_RPCS]).
 *
 * Eine Anfrage je Adresse: JSON-RPC-Stapel (Array) mit `eth_getBalance` (id 0) und je Token `balanceOf` (id 1…n).
 * Liefert ein Knoten keinen Stapel, fragt der Vordergrund einzeln (ETH, dann je Token) — der Hintergrund nicht.
 * [Token.symbol] = Coin im Portfolio (Kurs wie bei jedem anderen Coin).
 *
 * Quelle der Adressen und Dezimalstellen (übernommen 14.10.2026): Uniswap Default Token List
 * (github.com/Uniswap/default-token-list, src/tokens/mainnet.json, chainId 1, Commit 583cb69 vom 08.10.2026);
 * nachprüfbar je Token auf etherscan.io/token/{vertrag} (Feld «Decimals»). Nicht aufgenommen: stETH (Rebasing,
 * kein eigener Börsenkurs), USDe (nicht in der Liste), Brücken-Token anderer Netze.
 * Reines Kotlin, gleich wie `Erc20Tokens.swift`.
 */
object Erc20Tokens {

    /**
     * [symbol] = Kennung und Anzeige («USDC.e»), Vertrag klein geschrieben (für die Anfrage), [decimals] laut Vertrag,
     * [coin] = Coin im Portfolio für den Kurs (gebrückte Stablecoins wie USDC.e → USDC; gekapselte Coins wie WETH bleiben eigene Coins, [ChainAssets.WRAPPED]).
     */
    data class Token(val symbol: String, val contract: String, val decimals: Int, val coin: String = symbol)

    val LIST: List<Token> = listOf(
        Token("USDT", "0xdac17f958d2ee523a2206206994597c13d831ec7", 6),
        Token("USDC", "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", 6),
        Token("DAI", "0x6b175474e89094c44da98b954eedeac495271d0f", 18),
        Token("WBTC", "0x2260fac5e5542a773aa44fbcfedf7c193bc2c599", 8),
        Token("LINK", "0x514910771af9ca656af840dff83e8264ecf986ca", 18),
        Token("UNI", "0x1f9840a85d5af5bf1d1762f925bdaddc4201f984", 18),
        Token("AAVE", "0x7fc66500c84a76ad7e9c93437bfc5ac33e2ddae9", 18),
        Token("PEPE", "0x6982508145454ce325ddbe47a25d4ec3d2311933", 18),
        Token("SHIB", "0x95ad61b0a150d79219dcf64e1e6cc01f0b64c4ce", 18),
        Token("POL", "0x455e53cbb86018ac2b8092fdcd39d8444affc3f6", 18),
        // Alter MATIC-Vertrag (nicht umgetauscht); Kurs nur, wo MATIC noch gehandelt wird
        Token("MATIC", "0x7d1afa7b718fb893db30a3abc0cfc608aacfebb0", 18),
        Token("ARB", "0xb50721bcf8d664c30412cfbc6cf7a15145234ad1", 18),
        Token("LDO", "0x5a98fcbea516cf06857215779fd812ca3bef1b32", 18),
        Token("MKR", "0x9f8f72aa9304c8b593d555f12ef6589cc3a579a2", 18),
        Token("ENA", "0x57e114b691db790c35207b2e685d4a43181e6061", 18),
        Token("ONDO", "0xfaba6f8e4a5e8ab82f62fe7c39859fa577269be3", 18),
        Token("APE", "0x4d224452801aced8b2f0aebe155379bb5d594381", 18),
        Token("GRT", "0xc944e90c64b2c07662a292be6244bdf05cda44a7", 18),
        Token("CRV", "0xd533a949740bb3306d119cc777fa900ba034cd52", 18),
        Token("SAND", "0x3845badade8e6dff049820680d1f14bd3903a5d0", 18),
        Token("MANA", "0x0f5d2fb29fb7d3cfee444a200298f468908cc942", 18),
        Token("ENS", "0xc18360217d8f7ab5e7c516566761ea12ce7f9d72", 18),
        Token("FET", "0xaea46a60368a7bd060eec7df8cba43b7ef41ad85", 18),
        Token("FLOKI", "0xcf0c122c6b73ff809c693db761e7baebe62b6a2e", 9),
        // Gekapseltes ETH: Kurs wie ETH
        Token("WETH", "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2", 18),
    )

    /** Symbol → Token. */
    fun bySymbol(symbol: String): Token? = LIST.firstOrNull { it.symbol == symbol }

    /** Reihenfolge der Liste (Anzeige, Bestand); unbekannte Symbole ans Ende. */
    fun order(symbol: String): Int = LIST.indexOfFirst { it.symbol == symbol }.let { if (it < 0) LIST.size else it }

    /** Funktionsauswahl `balanceOf(address)` = erste 4 Byte von keccak256("balanceOf(address)"). */
    const val BALANCE_OF = "0x70a08231"

    /** Aufruf-Daten: Auswahl + Adresse auf 32 Byte links mit Nullen aufgefüllt (Adresse geprüft: 40 Hex). */
    fun callData(address: String): String = BALANCE_OF + "000000000000000000000000" + address.removePrefix("0x").lowercase()

    private fun balanceOfCall(id: Int, address: String, token: Token): String =
        "{\"jsonrpc\":\"2.0\",\"id\":$id,\"method\":\"eth_call\",\"params\":[{\"to\":\"${token.contract}\"," +
            "\"data\":\"${callData(address)}\"},\"latest\"]}"

    /** Einzelanfrage `balanceOf` (Rückfall ohne Stapel). */
    fun singleRequest(address: String, token: Token): String = balanceOfCall(1, address, token)

    /** Stapel: `eth_getBalance` (id 0) + `balanceOf` je Token (id 1…n, Reihenfolge [tokens]; auch andere EVM-Netze). */
    fun batchRequest(address: String, tokens: List<Token> = LIST): String {
        val sb = StringBuilder("[")
        sb.append("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"eth_getBalance\",\"params\":[\"${address.lowercase()}\",\"latest\"]}")
        tokens.forEachIndexed { i, token -> sb.append(',').append(balanceOfCall(i + 1, address, token)) }
        return sb.append(']').toString()
    }

    /** Antwort ist kein Stapel (Knoten kann keine Arrays): einzeln fragen. */
    class BatchUnsupported(message: String) : Exception(message)

    /** Ergebnis eines Stapels: Wei des ETH-Guthabens und je gelesenem Token das Guthaben als exakter Text. */
    data class Batch(val weiDigits: String, val tokens: Map<String, String>)

    /**
     * Stapel-Antwort lesen. Kein Array → [BatchUnsupported]; ETH-Guthaben (id 0) fehlt oder ist ungültig → Fehler.
     * Token mit Fehler oder unlesbarem Wert fehlen im Ergebnis (ihr letztes Guthaben bleibt stehen).
     */
    fun parseBatch(body: String, list: List<Token> = LIST): Batch {
        val trimmed = body.trim()
        if (!trimmed.startsWith("[")) throw BatchUnsupported("kein Stapel")
        val array = JSONArray(trimmed)
        val byId = HashMap<Int, JSONObject>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = (o.opt("id") as? Number)?.toInt() ?: continue
            byId[id] = o
        }
        val eth = byId[0] ?: throw IllegalStateException("eth_getBalance fehlt")
        val wei = AddressBalance.parseEthWei(eth.toString())
        val tokens = LinkedHashMap<String, String>()
        list.forEachIndexed { i, token ->
            val o = byId[i + 1] ?: return@forEachIndexed
            val digits = runCatching { parseUnits(o.toString()) }.getOrNull() ?: return@forEachIndexed
            tokens[token.symbol] = AddressBalance.unitsText(digits, token.decimals)
        }
        return Batch(wei, tokens)
    }

    /** Einzelantwort `balanceOf` → Text mit den Dezimalstellen des Tokens; wirft bei Fehler. */
    fun parseSingle(body: String, token: Token): String = AddressBalance.unitsText(parseUnits(body), token.decimals)

    /** `{"result":"0x…"}` (32 Byte) → Dezimalziffern; Fehler/«0x» (kein Vertrag) → wirft. */
    fun parseUnits(body: String): String {
        val json = JSONObject(body)
        json.optJSONObject("error")?.let { throw IllegalStateException("RPC: " + it.optString("message")) }
        val result = json.opt("result") as? String ?: throw IllegalStateException("result fehlt")
        if (!result.startsWith("0x") || result.length < 3 || result.length > 66) throw IllegalStateException("result ungültig")
        return AddressBalance.hexToDecimal(result.substring(2)) ?: throw IllegalStateException("result ungültig")
    }
}
