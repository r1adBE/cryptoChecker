import Foundation

/// Guthaben-Abfragen der weiteren Netze (ohne Schlüssel, ohne Konto) — Anfragen und Antworten, gleich wie `ChainBalance.kt`
/// (dort beschrieben: TRON java-tron-HTTP-API, Solana JSON-RPC, Litecoin Esplora, XRP rippled `account_info`, TON Center
/// v2/v3 mit Jettons, Cardano Koios `address_info`). Der Abruf selbst steht in `WatchedAddressSource`.
enum ChainBalance {

    static let tronHosts = ["https://tron-rpc.publicnode.com", "https://api.trongrid.io"]
    static let solanaRpcs = ["https://api.mainnet.solana.com", "https://solana-rpc.publicnode.com"]
    static let ltcHosts = ["litecoinspace.org"]
    static let xrpRpcs = ["https://xrplcluster.com/", "https://s2.ripple.com:51234/"]
    static let tonHosts = ["https://toncenter.com"]
    static let adaHosts = ["https://api.koios.rest"]

    /// TON Center ohne Schlüssel: höchstens 1 Anfrage/s.
    static let tonPauseMillis: Int64 = 1_100

    /// Hosts eines Netzes (Datenschutz-Hinweis); ETH mit allen weiteren EVM-Netzen.
    static func hosts(_ chain: AddressChain) -> [String] {
        switch chain {
        case .BTC: return AddressBalance.btcHosts
        case .ETH: return (AddressBalance.ethRpcs + ChainAssets.evm.flatMap(\.rpcs)).map(host)
        case .SOL: return solanaRpcs.map(host)
        case .TRX: return tronHosts.map(host)
        case .XRP: return xrpRpcs.map(host)
        case .LTC: return ltcHosts
        case .TON: return tonHosts.map(host)
        case .ADA: return adaHosts.map(host)
        }
    }

    /// «https://s2.ripple.com:51234/» → «s2.ripple.com».
    static func host(_ entry: String) -> String {
        var h = entry.hasPrefix("https://") ? String(entry.dropFirst(8)) : entry
        if let slash = h.firstIndex(of: "/") { h = String(h[..<slash]) }
        if let colon = h.firstIndex(of: ":") { h = String(h[..<colon]) }
        return h
    }

    private static func object(_ body: String) throws -> [String: Any] {
        guard let data = body.data(using: .utf8),
              let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        else { throw AddressBalance.ParseError(message: "kein JSON-Objekt") }
        return o
    }

    /// Nicht negative ganze Zahl (kein true/false) als Ziffern; sonst nil.
    private static func digits(_ value: Any?) -> String? {
        guard let n = value as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() else { return nil }
        let d = n.doubleValue
        guard d.isFinite, d >= 0, d == d.rounded(.down) else { return nil }
        return String(n.int64Value)
    }

    private static func isDigits(_ s: String) -> Bool { !s.isEmpty && s.allSatisfy { $0.isASCII && $0.isNumber } }

    private static func trimZeros(_ s: String) -> String {
        let t = String(s.drop(while: { $0 == "0" }))
        return t.isEmpty ? "0" : t
    }

    /// Dezimalziffern addieren (Summen von Token-Konten, beliebig gross).
    static func addDigits(_ a: String, _ b: String) -> String {
        let x = Array(a.reversed()), y = Array(b.reversed())
        var out: [Character] = []
        var carry = 0
        for i in 0..<max(x.count, y.count) {
            // In Schritten mit festen Typen (verschachtelte `?:`/`??` sind für den Type-Checker teuer)
            let dx: Int = i < x.count ? (x[i].wholeNumberValue ?? 0) : 0
            let dy: Int = i < y.count ? (y[i].wholeNumberValue ?? 0) : 0
            let s: Int = dx + dy + carry
            out.append(Character(String(s % 10)))
            carry = s / 10
        }
        if carry > 0 { out.append(Character(String(carry))) }
        return trimZeros(String(out.reversed()))
    }

    // MARK: TRON

    static func tronAccountUrl(_ host: String) -> String { "\(host)/wallet/getaccount" }
    static func tronCallUrl(_ host: String) -> String { "\(host)/wallet/triggerconstantcontract" }

    static func tronAccountRequest(_ address: String) -> String { "{\"address\":\"\(address)\",\"visible\":true}" }

