package com.cryptochecker.app.data.remote

import javax.inject.Qualifier

/**
 * Eigener User-Agent mit voller App-Version («cryptoChecker-Android/16.2.2», [HttpUserAgent.android]) für Abrufe, die
 * nicht über den gemeinsamen Client mit `UserAgentInterceptor` laufen (Logo-Bilder). Die App liefert ihn
 * (`UserAgentModule`, aus `BuildConfig.VERSION_NAME`) — die Datenschicht kennt die App-Version nicht selbst.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppUserAgent
