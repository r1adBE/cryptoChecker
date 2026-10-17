import Foundation

/// ERC-20-Guthaben beobachteter Ethereum-Adressen — gleich wie `Erc20Tokens.kt`: feste, kuratierte Liste (nur
/// Ethereum-Mainnet-Verträge), Abfrage `eth_call balanceOf(adr)` an dieselben öffentlichen Knoten wie das ETH-Guthaben
/// (`AddressBalance.ethRpcs`).
///
/// Eine Anfrage je Adresse: JSON-RPC-Stapel (Array) mit `eth_getBalance` (id 0) und je Token `balanceOf` (id 1…n).
/// Liefert ein Knoten keinen Stapel, fragt der Vordergrund einzeln (ETH, dann je Token) — der Hintergrund nicht.
/// `Token.symbol` = Coin im Portfolio (Kurs wie bei jedem anderen Coin).
///
/// Quelle der Adressen und Dezimalstellen (übernommen 14.10.2026): Uniswap Default Token List
/// (github.com/Uniswap/default-token-list, src/tokens/mainnet.json, chainId 1, Commit 583cb69 vom 08.10.2026);
/// nachprüfbar je Token auf etherscan.io/token/{vertrag} (Feld «Decimals»). Nicht aufgenommen: stETH (Rebasing, kein
/// eigener Börsenkurs), USDe (nicht in der Liste), Brücken-Token anderer Netze.
enum Erc20Tokens {

    /// `symbol` = Kennung und Anzeige («USDC.e»), Vertrag klein geschrieben (für die Anfrage), `decimals` laut Vertrag,
    /// `coin` = Coin im Portfolio für den Kurs (gebrückte Stablecoins wie USDC.e → USDC; gekapselte Coins wie WETH bleiben eigene Coins, `ChainAssets.wrapped`).
    struct Token: Equatable, Sendable {
        let symbol: String
        let contract: String
        let decimals: Int
        let coin: String

        init(symbol: String, contract: String, decimals: Int, coin: String? = nil) {
            self.symbol = symbol
            self.contract = contract
            self.decimals = decimals
            self.coin = coin ?? symbol
        }
    }

    static let list: [Token] = [
        Token(symbol: "USDT", contract: "0xdac17f958d2ee523a2206206994597c13d831ec7", decimals: 6),
        Token(symbol: "USDC", contract: "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", decimals: 6),
        Token(symbol: "DAI", contract: "0x6b175474e89094c44da98b954eedeac495271d0f", decimals: 18),
        Token(symbol: "WBTC", contract: "0x2260fac5e5542a773aa44fbcfedf7c193bc2c599", decimals: 8),
        Token(symbol: "LINK", contract: "0x514910771af9ca656af840dff83e8264ecf986ca", decimals: 18),
        Token(symbol: "UNI", contract: "0x1f9840a85d5af5bf1d1762f925bdaddc4201f984", decimals: 18),
        Token(symbol: "AAVE", contract: "0x7fc66500c84a76ad7e9c93437bfc5ac33e2ddae9", decimals: 18),
        Token(symbol: "PEPE", contract: "0x6982508145454ce325ddbe47a25d4ec3d2311933", decimals: 18),
        Token(symbol: "SHIB", contract: "0x95ad61b0a150d79219dcf64e1e6cc01f0b64c4ce", decimals: 18),
        Token(symbol: "POL", contract: "0x455e53cbb86018ac2b8092fdcd39d8444affc3f6", decimals: 18),
        // Alter MATIC-Vertrag (nicht umgetauscht); Kurs nur, wo MATIC noch gehandelt wird
        Token(symbol: "MATIC", contract: "0x7d1afa7b718fb893db30a3abc0cfc608aacfebb0", decimals: 18),
        Token(symbol: "ARB", contract: "0xb50721bcf8d664c30412cfbc6cf7a15145234ad1", decimals: 18),
        Token(symbol: "LDO", contract: "0x5a98fcbea516cf06857215779fd812ca3bef1b32", decimals: 18),
        Token(symbol: "MKR", contract: "0x9f8f72aa9304c8b593d555f12ef6589cc3a579a2", decimals: 18),
        Token(symbol: "ENA", contract: "0x57e114b691db790c35207b2e685d4a43181e6061", decimals: 18),
        Token(symbol: "ONDO", contract: "0xfaba6f8e4a5e8ab82f62fe7c39859fa577269be3", decimals: 18),
        Token(symbol: "APE", contract: "0x4d224452801aced8b2f0aebe155379bb5d594381", decimals: 18),
        Token(symbol: "GRT", contract: "0xc944e90c64b2c07662a292be6244bdf05cda44a7", decimals: 18),
        Token(symbol: "CRV", contract: "0xd533a949740bb3306d119cc777fa900ba034cd52", decimals: 18),
        Token(symbol: "SAND", contract: "0x3845badade8e6dff049820680d1f14bd3903a5d0", decimals: 18),
        Token(symbol: "MANA", contract: "0x0f5d2fb29fb7d3cfee444a200298f468908cc942", decimals: 18),
        Token(symbol: "ENS", contract: "0xc18360217d8f7ab5e7c516566761ea12ce7f9d72", decimals: 18),
        Token(symbol: "FET", contract: "0xaea46a60368a7bd060eec7df8cba43b7ef41ad85", decimals: 18),
        Token(symbol: "FLOKI", contract: "0xcf0c122c6b73ff809c693db761e7baebe62b6a2e", decimals: 9),
        // Gekapseltes ETH: Kurs wie ETH
        Token(symbol: "WETH", contract: "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2", decimals: 18, coin: "WETH"),
    ]

