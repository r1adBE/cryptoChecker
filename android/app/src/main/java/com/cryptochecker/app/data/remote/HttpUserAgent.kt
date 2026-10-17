package com.cryptochecker.app.data.remote

/**
 * User-Agent aller Börsen-Anfragen (Kurse, Kerzen, Kennzahlen, Live-Strom), wie iOS
 * («cryptoChecker-iOS/…»). Gesetzt im gemeinsamen OkHttpClient (`RemoteDataModule`); eine
 * Anfrage mit eigenem User-Agent (z. B. Coin-Logos) behält ihren.
 */
object HttpUserAgent {
    const val HEADER = "User-Agent"

    /** «cryptoChecker-Android/16.2.2» aus dem versionName der App. */
    fun android(versionName: String): String = "cryptoChecker-Android/${versionName.trim()}"

    /** Nur setzen, wenn die Anfrage noch keinen eigenen hat. */
    fun shouldSet(existing: String?): Boolean = existing.isNullOrBlank()
}
