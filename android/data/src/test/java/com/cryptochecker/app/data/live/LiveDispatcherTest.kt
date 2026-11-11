package com.cryptochecker.app.data.live

import com.cryptochecker.app.data.remote.UserAgentInterceptor
import com.cryptochecker.app.domain.live.LivePlanner
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Eigener Client des Live-Stroms: eigener Dispatcher, User-Agent im Handshake, keine Wache und kein Mitschnitt. */
class LiveDispatcherTest {

    @Test
    fun jederHostHatPlatzFuerAlleVerbindungenSeinerBoersen() {
        // OKX und OKX-Swap (ws.okx.com) bzw. Bybit Spot und Linear teilen sich einen Host
        assertTrue(LiveDispatcher.maxRequestsPerHost >= 2 * LivePlanner.MAX_CONNECTIONS_PER_EXCHANGE)
        assertTrue(LiveDispatcher.maxRequests >= LiveDispatcher.maxRequestsPerHost)
    }

    @Test
    fun liveClientBehaeltNurDenUserAgent() {
        val other = Interceptor { chain -> chain.proceed(chain.request()) }
        val shared = Dispatcher()
        val base = OkHttpClient.Builder()
            .dispatcher(shared)
            .addInterceptor(other)
            .addInterceptor(UserAgentInterceptor("cryptoChecker-Android/test"))
            .build()
        val live = LiveDispatcher.client(base, pingSeconds = 20)
        assertEquals(1, live.interceptors.size)
        assertTrue(live.interceptors.single() is UserAgentInterceptor)
        assertNotSame(shared, live.dispatcher)
        assertEquals(LiveDispatcher.maxRequestsPerHost, live.dispatcher.maxRequestsPerHost)
        assertEquals(0, live.callTimeoutMillis)
        assertEquals(20_000, live.pingIntervalMillis)
    }
}
