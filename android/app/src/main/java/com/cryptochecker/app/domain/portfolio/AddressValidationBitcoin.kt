// Bitcoin-Adressen (Teil von [AddressValidation], gespiegelt in `AddressValidation+Bitcoin.swift`): Base58Check und
// Bech32/Bech32m. Erweiterungsfunktionen des Objekts.
package com.cryptochecker.app.domain.portfolio

import java.security.MessageDigest
import com.cryptochecker.app.domain.portfolio.AddressValidation.Check
import com.cryptochecker.app.domain.portfolio.AddressValidation.Kind
import com.cryptochecker.app.domain.portfolio.AddressValidation.Problem

// ---------------- Bitcoin Base58Check ----------------

private const val BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

/** Base58 → Bytes (führende Null-Ziffer = Null-Bytes); ungültiges Zeichen = null. Ripple: eigenes Alphabet. */
internal fun AddressValidation.base58Decode(s: String, alphabet: String = BASE58): ByteArray? {
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

private fun AddressValidation.sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

internal fun AddressValidation.checksumOk(raw: ByteArray): Boolean {
    if (raw.size < 5) return false
    val payload = raw.copyOfRange(0, raw.size - 4)
    val sum = sha256(sha256(payload))
    for (i in 0 until 4) if (sum[i] != raw[raw.size - 4 + i]) return false
    return true
}

internal fun AddressValidation.base58(s: String): Check {
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

private const val BECH32M_CONST = 0x2bc830a3
private val GEN = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

internal fun AddressValidation.polymod(values: IntArray): Int {
    var chk = 1
    for (v in values) {
        val top = chk ushr 25
        chk = ((chk and 0x1ffffff) shl 5) xor v
        for (i in 0 until 5) if ((top ushr i) and 1 == 1) chk = chk xor GEN[i]
    }
    return chk
}

internal fun AddressValidation.segwit(input: String): Check {
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
internal fun AddressValidation.convertBits(data: IntArray): IntArray? {
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
