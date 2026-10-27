package com.cryptochecker.app.domain.portfolio

/**
 * Prüft öffentliche Adressen für «Adresse beobachten» (nur Mainnet, nur einzelne Adressen):
 *  - Bitcoin Base58Check: P2PKH («1…», Version 0x00), P2SH («3…», 0x05), Prüfsumme = doppeltes SHA-256.
 *  - Bitcoin Bech32/Bech32m (BIP173/BIP350, «bc1…»): v0 P2WPKH (20 Byte) / P2WSH (32 Byte) mit Bech32,
 *    v1 Taproot (32 Byte) mit Bech32m. Spätere Segwit-Versionen: [Problem.UNSUPPORTED_VERSION].
 *  - Ethereum: «0x» + 40 Hex; gemischte Schreibweise muss der EIP-55-Prüfsumme entsprechen
 *    (Keccak-256), nur klein oder nur gross wird ohne Prüfsumme angenommen.
 *  - Erweiterte öffentliche Schlüssel xpub/ypub/zpub (BIP32, nur Mainnet, [Bip32]): Prüfsumme, Version und
 *    Kurvenpunkt; Ergebnis unverändert ([Kind.XPUB]/[Kind.YPUB]/[Kind.ZPUB]). Multisig (Ypub/Zpub):
 *    [Problem.EXTENDED_KEY].
 *  - Taproot-Konto als Deskriptor (BIP86/BIP380/BIP389): `tr(` + optionale Herkunft `[fingerprint/86'/0'/0']` + xpub +
 *    Kette `/0/`, `/1/` oder `/<0;1>/` mit Platzhalter, optional `#prüfsumme` (wird geprüft). Normalisiert auf beide
 *    Ketten ohne Herkunft ([Bip32.taprootDescriptor], [Kind.TR_XPUB]). Skriptbaum, Einzelschlüssel oder andere Pfade:
 *    [Problem.UNSUPPORTED_VERSION]; privater Schlüssel im Deskriptor: [Problem.SECRET].
 *  - Litecoin: Base58Check «L…» (0x30) / «M…» (0x32), Bech32 «ltc1…» (Kinds wie Bitcoin, Netz LTC); alte «3…»-Adressen
 *    gelten als Bitcoin.
 *  - TRON: Base58Check «T…» (Version 0x41, 25 Byte).
 *  - Solana: Base58 ohne Prüfsumme, genau 32 Byte (32–44 Zeichen) — ein Tippfehler ist hier nicht erkennbar.
 *  - XRP: klassische Adresse «r…» (Ripple-Base58, Version 0x00, Prüfsumme); X-Adressen: [Problem.UNSUPPORTED_VERSION].
 *  - TON: «EQ…/UQ…» (48 Zeichen Base64(url), CRC16) oder roh «0:<64 Hex>»; Masterchain: [Problem.UNSUPPORTED_VERSION].
 *  - BNB Beacon Chain «bnb1…» (BEP-2): seit 19.11.2024 abgeschaltet → [Problem.BEP2_SHUTDOWN].
 *  - Cardano: Shelley «addr1…» (Bech32 ohne Längengrenze, Netz-ID 1); Stake-Adressen und Byron: nicht unterstützt.
 *  - Dogecoin «D…/A…/9…» und Bitcoin Cash («bitcoincash:q…», CashAddr): erkannt, aber abgelehnt — kein freier öffentlicher
 *    Dienst ohne Schlüssel mit passenden Bedingungen ([Problem.DOGE_UNSUPPORTED], [Problem.BCH_UNSUPPORTED]). Alte
 *    BCH-Adressen «1…/3…» sehen aus wie Bitcoin und gelten als Bitcoin.
 * Nicht unterstützt: ENS-Namen ([Problem.ENS_NAME]), Testnet.
 * Schutz: private Schlüssel (WIF, xprv, 64 Hex, Solana-Schlüssel mit 64 Byte als Base58 oder Zahlen-Array, XRP-Seed
 * «s…») und Seed-Wörter werden erkannt und abgelehnt ([Problem.SECRET]) — die App speichert so etwas nie.
 * Ergebnis normalisiert: Bech32 klein, Ethereum mit EIP-55-Prüfsumme, TON roh mit kleinem Hex, sonst unverändert.
 * Reines Kotlin, gleich wie `AddressValidation.swift`.
 */
