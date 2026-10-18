package com.cryptochecker.app.domain.portfolio

import java.security.MessageDigest

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
    private const val MAX_INPUT = 200

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

    private fun invalid(problem: Problem) = Check(null, null, null, problem)

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

    // ---------------- Cardano ----------------

    /** Shelley-Adresse (Bech32, Länge bis [MAX_INPUT]): Kopfbyte Typ 0…7 und Netz-ID 1 (Mainnet). */
    private fun cardano(input: String): Check {
        val hasLower = input.any { it in 'a'..'z' }
        val hasUpper = input.any { it in 'A'..'Z' }
        if (hasLower && hasUpper) return invalid(Problem.UNKNOWN_FORMAT)
        val s = input.lowercase()
        val sep = s.lastIndexOf('1')
        if (sep < 1 || sep + 7 > s.length) return invalid(Problem.UNKNOWN_FORMAT)
        val hrp = s.substring(0, sep)
        val data = IntArray(s.length - sep - 1)
        for (i in data.indices) {
            val v = CHARSET.indexOf(s[sep + 1 + i])
            if (v < 0) return invalid(Problem.UNKNOWN_FORMAT)
            data[i] = v
        }
        val expanded = IntArray(hrp.length * 2 + 1 + data.size)
        for (i in hrp.indices) {
            expanded[i] = hrp[i].code ushr 5
            expanded[hrp.length + 1 + i] = hrp[i].code and 31
        }
        data.copyInto(expanded, hrp.length * 2 + 1)
        if (polymod(expanded) != BECH32_CONST) return invalid(Problem.BAD_CHECKSUM)
        if (hrp == "addr_test" || hrp == "stake_test") return invalid(Problem.TESTNET)
        if (hrp == "stake") return invalid(Problem.UNSUPPORTED_VERSION)
        if (hrp != "addr") return invalid(Problem.UNKNOWN_FORMAT)
        val bytes = convertBits(data.copyOfRange(0, data.size - 6)) ?: return invalid(Problem.UNKNOWN_FORMAT)
        if (bytes.size < 29) return invalid(Problem.UNKNOWN_FORMAT)
        val type = bytes[0] ushr 4
        val network = bytes[0] and 0x0f
        if (type > 7) return invalid(Problem.UNKNOWN_FORMAT)
        if (network != 1) return invalid(Problem.TESTNET)
        return Check(AddressChain.ADA, Kind.ADA, s, null)
    }

    /** «[1, 2, …]» mit 32 bis 64 Zahlen 0…255 (Schlüsseldatei). */
    private fun isByteArrayText(s: String): Boolean {
        if (!s.endsWith("]")) return false
        val parts = s.substring(1, s.length - 1).split(',').map { it.trim() }
        return parts.size in 32..64 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all { it in '0'..'9' } && p.toInt() <= 255 }
    }

    // ---------------- XRP ----------------

    private const val RIPPLE = "rpshnaf39wBUDNEGHJKLM4PQRST7VWXYZ2bcdeCg65jkm8oFqi1tuvAxyz"

    /** Ripple-Base58 mit gültiger Prüfsumme → Nutzdaten ohne Prüfsumme; sonst null. */
    private fun rippleChecked(s: String): ByteArray? {
        val raw = base58Decode(s, RIPPLE) ?: return null
        if (!checksumOk(raw)) return null
        return raw.copyOfRange(0, raw.size - 4)
    }

    /** Klassische XRP-Adresse; null = keine (weiter mit Base58). */
    private fun xrp(s: String): Check? {
        val raw = base58Decode(s, RIPPLE) ?: return null
        if (raw.size != 25 || raw[0].toInt() != 0) return null
        if (!checksumOk(raw)) return invalid(Problem.BAD_CHECKSUM)
        return Check(AddressChain.XRP, Kind.XRP, s, null)
    }

    // ---------------- TON ----------------

    /** «0:<64 Hex>» (Basis-Workchain); «-1:…» Masterchain nicht unterstützt; null = keine rohe Adresse. */
    private fun tonRaw(s: String): Check? {
        val sep = s.indexOf(':')
        if (sep < 1 || s.length - sep - 1 != 64) return null
        val wc = s.substring(0, sep)
        val hex = s.substring(sep + 1)
        if (!hex.all { isHex(it) } || !(wc == "0" || wc == "-1")) return null
        if (wc == "-1") return invalid(Problem.UNSUPPORTED_VERSION)
        return Check(AddressChain.TON, Kind.TON, "0:" + hex.lowercase(), null)
    }

    /** Benutzerfreundliche TON-Adresse (36 Byte: Kennung, Workchain, 32 Byte, CRC16); null = keine. */
    private fun tonFriendly(s: String): Check? {
        val raw = base64Decode(s) ?: return null
        if (raw.size != 36) return null
        val tag = raw[0].toInt() and 0xff
        if (tag and 0x7f != 0x11 && tag and 0x7f != 0x51) return null
        val crc = crc16(raw.copyOfRange(0, 34))
        if (((raw[34].toInt() and 0xff) shl 8 or (raw[35].toInt() and 0xff)) != crc) return invalid(Problem.BAD_CHECKSUM)
        if (tag and 0x80 != 0) return invalid(Problem.TESTNET)
        return when (raw[1].toInt() and 0xff) {
            0x00 -> Check(AddressChain.TON, Kind.TON, s, null)
            0xff -> invalid(Problem.UNSUPPORTED_VERSION)
            else -> invalid(Problem.UNKNOWN_FORMAT)
        }
    }

    /** Base64 (Standard oder URL-sicher, ohne Auffüllen); ungültig = null. */
    internal fun base64Decode(s: String): ByteArray? {
        val out = ArrayList<Byte>()
        var acc = 0
        var bits = 0
        for (c in s) {
            val v = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '+', '-' -> 62
                '/', '_' -> 63
                else -> return null
            }
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((acc shr bits) and 0xff).toByte())
            }
            acc = acc and 0xffff
        }
        return out.toByteArray()
    }

    /** CRC16-XMODEM (Polynom 0x1021, Start 0) — TON-Adressen. */
    internal fun crc16(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xff) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
            crc = crc and 0xffff
        }
        return crc
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

    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

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

    // ---------------- Bitcoin Base58Check ----------------

    private const val BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    /** Base58 → Bytes (führende Null-Ziffer = Null-Bytes); ungültiges Zeichen = null. Ripple: eigenes Alphabet. */
    internal fun base58Decode(s: String, alphabet: String = BASE58): ByteArray? {
        val number = ArrayList<Int>() // Basis 256, niedrigstes Byte zuerst
        for (ch in s) {
            var carry = alphabet.indexOf(ch)
            if (carry < 0) return null
            for (i in number.indices) {
                carry += number[i] * 58
                number[i] = carry and 0xff
                carry = carry shr 8
            }
            while (carry > 0) {
                number.add(carry and 0xff)
                carry = carry shr 8
            }
        }
        val zeros = s.takeWhile { it == alphabet[0] }.length
        val out = ByteArray(zeros + number.size)
        for (i in number.indices) out[zeros + i] = number[number.size - 1 - i].toByte()
        return out
    }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    private fun checksumOk(raw: ByteArray): Boolean {
        if (raw.size < 5) return false
        val payload = raw.copyOfRange(0, raw.size - 4)
        val sum = sha256(sha256(payload))
        for (i in 0 until 4) if (sum[i] != raw[raw.size - 4 + i]) return false
        return true
    }

    private fun base58(s: String): Check {
        val raw = base58Decode(s) ?: return invalid(Problem.UNKNOWN_FORMAT)
        val version = if (raw.isNotEmpty()) raw[0].toInt() and 0xff else -1
        return when (raw.size) {
            25 -> {
                if (!checksumOk(raw)) return invalid(Problem.BAD_CHECKSUM)
                when (version) {
                    0x00 -> Check(AddressChain.BTC, Kind.P2PKH, s, null)
                    0x05 -> Check(AddressChain.BTC, Kind.P2SH, s, null)
                    0x30 -> Check(AddressChain.LTC, Kind.P2PKH, s, null)
                    0x32 -> Check(AddressChain.LTC, Kind.P2SH, s, null)
                    0x41 -> Check(AddressChain.TRX, Kind.TRX, s, null)
                    0x1e, 0x16 -> invalid(Problem.DOGE_UNSUPPORTED)
                    0x6f, 0xc4, 0x3a -> invalid(Problem.TESTNET)
                    else -> invalid(Problem.UNKNOWN_FORMAT)
                }
            }
            // WIF: privater Schlüssel (0x80 Bitcoin, 0xb0 Litecoin, 0xef Testnet; 33 bzw. 34 Byte + Prüfsumme)
            37, 38 -> if ((version == 0x80 || version == 0xb0 || version == 0x9e || version == 0xef) && checksumOk(raw)) invalid(Problem.SECRET) else invalid(Problem.UNKNOWN_FORMAT)
            // Solana: öffentlicher Schlüssel (32 Byte, ohne Prüfsumme) bzw. privater Schlüssel (64 Byte)
            32 -> if (s.length in 32..44) Check(AddressChain.SOL, Kind.SOL, s, null) else invalid(Problem.UNKNOWN_FORMAT)
            64 -> invalid(Problem.SECRET)
            else -> invalid(Problem.UNKNOWN_FORMAT)
        }
    }

    // ---------------- Bitcoin Bech32 / Bech32m ----------------

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private const val BECH32_CONST = 1
    private const val BECH32M_CONST = 0x2bc830a3
    private val GEN = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) if ((top ushr i) and 1 == 1) chk = chk xor GEN[i]
        }
        return chk
    }

    private fun segwit(input: String): Check {
        if (input.length < 8 || input.length > 90) return invalid(Problem.UNKNOWN_FORMAT)
        if (input.any { it.code < 33 || it.code > 126 }) return invalid(Problem.UNKNOWN_FORMAT)
        val hasLower = input.any { it in 'a'..'z' }
        val hasUpper = input.any { it in 'A'..'Z' }
        if (hasLower && hasUpper) return invalid(Problem.UNKNOWN_FORMAT)
        val s = input.lowercase()
        val sep = s.lastIndexOf('1')
        if (sep < 1 || sep + 7 > s.length) return invalid(Problem.UNKNOWN_FORMAT)
        val hrp = s.substring(0, sep)
        val data = IntArray(s.length - sep - 1)
        for (i in data.indices) {
            val v = CHARSET.indexOf(s[sep + 1 + i])
            if (v < 0) return invalid(Problem.UNKNOWN_FORMAT)
            data[i] = v
        }
        if (hrp == "tb" || hrp == "bcrt" || hrp == "tltc") return invalid(Problem.TESTNET)
        val chain = when (hrp) {
            "bc" -> AddressChain.BTC
            "ltc" -> AddressChain.LTC
            else -> return invalid(Problem.UNKNOWN_FORMAT)
        }

        val expanded = IntArray(hrp.length * 2 + 1 + data.size)
        for (i in hrp.indices) {
            expanded[i] = hrp[i].code ushr 5
            expanded[hrp.length + 1 + i] = hrp[i].code and 31
        }
        data.copyInto(expanded, hrp.length * 2 + 1)
        val bech32m = when (polymod(expanded)) {
            BECH32_CONST -> false
            BECH32M_CONST -> true
            else -> return invalid(Problem.BAD_CHECKSUM)
        }
        val values = data.copyOfRange(0, data.size - 6)
        if (values.isEmpty()) return invalid(Problem.UNKNOWN_FORMAT)
        val version = values[0]
        if (version > 16) return invalid(Problem.UNKNOWN_FORMAT)
        val program = convertBits(values.copyOfRange(1, values.size)) ?: return invalid(Problem.UNKNOWN_FORMAT)
        if (program.size < 2 || program.size > 40) return invalid(Problem.UNKNOWN_FORMAT)
        // v0 nur mit Bech32, ab v1 nur mit Bech32m (BIP350)
        if ((version == 0) == bech32m) return invalid(Problem.BAD_CHECKSUM)
        return when {
            version == 0 && program.size == 20 -> Check(chain, Kind.P2WPKH, s, null)
            version == 0 && program.size == 32 -> Check(chain, Kind.P2WSH, s, null)
            version == 0 -> invalid(Problem.UNKNOWN_FORMAT)
            version == 1 && program.size == 32 -> Check(chain, Kind.P2TR, s, null)
            else -> invalid(Problem.UNSUPPORTED_VERSION)
        }
    }

    /** 5-Bit-Gruppen → Bytes ohne Auffüllen; überzählige Bits müssen null sein. */
    private fun convertBits(data: IntArray): IntArray? {
        var acc = 0
        var bits = 0
        val out = ArrayList<Int>()
        for (v in data) {
            acc = ((acc shl 5) or v) and 0xfff
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.add((acc ushr bits) and 0xff)
            }
        }
        if (bits >= 5 || ((acc shl (8 - bits)) and 0xff) != 0) return null
        return out.toIntArray()
    }
}
