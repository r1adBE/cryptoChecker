package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.util.json.JsonObject
import com.cryptochecker.app.util.json.JsonArray
import java.math.BigInteger

/**
 * Guthaben-Abfragen der weiteren Netze (ohne Schlüssel, ohne Konto) — Anfragen und Antworten, reines Kotlin, gleich wie
 * `ChainBalance.swift`. Der Abruf selbst steht in `WatchedAddressSource` (Pausen, Rückfall auf den zweiten Host).
 *
 *  - TRON: java-tron-HTTP-API `POST /wallet/getaccount` (TRX in Sun) und je TRC-20 `POST /wallet/triggerconstantcontract`
 *    `balanceOf(address)` — zuerst PublicNode, sonst TronGrid (ohne Schlüssel nur wenige Anfragen erlaubt).
 *  - Solana: JSON-RPC `getBalance` + `getTokenAccountsByOwner` (Token-Programm und Token-2022, `jsonParsed`) als Stapel —
 *    zuerst der öffentliche Solana-Knoten, sonst PublicNode.
 *  - Litecoin: Esplora-API von litecoinspace.org (gleiches Format wie mempool.space, [AddressBalance.parseBtc]).
 *  - XRP: rippled JSON-RPC `account_info` (validierter Ledger) — xrplcluster.com, sonst Ripple s2.
 *  - TON: TON Center API v2 `getAddressBalance`, Jettons über v3 `jetton/wallets` (ohne Schlüssel 1 Anfrage/s — zwischen
 *    beiden Anfragen eine Pause).
 *  - Cardano: Koios `POST /api/v1/address_info` (Public Tier ohne Schlüssel, 5000 Anfragen/Tag); nur ADA, native Assets
 *    zählen nicht. Format laut koios-artifacts (specs/results/koiosapi-mainnet.yaml, Commit 2e2eb57).
 */
object ChainBalance {

    val TRON_HOSTS: List<String> = listOf("https://tron-rpc.publicnode.com", "https://api.trongrid.io")
    val SOLANA_RPCS: List<String> = listOf("https://api.mainnet.solana.com", "https://solana-rpc.publicnode.com")
    val LTC_HOSTS: List<String> = listOf("litecoinspace.org")
    val XRP_RPCS: List<String> = listOf("https://xrplcluster.com/", "https://s2.ripple.com:51234/")
    val TON_HOSTS: List<String> = listOf("https://toncenter.com")
    val ADA_HOSTS: List<String> = listOf("https://api.koios.rest")

    /** TON Center ohne Schlüssel: höchstens 1 Anfrage/s. */
    const val TON_PAUSE_MILLIS = 1_100L

    /** Hosts eines Netzes (Datenschutz-Hinweis); ETH mit allen weiteren EVM-Netzen. */
    fun hosts(chain: AddressChain): List<String> = when (chain) {
        AddressChain.BTC -> AddressBalance.BTC_HOSTS
        AddressChain.ETH -> (AddressBalance.ETH_RPCS + ChainAssets.EVM.flatMap { it.rpcs }).map { host(it) }
        AddressChain.SOL -> SOLANA_RPCS.map { host(it) }
        AddressChain.TRX -> TRON_HOSTS.map { host(it) }
        AddressChain.XRP -> XRP_RPCS.map { host(it) }
        AddressChain.LTC -> LTC_HOSTS
        AddressChain.TON -> TON_HOSTS.map { host(it) }
        AddressChain.ADA -> ADA_HOSTS.map { host(it) }
    }

    /** «https://s2.ripple.com:51234/» → «s2.ripple.com». */
    fun host(entry: String): String = entry.removePrefix("https://").substringBefore('/').substringBefore(':')

    // ---------------- TRON ----------------

    fun tronAccountUrl(host: String): String = "$host/wallet/getaccount"
    fun tronCallUrl(host: String): String = "$host/wallet/triggerconstantcontract"

    fun tronAccountRequest(address: String): String = "{\"address\":\"$address\",\"visible\":true}"

