package com.cryptochecker.marketdata.usage

/** Eine Stunde ([hour] = Millisekunden seit 1970 / 1 h): Hintergrund-Läufe, Netzanfragen und empfangene Bytes. */
data class UsageBucket(val hour: Long, val runs: Int, val requests: Int, val bytes: Long)

/** Summe über ein Fenster (24 h): Hintergrund-Läufe, Netzanfragen, empfangene Bytes. */
data class UsageTotals(val runs: Int, val requests: Int, val bytes: Long)

/** Stand der Uhr fürs Handy: Summe der letzten 24 h zur Zeit [at]. */
data class UsageReport(val at: Long, val totals: UsageTotals)

/** Einstufung «gering / mittel / hoch» ([UsageLog.level]); [code] steht im Testfall (gleich auf iOS). */
enum class UsageLevel(val code: String) { LOW("low"), MEDIUM("medium"), HIGH("high") }

/** Was die Aktivität vor allem treibt ([UsageLog.driver]); [code] steht im Testfall (gleich auf iOS). */
enum class UsageDriver(val code: String) {
    /** Keine Hintergrund-Prüfung geplant: es zählt, was die offene App und die Widgets abrufen. */
    NONE("none"),

    /** Hintergrund-Prüfung im gewählten Intervall. */
    INTERVAL("interval"),

    /** Dazu «Pünktlich prüfen» (nur Android). */
    INTERVAL_EXACT("interval_exact"),
}

/**
 * «Akku & Aktivität»: Apps können ihren eigenen Akkuverbrauch nicht auslesen — gezählt werden darum je Stunde nur
 * Hintergrund-Läufe, Netzanfragen und empfangene Bytes, auf dem Gerät, ohne Inhalte und ohne Hosts. Gezeigt wird die
 * Summe der letzten [WINDOW_HOURS] Stunden; behalten höchstens [KEEP_HOURS] Stunden. Feste Grenzen je 24 h (gleich auf
 * iOS, `testdata/parity/usage_log.json`): mittel ab [MEDIUM_RUNS] Läufen, [MEDIUM_REQUESTS] Anfragen oder
 * [MEDIUM_BYTES] Bytes, hoch ab [HIGH_RUNS], [HIGH_REQUESTS] bzw. [HIGH_BYTES]; es zählt die höchste der drei Stufen.
 * Datei: eine Zeile je Stunde `stunde;läufe;anfragen;bytes`. Reine Logik — wie `UsageLog.swift`.
 */
object UsageLog {
    const val HOUR_MILLIS = 3_600_000L
    const val WINDOW_HOURS = 24
    const val KEEP_HOURS = 168

    /** 48 Läufe = alle 30 Min.; 144 = alle 10 Min. (etwa Intervall 15 Min. und «Pünktlich prüfen»). */
    const val MEDIUM_RUNS = 48
    const val HIGH_RUNS = 144
    const val MEDIUM_REQUESTS = 500
    const val HIGH_REQUESTS = 2_000
    const val MEDIUM_BYTES = 10_000_000L
    const val HIGH_BYTES = 50_000_000L

    /** Höchstens so viele Zeilen lesen (eine Datei wächst nie darüber, [merge] kürzt). */
    private const val MAX_LINES = 1_000
    private const val REPORT_VERSION = "1"

    /** Stunde zu [millis] (abgerundet, auch vor 1970). */
    fun hourOf(millis: Long): Long = Math.floorDiv(millis, HOUR_MILLIS)

    /** [runs], [requests] und [bytes] zur Stunde von [nowMillis] dazuzählen. */
    fun add(buckets: List<UsageBucket>, nowMillis: Long, runs: Int = 0, requests: Int = 0, bytes: Long = 0): List<UsageBucket> =
        merge(buckets, listOf(UsageBucket(hourOf(nowMillis), runs, requests, bytes)), nowMillis)

    /**
     * [a] und [b] je Stunde zusammenzählen, aufsteigend; nur Stunden der letzten [KEEP_HOURS] bis zur Stunde von
     * [nowMillis] (spätere = falsch gestellte Uhr, fallen weg). Negative Werte zählen als 0, Summen höchstens
     * [Int.MAX_VALUE] bzw. [Long.MAX_VALUE].
     */
    fun merge(a: List<UsageBucket>, b: List<UsageBucket>, nowMillis: Long): List<UsageBucket> {
        val now = hourOf(nowMillis)
        val sums = sortedMapOf<Long, LongArray>()
        for (bucket in a + b) {
            if (bucket.hour <= now - KEEP_HOURS || bucket.hour > now) continue
            val sum = sums.getOrPut(bucket.hour) { LongArray(3) }
            sum[0] = plus(sum[0], bucket.runs.toLong())
            sum[1] = plus(sum[1], bucket.requests.toLong())
            sum[2] = plus(sum[2], bucket.bytes)
        }
        return sums.map { (hour, s) -> UsageBucket(hour, toInt(s[0]), toInt(s[1]), s[2]) }
    }

