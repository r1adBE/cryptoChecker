package com.cryptochecker.app.domain.market

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

/**
 * Netzwerkgebühren (#167): EVM-Netze über öffentliche JSON-RPC-Knoten
 * (eth_feeHistory, sonst eth_gasPrice), Bitcoin über mempool.space.
 * Alles ohne API-Schlüssel.
 */
enum class GasNetwork(
    val title: String,
    /** Coin, in dem die Gebühr bezahlt wird (für den Preis in USD). */
    val coin: String,
    /** Öffentliche RPC-Knoten der Reihe nach; der erste, der antwortet, gilt. */
    val rpcs: List<String>,
) {
    ETHEREUM("Ethereum", "ETH", listOf("https://ethereum-rpc.publicnode.com", "https://eth.llamarpc.com", "https://cloudflare-eth.com")),
    BASE("Base", "ETH", listOf("https://base-rpc.publicnode.com", "https://mainnet.base.org")),
    ARBITRUM("Arbitrum", "ETH", listOf("https://arbitrum-one-rpc.publicnode.com", "https://arb1.arbitrum.io/rpc")),
    POLYGON("Polygon", "POL", listOf("https://polygon-bor-rpc.publicnode.com", "https://polygon-rpc.com")),
    BNB("BNB Chain", "BNB", listOf("https://bsc-rpc.publicnode.com", "https://bsc-dataseed.bnbchain.org")),
}

/** Gebühr eines EVM-Netzes in gwei; [transferUsd] = einfache Überweisung (21 000 Gas). */
data class EvmGas(
    val network: GasNetwork,
    val slowGwei: Double,
    val normalGwei: Double,
    val fastGwei: Double,
    val transferUsd: Double?,
)

/** Bitcoin-Gebühren in sat/vB (mempool.space-Empfehlungen). */
data class BtcFees(
    val fast: Double,
    val normal: Double,
    val slow: Double,
    /** Einfache Überweisung (~140 vB) zur normalen Gebühr. */
    val transferUsd: Double?,
)

data class GasReport(
    val evm: List<EvmGas>,
    val btc: BtcFees?,
    val time: Long,
)

object GasFees {
    /** Gas einer einfachen Überweisung im EVM-Netz. */
    const val TRANSFER_GAS = 21_000.0

    /** Typische Grösse einer Bitcoin-Überweisung (1 Eingang, 2 Ausgänge, SegWit). */
    const val BTC_TRANSFER_VBYTES = 140.0

    /** Blöcke für eth_feeHistory und Perzentile für langsam/normal/schnell. */
    const val FEE_HISTORY_BLOCKS = 20
    val PERCENTILES = listOf(10, 50, 90)

    fun feeHistoryRequest(): String = JSONObject()
        .put("jsonrpc", "2.0").put("id", 1).put("method", "eth_feeHistory")
        .put("params", JSONArray().put("0x" + FEE_HISTORY_BLOCKS.toString(16)).put("latest").put(JSONArray(PERCENTILES)))
        .toString()

    fun gasPriceRequest(): String = JSONObject()
        .put("jsonrpc", "2.0").put("id", 1).put("method", "eth_gasPrice").put("params", JSONArray())
        .toString()