    /// 20-Byte-Hex der Adresse (ohne 0x41) — aus der geprüften Base58-Form.
    static func tronHex20(_ address: String) throws -> String {
        guard let raw = AddressValidation.base58Decode(address), raw.count == 25, raw[0] == 0x41 else {
            throw AddressBalance.ParseError(message: "TRON-Adresse ungültig")
        }
        return raw[1..<21].map { String(format: "%02x", $0) }.joined()
    }

    static func tronTokenRequest(_ address: String, _ token: ChainAssets.TronToken) throws -> String {
        let hex = try tronHex20(address)
        return "{\"owner_address\":\"\(address)\",\"contract_address\":\"\(token.contract)\","
            + "\"function_selector\":\"balanceOf(address)\",\"parameter\":\"000000000000000000000000\(hex)\","
            + "\"visible\":true}"
    }

    /// `getaccount` → Sun als Ziffern; `{}` (Konto noch nie benutzt) = «0».
    static func parseTronAccount(_ body: String) throws -> String {
        let json = try object(body)
        if let error = json["Error"] as? String, !error.isEmpty { throw AddressBalance.ParseError(message: "TRON: \(error)") }
        guard json["balance"] != nil else { return "0" }
        guard let sun = digits(json["balance"]) else { throw AddressBalance.ParseError(message: "balance ungültig") }
        return sun
    }

    /// `triggerconstantcontract` → Ziffern; Fehler oder leeres Ergebnis wirft.
    static func parseTronCall(_ body: String) throws -> String {
        let json = try object(body)
        let ok = (json["result"] as? [String: Any])?["result"] as? Bool == true
        guard ok, let words = json["constant_result"] as? [Any], let word = words.first as? String,
              !word.isEmpty, word.count <= 64, let value = AddressBalance.hexToDecimal(word)
        else { throw AddressBalance.ParseError(message: "TRON: kein Ergebnis") }
        return value
    }

    // MARK: Solana

    static let splToken = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    static let splToken2022 = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PE9qJkU2J9Gm2"

    static func solanaBalanceRequest(_ id: Int, _ address: String) -> String {
        "{\"jsonrpc\":\"2.0\",\"id\":\(id),\"method\":\"getBalance\",\"params\":[\"\(address)\",{\"commitment\":\"confirmed\"}]}"
    }

    static func solanaTokensRequest(_ id: Int, _ address: String, _ program: String) -> String {
        "{\"jsonrpc\":\"2.0\",\"id\":\(id),\"method\":\"getTokenAccountsByOwner\",\"params\":[\"\(address)\","
            + "{\"programId\":\"\(program)\"},{\"encoding\":\"jsonParsed\",\"commitment\":\"confirmed\"}]}"
    }

    /// Stapel: Guthaben (id 0), Token-Konten (id 1 Token-Programm, id 2 Token-2022).
    static func solanaBatch(_ address: String) -> String {
        "[" + solanaBalanceRequest(0, address) + "," + solanaTokensRequest(1, address, splToken) + ","
            + solanaTokensRequest(2, address, splToken2022) + "]"
    }

    /// Einzelanfragen (Rückfall ohne Stapel), gleiche Reihenfolge.
    static func solanaSingles(_ address: String) -> [String] {
        [solanaBalanceRequest(1, address), solanaTokensRequest(1, address, splToken), solanaTokensRequest(1, address, splToken2022)]
    }

    /// Lamports, bekannte Token (Symbol → Text) und Anzahl unbekannter Token mit Guthaben > 0.
    struct Solana: Equatable, Sendable {
        let lamports: String
        let tokens: [String: String]
        let unknown: Int
    }

    private static func rpcResult(_ o: [String: Any]) throws -> [String: Any] {
        if let error = o["error"] as? [String: Any] {
            throw AddressBalance.ParseError(message: "RPC: " + (error["message"] as? String ?? ""))
        }
        guard let result = o["result"] as? [String: Any] else { throw AddressBalance.ParseError(message: "result fehlt") }
        return result
    }

    /// `getBalance` → Lamports als Ziffern.
    static func parseSolanaBalance(_ o: [String: Any]) throws -> String {
        guard let v = digits(try rpcResult(o)["value"]) else { throw AddressBalance.ParseError(message: "value fehlt") }
        return v
    }