object AddressValidation {

    enum class Kind { P2PKH, P2SH, P2WPKH, P2WSH, P2TR, ETH, XPUB, YPUB, ZPUB, TR_XPUB, SOL, TRX, XRP, TON, ADA }

    enum class Problem {
        EMPTY,
        UNKNOWN_FORMAT,
        BAD_CHECKSUM,
        TESTNET,
        /** Erweiterter Schlüssel, der nicht unterstützt wird (Multisig Ypub/Zpub). */
        EXTENDED_KEY,
        /** Segwit v1 ohne 32 Byte oder v2…16. */
        UNSUPPORTED_VERSION,
        ENS_NAME,
        /** Privater Schlüssel oder Seed-Wörter — nie speichern. */
        SECRET,
        /** Gültig, aber anderes Netz als verlangt. */
        WRONG_CHAIN,
        /** BNB Beacon Chain (BEP-2, «bnb1…») — 2024 abgeschaltet, Guthaben liegen auf BNB Smart Chain (0x…). */
        BEP2_SHUTDOWN,
        /** Dogecoin: kein freier öffentlicher Dienst ohne Schlüssel, dessen Bedingungen eine App erlauben. */
        DOGE_UNSUPPORTED,
        /** Bitcoin Cash: dito. */
        BCH_UNSUPPORTED,
    }

    /** Gültig genau dann, wenn [problem] null ist; dann sind [chain], [kind] und [normalized] gesetzt. */
    data class Check(
        val chain: AddressChain?,
        val kind: Kind?,
        val normalized: String?,
        val problem: Problem?,
    ) {
        val isValid: Boolean get() = problem == null
    }

    /** Längste Eingabe, die überhaupt geprüft wird (Bech32 höchstens 90 Zeichen). */
    const val MAX_INPUT = 200

    private val EXTENDED_PUBLIC = listOf("xpub", "ypub", "zpub", "Ypub", "Zpub", "tpub", "upub", "vpub", "Upub", "Vpub")
    private val EXTENDED_PRIVATE = listOf("xprv", "yprv", "zprv", "Yprv", "Zprv", "tprv", "uprv", "vprv", "Uprv", "Vprv")

    /**
     * @param expected Netz, das der Nutzer gewählt hat; null = selbst erkennen.
     */
    fun check(input: String?, expected: AddressChain? = null): Check {
        val result = detect(input)
        if (result.problem == null && expected != null && result.chain != expected) {
            return Check(result.chain, result.kind, null, Problem.WRONG_CHAIN)
        }
        return result
    }

    fun invalid(problem: Problem) = Check(null, null, null, problem)

    private fun detect(input: String?): Check {
        var s = input?.trim().orEmpty()
        if (s.isEmpty()) return invalid(Problem.EMPTY)
        // Schlüssel als Zahlen-Array (Solana-Keypair-Datei «[12,34,…]»): nie annehmen
        if (s.startsWith("[")) return invalid(if (isByteArrayText(s)) Problem.SECRET else Problem.UNKNOWN_FORMAT)
        if (s.length > MAX_INPUT) return invalid(Problem.UNKNOWN_FORMAT)
        s = stripUri(s)
        if (s.isEmpty()) return invalid(Problem.EMPTY)

        // Seed-Wörter (12+ Wörter aus Buchstaben) nie annehmen
        if (s.any { isSpace(it) }) {
            val words = s.split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }
            return if (words.size >= 12 && words.all { w -> w.isNotEmpty() && w.all { it in 'a'..'z' || it in 'A'..'Z' } }) {
                invalid(Problem.SECRET)
            } else {
                invalid(Problem.UNKNOWN_FORMAT)
            }
        }

