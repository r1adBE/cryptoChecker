// Hex-Betrag einer Ethereum-artigen Antwort als Dezimalzahl — Teil von AddressBalance.
package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.domain.util.json.JsonObject
import java.math.BigDecimal

/** Hex (ohne «0x», höchstens 64 Stellen) → Dezimalziffern ohne führende Nullen; ungültig = null. */
fun AddressBalance.hexToDecimal(hex: String): String? {
    if (hex.isEmpty() || hex.length > 64) return null
    val digits = ArrayList<Int>() // Basis 10, niedrigste Stelle zuerst
    digits.add(0)
    for (c in hex) {
        var carry = Character.digit(c, 16)
        if (carry < 0 || !(c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F')) return null
        for (i in digits.indices) {
            val v = digits[i] * 16 + carry
            digits[i] = v % 10
            carry = v / 10
        }
        while (carry > 0) {
            digits.add(carry % 10)
            carry /= 10
        }
    }
    while (digits.size > 1 && digits.last() == 0) digits.removeAt(digits.size - 1)
    return digits.asReversed().joinToString("")
}
