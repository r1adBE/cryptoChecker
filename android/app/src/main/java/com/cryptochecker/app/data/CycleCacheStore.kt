package com.cryptochecker.app.data

import android.content.Context
import com.cryptochecker.app.domain.market.AltSeason
import com.cryptochecker.app.domain.market.BtcFees
import com.cryptochecker.app.domain.market.CoinInputs
import com.cryptochecker.app.domain.market.CycleCachePolicy
import com.cryptochecker.app.domain.market.CycleHistory
import com.cryptochecker.app.domain.market.CycleInputs
import com.cryptochecker.app.domain.market.CycleMarker
import com.cryptochecker.app.domain.market.CycleSeries
import com.cryptochecker.app.domain.market.Dominance
import com.cryptochecker.app.domain.market.EvmGas
import com.cryptochecker.app.domain.market.FearGreed
import com.cryptochecker.app.domain.market.GasNetwork
import com.cryptochecker.app.domain.market.GasReport
import com.cryptochecker.app.domain.market.GlobalMarket
import com.cryptochecker.app.domain.market.MarketTotals
import com.cryptochecker.app.domain.market.OnChainValues
import com.cryptochecker.app.domain.market.PulseInput
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Ein gespeicherter Wert und wann er geholt wurde (Epoch-ms). */
data class CachedValue<T>(val value: T, val savedAt: Long)

/** Wandelt einen Wert in JSON und zurück; [decode] darf bei ungültigem Inhalt werfen. */
class CacheCodec<T>(val encode: (T) -> JSONObject, val decode: (JSONObject) -> T)

/**
 * Zwischenspeicher des Markt-Tabs auf dem Gerät: eine kleine JSON-Datei je Bereich
 * in `cacheDir/cycle` (das System darf sie bei Platzmangel löschen — dann wird eben
 * neu geladen). Format mit Version im Namen und im Inhalt ([CycleCachePolicy.FORMAT_VERSION]);
 * alte, fremde oder kaputte Dateien gelten als «nichts gespeichert». Geschrieben wird
 * über eine Hilfsdatei und Umbenennen, damit nie eine halbe Datei gelesen wird.
 * Nur öffentliche Marktdaten, nichts zur Merkliste.
 */
@Singleton
class CycleCacheStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val dir: File get() = File(context.cacheDir, DIR)

    suspend fun <T> read(name: String, codec: CacheCodec<T>): CachedValue<T>? = withContext(Dispatchers.IO) {
        try {
            val file = File(dir, CycleCachePolicy.fileName(name))
            if (!file.isFile) return@withContext null
            val root = JSONObject(file.readText())
            val version = if (root.has(KEY_VERSION)) root.optInt(KEY_VERSION, -1) else null
            if (!CycleCachePolicy.isCurrentFormat(version) || root.optString(KEY_NAME) != name) {
                return@withContext null
            }
            val savedAt = root.optLong(KEY_AT, 0L)
            if (savedAt <= 0L) return@withContext null
            CachedValue(codec.decode(root.getJSONObject(KEY_DATA)), savedAt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "Zyklus-Zwischenspeicher %s unlesbar", name)
            null
        }
    }

    suspend fun <T> write(name: String, value: T, savedAt: Long, codec: CacheCodec<T>) {
        withContext(Dispatchers.IO) {
            try {
                val folder = dir
                if (!folder.isDirectory && !folder.mkdirs()) return@withContext
                val root = JSONObject()
                    .put(KEY_VERSION, CycleCachePolicy.FORMAT_VERSION)
                    .put(KEY_NAME, name)
                    .put(KEY_AT, savedAt)
                    .put(KEY_DATA, codec.encode(value))
                val target = File(folder, CycleCachePolicy.fileName(name))
                val temp = File(folder, target.name + ".tmp")
                temp.writeText(root.toString())
                if (!temp.renameTo(target)) {
                    target.delete()
                    if (!temp.renameTo(target)) temp.delete()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d(e, "Zyklus-Zwischenspeicher %s nicht geschrieben", name)
            }
        }
    }

    private companion object {
        const val DIR = "cycle"
        const val KEY_VERSION = "v"
        const val KEY_NAME = "name"
        const val KEY_AT = "at"
        const val KEY_DATA = "data"
    }
}

