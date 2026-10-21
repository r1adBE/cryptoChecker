package com.cryptochecker.app.data.remote

import okhttp3.Interceptor
import okhttp3.Response

/**
 * User-Agent aller Börsen-Anfragen (Kurse, Kerzen, Kennzahlen, Live-Strom), wie iOS
 * («cryptoChecker-iOS/…»). Gesetzt im gemeinsamen OkHttpClient (`RemoteDataModule`, [UserAgentInterceptor]),
 * seit Build 102 auch im Handshake des Live-Stroms (`LivePriceStream` übernimmt den Interceptor); eine
 * Anfrage mit eigenem User-Agent (z. B. Coin-Logos) behält ihren.
 */
object HttpUserAgent {
    const val HEADER = "User-Agent"

    /** «cryptoChecker-Android/16.2.2» aus dem versionName der App. */
    fun android(versionName: String): String = "cryptoChecker-Android/${versionName.trim()}"

    /** Nur setzen, wenn die Anfrage noch keinen eigenen hat. */
    fun shouldSet(existing: String?): Boolean = existing.isNullOrBlank()
}

/** Setzt den eigenen User-Agent, ausser die Anfrage bringt schon einen eigenen mit ([HttpUserAgent.shouldSet]). */
internal class UserAgentInterceptor(private val agent: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (!HttpUserAgent.shouldSet(original.header(HttpUserAgent.HEADER))) return chain.proceed(original)
        return chain.proceed(original.newBuilder().header(HttpUserAgent.HEADER, agent).build())
    }
}
