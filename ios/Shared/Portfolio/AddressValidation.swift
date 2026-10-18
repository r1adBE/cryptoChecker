import CryptoKit
import Foundation

/// Prüft öffentliche Adressen für «Adresse beobachten» (nur Mainnet, nur einzelne Adressen) — gleich wie
/// `AddressValidation.kt`:
///  - Bitcoin Base58Check: P2PKH («1…», Version 0x00), P2SH («3…», 0x05), Prüfsumme = doppeltes SHA-256.
///  - Bitcoin Bech32/Bech32m (BIP173/BIP350, «bc1…»): v0 P2WPKH (20 Byte) / P2WSH (32 Byte) mit Bech32,
///    v1 Taproot (32 Byte) mit Bech32m. Spätere Segwit-Versionen: `.UNSUPPORTED_VERSION`.
///  - Ethereum: «0x» + 40 Hex; gemischte Schreibweise muss der EIP-55-Prüfsumme entsprechen
///    (Keccak-256), nur klein oder nur gross wird ohne Prüfsumme angenommen.
///  - Erweiterte öffentliche Schlüssel xpub/ypub/zpub (BIP32, nur Mainnet, `Bip32`): Prüfsumme, Version und
///    Kurvenpunkt; Ergebnis unverändert (`.XPUB`/`.YPUB`/`.ZPUB`). Multisig (Ypub/Zpub): `.EXTENDED_KEY`.
///  - Taproot-Konto als Deskriptor (BIP86/BIP380/BIP389): `tr(` + optionale Herkunft `[fingerprint/86'/0'/0']` + xpub +
///    Kette `/0/`, `/1/` oder `/<0;1>/` mit Platzhalter, optional `#prüfsumme` (wird geprüft). Normalisiert auf beide
///    Ketten ohne Herkunft (`Bip32.taprootDescriptor`, `.TR_XPUB`). Skriptbaum, Einzelschlüssel oder andere Pfade:
///    `.UNSUPPORTED_VERSION`; privater Schlüssel im Deskriptor: `.SECRET`.
///  - Litecoin «L…»/«M…»/«ltc1…» (Kinds wie Bitcoin, Netz LTC), TRON «T…» (0x41), Solana (Base58, genau 32 Byte, ohne
///    Prüfsumme), XRP «r…» (Ripple-Base58; X-Adressen nicht unterstützt), TON «EQ…/UQ…» (CRC16) oder «0:<hex>»,
///    BNB Beacon Chain «bnb1…»: `.BEP2_SHUTDOWN` (2024 abgeschaltet), Cardano Shelley «addr1…» (Bech32 ohne Längengrenze),
///    Dogecoin und Bitcoin Cash erkannt, aber abgelehnt (`.DOGE_UNSUPPORTED`, `.BCH_UNSUPPORTED`: kein freier öffentlicher
///    Dienst ohne Schlüssel mit passenden Bedingungen) — alles wie Android.
/// Nicht unterstützt: ENS-Namen (`.ENS_NAME`), Testnet.
/// Schutz: private Schlüssel (WIF, xprv, 64 Hex) und Seed-Wörter werden erkannt und abgelehnt
/// (`.SECRET`) — die App speichert so etwas nie.
/// Ergebnis normalisiert: Bech32 klein, Ethereum mit EIP-55-Prüfsumme, Base58 unverändert.
enum AddressValidation {

    enum Kind: String, Sendable { case P2PKH, P2SH, P2WPKH, P2WSH, P2TR, ETH, XPUB, YPUB, ZPUB, TR_XPUB, SOL, TRX, XRP, TON, ADA }

    enum Problem: String, Sendable {
        case EMPTY
        case UNKNOWN_FORMAT
        case BAD_CHECKSUM
        case TESTNET
        /// Erweiterter Schlüssel, der nicht unterstützt wird (Multisig Ypub/Zpub).
        case EXTENDED_KEY
        /// Segwit v1 ohne 32 Byte oder v2…16.
        case UNSUPPORTED_VERSION
        case ENS_NAME
        /// Privater Schlüssel oder Seed-Wörter — nie speichern.
        case SECRET
        /// Gültig, aber anderes Netz als verlangt.
        case WRONG_CHAIN
        /// BNB Beacon Chain (BEP-2, «bnb1…») — 2024 abgeschaltet, Guthaben liegen auf BNB Smart Chain (0x…).
        case BEP2_SHUTDOWN
        /// Dogecoin: kein freier öffentlicher Dienst ohne Schlüssel, dessen Bedingungen eine App erlauben.
        case DOGE_UNSUPPORTED
        /// Bitcoin Cash: dito.
        case BCH_UNSUPPORTED
    }