/** JSON-Formate der zwischengespeicherten Bereiche (Version: [CycleCachePolicy.FORMAT_VERSION]). */
object CycleCacheCodecs {

    val pulseInput = CacheCodec<PulseInput>(
        encode = { p ->
            JSONObject()
                .putNumber("btc24h", p.btc24h)
                .putNumber("eth24h", p.eth24h)
                .putNumber("sol24h", p.sol24h)
                .putNumber("volumeRatio", p.btcVolumeRatio)
                .putNumber("fearGreed", p.fearGreed)
                .putNumber("funding", p.fundingPercent)
                .putNumber("gas", p.ethGasGwei)
                .put("time", p.time)
        },
        decode = { o ->
            PulseInput(
                btc24h = o.doubleOrNull("btc24h"),
                eth24h = o.doubleOrNull("eth24h"),
                sol24h = o.doubleOrNull("sol24h"),
                btcVolumeRatio = o.doubleOrNull("volumeRatio"),
                fearGreed = o.intOrNull("fearGreed"),
                fundingPercent = o.doubleOrNull("funding"),
                ethGasGwei = o.doubleOrNull("gas"),
                time = o.getLong("time"),
            )
        },
    )

    /** Ohne «today»: das Modell rechnet nach dem Laden mit dem aktuellen Datum. */
    val cycleInputs = CacheCodec<CycleInputs>(
        encode = { c ->
            JSONObject()
                .put("price", c.price)
                .putNumber("sma200d", c.sma200d)
                .putNumber("sma111d", c.sma111d)
                .putNumber("sma350d", c.sma350d)
                .putNumber("price30dAgo", c.price30dAgo)
                .putNumber("sma200w", c.sma200w)
                .putNumber("ath", c.ath)
                .putDate("athDate", c.athDate)
                .putNumber("mvrv", c.mvrv)
                .putNumber("puell", c.puell)
                .putNumber("hash30d", c.hash30d)
                .putNumber("hash60d", c.hash60d)
        },
        decode = { o ->
            CycleInputs(
                price = o.requireDouble("price"),
                sma200d = o.doubleOrNull("sma200d"),
                sma111d = o.doubleOrNull("sma111d"),
                sma350d = o.doubleOrNull("sma350d"),
                price30dAgo = o.doubleOrNull("price30dAgo"),
                sma200w = o.doubleOrNull("sma200w"),
                ath = o.doubleOrNull("ath"),
                athDate = o.dateOrNull("athDate"),
                mvrv = o.doubleOrNull("mvrv"),
                puell = o.doubleOrNull("puell"),
                hash30d = o.doubleOrNull("hash30d"),
                hash60d = o.doubleOrNull("hash60d"),
            )
        },
    )

    val onChain = CacheCodec<OnChainValues>(
        encode = { v ->
            JSONObject()
                .putNumber("mvrv", v.mvrv)
                .putNumber("puell", v.puell)
                .putNumber("hash30d", v.hash30d)
                .putNumber("hash60d", v.hash60d)
        },
        decode = { o ->
            OnChainValues(
                mvrv = o.doubleOrNull("mvrv"),
                puell = o.doubleOrNull("puell"),
                hash30d = o.doubleOrNull("hash30d"),
                hash60d = o.doubleOrNull("hash60d"),
            )
        },
    )

    val fearGreed = CacheCodec<FearGreed>(
        encode = { f ->
            JSONObject()
                .put("value", f.value)
                .putNumber("yesterday", f.yesterday)
                .putNumber("weekAgo", f.weekAgo)
                .putNumber("monthAgo", f.monthAgo)
        },
        decode = { o ->
            FearGreed(
                value = o.getInt("value"),
                yesterday = o.intOrNull("yesterday"),
                weekAgo = o.intOrNull("weekAgo"),
                monthAgo = o.intOrNull("monthAgo"),
            )
        },
    )