    /** 20-Byte-Hex der Adresse (ohne 0x41) — aus der geprüften Base58-Form. */
    fun tronHex20(address: String): String {
        val raw = AddressValidation.base58Decode(address) ?: throw IllegalArgumentException("TRON-Adresse ungültig")
        require(raw.size == 25 && (raw[0].toInt() and 0xff) == 0x41) { "TRON-Adresse ungültig" }
        return raw.copyOfRange(1, 21).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun tronTokenRequest(address: String, token: ChainAssets.TronToken): String =
        "{\"owner_address\":\"$address\",\"contract_address\":\"${token.contract}\"," +
            "\"function_selector\":\"balanceOf(address)\",\"parameter\":\"000000000000000000000000${tronHex20(address)}\"," +
            "\"visible\":true}"

    /** `getaccount` → Sun als Ziffern; `{}` (Konto noch nie benutzt) = «0». */
    fun parseTronAccount(body: String): String {
        val json = JsonObject(body)
        json.optString("Error").takeIf { it.isNotEmpty() }?.let { throw IllegalStateException("TRON: $it") }
        if (!json.has("balance")) return "0"
        val v = json.opt("balance") as? Number ?: throw IllegalStateException("balance ungültig")
        val d = v.toDouble()
        if (!d.isFinite() || d < 0 || d != Math.floor(d)) throw IllegalStateException("balance ungültig")
        return v.toLong().toString()
    }

    /** `triggerconstantcontract` → Ziffern; Fehler oder leeres Ergebnis wirft. */
    fun parseTronCall(body: String): String {
        val json = JsonObject(body)
        val ok = json.optJSONObject("result")?.optBoolean("result") == true
        val words = json.optJSONArray("constant_result")
        if (!ok || words == null || words.length() == 0) throw IllegalStateException("TRON: kein Ergebnis")
        val word = words.optString(0)
        if (word.isEmpty() || word.length > 64) throw IllegalStateException("TRON: Ergebnis ungültig")
        return AddressBalance.hexToDecimal(word) ?: throw IllegalStateException("TRON: Ergebnis ungültig")
    }

    // ---------------- Solana ----------------

    const val SPL_TOKEN = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    const val SPL_TOKEN_2022 = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PE9qJkU2J9Gm2"

    fun solanaBalanceRequest(id: Int, address: String): String =
        "{\"jsonrpc\":\"2.0\",\"id\":$id,\"method\":\"getBalance\",\"params\":[\"$address\",{\"commitment\":\"confirmed\"}]}"

    fun solanaTokensRequest(id: Int, address: String, program: String): String =
        "{\"jsonrpc\":\"2.0\",\"id\":$id,\"method\":\"getTokenAccountsByOwner\",\"params\":[\"$address\"," +
            "{\"programId\":\"$program\"},{\"encoding\":\"jsonParsed\",\"commitment\":\"confirmed\"}]}"

    /** Stapel: Guthaben (id 0), Token-Konten (id 1 Token-Programm, id 2 Token-2022). */
    fun solanaBatch(address: String): String =
        "[" + solanaBalanceRequest(0, address) + "," + solanaTokensRequest(1, address, SPL_TOKEN) + "," +
            solanaTokensRequest(2, address, SPL_TOKEN_2022) + "]"

    /** Einzelanfragen (Rückfall ohne Stapel), gleiche Reihenfolge. */
    fun solanaSingles(address: String): List<String> = listOf(
        solanaBalanceRequest(1, address), solanaTokensRequest(1, address, SPL_TOKEN), solanaTokensRequest(1, address, SPL_TOKEN_2022),
    )

    /** Lamports, bekannte Token (Symbol → Text) und Anzahl unbekannter Token mit Guthaben > 0. */
    data class Solana(val lamports: String, val tokens: Map<String, String>, val unknown: Int)

    private fun rpcResult(o: JsonObject): Any {
        o.optJSONObject("error")?.let { throw IllegalStateException("RPC: " + it.optString("message")) }
        return o.opt("result") ?: throw IllegalStateException("result fehlt")
    }

    /** `getBalance` → Lamports als Ziffern. */
    fun parseSolanaBalance(o: JsonObject): String {
        val result = rpcResult(o) as? JsonObject ?: throw IllegalStateException("result ungültig")
        val v = result.opt("value") as? Number ?: throw IllegalStateException("value fehlt")
        if (v.toDouble() < 0) throw IllegalStateException("value ungültig")
        return v.toLong().toString()
    }

    /** `getTokenAccountsByOwner` → je Mint die Summe der Rohbeträge (Ziffern). */
    fun parseSolanaTokens(o: JsonObject): Map<String, String> {
        val result = rpcResult(o) as? JsonObject ?: throw IllegalStateException("result ungültig")
        val accounts = result.optJSONArray("value") ?: throw IllegalStateException("value fehlt")
        val sums = LinkedHashMap<String, BigInteger>()
        for (i in 0 until accounts.length()) {
            val info = accounts.optJSONObject(i)?.optJSONObject("account")?.optJSONObject("data")
                ?.optJSONObject("parsed")?.optJSONObject("info") ?: continue
            val mint = info.optString("mint").takeIf { it.isNotEmpty() } ?: continue
            val amount = info.optJSONObject("tokenAmount")?.optString("amount")?.takeIf { s -> s.isNotEmpty() && s.all { it in '0'..'9' } } ?: continue
            sums[mint] = (sums[mint] ?: BigInteger.ZERO) + BigInteger(amount)
        }
        return sums.mapValues { it.value.toString() }
    }

    /** Einzelantwort `getBalance` (Rückfall ohne Stapel). */
    fun parseSolanaBalanceBody(body: String): String = parseSolanaBalance(JsonObject(body))

    /** Einzelantwort `getTokenAccountsByOwner` (Rückfall ohne Stapel). */
    fun parseSolanaTokensBody(body: String): Map<String, String> = parseSolanaTokens(JsonObject(body))

    /** Ergebnisse zusammenführen: bekannte Mints nach [ChainAssets.SOLANA_TOKENS]; unbekannte > 0 nur gezählt. */
    fun solana(lamports: String, mints: List<Map<String, String>>): Solana {
        val total = LinkedHashMap<String, BigInteger>()
        for (m in mints) for ((mint, digits) in m) total[mint] = (total[mint] ?: BigInteger.ZERO) + BigInteger(digits)
        val tokens = LinkedHashMap<String, String>()
        var unknown = 0
        for ((mint, sum) in total) {
            val token = ChainAssets.SOLANA_TOKENS.firstOrNull { it.mint == mint }
            if (token == null) {
                if (sum.signum() > 0) unknown++
                continue
            }
            tokens[token.symbol] = AddressBalance.unitsText(sum.toString(), token.decimals)
        }
        return Solana(lamports, tokens, unknown)
    }

    /** Stapel-Antwort; kein Array → [Erc20Tokens.BatchUnsupported]. Token-Fehler = Fehler (sonst fehlte ein Teil). */
    fun parseSolanaBatch(body: String): Solana {
        val trimmed = body.trim()
        if (!trimmed.startsWith("[")) throw Erc20Tokens.BatchUnsupported("kein Stapel")
        val array = JsonArray(trimmed)
        val byId = HashMap<Int, JsonObject>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = (o.opt("id") as? Number)?.toInt() ?: continue
            byId[id] = o
        }
        val lamports = parseSolanaBalance(byId[0] ?: throw IllegalStateException("getBalance fehlt"))
        val legacy = parseSolanaTokens(byId[1] ?: throw IllegalStateException("Token-Konten fehlen"))
        val t2022 = parseSolanaTokens(byId[2] ?: throw IllegalStateException("Token-2022-Konten fehlen"))
        return solana(lamports, listOf(legacy, t2022))
    }