    /// Symbol → Token.
    static func bySymbol(_ symbol: String) -> Token? { list.first { $0.symbol == symbol } }

    /// Reihenfolge der Liste (Anzeige, Bestand); unbekannte Symbole ans Ende.
    static func order(_ symbol: String) -> Int { list.firstIndex { $0.symbol == symbol } ?? list.count }

    /// Funktionsauswahl `balanceOf(address)` = erste 4 Byte von keccak256("balanceOf(address)").
    static let balanceOf = "0x70a08231"

    /// Aufruf-Daten: Auswahl + Adresse auf 32 Byte links mit Nullen aufgefüllt (Adresse geprüft: 40 Hex).
    static func callData(_ address: String) -> String {
        let body = address.hasPrefix("0x") ? String(address.dropFirst(2)) : address
        return balanceOf + "000000000000000000000000" + body.lowercased()
    }

    private static func balanceOfCall(_ id: Int, _ address: String, _ token: Token) -> String {
        "{\"jsonrpc\":\"2.0\",\"id\":\(id),\"method\":\"eth_call\",\"params\":[{\"to\":\"\(token.contract)\","
            + "\"data\":\"\(callData(address))\"},\"latest\"]}"
    }

    /// Einzelanfrage `balanceOf` (Rückfall ohne Stapel).
    static func singleRequest(_ address: String, _ token: Token) -> String { balanceOfCall(1, address, token) }

    /// Stapel: `eth_getBalance` (id 0) + `balanceOf` je Token (id 1…n, Reihenfolge `tokens`; auch andere EVM-Netze).
    static func batchRequest(_ address: String, tokens: [Token] = Erc20Tokens.list) -> String {
        var out = "[{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"eth_getBalance\",\"params\":[\"\(address.lowercased())\",\"latest\"]}"
        for (i, token) in tokens.enumerated() { out += "," + balanceOfCall(i + 1, address, token) }
        return out + "]"
    }

    /// Antwort ist kein Stapel (Knoten kann keine Arrays): einzeln fragen.
    struct BatchUnsupported: LocalizedError {
        var errorDescription: String? { "kein Stapel" }
    }

    /// Ergebnis eines Stapels: Wei des ETH-Guthabens und je gelesenem Token das Guthaben als exakter Text.
    struct Batch: Equatable, Sendable {
        let weiDigits: String
        let tokens: [String: String]
    }

    /// Stapel-Antwort lesen. Kein Array → `BatchUnsupported`; ETH-Guthaben (id 0) fehlt oder ist ungültig → Fehler.
    /// Token mit Fehler oder unlesbarem Wert fehlen im Ergebnis (ihr letztes Guthaben bleibt stehen).
    static func parseBatch(_ body: String, tokens wanted: [Token] = Erc20Tokens.list) throws -> Batch {
        let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix("[") else { throw BatchUnsupported() }
        guard let data = trimmed.data(using: .utf8),
              let array = (try? JSONSerialization.jsonObject(with: data)) as? [Any]
        else { throw AddressBalance.ParseError(message: "Stapel unlesbar") }
        var byId: [Int: [String: Any]] = [:]
        for item in array {
            guard let o = item as? [String: Any], let id = o["id"] as? NSNumber, CFGetTypeID(id) != CFBooleanGetTypeID() else { continue }
            byId[id.intValue] = o
        }
        guard let eth = byId[0] else { throw AddressBalance.ParseError(message: "eth_getBalance fehlt") }
        let wei = try AddressBalance.parseEthWei(eth)
        var tokens: [String: String] = [:]
        for (i, token) in wanted.enumerated() {
            guard let o = byId[i + 1], let digits = try? units(o) else { continue }
            tokens[token.symbol] = AddressBalance.unitsText(digits, decimals: token.decimals)
        }
        return Batch(weiDigits: wei, tokens: tokens)
    }

    /// Einzelantwort `balanceOf` → Text mit den Dezimalstellen des Tokens; wirft bei Fehler.
    static func parseSingle(_ body: String, _ token: Token) throws -> String {
        guard let data = body.data(using: .utf8),
              let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        else { throw AddressBalance.ParseError(message: "kein JSON-Objekt") }
        return AddressBalance.unitsText(try units(o), decimals: token.decimals)
    }

    /// `{"result":"0x…"}` (32 Byte) → Dezimalziffern; Fehler/«0x» (kein Vertrag) → wirft.
    private static func units(_ json: [String: Any]) throws -> String {
        if let error = json["error"] as? [String: Any] {
            throw AddressBalance.ParseError(message: "RPC: " + (error["message"] as? String ?? ""))
        }
        guard let result = json["result"] as? String else { throw AddressBalance.ParseError(message: "result fehlt") }
        guard result.hasPrefix("0x"), result.count >= 3, result.count <= 66,
              let digits = AddressBalance.hexToDecimal(String(result.dropFirst(2)))
        else { throw AddressBalance.ParseError(message: "result ungültig") }
        return digits
    }
}