    /** Summe der letzten [WINDOW_HOURS] Stunden bis zur Stunde von [nowMillis]. */
    fun totals(buckets: List<UsageBucket>, nowMillis: Long): UsageTotals {
        val now = hourOf(nowMillis)
        var runs = 0L
        var requests = 0L
        var bytes = 0L
        for (bucket in buckets) {
            if (bucket.hour <= now - WINDOW_HOURS || bucket.hour > now) continue
            runs = plus(runs, bucket.runs.toLong())
            requests = plus(requests, bucket.requests.toLong())
            bytes = plus(bytes, bucket.bytes)
        }
        return UsageTotals(toInt(runs), toInt(requests), bytes)
    }

    /** Höchste Stufe der drei Zahlen nach den festen Grenzen. */
    fun level(totals: UsageTotals): UsageLevel {
        val high = totals.runs >= HIGH_RUNS || totals.requests >= HIGH_REQUESTS || totals.bytes >= HIGH_BYTES
        if (high) return UsageLevel.HIGH
        val medium = totals.runs >= MEDIUM_RUNS || totals.requests >= MEDIUM_REQUESTS || totals.bytes >= MEDIUM_BYTES
        return if (medium) UsageLevel.MEDIUM else UsageLevel.LOW
    }

    /** Treiber: ohne geplante Hintergrund-Prüfung [UsageDriver.NONE], mit wirksamem «Pünktlich prüfen» dazu dieses. */
    fun driver(backgroundPlanned: Boolean, exactActive: Boolean): UsageDriver = when {
        !backgroundPlanned -> UsageDriver.NONE
        exactActive -> UsageDriver.INTERVAL_EXACT
        else -> UsageDriver.INTERVAL
    }

    fun encode(buckets: List<UsageBucket>): String =
        buckets.joinToString("\n") { "${it.hour};${it.runs};${it.requests};${it.bytes}" }

    /** Unlesbare Zeilen fallen weg, negative Zahlen zählen als 0; Reihenfolge der Datei ([merge] ordnet). */
    fun decode(text: String?): List<UsageBucket> {
        if (text.isNullOrBlank()) return emptyList()
        val out = ArrayList<UsageBucket>()
        for (raw in text.split('\n').take(MAX_LINES)) {
            val parts = raw.trim().split(';')
            if (parts.size != 4) continue
            val hour = parts[0].toLongOrNull() ?: continue
            val runs = parts[1].toIntOrNull() ?: continue
            val requests = parts[2].toIntOrNull() ?: continue
            val bytes = parts[3].toLongOrNull() ?: continue
            out += UsageBucket(hour, runs.coerceAtLeast(0), requests.coerceAtLeast(0), bytes.coerceAtLeast(0))
        }
        return out
    }

    /** Stand der Uhr als Text fürs Handy: `1;zeit;läufe;anfragen;bytes`. */
    fun encodeReport(report: UsageReport): String = with(report.totals) {
        "$REPORT_VERSION;${report.at};$runs;$requests;$bytes"
    }

    /** null = fehlt, andere Fassung oder unlesbar (dann zeigt das Handy «Keine Uhr verbunden»). */
    fun decodeReport(text: String?): UsageReport? {
        val parts = text?.trim()?.split(';') ?: return null
        if (parts.size != 5 || parts[0] != REPORT_VERSION) return null
        val at = parts[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
        val runs = parts[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val requests = parts[3].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val bytes = parts[4].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        return UsageReport(at, UsageTotals(runs, requests, bytes))
    }

    /** Länge von [text] in UTF-8 (Bytes einer Live-Nachricht), ohne Kopie; wie `String.utf8.count` auf iOS. */
    fun utf8Length(text: CharSequence): Long {
        var bytes = 0L
        var i = 0
        while (i < text.length) {
            val c = text[i]
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> { i++; 4 }
                else -> 3
            }
            i++
        }
        return bytes
    }

    /** Summe ohne Überlauf; negative Teile zählen als 0. */
    private fun plus(a: Long, b: Long): Long {
        val add = b.coerceAtLeast(0)
        return if (a > Long.MAX_VALUE - add) Long.MAX_VALUE else a + add
    }

    private fun toInt(value: Long): Int = value.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
}