    val global = CacheCodec<GlobalMarket>(
        encode = { g ->
            JSONObject()
                .put("btc", g.dominance.btc)
                .putNumber("eth", g.dominance.eth)
                .apply {
                    g.totals?.let { t ->
                        put(
                            "totals",
                            JSONObject()
                                .put("marketCap", t.marketCap.toJson())
                                .put("volume", t.volume.toJson())
                                .putNumber("change24h", t.changePercent24h)
                        )
                    }
                }
        },
        decode = { o ->
            GlobalMarket(
                dominance = Dominance(btc = o.requireDouble("btc"), eth = o.doubleOrNull("eth")),
                totals = o.optJSONObject("totals")?.let { t ->
                    MarketTotals(
                        marketCap = t.getJSONObject("marketCap").toDoubleMap(),
                        volume = t.getJSONObject("volume").toDoubleMap(),
                        changePercent24h = t.doubleOrNull("change24h"),
                    ).takeIf { it.marketCap.isNotEmpty() && it.volume.isNotEmpty() }
                },
            )
        },
    )

    val altSeason = CacheCodec<AltSeason>(
        encode = { a -> JSONObject().put("outperformers", a.outperformers).put("total", a.total) },
        decode = { o ->
            AltSeason(outperformers = o.getInt("outperformers"), total = o.getInt("total")).also {
                require(it.total >= 0 && it.outperformers in 0..it.total)
            }
        },
    )

    val history = CacheCodec<CycleHistory>(
        encode = { h ->
            val series = JSONArray()
            h.series.forEach { s ->
                val points = JSONArray()
                s.points.forEach { (day, value) -> if (value.isFinite()) points.put(JSONArray().put(day).put(value)) }
                series.put(
                    JSONObject()
                        .put("halving", s.halving.toString())
                        .put("points", points)
                        .putMarker("top", s.top)
                        .putMarker("bottom", s.bottom)
                        .putMarker("secondTop", s.secondTop)
                        .putMarker("secondBottom", s.secondBottom)
                )
            }
            JSONObject().put("series", series)
        },
        decode = { o ->
            val array = o.getJSONArray("series")
            CycleHistory(
                (0 until array.length()).map { i ->
                    val s = array.getJSONObject(i)
                    val points = s.getJSONArray("points")
                    CycleSeries(
                        halving = LocalDate.parse(s.getString("halving")),
                        points = (0 until points.length()).map { p ->
                            val pair = points.getJSONArray(p)
                            pair.getInt(0) to pair.getDouble(1)
                        },
                        top = s.markerOrNull("top"),
                        bottom = s.markerOrNull("bottom"),
                        secondTop = s.markerOrNull("secondTop"),
                        secondBottom = s.markerOrNull("secondBottom"),
                    )
                }
            )
        },
    )

    val coinInputs = CacheCodec<CoinInputs>(
        encode = { c ->
            JSONObject()
                .put("price", c.price)
                .putNumber("sma50d", c.sma50d)
                .putNumber("sma200d", c.sma200d)
                .putNumber("sma111d", c.sma111d)
                .putNumber("sma350d", c.sma350d)
                .putNumber("price30dAgo", c.price30dAgo)
                .putNumber("sma200w", c.sma200w)
                .putNumber("ath", c.ath)
                .putDate("athDate", c.athDate)
                .putNumber("rsiDaily", c.rsiDaily)
                .putNumber("rsiWeekly", c.rsiWeekly)
                .putNumber("vsBtc90d", c.vsBtc90d)
                .put("historyDays", c.historyDays)
        },
        decode = { o ->
            CoinInputs(
                price = o.requireDouble("price"),
                sma50d = o.doubleOrNull("sma50d"),
                sma200d = o.doubleOrNull("sma200d"),
                sma111d = o.doubleOrNull("sma111d"),
                sma350d = o.doubleOrNull("sma350d"),
                price30dAgo = o.doubleOrNull("price30dAgo"),
                sma200w = o.doubleOrNull("sma200w"),
                ath = o.doubleOrNull("ath"),
                athDate = o.dateOrNull("athDate"),
                rsiDaily = o.doubleOrNull("rsiDaily"),
                rsiWeekly = o.doubleOrNull("rsiWeekly"),
                vsBtc90d = o.doubleOrNull("vsBtc90d"),
                historyDays = o.getInt("historyDays"),
            )
        },
    )