    /// Gültig genau dann, wenn `problem` nil ist; dann sind `chain`, `kind` und `normalized` gesetzt.
    struct Check: Equatable, Sendable {
        var chain: AddressChain?
        var kind: Kind?
        var normalized: String?
        var problem: Problem?
        var isValid: Bool { problem == nil }
    }

    /// Längste Eingabe, die überhaupt geprüft wird (Bech32 höchstens 90 Zeichen).
    private static let maxInput = 200

    private static let extendedPublic = ["xpub", "ypub", "zpub", "Ypub", "Zpub", "tpub", "upub", "vpub", "Upub", "Vpub"]
    private static let extendedPrivate = ["xprv", "yprv", "zprv", "Yprv", "Zprv", "tprv", "uprv", "vprv", "Uprv", "Vprv"]

    /// - Parameter expected: Netz, das der Nutzer gewählt hat; nil = selbst erkennen.
    static func check(_ input: String?, expected: AddressChain? = nil) -> Check {
        let result = detect(input)
        if result.problem == nil, let expected, result.chain != expected {
            return Check(chain: result.chain, kind: result.kind, normalized: nil, problem: .WRONG_CHAIN)
        }
        return result
    }

    private static func invalid(_ problem: Problem) -> Check {
        Check(chain: nil, kind: nil, normalized: nil, problem: problem)
    }

    /// Nur ASCII-Leerraum zählt (gleich wie Android).
    private static func isSpace(_ c: Character) -> Bool { c == " " || c == "\t" || c == "\n" || c == "\r" || c == "\r\n" }

    private static func isHex(_ c: Character) -> Bool {
        guard let a = c.asciiValue else { return false }
        return (a >= 48 && a <= 57) || (a >= 97 && a <= 102) || (a >= 65 && a <= 70)
    }

    private static func isAsciiLetter(_ c: Character) -> Bool {
        guard let a = c.asciiValue else { return false }
        return (a >= 97 && a <= 122) || (a >= 65 && a <= 90)
    }

