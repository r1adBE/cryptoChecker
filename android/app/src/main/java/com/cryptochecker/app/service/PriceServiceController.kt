package com.cryptochecker.app.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Startet und stoppt den Vordergrunddienst. */
@Singleton
class PriceServiceController @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun start() {
        val intent = Intent(context, PriceService::class.java).apply {
            action = PriceService.ACTION_START
        }
        // Ab Android 12 kann der Start aus dem Hintergrund abgelehnt werden.
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { Timber.w(it, "Live-Dienst konnte nicht gestartet werden") }
    }

    fun stop() {
        val intent = Intent(context, PriceService::class.java).apply {
            action = PriceService.ACTION_STOP
        }
        runCatching { context.startService(intent) }
            .onFailure { context.stopService(Intent(context, PriceService::class.java)) }
    }

    fun apply(enabled: Boolean) {
        if (enabled) start() else stop()
    }
}
