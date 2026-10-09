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
                    _online.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
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

    private fun currentlyOnline(): Boolean {
        val m = manager ?: return true
        return runCatching {
            val network = m.activeNetwork ?: return false
            m.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }.getOrDefault(true)
    }
}
