package com.cryptochecker.app.settings

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Farbabstand nach CIEDE2000 (Sharma, Wu, Dalal 2005) — reines Kotlin, wie iOS
 * `ColorDifference.swift`. Damit prüft [PriceColorScheme], ob eine Kursfarbe neben der
 * Akzentfarbe zu ähnlich aussieht (ΔE2000 unter [PriceColorScheme.MIN_ACCENT_DELTA_E]).
 */
object ColorDifference {

    /** sRGB (ARGB/RGB, Alpha egal) → CIELAB (D65): [L, a, b]. */
    fun lab(rgb: Int): DoubleArray {
        val r = linear((rgb shr 16) and 0xFF)
        val g = linear((rgb shr 8) and 0xFF)
        val b = linear(rgb and 0xFF)
        val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
        val fx = f(x)
        val fy = f(y)
        val fz = f(z)
        return doubleArrayOf(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    /** ΔE2000 zweier sRGB-Farben. */
    fun deltaE2000(rgb1: Int, rgb2: Int): Double = deltaE2000(lab(rgb1), lab(rgb2))

    /** ΔE2000 zweier CIELAB-Werte [L, a, b] (kL = kC = kH = 1). */
    fun deltaE2000(lab1: DoubleArray, lab2: DoubleArray): Double {
        val (l1, a1, b1) = Triple(lab1[0], lab1[1], lab1[2])
        val (l2, a2, b2) = Triple(lab2[0], lab2[1], lab2[2])
        val cBar = (hypot(a1, b1) + hypot(a2, b2)) / 2
        val g = 0.5 * (1 - sqrt(cBar.pow(7) / (cBar.pow(7) + POW25_7)))
        val a1p = (1 + g) * a1
        val a2p = (1 + g) * a2
        val c1p = hypot(a1p, b1)
        val c2p = hypot(a2p, b2)
        val h1 = hue(b1, a1p)
        val h2 = hue(b2, a2p)
        val dL = l2 - l1
        val dC = c2p - c1p
        val product = c1p * c2p
        var dh = h2 - h1
        if (product == 0.0) dh = 0.0 else if (dh > 180) dh -= 360 else if (dh < -180) dh += 360
        val dH = 2 * sqrt(product) * sin(rad(dh / 2))
        val lBar = (l1 + l2) / 2
        val cBarP = (c1p + c2p) / 2
        val hBar = when {
            product == 0.0 -> h1 + h2
            abs(h1 - h2) <= 180 -> (h1 + h2) / 2
            h1 + h2 < 360 -> (h1 + h2 + 360) / 2
            else -> (h1 + h2 - 360) / 2
        }
        val t = 1 - 0.17 * cos(rad(hBar - 30)) + 0.24 * cos(rad(2 * hBar)) +
            0.32 * cos(rad(3 * hBar + 6)) - 0.20 * cos(rad(4 * hBar - 63))
        val dTheta = 30 * exp(-((hBar - 275) / 25).pow(2))
        val rc = 2 * sqrt(cBarP.pow(7) / (cBarP.pow(7) + POW25_7))
        val sl = 1 + 0.015 * (lBar - 50).pow(2) / sqrt(20 + (lBar - 50).pow(2))
        val sc = 1 + 0.045 * cBarP
        val sh = 1 + 0.015 * cBarP * t
        val rt = -sin(rad(2 * dTheta)) * rc
        return sqrt((dL / sl).pow(2) + (dC / sc).pow(2) + (dH / sh).pow(2) + rt * (dC / sc) * (dH / sh))
    }

    private const val POW25_7 = 6_103_515_625.0 // 25^7

    private fun linear(channel: Int): Double {
        val s = channel / 255.0
        return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    private fun f(t: Double): Double = if (t > 216.0 / 24389.0) cbrt(t) else (24389.0 / 27.0 * t + 16.0) / 116.0

    /** Farbwinkel in Grad 0 ..< 360; ohne Buntheit 0. */
    private fun hue(b: Double, a: Double): Double =
        if (a == 0.0 && b == 0.0) 0.0 else (Math.toDegrees(atan2(b, a)) + 360) % 360

    private fun rad(degrees: Double): Double = Math.toRadians(degrees)
}