    /**
     * eth_feeHistory → (langsam, normal, schnell) in gwei: Grundgebühr des
     * nächsten Blocks plus Median der Trinkgelder je Perzentil.
     */
    fun parseFeeHistory(response: String): Triple<Double, Double, Double> {
        val json = JSONObject(response)
        json.optJSONObject("error")?.let { throw IllegalStateException(it.optString("message")) }
        val result = json.getJSONObject("result")
        val baseFees = result.getJSONArray("baseFeePerGas")
        require(baseFees.length() > 0) { "Keine Grundgebühr" }
        val nextBase = hexToGwei(baseFees.getString(baseFees.length() - 1))
        val rewards = result.optJSONArray("reward")
        val tips = DoubleArray(PERCENTILES.size)
        if (rewards != null && rewards.length() > 0) {
            for (k in PERCENTILES.indices) {
                val values = (0 until rewards.length()).mapNotNull { i ->
                    rewards.optJSONArray(i)?.optString(k)?.takeIf { it.startsWith("0x") }?.let(::hexToGwei)
                }.sorted()
                tips[k] = if (values.isEmpty()) 0.0 else values[values.size / 2]
            }
        }
        // Schnell nie unter normal, normal nie unter langsam
        val slow = nextBase + tips[0]
        val normal = maxOf(slow, nextBase + tips[1])
        val fast = maxOf(normal, nextBase + tips[2])
        return Triple(slow, normal, fast)
    }

    /** eth_gasPrice → gwei. */
    fun parseGasPrice(response: String): Double {
        val json = JSONObject(response)
        json.optJSONObject("error")?.let { throw IllegalStateException(it.optString("message")) }
        return hexToGwei(json.getString("result"))
    }

    /** mempool.space /api/v1/fees/recommended → (schnell, normal, langsam). */
    fun parseMempool(response: String): Triple<Double, Double, Double> {
        val json = JSONObject(response)
        val fast = json.getDouble("fastestFee")
        val normal = json.optDouble("halfHourFee", fast)
        val slow = json.optDouble("hourFee", normal)
        return Triple(fast, normal, slow)
    }

    /** Binance-Preisliste (ticker/price?symbols=…) → Coin → USD. */
    fun parseBinancePrices(response: String): Map<String, Double> {
        val array = JSONArray(response)
        val prices = HashMap<String, Double>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val symbol = item.optString("symbol")
            val price = item.optDouble("price")
            if (symbol.endsWith("USDT") && !price.isNaN() && price > 0) prices[symbol.removeSuffix("USDT")] = price
        }
        return prices
    }

    /** Coinbase exchange-rates?currency=USD: 1 USD = x Coin → Preis = 1/x. */
    fun parseCoinbaseRates(response: String, coins: Collection<String>): Map<String, Double> {
        val rates = JSONObject(response).getJSONObject("data").getJSONObject("rates")
        val prices = HashMap<String, Double>()
        for (coin in coins) {
            val rate = rates.optDouble(coin)
            if (!rate.isNaN() && rate > 0) prices[coin] = 1.0 / rate
        }
        return prices
    }

    fun evmTransferUsd(gwei: Double, coinUsd: Double?): Double? =
        coinUsd?.let { gwei * 1e-9 * TRANSFER_GAS * it }

    fun btcTransferUsd(satPerVb: Double, btcUsd: Double?): Double? =
        btcUsd?.let { satPerVb * BTC_TRANSFER_VBYTES * 1e-8 * it }

    fun hexToGwei(hex: String): Double {
        val clean = hex.removePrefix("0x").removePrefix("0X").ifEmpty { "0" }
        return BigInteger(clean, 16).toBigDecimal().movePointLeft(9).toDouble()
    }

    /** «0.012», «1.4», «23» — so kurz wie möglich, aber nie «0». */
    fun formatGwei(gwei: Double): String = when {
        gwei <= 0.0 -> "0"
        gwei < 0.001 -> "<0.001"
        gwei < 1 -> String.format(java.util.Locale.US, "%.3f", gwei).trimEnd('0').trimEnd('.')
        gwei < 10 -> String.format(java.util.Locale.US, "%.1f", gwei).removeSuffix(".0")
        else -> String.format(java.util.Locale.US, "%.0f", gwei)
    }

    /** Kosten in USD: «$0.42», «<$0.01». */
    fun formatUsd(usd: Double): String = when {
        usd < 0.01 -> "<\$0.01"
        usd < 100 -> String.format(java.util.Locale.US, "\$%.2f", usd)
        else -> String.format(java.util.Locale.US, "\$%.0f", usd)
    }
}
