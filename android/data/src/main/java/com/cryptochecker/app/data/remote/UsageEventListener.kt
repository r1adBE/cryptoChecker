package com.cryptochecker.app.data.remote

import com.cryptochecker.marketdata.usage.UsageMeter
import okhttp3.Call
import okhttp3.EventListener
import java.io.IOException

/**
 * «Akku & Aktivität»: zählt je Abruf über den gemeinsamen OkHttp-Client eine Anfrage (am Ende, gelungen oder nicht) und
 * die empfangenen Bytes des Antwortkörpers (wie übertragen) in [UsageMeter.shared] — nur Zahlen, weder Adresse noch
 * Inhalt. Hängt am Client aus `RemoteDataModule`; abgeleitete Clients (`newBuilder()`) behalten ihn. WebSocket-
 * Verbindungen zählt `LiveConnection` selbst (OkHttp meldet sie hier nicht).
 */
object UsageEventListener : EventListener() {

    val factory = EventListener.Factory { UsageEventListener }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        UsageMeter.shared.countBytes(byteCount)
    }

    override fun callEnd(call: Call) {
        UsageMeter.shared.countRequest(0)
    }

    override fun callFailed(call: Call, ioe: IOException) {
        UsageMeter.shared.countRequest(0)
    }
}