    val gas = CacheCodec<GasReport>(
        encode = { g ->
            val evm = JSONArray()
            g.evm.forEach { e ->
                evm.put(
                    JSONObject()
                        .put("network", e.network.name)
                        .put("slow", e.slowGwei)
                        .put("normal", e.normalGwei)
                        .put("fast", e.fastGwei)
                        .putNumber("usd", e.transferUsd)
                )
            }
            JSONObject()
                .put("evm", evm)
                .put("time", g.time)
                .apply {
                    g.btc?.let { b ->
                        put(
                            "btc",
                            JSONObject()
                                .put("fast", b.fast)
                                .put("normal", b.normal)
                                .put("slow", b.slow)
                                .putNumber("usd", b.transferUsd)
                        )
                    }
                }
        },
        decode = { o ->
            val evm = o.getJSONArray("evm")
            val report = GasReport(
                evm = (0 until evm.length()).mapNotNull { i ->
                    val e = evm.getJSONObject(i)
                    // Unbekanntes Netz (ältere/neuere App-Version): auslassen
                    val network = GasNetwork.entries.firstOrNull { it.name == e.optString("network") }
                        ?: return@mapNotNull null
                    EvmGas(
                        network = network,
                        slowGwei = e.requireDouble("slow"),
                        normalGwei = e.requireDouble("normal"),
                        fastGwei = e.requireDouble("fast"),
                        transferUsd = e.doubleOrNull("usd"),
                    )
                },
                btc = o.optJSONObject("btc")?.let { b ->
                    BtcFees(
                        fast = b.requireDouble("fast"),
                        normal = b.requireDouble("normal"),
                        slow = b.requireDouble("slow"),
                        transferUsd = b.doubleOrNull("usd"),
                    )
                },
                time = o.getLong("time"),
            )
            require(report.evm.isNotEmpty() || report.btc != null) { "Gas ohne Werte" }
            report
        },
    )

    // ---- Hilfen: fehlende oder nicht endliche Zahlen werden weggelassen (JSON kennt kein NaN)

    private fun JSONObject.putNumber(key: String, value: Double?): JSONObject =
        if (value != null && value.isFinite()) put(key, value) else this

    private fun JSONObject.putNumber(key: String, value: Int?): JSONObject =
        if (value != null) put(key, value) else this

    private fun JSONObject.putDate(key: String, value: LocalDate?): JSONObject =
        if (value != null) put(key, value.toString()) else this

    private fun JSONObject.doubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key).takeIf { it.isFinite() } else null

    private fun JSONObject.requireDouble(key: String): Double =
        getDouble(key).also { require(it.isFinite()) { "$key nicht endlich" } }

    private fun JSONObject.intOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) getInt(key) else null

    private fun JSONObject.dateOrNull(key: String): LocalDate? =
        if (has(key) && !isNull(key)) LocalDate.parse(getString(key)) else null

    private fun Map<String, Double>.toJson(): JSONObject {
        val o = JSONObject()
        forEach { (key, value) -> if (value.isFinite()) o.put(key, value) }
        return o
    }

    private fun JSONObject.toDoubleMap(): Map<String, Double> =
        keys().asSequence().mapNotNull { key -> doubleOrNull(key)?.let { key to it } }.toMap()

    private fun JSONObject.putMarker(key: String, marker: CycleMarker?): JSONObject {
        if (marker == null) return this
        return put(
            key,
            JSONObject()
                .put("day", marker.day)
                .put("date", marker.date.toString())
                .put("price", marker.priceUsd)
                .put("multiple", marker.multiple)
                .put("change", marker.change)
        )
    }

    private fun JSONObject.markerOrNull(key: String): CycleMarker? {
        val m = optJSONObject(key) ?: return null
        return CycleMarker(
            day = m.getInt("day"),
            date = LocalDate.parse(m.getString("date")),
            priceUsd = m.requireDouble("price"),
            multiple = m.requireDouble("multiple"),
            change = m.requireDouble("change"),
        )
    }
}
