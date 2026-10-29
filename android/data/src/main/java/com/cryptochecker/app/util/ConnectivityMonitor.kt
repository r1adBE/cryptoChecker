package com.cryptochecker.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hat das Gerät gerade Internet (#22/#23)? Ohne Netz wird nicht aktualisiert (keine
 * Fehlerzustände, nur «Offline · Stand 19:41»); kommt es zurück, folgt eine Aktualisierung
 * (`ManualRefresh`). Beobachtet das Standardnetz, ab dem ersten Zugriff für die Prozessdauer.
 *
 * «Online» heisst: das Standardnetz hat `INTERNET` **und** `VALIDATED` – Android hat den Internet-Zugang
 * bestätigt. Ein Captive-Portal-WLAN (Hotel, Bahn) vor dem Login gilt damit als offline, statt dass ein
 * Durchlauf startet und jedes Paar mit «nicht erreichbar» endet. iOS kennt dafür kein Gegenstück
 * (`NWPath` hat keine Bestätigung, siehe `NetworkStatus.swift`).
 */
@Singleton
class ConnectivityMonitor @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val manager: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

    private val _online = MutableStateFlow(currentlyOnline())

    /** true = Internet verfügbar (oder nicht feststellbar — dann lieber versuchen). */
    val online: StateFlow<Boolean> = _online.asStateFlow()

    init {
        runCatching {
            manager?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    _online.value = usable(capabilities::hasCapability)
                }

                override fun onLost(network: Network) {
                    _online.value = currentlyOnline()
                }

                override fun onUnavailable() {
                    _online.value = false
                }
            })
        }.onFailure { Timber.w(it, "Netzstatus nicht beobachtbar") }
    }

    fun isOnline(): Boolean = _online.value

    /** Frisch beim System nachgefragt (nach einem Wecker kann der gemerkte Stand veraltet sein). */
    fun isOnlineNow(): Boolean = currentlyOnline().also { _online.value = it }

    private fun currentlyOnline(): Boolean {
        val m = manager ?: return true
        return runCatching {
            val network = m.activeNetwork ?: return false
            val capabilities = m.getNetworkCapabilities(network) ?: return false
            usable(capabilities::hasCapability)
        }.getOrDefault(true)
    }

    companion object {
        /** Internet-Fähigkeit und vom System bestätigter Zugang (kein Captive Portal vor dem Login). */
        fun usable(hasCapability: (Int) -> Boolean): Boolean =
            hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