    /// `getTokenAccountsByOwner` → je Mint die Summe der Rohbeträge (Ziffern).
    static func parseSolanaTokens(_ o: [String: Any]) throws -> [String: String] {
        guard let accounts = try rpcResult(o)["value"] as? [Any] else { throw AddressBalance.ParseError(message: "value fehlt") }
        var sums: [String: String] = [:]
        for item in accounts {
            guard let info = ((((item as? [String: Any])?["account"] as? [String: Any])?["data"] as? [String: Any])?["parsed"]
                    as? [String: Any])?["info"] as? [String: Any],
                  let mint = info["mint"] as? String, !mint.isEmpty,
                  let amount = (info["tokenAmount"] as? [String: Any])?["amount"] as? String, isDigits(amount)
            else { continue }
            sums[mint] = addDigits(sums[mint] ?? "0", amount)
        }
        return sums
    }

    static func parseSolanaBalanceBody(_ body: String) throws -> String { try parseSolanaBalance(try object(body)) }

    static func parseSolanaTokensBody(_ body: String) throws -> [String: String] { try parseSolanaTokens(try object(body)) }

    /// Ergebnisse zusammenführen: bekannte Mints nach `ChainAssets.solanaTokens`; unbekannte > 0 nur gezählt.
    static func solana(lamports: String, mints: [[String: String]]) -> Solana {
        var total: [String: String] = [:]
        for m in mints { for (mint, value) in m { total[mint] = addDigits(total[mint] ?? "0", value) } }
        var tokens: [String: String] = [:]
        var unknown = 0
        for (mint, sum) in total {
            guard let token = ChainAssets.solanaTokens.first(where: { $0.mint == mint }) else {
                if sum != "0" { unknown += 1 }
                continue
            }
            tokens[token.symbol] = AddressBalance.unitsText(sum, decimals: token.decimals)
        }
        return Solana(lamports: lamports, tokens: tokens, unknown: unknown)
    }

    /// Stapel-Antwort; kein Array → `Erc20Tokens.BatchUnsupported`. Token-Fehler = Fehler (sonst fehlte ein Teil).
    static func parseSolanaBatch(_ body: String) throws -> Solana {
        let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix("[") else { throw Erc20Tokens.BatchUnsupported() }
        guard let data = trimmed.data(using: .utf8), let array = (try? JSONSerialization.jsonObject(with: data)) as? [Any]
        else { throw AddressBalance.ParseError(message: "Stapel unlesbar") }
        var byId: [Int: [String: Any]] = [:]
        for item in array {
            guard let o = item as? [String: Any], let id = o["id"] as? NSNumber, CFGetTypeID(id) != CFBooleanGetTypeID() else { continue }
            byId[id.intValue] = o
        }
        guard let balance = byId[0], let legacy = byId[1], let t2022 = byId[2] else {
            throw AddressBalance.ParseError(message: "Teil fehlt")
        }
        return solana(lamports: try parseSolanaBalance(balance),
                      mints: [try parseSolanaTokens(legacy), try parseSolanaTokens(t2022)])
    }

    // MARK: Litecoin

    static func ltcUrl(host: String, address: String) -> String { "https://\(host)/api/address/\(address)" }

    // MARK: XRP

    static func xrpRequest(_ address: String) -> String {
        "{\"method\":\"account_info\",\"params\":[{\"account\":\"\(address)\",\"ledger_index\":\"validated\"}]}"
    }

    /// `account_info` → Drops als Ziffern; Konto nicht aktiviert (`actNotFound`) = «0».
    static func parseXrp(_ body: String) throws -> String {
        guard let result = try object(body)["result"] as? [String: Any] else {
            throw AddressBalance.ParseError(message: "result fehlt")
        }
        let error = result["error"] as? String ?? ""
        if error == "actNotFound" { return "0" }
        if !error.isEmpty { throw AddressBalance.ParseError(message: "XRPL: \(error)") }
        guard let drops = (result["account_data"] as? [String: Any])?["Balance"] as? String, drops.count <= 20, isDigits(drops)
        else { throw AddressBalance.ParseError(message: "Balance ungültig") }
        return trimZeros(drops)
    }

    // MARK: TON

    static func tonUrl(host: String, address: String) -> String { "\(host)/api/v2/getAddressBalance?address=" + percent(address) }

    /// Nur Buchstaben, Ziffern, «-», «_», «.» unverändert (Adresse geprüft: Base64(url) oder «0:hex»).
    static func percent(_ s: String) -> String {
        var out = ""
        for byte in Array(s.utf8) {
            let c = Character(UnicodeScalar(byte))
            if (byte >= 97 && byte <= 122) || (byte >= 65 && byte <= 90) || (byte >= 48 && byte <= 57) || c == "-" || c == "_" || c == "." {
                out.append(c)
            } else {
                out += String(format: "%%%02X", byte)
            }
        }
        return out
    }

