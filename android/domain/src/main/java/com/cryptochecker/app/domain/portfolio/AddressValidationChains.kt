// Adressen weiterer Netze (Teil von [AddressValidation], gespiegelt in `AddressValidation+Chains.swift`): Cardano, XRP
// und TON. Erweiterungsfunktionen des Objekts.
package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.domain.portfolio.AddressValidation.Check
import com.cryptochecker.app.domain.portfolio.AddressValidation.Kind
import com.cryptochecker.app.domain.portfolio.AddressValidation.Problem

// ---------------- Cardano ----------------

/** Shelley-Adresse (Bech32, Länge bis [MAX_INPUT]): Kopfbyte Typ 0…7 und Netz-ID 1 (Mainnet). */
fun AddressValidation.cardano(input: String): Check {
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
fun AddressValidation.isByteArrayText(s: String): Boolean {
    if (!s.endsWith("]")) return false
    val parts = s.substring(1, s.length - 1).split(',').map { it.trim() }
    return parts.size in 32..64 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all { it in '0'..'9' } && p.toInt() <= 255 }
}

// ---------------- XRP ----------------

private const val RIPPLE = "rpshnaf39wBUDNEGHJKLM4PQRST7VWXYZ2bcdeCg65jkm8oFqi1tuvAxyz"

/** Ripple-Base58 mit gültiger Prüfsumme → Nutzdaten ohne Prüfsumme; sonst null. */
fun AddressValidation.rippleChecked(s: String): ByteArray? {
    val raw = base58Decode(s, RIPPLE) ?: return null
    if (!checksumOk(raw)) return null
    return raw.copyOfRange(0, raw.size - 4)
}

/** Klassische XRP-Adresse; null = keine (weiter mit Base58). */
fun AddressValidation.xrp(s: String): Check? {
    val raw = base58Decode(s, RIPPLE) ?: return null
    if (raw.size != 25 || raw[0].toInt() != 0) return null
    if (!checksumOk(raw)) return invalid(Problem.BAD_CHECKSUM)
    return Check(AddressChain.XRP, Kind.XRP, s, null)
}

// ---------------- TON ----------------

/** «0:<64 Hex>» (Basis-Workchain); «-1:…» Masterchain nicht unterstützt; null = keine rohe Adresse. */
fun AddressValidation.tonRaw(s: String): Check? {
    val sep = s.indexOf(':')
    if (sep < 1 || s.length - sep - 1 != 64) return null
    val wc = s.substring(0, sep)
    val hex = s.substring(sep + 1)
    if (!hex.all { isHex(it) } || !(wc == "0" || wc == "-1")) return null
    if (wc == "-1") return invalid(Problem.UNSUPPORTED_VERSION)
    return Check(AddressChain.TON, Kind.TON, "0:" + hex.lowercase(), null)
}

/** Benutzerfreundliche TON-Adresse (36 Byte: Kennung, Workchain, 32 Byte, CRC16); null = keine. */
fun AddressValidation.tonFriendly(s: String): Check? {
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
fun AddressValidation.base64Decode(s: String): ByteArray? {
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
fun AddressValidation.crc16(data: ByteArray): Int {
    var crc = 0
    for (b in data) {
        crc = crc xor ((b.toInt() and 0xff) shl 8)
        repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
        crc = crc and 0xffff
    }
    return crc
}