    private static func detect(_ input: String?) -> Check {
        var s = (input ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if s.isEmpty { return invalid(.EMPTY) }
        // Schlüssel als Zahlen-Array (Solana-Keypair-Datei «[12,34,…]»): nie annehmen
        if s.hasPrefix("[") { return invalid(isByteArrayText(s) ? .SECRET : .UNKNOWN_FORMAT) }
        if s.utf16.count > maxInput { return invalid(.UNKNOWN_FORMAT) }
        s = stripUri(s)
        if s.isEmpty { return invalid(.EMPTY) }

        // Seed-Wörter (12+ Wörter aus Buchstaben) nie annehmen
        if s.contains(where: isSpace) {
            let words = s.split(whereSeparator: isSpace).filter { !$0.isEmpty }
            if words.count >= 12 && words.allSatisfy({ $0.allSatisfy(isAsciiLetter) }) {
                return invalid(.SECRET)
            }
            return invalid(.UNKNOWN_FORMAT)
        }

        if s.lowercased().hasSuffix(".eth") { return invalid(.ENS_NAME) }

        // Ethereum
        if s.hasPrefix("0x") || s.hasPrefix("0X") {
            let body = String(s.dropFirst(2))
            guard body.allSatisfy(isHex) else { return invalid(.UNKNOWN_FORMAT) }
            switch body.count {
            case 40: return ethereum(body)
            case 64: return invalid(.SECRET)
            default: return invalid(.UNKNOWN_FORMAT)
            }
        }
        if s.count == 64 && s.allSatisfy(isHex) { return invalid(.SECRET) }

        if s.hasPrefix("tr(") { return taprootDescriptor(s) }
        if extendedPrivate.contains(where: { s.hasPrefix($0) }) { return invalid(.SECRET) }
        if extendedPublic.contains(where: { s.hasPrefix($0) }) { return extended(s) }

        let lower = s.lowercased()
        if lower.hasPrefix("bc1") || lower.hasPrefix("tb1") || lower.hasPrefix("bcrt1")
            || lower.hasPrefix("ltc1") || lower.hasPrefix("tltc1") { return segwit(s) }
        if lower.hasPrefix("bnb1") || lower.hasPrefix("tbnb1") { return invalid(.BEP2_SHUTDOWN) }
        if lower.hasPrefix("addr1") || lower.hasPrefix("addr_test1") || lower.hasPrefix("stake1") || lower.hasPrefix("stake_test1") {
            return cardano(s)
        }
        if lower.hasPrefix("bitcoincash:") || lower.hasPrefix("bchtest:") { return invalid(.BCH_UNSUPPORTED) }
        // Byron-Adressen (Cardano, Base58-CBOR) nicht unterstützt
        if s.hasPrefix("Ae2") || s.hasPrefix("DdzFF") { return invalid(.UNSUPPORTED_VERSION) }

        if let ton = tonRaw(s) { return ton }
        if s.count == 48, let ton = tonFriendly(s) { return ton }
        if s.hasPrefix("r"), (25...35).contains(s.count), let x = xrp(s) { return x }
        // X-Adresse (XRP mit Tag) und Familien-Seed «s…» (privat)
        if s.hasPrefix("X"), (46...48).contains(s.count), rippleChecked(s)?.count == 31 { return invalid(.UNSUPPORTED_VERSION) }
        // Seed: Version 0x21 + 16 Byte (secp256k1) bzw. 3 Byte Präfix + 16 Byte (Ed25519, «sEd…»)
        if s.hasPrefix("s"), let seed = rippleChecked(s), seed.count == 17 || seed.count == 19 { return invalid(.SECRET) }

        let result = base58(s)
        // CashAddr ohne Präfix («q…»/«p…», 42 Zeichen Bech32-Alphabet)
        if result.problem == .UNKNOWN_FORMAT, s.count == 42, s.hasPrefix("q") || s.hasPrefix("p"),
           s.allSatisfy({ charset.contains($0) }) {
            return invalid(.BCH_UNSUPPORTED)
        }
        return result
    }

    // MARK: Cardano

    /// Shelley-Adresse (Bech32, Länge bis `maxInput`): Kopfbyte Typ 0…7 und Netz-ID 1 (Mainnet). Wie Android.
    private static func cardano(_ input: String) -> Check {
        let scalars = Array(input.unicodeScalars)
        let hasLower = scalars.contains { $0.value >= 97 && $0.value <= 122 }
        let hasUpper = scalars.contains { $0.value >= 65 && $0.value <= 90 }
        if hasLower && hasUpper { return invalid(.UNKNOWN_FORMAT) }
        let s = input.lowercased()
        let chars = Array(s)
        guard let sep = chars.lastIndex(of: "1"), sep >= 1, sep + 7 <= chars.count else { return invalid(.UNKNOWN_FORMAT) }
        let hrp = String(chars[0..<sep])
        var data: [Int] = []
        for c in chars[(sep + 1)...] {
            guard let v = charset.firstIndex(of: c) else { return invalid(.UNKNOWN_FORMAT) }
            data.append(v)
        }
        let hrpBytes = hrp.unicodeScalars.map { Int($0.value) }
        guard polymod(hrpBytes.map { $0 >> 5 } + [0] + hrpBytes.map { $0 & 31 } + data) == bech32Const else {
            return invalid(.BAD_CHECKSUM)
        }
        if hrp == "addr_test" || hrp == "stake_test" { return invalid(.TESTNET) }
        if hrp == "stake" { return invalid(.UNSUPPORTED_VERSION) }
        guard hrp == "addr" else { return invalid(.UNKNOWN_FORMAT) }
        guard let bytes = convertBits(Array(data[0..<(data.count - 6)])), bytes.count >= 29 else { return invalid(.UNKNOWN_FORMAT) }
        let type = bytes[0] >> 4
        let network = bytes[0] & 0x0f
        if type > 7 { return invalid(.UNKNOWN_FORMAT) }
        if network != 1 { return invalid(.TESTNET) }
        return Check(chain: .ADA, kind: .ADA, normalized: s, problem: nil)
    }

    /// «[1, 2, …]» mit 32 bis 64 Zahlen 0…255 (Schlüsseldatei).
    private static func isByteArrayText(_ s: String) -> Bool {
        guard s.hasSuffix("]"), s.count >= 2 else { return false }
        let parts = s.dropFirst().dropLast().split(separator: ",", omittingEmptySubsequences: false)
            .map { $0.trimmingCharacters(in: .whitespaces) }
        return (32...64).contains(parts.count) && parts.allSatisfy { p in
            !p.isEmpty && p.count <= 3 && p.allSatisfy { $0.isASCII && $0.isNumber } && (Int(p) ?? 999) <= 255
        }
    }

    // MARK: XRP

    private static let rippleAlphabet = Array("rpshnaf39wBUDNEGHJKLM4PQRST7VWXYZ2bcdeCg65jkm8oFqi1tuvAxyz")

    /// Ripple-Base58 mit gültiger Prüfsumme → Nutzdaten ohne Prüfsumme; sonst nil.
    private static func rippleChecked(_ s: String) -> [UInt8]? {
        guard let raw = base58Decode(s, alphabet: rippleAlphabet), checksumOk(raw) else { return nil }
        return Array(raw[0..<(raw.count - 4)])
    }

    /// Klassische XRP-Adresse; nil = keine (weiter mit Base58).
    private static func xrp(_ s: String) -> Check? {
        guard let raw = base58Decode(s, alphabet: rippleAlphabet), raw.count == 25, raw[0] == 0 else { return nil }
        guard checksumOk(raw) else { return invalid(.BAD_CHECKSUM) }
        return Check(chain: .XRP, kind: .XRP, normalized: s, problem: nil)
    }

    // MARK: TON

    /// «0:<64 Hex>» (Basis-Workchain); «-1:…» Masterchain nicht unterstützt; nil = keine rohe Adresse.
    private static func tonRaw(_ s: String) -> Check? {
        guard let sep = s.firstIndex(of: ":") else { return nil }
        let wc = String(s[..<sep])
        let hex = String(s[s.index(after: sep)...])
        guard !wc.isEmpty, hex.count == 64, hex.allSatisfy(isHex), wc == "0" || wc == "-1" else { return nil }
        if wc == "-1" { return invalid(.UNSUPPORTED_VERSION) }
        return Check(chain: .TON, kind: .TON, normalized: "0:" + hex.lowercased(), problem: nil)
    }

    /// Benutzerfreundliche TON-Adresse (36 Byte: Kennung, Workchain, 32 Byte, CRC16); nil = keine.
    private static func tonFriendly(_ s: String) -> Check? {
        guard let raw = base64Decode(s), raw.count == 36 else { return nil }
        let tag = Int(raw[0])
        guard tag & 0x7f == 0x11 || tag & 0x7f == 0x51 else { return nil }
        let crc = crc16(Array(raw[0..<34]))
        guard (Int(raw[34]) << 8 | Int(raw[35])) == crc else { return invalid(.BAD_CHECKSUM) }
        if tag & 0x80 != 0 { return invalid(.TESTNET) }
        switch raw[1] {
        case 0x00: return Check(chain: .TON, kind: .TON, normalized: s, problem: nil)
        case 0xff: return invalid(.UNSUPPORTED_VERSION)
        default: return invalid(.UNKNOWN_FORMAT)
        }
    }

    /// Base64 (Standard oder URL-sicher, ohne Auffüllen); ungültig = nil.
    static func base64Decode(_ s: String) -> [UInt8]? {
        var out: [UInt8] = []
        var acc = 0
        var bits = 0
        for scalar in s.unicodeScalars {
            let c = Int(scalar.value)
            let v: Int
            switch c {
            case 65...90: v = c - 65
            case 97...122: v = c - 97 + 26
            case 48...57: v = c - 48 + 52
            case 43, 45: v = 62
            case 47, 95: v = 63
            default: return nil
            }
            acc = (acc << 6) | v
            bits += 6
            if bits >= 8 {
                bits -= 8
                out.append(UInt8((acc >> bits) & 0xff))
            }
            acc &= 0xffff
        }
        return out
    }

    /// CRC16-XMODEM (Polynom 0x1021, Start 0) — TON-Adressen.
    static func crc16(_ data: [UInt8]) -> Int {
        var crc = 0
        for b in data {
            crc ^= Int(b) << 8
            for _ in 0..<8 { crc = crc & 0x8000 != 0 ? (crc << 1) ^ 0x1021 : crc << 1 }
            crc &= 0xffff
        }
        return crc
    }

    /// xpub/ypub/zpub (`Bip32.check`); unverändert übernommen.
    private static func extended(_ s: String) -> Check {
        if let problem = Bip32.check(s) { return invalid(problem) }
        let kind: Kind = s.hasPrefix("ypub") ? .YPUB : s.hasPrefix("zpub") ? .ZPUB : .XPUB
        return Check(chain: .BTC, kind: kind, normalized: s, problem: nil)
    }

    /// Erweiterter öffentlicher Schlüssel (auch Taproot-Konto) statt einzelner Adresse (beobachtete Adresse)?
    static func isExtendedKey(_ address: String) -> Bool {
        address.hasPrefix("xpub") || address.hasPrefix("ypub") || address.hasPrefix("zpub") || address.hasPrefix("tr(")
    }

    // MARK: Taproot-Deskriptor

    /// Erlaubte Ketten hinter dem Schlüssel: Empfang, Wechselgeld oder beide (BIP389).
    private static let taprootPaths: Set<String> = ["/0/*", "/1/*", "/<0;1>/*"]

    /// `tr(KEY)` mit erweitertem Schlüssel; Prüfsumme (BIP380) wird geprüft, wenn vorhanden. Herkunft und gewählte Kette
    /// fallen weg: beobachtet werden immer beide Ketten (Wallets exportieren Empfang und Wechselgeld oft getrennt, beide
    /// ergeben dieselbe Normalform und damit dieselbe beobachtete Adresse). Wie Android.
    private static func taprootDescriptor(_ input: String) -> Check {
        var s = input
        if let hash = s.lastIndex(of: "#") {
            let sum = String(s[s.index(after: hash)...])
            s = String(s[..<hash])
            guard sum.count == 8, sum.allSatisfy({ charset.contains($0) }) else { return invalid(.UNKNOWN_FORMAT) }
            guard let expected = descriptorChecksum(s) else { return invalid(.UNKNOWN_FORMAT) }
            if expected != sum { return invalid(.BAD_CHECKSUM) }
        }
        guard s.hasSuffix(")"), s.count >= 5 else { return invalid(.UNKNOWN_FORMAT) }
        var inner = String(s.dropFirst(3).dropLast())
        // Skriptbaum (tr(KEY,{…})) wird nicht unterstützt — die Adressen hingen vom Baum ab
        if inner.contains(",") || inner.contains("{") { return invalid(.UNSUPPORTED_VERSION) }
        if inner.hasPrefix("[") {
            guard let end = inner.firstIndex(of: "]"),
                  isKeyOrigin(String(inner[inner.index(after: inner.startIndex)..<end])) else { return invalid(.UNKNOWN_FORMAT) }
            inner = String(inner[inner.index(after: end)...])
        }
        let key: String
        let path: String
        if let slash = inner.firstIndex(of: "/") {
            key = String(inner[..<slash])
            path = String(inner[slash...])
        } else {
            key = inner
            path = ""
        }
        if key.isEmpty { return invalid(.UNKNOWN_FORMAT) }
        if extendedPrivate.contains(where: { key.hasPrefix($0) }) { return invalid(.SECRET) }
        if extendedPublic.contains(where: { key.hasPrefix($0) }) {
            if let problem = Bip32.check(key) { return invalid(problem) }
            // ypub/zpub in tr() ergäben keine BIP86-Adressen
            if !key.hasPrefix("xpub") { return invalid(.UNKNOWN_FORMAT) }
        } else {
            // WIF (privat) nie annehmen; einzelner öffentlicher Schlüssel (Hex) ist kein Konto
            if base58(key).problem == .SECRET { return invalid(.SECRET) }
            if (key.count == 64 || key.count == 66) && key.allSatisfy(isHex) { return invalid(.UNSUPPORTED_VERSION) }
            return invalid(.UNKNOWN_FORMAT)
        }
        guard taprootPaths.contains(path) else { return invalid(.UNSUPPORTED_VERSION) }
        return Check(chain: .BTC, kind: .TR_XPUB, normalized: Bip32.taprootDescriptor(key), problem: nil)
    }

    /// «73c5da0a/86'/0'/0'»: 8 Hex-Ziffern, dann Indizes (gehärtet mit ', h oder H).
    private static func isKeyOrigin(_ origin: String) -> Bool {
        let parts = origin.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
        guard parts[0].count == 8, parts[0].allSatisfy(isHex) else { return false }
        return parts.dropFirst().allSatisfy { p in
            let digits = p.hasSuffix("'") || p.hasSuffix("h") || p.hasSuffix("H") ? String(p.dropLast()) : p
            guard !digits.isEmpty, digits.count <= 10, digits.allSatisfy({ $0.isASCII && $0.isNumber }),
                  let n = Int64(digits) else { return false }
            return n <= Bip32.maxIndex
        }
    }

    private static let descriptorInput =
        Array("0123456789()[],'/*abcdefgh@:$%{}IJKLMNOPQRSTUVWXYZ&+-.;<=>?!^_|~ijklmnopqrstuvwxyzABCDEFGH`#\"\\ ")
    private static let descriptorGen: [UInt64] = [0xf5dee51989, 0xa9fdca3312, 0x1bab10e32d, 0x3706b1677a, 0x644d626ffd]

    /// Prüfsumme eines Deskriptors (BIP380, 8 Zeichen); Zeichen ausserhalb des Deskriptor-Alphabets = nil. Wie Android.
    static func descriptorChecksum(_ descriptor: String) -> String? {
        var symbols: [Int] = []
        var groups: [Int] = []
        for ch in descriptor {
            guard let v = descriptorInput.firstIndex(of: ch) else { return nil }
            symbols.append(v & 31)
            groups.append(v >> 5)
            if groups.count == 3 {
                symbols.append(groups[0] * 9 + groups[1] * 3 + groups[2])
                groups.removeAll()
            }
        }
        if groups.count == 1 { symbols.append(groups[0]) }
        if groups.count == 2 { symbols.append(groups[0] * 3 + groups[1]) }
        symbols.append(contentsOf: [Int](repeating: 0, count: 8))
        var c: UInt64 = 1
        for v in symbols {
            let top = c >> 35
            c = ((c & 0x7ffffffff) << 5) ^ UInt64(v)
            for i in 0..<5 where (top >> UInt64(i)) & 1 == 1 { c ^= descriptorGen[i] }
        }
        c ^= 1
        var out = ""
        for i in 0..<8 { out.append(charset[Int((c >> UInt64(5 * (7 - i))) & 31)]) }
        return out
    }

    /// «bitcoin:ADR?amount=…», «ethereum:pay-ADR@1/…» → ADR.
    private static func stripUri(_ s: String) -> String {
        let lower = s.lowercased()
        var out = s
        if lower.hasPrefix("bitcoin:") {
            out = before(String(s.dropFirst(8)), "?")
        } else if lower.hasPrefix("litecoin:") {
            out = before(String(s.dropFirst(9)), "?")
        } else if lower.hasPrefix("solana:") {
            out = before(String(s.dropFirst(7)), "?")
        } else if lower.hasPrefix("ethereum:") {
            var rest = String(s.dropFirst(9))
            if rest.lowercased().hasPrefix("pay-") { rest = String(rest.dropFirst(4)) }
            out = before(before(before(rest, "?"), "@"), "/")
        }
        return out.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func before(_ s: String, _ c: Character) -> String {
        s.firstIndex(of: c).map { String(s[..<$0]) } ?? s
    }

    // MARK: Ethereum

    /// EIP-55: Buchstabe gross, wenn das Halbbyte des Keccak-256 der kleingeschriebenen Adresse ≥ 8 ist.
    static func eip55(_ body40: String) -> String {
        let lower = body40.lowercased()
        let hash = Array(Keccak256.hex(Array(lower.utf8)))
        var out = "0x"
        for (i, c) in lower.enumerated() {
            let isLetter = c >= "a" && c <= "f"
            let nibble = hash[i].hexDigitValue ?? 0
            out.append(isLetter && nibble >= 8 ? Character(c.uppercased()) : c)
        }
        return out
    }

    private static func ethereum(_ body: String) -> Check {
        let checksummed = eip55(body)
        let hasLower = body.contains { $0 >= "a" && $0 <= "f" }
        let hasUpper = body.contains { $0 >= "A" && $0 <= "F" }
        if hasLower && hasUpper && "0x" + body != checksummed { return invalid(.BAD_CHECKSUM) }
        return Check(chain: .ETH, kind: .ETH, normalized: checksummed, problem: nil)
    }

    // MARK: Bitcoin Base58Check

    private static let base58Alphabet = Array("123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz")

    /// Base58 → Bytes (führende «1» = Null-Bytes); ungültiges Zeichen = nil.
    static func base58Decode(_ s: String) -> [UInt8]? { base58Decode(s, alphabet: base58Alphabet) }

    /// Base58 mit eigenem Alphabet (Ripple); führende Null-Ziffer = Null-Bytes.
    private static func base58Decode(_ s: String, alphabet: [Character]) -> [UInt8]? {
        var number: [Int] = [] // Basis 256, niedrigstes Byte zuerst
        for ch in s {
            guard var carry = alphabet.firstIndex(of: ch) else { return nil }
            for i in number.indices {
                carry += number[i] * 58
                number[i] = carry & 0xff
                carry >>= 8
            }
            while carry > 0 {
                number.append(carry & 0xff)
                carry >>= 8
            }
        }
        let zeros = s.prefix(while: { $0 == alphabet[0] }).count
        return [UInt8](repeating: 0, count: zeros) + number.reversed().map { UInt8($0) }
    }

    private static func sha256(_ data: [UInt8]) -> [UInt8] { Array(SHA256.hash(data: data)) }

    private static func checksumOk(_ raw: [UInt8]) -> Bool {
        guard raw.count >= 5 else { return false }
        let payload = Array(raw[0..<(raw.count - 4)])
        let sum = sha256(sha256(payload))
        return Array(sum[0..<4]) == Array(raw[(raw.count - 4)...])
    }

    private static func base58(_ s: String) -> Check {
        guard let raw = base58Decode(s) else { return invalid(.UNKNOWN_FORMAT) }
        let version = raw.first.map { Int($0) } ?? -1
        switch raw.count {
        case 25:
            guard checksumOk(raw) else { return invalid(.BAD_CHECKSUM) }
            switch version {
            case 0x00: return Check(chain: .BTC, kind: .P2PKH, normalized: s, problem: nil)
            case 0x05: return Check(chain: .BTC, kind: .P2SH, normalized: s, problem: nil)
            case 0x30: return Check(chain: .LTC, kind: .P2PKH, normalized: s, problem: nil)
            case 0x32: return Check(chain: .LTC, kind: .P2SH, normalized: s, problem: nil)
            case 0x41: return Check(chain: .TRX, kind: .TRX, normalized: s, problem: nil)
            case 0x1e, 0x16: return invalid(.DOGE_UNSUPPORTED)
            case 0x6f, 0xc4, 0x3a: return invalid(.TESTNET)
            default: return invalid(.UNKNOWN_FORMAT)
            }
        // WIF: privater Schlüssel (0x80 Bitcoin, 0xb0 Litecoin, 0xef Testnet; 33 bzw. 34 Byte + Prüfsumme)
        case 37, 38:
            return (version == 0x80 || version == 0xb0 || version == 0x9e || version == 0xef) && checksumOk(raw) ? invalid(.SECRET) : invalid(.UNKNOWN_FORMAT)
        // Solana: öffentlicher Schlüssel (32 Byte, ohne Prüfsumme) bzw. privater Schlüssel (64 Byte)
        case 32:
            return (32...44).contains(s.count) ? Check(chain: .SOL, kind: .SOL, normalized: s, problem: nil) : invalid(.UNKNOWN_FORMAT)
        case 64:
            return invalid(.SECRET)
        default:
            return invalid(.UNKNOWN_FORMAT)
        }
    }

    // MARK: Bitcoin Bech32 / Bech32m

    private static let charset = Array("qpzry9x8gf2tvdw0s3jn54khce6mua7l")
    private static let bech32Const = 1
    private static let bech32mConst = 0x2bc830a3
    private static let gen = [0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3]

    private static func polymod(_ values: [Int]) -> Int {
        var chk = 1
        for v in values {
            let top = chk >> 25
            chk = ((chk & 0x1ffffff) << 5) ^ v
            for i in 0..<5 where (top >> i) & 1 == 1 { chk ^= gen[i] }
        }
        return chk
    }

    private static func segwit(_ input: String) -> Check {
        let scalars = Array(input.unicodeScalars)
        guard scalars.count >= 8, scalars.count <= 90 else { return invalid(.UNKNOWN_FORMAT) }
        guard scalars.allSatisfy({ $0.value >= 33 && $0.value <= 126 }) else { return invalid(.UNKNOWN_FORMAT) }
        let hasLower = scalars.contains { $0.value >= 97 && $0.value <= 122 }
        let hasUpper = scalars.contains { $0.value >= 65 && $0.value <= 90 }
        if hasLower && hasUpper { return invalid(.UNKNOWN_FORMAT) }
        let s = input.lowercased()
        let chars = Array(s)
        guard let sep = chars.lastIndex(of: "1"), sep >= 1, sep + 7 <= chars.count else { return invalid(.UNKNOWN_FORMAT) }
        let hrp = String(chars[0..<sep])
        var data: [Int] = []
        for c in chars[(sep + 1)...] {
            guard let v = charset.firstIndex(of: c) else { return invalid(.UNKNOWN_FORMAT) }
            data.append(v)
        }
        if hrp == "tb" || hrp == "bcrt" || hrp == "tltc" { return invalid(.TESTNET) }
        let chain: AddressChain
        switch hrp {
        case "bc": chain = .BTC
        case "ltc": chain = .LTC
        default: return invalid(.UNKNOWN_FORMAT)
        }

        let hrpBytes = hrp.unicodeScalars.map { Int($0.value) }
        let expanded = hrpBytes.map { $0 >> 5 } + [0] + hrpBytes.map { $0 & 31 } + data
        let bech32m: Bool
        switch polymod(expanded) {
        case bech32Const: bech32m = false
        case bech32mConst: bech32m = true
        default: return invalid(.BAD_CHECKSUM)
        }
        let values = Array(data[0..<(data.count - 6)])
        guard let version = values.first else { return invalid(.UNKNOWN_FORMAT) }
        if version > 16 { return invalid(.UNKNOWN_FORMAT) }
        guard let program = convertBits(Array(values.dropFirst())) else { return invalid(.UNKNOWN_FORMAT) }
        if program.count < 2 || program.count > 40 { return invalid(.UNKNOWN_FORMAT) }
        // v0 nur mit Bech32, ab v1 nur mit Bech32m (BIP350)
        if (version == 0) == bech32m { return invalid(.BAD_CHECKSUM) }
        switch (version, program.count) {
        case (0, 20): return Check(chain: chain, kind: .P2WPKH, normalized: s, problem: nil)
        case (0, 32): return Check(chain: chain, kind: .P2WSH, normalized: s, problem: nil)
        case (0, _): return invalid(.UNKNOWN_FORMAT)
        case (1, 32): return Check(chain: chain, kind: .P2TR, normalized: s, problem: nil)
        default: return invalid(.UNSUPPORTED_VERSION)
        }
    }

    /// 5-Bit-Gruppen → Bytes ohne Auffüllen; überzählige Bits müssen null sein.
    private static func convertBits(_ data: [Int]) -> [Int]? {
        var acc = 0
        var bits = 0
        var out: [Int] = []
        for v in data {
            acc = ((acc << 5) | v) & 0xfff
            bits += 5
            if bits >= 8 {
                bits -= 8
                out.append((acc >> bits) & 0xff)
            }
        }
        if bits >= 5 || ((acc << (8 - bits)) & 0xff) != 0 { return nil }
        return out
    }
}
