package com.cryptochecker.app.data.remote

import com.cryptochecker.app.domain.portfolio.AddressBalance
import okhttp3.Interceptor
import okhttp3.Response

/**
 * User-Agent aller Börsen-Anfragen (Kurse, Kerzen, Kennzahlen, Live-Strom), wie iOS
 * («cryptoChecker-iOS/…»). Gesetzt im gemeinsamen OkHttpClient (`RemoteDataModule`, [UserAgentInterceptor]),
 * seit Build 102 auch im Handshake des Live-Stroms (`LivePriceStream` übernimmt den Interceptor); eine
 * Anfrage mit eigenem User-Agent (z. B. Coin-Logos) behält ihren.
 * Seit Build 104: Hosts, die Adressen zu sehen bekommen ([isAddressHost]), erhalten bei JEDER Anfrage
 * [NEUTRAL] — auch Gebühren und Halving (mempool.space, publicnode …), sonst verbände der Host App-Kennung und Adresse.
 */
object HttpUserAgent {
    const val HEADER = "User-Agent"

    /** «cryptoChecker-Android/16.2.2» aus dem versionName der App. */
    fun android(versionName: String): String = "cryptoChecker-Android/${versionName.trim()}"

    /**
     * Neutraler User-Agent für Adress-Hosts (seit Build 103, seit Build 104 verbreiteter vollständiger Browser-Wert:
     * Firefox ESR 153). Er nennt weder App noch Version; er tarnt die Anfrage aber nicht als Browser — TLS- und
     * HTTP-Merkmale, IP und Abfragemuster bleiben die der App. Gleicher Wert wie iOS
     * (`testdata/parity/address_privacy.json`).
     */
    const val NEUTRAL = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0"

    /** Hosts, die eine Adresse zu sehen bekommen ([AddressBalance.PRIVACY_HOSTS]), seit Build 104. */
    private val ADDRESS_HOSTS: Set<String> by lazy { AddressBalance.PRIVACY_HOSTS.map { it.lowercase() }.toSet() }

    /** Bekommt dieser Host Adressen (dann immer [NEUTRAL])? Seit Build 104. */
    fun isAddressHost(host: String): Boolean = host.lowercase().trimEnd('.') in ADDRESS_HOSTS

    /** User-Agent für eine Anfrage an [host]: Adress-Hosts [NEUTRAL], sonst [appAgent] (seit Build 104). */
    fun forHost(host: String, appAgent: String): String = if (isAddressHost(host)) NEUTRAL else appAgent

    /** Nur setzen, wenn die Anfrage noch keinen eigenen hat. */
    fun shouldSet(existing: String?): Boolean = existing.isNullOrBlank()
}

/**
 * Setzt den eigenen User-Agent, ausser die Anfrage bringt schon einen eigenen mit ([HttpUserAgent.shouldSet]).
 * Adress-Hosts bekommen immer [HttpUserAgent.NEUTRAL], auch über einen mitgebrachten (seit Build 104).
 */
internal class UserAgentInterceptor(private val agent: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (HttpUserAgent.isAddressHost(original.url.host)) {
            return chain.proceed(original.newBuilder().header(HttpUserAgent.HEADER, HttpUserAgent.NEUTRAL).build())
        }
        if (!HttpUserAgent.shouldSet(original.header(HttpUserAgent.HEADER))) return chain.proceed(original)
        return chain.proceed(original.newBuilder().header(HttpUserAgent.HEADER, agent).build())
    }
}