    // ---------------- Litecoin ----------------

    fun ltcUrl(host: String, address: String): String = "https://$host/api/address/$address"

    // ---------------- XRP ----------------

    fun xrpRequest(address: String): String =
        "{\"method\":\"account_info\",\"params\":[{\"account\":\"$address\",\"ledger_index\":\"validated\"}]}"

    /** `account_info` → Drops als Ziffern; Konto nicht aktiviert (`actNotFound`) = «0». */
    fun parseXrp(body: String): String {
        val result = JsonObject(body).optJSONObject("result") ?: throw IllegalStateException("result fehlt")
        val error = result.optString("error")
        if (error == "actNotFound") return "0"
        if (error.isNotEmpty()) throw IllegalStateException("XRPL: $error")
        val drops = result.optJSONObject("account_data")?.opt("Balance") as? String ?: throw IllegalStateException("Balance fehlt")
        if (drops.isEmpty() || drops.length > 20 || !drops.all { it in '0'..'9' }) throw IllegalStateException("Balance ungültig")
        return drops.trimStart('0').ifEmpty { "0" }
    }

    // ---------------- TON ----------------

    fun tonUrl(host: String, address: String): String = "$host/api/v2/getAddressBalance?address=" + percent(address)

    /** Nur Buchstaben, Ziffern, «-», «_», «.» unverändert (Adresse geprüft: Base64(url) oder «0:hex»). */
    fun percent(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_' || c == '.') sb.append(c)
            else for (b in c.toString().toByteArray(Charsets.UTF_8)) sb.append('%').append("%02X".format(b.toInt() and 0xff))
        }
        return sb.toString()
    }

    /** `{"ok":true,"result":"123"}` → Nanoton als Ziffern. */
    fun parseTon(body: String): String {
        val json = JsonObject(body)
        if (json.opt("ok") != true) throw IllegalStateException("TON: " + json.optString("error"))
        val result = when (val r = json.opt("result")) {
            null -> throw IllegalStateException("result fehlt")
            is String -> r
            is Number -> r.toLong().toString()
            else -> throw IllegalStateException("result fehlt")
        }
        if (result.isEmpty() || !result.all { it in '0'..'9' }) throw IllegalStateException("result ungültig")
        return result.trimStart('0').ifEmpty { "0" }
    }

    /** Höchstens so viele Jetton-Wallets je Anfrage (eine Seite, ohne Blättern). */
    const val TON_JETTON_LIMIT = 50

    /** Jetton-Wallets eines Besitzers (v3); höchstens [TON_JETTON_LIMIT], Guthaben 0 ausgeschlossen. */
    fun tonJettonsUrl(host: String, address: String): String =
        "$host/api/v3/jetton/wallets?owner_address=" + percent(address) + "&exclude_zero_balance=true&limit=$TON_JETTON_LIMIT&offset=0"

    /** TON-Adresse (roh «0:hex» oder benutzerfreundlich) → 64 Hex klein; unlesbar = null. */
    fun tonRawHex(address: String): String? {
        val sep = address.indexOf(':')
        if (sep >= 0) {
            val hex = address.substring(sep + 1)
            return if (address.substring(0, sep) == "0" && hex.length == 64 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) hex.lowercase() else null
        }
        val raw = AddressValidation.base64Decode(address) ?: return null
        if (raw.size != 36 || raw[1].toInt() != 0) return null
        return raw.copyOfRange(2, 34).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /**
     * `{"jetton_wallets":[{"balance":"…","jetton":"0:…"}]}` → bekannte Jettons (Symbol → Text), Summe je Jetton. Volle
     * Seite ([TON_JETTON_LIMIT] Einträge): ein bekannter Jetton, der fehlt, kann auf der nächsten Seite stehen — er fehlt
     * dann im Ergebnis (unbekannt, der letzte Wert bleibt) statt als 0 zu gelten; siehe [parseTonJettonPage].
     */
    fun parseTonJettons(body: String): Map<String, String> = parseTonJettonPage(body).tokens

    /**
     * Gelesene Jetton-Seite: [tokens] wie [parseTonJettons], [kept] = bekannte Jettons, die auf der vollen Seite fehlen
     * (Reihenfolge der Liste). Ihr alter Wert bleibt, gilt aber als nicht bestätigt
     * ([WatchedAddress.staleTokens]) — sonst bliebe er bei dauerhaft voller Seite für immer stehen.
     */
    data class TonJettonPage(val tokens: Map<String, String>, val kept: List<String>)

    fun parseTonJettonPage(body: String): TonJettonPage {
        val wallets = JsonObject(body).optJSONArray("jetton_wallets") ?: throw IllegalStateException("jetton_wallets fehlt")
        val sums = LinkedHashMap<String, BigInteger>()
        for (i in 0 until wallets.length()) {
            val w = wallets.optJSONObject(i) ?: continue
            val master = tonRawHex(w.optString("jetton")) ?: continue
            val jetton = ChainAssets.TON_JETTONS.firstOrNull { it.rawHex == master } ?: continue
            val balance = w.optString("balance").takeIf { s -> s.isNotEmpty() && s.all { it in '0'..'9' } } ?: continue
            sums[jetton.symbol] = (sums[jetton.symbol] ?: BigInteger.ZERO) + BigInteger(balance)
        }
        val full = wallets.length() >= TON_JETTON_LIMIT
        val out = LinkedHashMap<String, String>()
        val kept = ArrayList<String>()
        for (j in ChainAssets.TON_JETTONS) {
            val sum = sums[j.symbol] ?: if (full) null else BigInteger.ZERO
            if (sum == null) {
                kept += j.symbol
                continue
            }
            out[j.symbol] = AddressBalance.unitsText(sum.toString(), j.decimals)
        }
        return TonJettonPage(out, kept)
    }

    // ---------------- Cardano ----------------

    fun adaUrl(host: String): String = "$host/api/v1/address_info"

    fun adaRequest(address: String): String = "{\"_addresses\":[\"$address\"]}"

    /** `[{"address":"…","balance":"123",…}]` → Lovelace als Ziffern; leeres Array (nie benutzt) = «0». */
    fun parseAda(body: String, address: String): String {
        val array = JsonArray(body.trim())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            if (o.optString("address") != address) continue
            val balance = o.opt("balance") as? String ?: throw IllegalStateException("balance fehlt")
            if (balance.isEmpty() || !balance.all { it in '0'..'9' }) throw IllegalStateException("balance ungültig")
            return balance.trimStart('0').ifEmpty { "0" }
        }
        if (array.length() == 0) return "0"
        throw IllegalStateException("Antwort für andere Adresse")
    }
}