        if (s.lowercase().endsWith(".eth")) return invalid(Problem.ENS_NAME)

        // Ethereum
        if (s.startsWith("0x") || s.startsWith("0X")) {
            val body = s.substring(2)
            if (!body.all { isHex(it) }) return invalid(Problem.UNKNOWN_FORMAT)
            return when (body.length) {
                40 -> ethereum(body)
                64 -> invalid(Problem.SECRET)
                else -> invalid(Problem.UNKNOWN_FORMAT)
            }
        }
        if (s.length == 64 && s.all { isHex(it) }) return invalid(Problem.SECRET)

        if (s.startsWith("tr(")) return taprootDescriptor(s)
        if (EXTENDED_PRIVATE.any { s.startsWith(it) }) return invalid(Problem.SECRET)
        if (EXTENDED_PUBLIC.any { s.startsWith(it) }) return extended(s)

        val lower = s.lowercase()
        if (lower.startsWith("bc1") || lower.startsWith("tb1") || lower.startsWith("bcrt1") ||
            lower.startsWith("ltc1") || lower.startsWith("tltc1")
        ) return segwit(s)
        if (lower.startsWith("bnb1") || lower.startsWith("tbnb1")) return invalid(Problem.BEP2_SHUTDOWN)
        if (lower.startsWith("addr1") || lower.startsWith("addr_test1") || lower.startsWith("stake1") || lower.startsWith("stake_test1")) {
            return cardano(s)
        }
        if (lower.startsWith("bitcoincash:") || lower.startsWith("bchtest:")) return invalid(Problem.BCH_UNSUPPORTED)
        // Byron-Adressen (Cardano, Base58-CBOR) nicht unterstützt
        if (s.startsWith("Ae2") || s.startsWith("DdzFF")) return invalid(Problem.UNSUPPORTED_VERSION)

        tonRaw(s)?.let { return it }
        if (s.length == 48) tonFriendly(s)?.let { return it }
        if (s.startsWith("r") && s.length in 25..35) xrp(s)?.let { return it }
        // X-Adresse (XRP mit Tag) und Familien-Seed «s…» (privat)
        if (s.startsWith("X") && s.length in 46..48 && rippleChecked(s)?.size == 31) return invalid(Problem.UNSUPPORTED_VERSION)
        // Seed: Version 0x21 + 16 Byte (secp256k1) bzw. 3 Byte Präfix + 16 Byte (Ed25519, «sEd…»)
        if (s.startsWith("s") && rippleChecked(s)?.size.let { it == 17 || it == 19 }) return invalid(Problem.SECRET)