    /// `{"ok":true,"result":"123"}` → Nanoton als Ziffern.
    static func parseTon(_ body: String) throws -> String {
        let json = try object(body)
        guard json["ok"] as? Bool == true else {
            throw AddressBalance.ParseError(message: "TON: " + (json["error"] as? String ?? ""))
        }
        let result: String
        if let text = json["result"] as? String {
            result = text
        } else if let n = digits(json["result"]) {
            result = n
        } else {
            throw AddressBalance.ParseError(message: "result fehlt")
        }
        guard isDigits(result) else { throw AddressBalance.ParseError(message: "result ungültig") }
        return trimZeros(result)
    }

    /// Höchstens so viele Jetton-Wallets je Anfrage (eine Seite, ohne Blättern).
    static let tonJettonLimit = 50

    /// Jetton-Wallets eines Besitzers (v3); höchstens `tonJettonLimit`, Guthaben 0 ausgeschlossen.
    static func tonJettonsUrl(host: String, address: String) -> String {
        "\(host)/api/v3/jetton/wallets?owner_address=" + percent(address)
            + "&exclude_zero_balance=true&limit=\(tonJettonLimit)&offset=0"
    }

    /// TON-Adresse (roh «0:hex» oder benutzerfreundlich) → 64 Hex klein; unlesbar = nil.
    static func tonRawHex(_ address: String) -> String? {
        if let sep = address.firstIndex(of: ":") {
            let hex = String(address[address.index(after: sep)...])
            guard address[..<sep] == "0", hex.count == 64, hex.allSatisfy({ $0.isHexDigit }) else { return nil }
            return hex.lowercased()
        }
        guard let raw = AddressValidation.base64Decode(address), raw.count == 36, raw[1] == 0 else { return nil }
        return raw[2..<34].map { String(format: "%02x", $0) }.joined()
    }

    /// `{"jetton_wallets":[{"balance":"…","jetton":"0:…"}]}` → bekannte Jettons (Symbol → Text), Summe je Jetton. Volle
    /// Seite (`tonJettonLimit` Einträge): ein bekannter Jetton, der fehlt, kann auf der nächsten Seite stehen — er fehlt dann
    /// im Ergebnis (unbekannt, der letzte Wert bleibt) statt als 0 zu gelten. Wie Android.
    static func parseTonJettons(_ body: String) throws -> [String: String] {
        guard let wallets = try object(body)["jetton_wallets"] as? [Any] else {
            throw AddressBalance.ParseError(message: "jetton_wallets fehlt")
        }
        var sums: [String: String] = [:]
        for item in wallets {
            guard let w = item as? [String: Any], let master = tonRawHex(w["jetton"] as? String ?? ""),
                  let jetton = ChainAssets.tonJettons.first(where: { $0.rawHex == master }),
                  let balance = w["balance"] as? String, isDigits(balance) else { continue }
            sums[jetton.symbol] = addDigits(sums[jetton.symbol] ?? "0", balance)
        }
        let full = wallets.count >= tonJettonLimit
        var out: [String: String] = [:]
        for j in ChainAssets.tonJettons {
            guard let sum = sums[j.symbol] ?? (full ? nil : "0") else { continue }
            out[j.symbol] = AddressBalance.unitsText(sum, decimals: j.decimals)
        }
        return out
    }

    // MARK: Cardano

    static func adaUrl(_ host: String) -> String { "\(host)/api/v1/address_info" }

    static func adaRequest(_ address: String) -> String { "{\"_addresses\":[\"\(address)\"]}" }

    /// `[{"address":"…","balance":"123",…}]` → Lovelace als Ziffern; leeres Array (nie benutzt) = «0».
    static func parseAda(_ body: String, address: String) throws -> String {
        guard let data = body.data(using: .utf8), let array = (try? JSONSerialization.jsonObject(with: data)) as? [Any] else {
            throw AddressBalance.ParseError(message: "kein JSON-Array")
        }
        for item in array {
            guard let o = item as? [String: Any], o["address"] as? String == address else { continue }
            guard let balance = o["balance"] as? String, isDigits(balance) else {
                throw AddressBalance.ParseError(message: "balance ungültig")
            }
            return trimZeros(balance)
        }
        if array.isEmpty { return "0" }
        throw AddressBalance.ParseError(message: "Antwort für andere Adresse")
    }
}
