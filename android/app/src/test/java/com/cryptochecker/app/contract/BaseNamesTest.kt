package com.cryptochecker.app.contract

import com.cryptochecker.marketdata.model.market.Bithumb
import com.cryptochecker.marketdata.model.market.GateIo
import com.cryptochecker.marketdata.model.market.Latoken
import com.cryptochecker.marketdata.model.market.Upbit
import org.junit.Assert.assertEquals
import org.junit.Test

/** Volle Namen aus den Paarlisten (gleicher Token, K-8) — wie `BaseNamesTests.swift`. */
class BaseNamesTest {

    @Test
    fun gateBaseAndQuoteNamesOfTradablePairs() {
        val body = """[
            {"id":"QTUM_ETH","base":"QTUM","base_name":"Quantum","quote":"ETH","quote_name":"Ethereum","trade_status":"tradable"},
            {"id":"CAT_USDT","base":"CAT","base_name":"Simon's Cat","quote":"USDT","quote_name":"Tether","trade_status":"tradable"},
            {"id":"OLD_USDT","base":"OLD","base_name":"Old Coin","quote":"USDT","quote_name":"Tether","trade_status":"untradable"},
            {"id":"NN_USDT","base":"NN","base_name":null,"quote":"USDT","trade_status":"tradable"}
        ]"""
        assertEquals(
            mapOf("QTUM" to "Quantum", "ETH" to "Ethereum", "CAT" to "Simon's Cat", "USDT" to "Tether"),
            GateIo().parseBaseNames(0, body),
        )
    }

    @Test
    fun upbitAndBithumbEnglishNames() {
        val body = """[
            {"market":"KRW-BTC","korean_name":"비트코인","english_name":"Bitcoin"},
            {"market":"BTC-TON","korean_name":"톤","english_name":"Tokamak Network"},
            {"market":"broken","english_name":"X"}
        ]"""
        val expected = mapOf("BTC" to "Bitcoin", "TON" to "Tokamak Network")
        assertEquals(expected, Upbit().parseBaseNames(0, body))
        assertEquals(expected, Bithumb().parseBaseNames(0, body))
    }

    @Test
    fun latokenCurrencyNames() {
        val currencies = """[
            {"id":"a","tag":"MCB","name":"MyCryptoBank"},
            {"id":"b","tag":"USDT","name":"Tether USD"},
            {"id":"c","tag":"","name":"Nameless"}
        ]"""
        assertEquals(
            mapOf("MCB" to "MyCryptoBank", "USDT" to "Tether USD"),
            Latoken().parseBaseNamesCombined(listOf(currencies, "[]")),
        )
    }
}