        val result = base58(s)
        // CashAddr ohne Präfix («q…»/«p…», 42 Zeichen Bech32-Alphabet)
        if (result.problem == Problem.UNKNOWN_FORMAT && s.length == 42 && (s[0] == 'q' || s[0] == 'p') && s.all { it in CHARSET }) {
            return invalid(Problem.BCH_UNSUPPORTED)
        }
        return result
    }

    /** xpub/ypub/zpub ([Bip32.check]); unverändert übernommen. */
    private fun extended(s: String): Check {
        Bip32.check(s)?.let { return invalid(it) }
        val kind = when {
            s.startsWith("ypub") -> Kind.YPUB
            s.startsWith("zpub") -> Kind.ZPUB
            else -> Kind.XPUB
        }
        return Check(AddressChain.BTC, kind, s, null)
    }

    /** Erweiterter öffentlicher Schlüssel (auch Taproot-Konto) statt einzelner Adresse (beobachtete Adresse)? */
    fun isExtendedKey(address: String): Boolean =
        address.startsWith("xpub") || address.startsWith("ypub") || address.startsWith("zpub") || address.startsWith("tr(")

    // ---------------- Taproot-Deskriptor ----------------

    /** Erlaubte Ketten hinter dem Schlüssel: Empfang, Wechselgeld oder beide (BIP389). */
    private val TAPROOT_PATHS = setOf("/0/*", "/1/*", "/<0;1>/*")

    /**
     * `tr(KEY)` mit erweitertem Schlüssel; Prüfsumme (BIP380) wird geprüft, wenn vorhanden. Herkunft und gewählte Kette
     * fallen weg: beobachtet werden immer beide Ketten (Wallets exportieren Empfang und Wechselgeld oft getrennt, beide
     * ergeben dieselbe Normalform und damit dieselbe beobachtete Adresse).
     */
    private fun taprootDescriptor(input: String): Check {
        var s = input
        val hash = s.lastIndexOf('#')
        if (hash >= 0) {
            val sum = s.substring(hash + 1)
            s = s.substring(0, hash)
            if (sum.length != 8 || !sum.all { it in CHARSET }) return invalid(Problem.UNKNOWN_FORMAT)
            val expected = descriptorChecksum(s) ?: return invalid(Problem.UNKNOWN_FORMAT)
            if (expected != sum) return invalid(Problem.BAD_CHECKSUM)
        }
        if (!s.endsWith(")") || s.length < 5) return invalid(Problem.UNKNOWN_FORMAT)
        var inner = s.substring(3, s.length - 1)
        // Skriptbaum (tr(KEY,{…})) wird nicht unterstützt — die Adressen hingen vom Baum ab
        if (inner.contains(',') || inner.contains('{')) return invalid(Problem.UNSUPPORTED_VERSION)
        if (inner.startsWith("[")) {
            val end = inner.indexOf(']')
            if (end < 0 || !isKeyOrigin(inner.substring(1, end))) return invalid(Problem.UNKNOWN_FORMAT)
            inner = inner.substring(end + 1)
        }
        val slash = inner.indexOf('/')
        val key = if (slash < 0) inner else inner.substring(0, slash)
        val path = if (slash < 0) "" else inner.substring(slash)
        if (key.isEmpty()) return invalid(Problem.UNKNOWN_FORMAT)
        if (EXTENDED_PRIVATE.any { key.startsWith(it) }) return invalid(Problem.SECRET)
        if (EXTENDED_PUBLIC.any { key.startsWith(it) }) {
            Bip32.check(key)?.let { return invalid(it) }
            // ypub/zpub in tr() ergäben keine BIP86-Adressen
            if (!key.startsWith("xpub")) return invalid(Problem.UNKNOWN_FORMAT)
        } else {
            // WIF (privat) nie annehmen; einzelner öffentlicher Schlüssel (Hex) ist kein Konto
            if (base58(key).problem == Problem.SECRET) return invalid(Problem.SECRET)
            if ((key.length == 64 || key.length == 66) && key.all { isHex(it) }) return invalid(Problem.UNSUPPORTED_VERSION)
            return invalid(Problem.UNKNOWN_FORMAT)
        }
        if (path !in TAPROOT_PATHS) return invalid(Problem.UNSUPPORTED_VERSION)
        return Check(AddressChain.BTC, Kind.TR_XPUB, Bip32.taprootDescriptor(key), null)
    }

    /** «73c5da0a/86'/0'/0'»: 8 Hex-Ziffern, dann Indizes (gehärtet mit ', h oder H). */
    private fun isKeyOrigin(origin: String): Boolean {
        val parts = origin.split('/')
        if (parts[0].length != 8 || !parts[0].all { isHex(it) }) return false
        return parts.drop(1).all { p ->
            val digits = if (p.endsWith("'") || p.endsWith("h") || p.endsWith("H")) p.dropLast(1) else p
            digits.isNotEmpty() && digits.length <= 10 && digits.all { it in '0'..'9' } && digits.toLong() <= Bip32.MAX_INDEX
        }
    }

    private const val DESCRIPTOR_INPUT =
        "0123456789()[],'/*abcdefgh@:\$%{}IJKLMNOPQRSTUVWXYZ&+-.;<=>?!^_|~ijklmnopqrstuvwxyzABCDEFGH`#\"\\ "
    private val DESCRIPTOR_GEN = longArrayOf(0xf5dee51989L, 0xa9fdca3312L, 0x1bab10e32dL, 0x3706b1677aL, 0x644d626ffdL)

    /** Prüfsumme eines Deskriptors (BIP380, 8 Zeichen); Zeichen ausserhalb des Deskriptor-Alphabets = null. */
    fun descriptorChecksum(descriptor: String): String? {
        val symbols = ArrayList<Int>()
        val groups = ArrayList<Int>()
        for (ch in descriptor) {
            val v = DESCRIPTOR_INPUT.indexOf(ch)
            if (v < 0) return null
            symbols.add(v and 31)
            groups.add(v shr 5)
            if (groups.size == 3) {
                symbols.add(groups[0] * 9 + groups[1] * 3 + groups[2])
                groups.clear()
            }
        }
        if (groups.size == 1) symbols.add(groups[0])
        if (groups.size == 2) symbols.add(groups[0] * 3 + groups[1])
        repeat(8) { symbols.add(0) }
        var c = 1L
        for (v in symbols) {
            val top = c ushr 35
            c = ((c and 0x7ffffffffL) shl 5) xor v.toLong()
            for (i in 0 until 5) if ((top ushr i) and 1L == 1L) c = c xor DESCRIPTOR_GEN[i]
        }
        c = c xor 1L
        val sb = StringBuilder()
        for (i in 0 until 8) sb.append(CHARSET[((c ushr (5 * (7 - i))) and 31L).toInt()])
        return sb.toString()
    }

    /** «bitcoin:ADR?amount=…», «ethereum:pay-ADR@1/…» → ADR. */
    private fun stripUri(s: String): String {
        val lower = s.lowercase()
        return when {
            lower.startsWith("bitcoin:") -> s.substring(8).substringBefore('?')
            lower.startsWith("litecoin:") -> s.substring(9).substringBefore('?')
            lower.startsWith("solana:") -> s.substring(7).substringBefore('?')
            lower.startsWith("ethereum:") -> {
                var rest = s.substring(9)
                if (rest.lowercase().startsWith("pay-")) rest = rest.substring(4)
                rest.substringBefore('?').substringBefore('@').substringBefore('/')
            }
            else -> s
        }.trim()
    }

    /** Nur ASCII-Leerraum zählt (gleich wie iOS). */
    private fun isSpace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r'

    fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    // ---------------- Ethereum ----------------

    /** EIP-55: Buchstabe gross, wenn das Halbbyte des Keccak-256 der kleingeschriebenen Adresse ≥ 8 ist. */
    fun eip55(body40: String): String {
        val lower = body40.lowercase()
        val hash = Keccak256.hex(lower.toByteArray(Charsets.US_ASCII))
        val sb = StringBuilder("0x")
        for (i in lower.indices) {
            val c = lower[i]
            sb.append(if (c in 'a'..'f' && hash[i].digitToInt(16) >= 8) c.uppercaseChar() else c)
        }
        return sb.toString()
    }

    private fun ethereum(body: String): Check {
        val checksummed = eip55(body)
        val hasLower = body.any { it in 'a'..'f' }
        val hasUpper = body.any { it in 'A'..'F' }
        if (hasLower && hasUpper && "0x$body" != checksummed) return invalid(Problem.BAD_CHECKSUM)
        return Check(AddressChain.ETH, Kind.ETH, checksummed, null)
    }

    // Bech32/Bech32m (AddressValidationBitcoin.kt), auch für Cardano und die Deskriptor-Prüfsumme
    const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    const val BECH32_CONST = 1
}
