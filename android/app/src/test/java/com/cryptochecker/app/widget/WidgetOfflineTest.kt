package com.cryptochecker.app.widget

import com.cryptochecker.app.util.SourceGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * «Keine Verbindung» in den Widgets (Owner-Wunsch Build 205): ohne Internet «Keine Verbindung · Stand 14:05» in der
 * Zeit-Zeile aller vier Widgets (offline > veraltet > Uhrzeit, [WidgetStatusRule]), Tipp auf «Aktualisieren» ohne
 * Netz holt nichts und zeigt keinen Countdown, bei Rückkehr des Netzes zeichnet [com.cryptochecker.app.work.WidgetReconnectWorker]
 * neu. Dazu der erste Auftritt der Liste: nur eine Ladeanzeige (Kreisel unten), das Symbol oben ist verborgen.
 */
class WidgetOfflineTest {

    @Test
    fun `rule puts offline before outdated before time`() {
        assertEquals(WidgetStatusRule.Kind.OFFLINE, WidgetStatusRule.kind(online = false, outdated = true, time = 1L))
        assertEquals(WidgetStatusRule.Kind.OFFLINE_NO_TIME, WidgetStatusRule.kind(online = false, outdated = false, time = 0L))
        assertEquals(WidgetStatusRule.Kind.OUTDATED, WidgetStatusRule.kind(online = true, outdated = true, time = 1L))
        assertEquals(WidgetStatusRule.Kind.TIME, WidgetStatusRule.kind(online = true, outdated = false, time = 1L))
    }

    /** Eng: «Keine Verbindung» bleibt als Ersatz stehen, bevor rechts nichts mehr steht (wie «veraltet»). */
    @Test
    fun `list header keeps the offline word when the time no longer fits`() {
        val title: (Float) -> Float = { if (it >= 14f) 60f else 50f }
        val status: (String) -> Float = { it.length * 5f }
        val fixed = ListWidgetHeader.FIXED_DP + WidgetTextFit.SAFETY_DP
        val full = "Keine Verbindung · Stand 14:05"
        fun layout(width: Float) = ListWidgetHeader.layout(width.toInt(), full, null, title, status, fallback = "Keine Verbindung")
        assertEquals(ListWidgetHeader.Layout(14f, full), layout(fixed + 60f + full.length * 5f))
        assertEquals(ListWidgetHeader.Layout(12f, "Keine Verbindung"), layout(fixed + 50f + 16 * 5f))
        assertEquals(ListWidgetHeader.Layout(12f, ""), layout(fixed + 50f))
    }

    @Test
    fun `offline texts reuse the app's status line strings`() {
        val offline = src("WidgetOffline.kt")
        assertTrue(offline.contains("R.string.offline_status_since, WidgetOutdated.stamp(context, time)"))
        assertTrue(offline.contains("context.getString(R.string.offline_status)"))
        // Erkennung der App: bestätigtes Netz, frisch beim System nachgefragt; offline → Neuzeichnen bei Rückkehr planen
        assertTrue(offline.contains("connectivity.isOnlineNow()"))
        assertTrue(offline.contains("if (!online) WidgetReconnectWorker.enqueue(context)"))
        for (dir in listOf("values", "values-de")) {
            val common = res("$dir/strings_common.xml")
            assertTrue(dir, common.contains("<string name=\"offline_status_since\">") && common.contains("<string name=\"offline_status\">"))
        }
    }

    @Test
    fun `every widget asks for connectivity once per draw and shows the offline line`() {
        val renderers = mapOf(
            "ListWidgetRenderer.kt" to "WidgetStatusRule.kind(connectivity.online(), outdated, lastRefresh)",
            "SingleWidgetRenderer.kt" to "val online = connectivity.online()",
            "PortfolioWidgetRenderer.kt" to "offline = !connectivity.online()",
            "PulseWidgetRenderer.kt" to "val online = connectivity.online()",
        )
        for ((file, call) in renderers) {
            val text = src(file)
            assertEquals(file, 1, text.split("connectivity.online()").size - 1)
            assertTrue(file, text.contains(call))
            assertTrue(file, text.contains("private val connectivity: WidgetConnectivity"))
        }
        assertTrue(src("ListWidgetRenderer.kt").contains("offline -> WidgetOffline.label(context, lastRefresh)"))
        assertTrue(src("ListWidgetRenderer.kt").contains("fallback = if (offline) WidgetOffline.short(context)"))
        assertTrue(src("SingleWidgetRenderer.kt").contains("offline -> WidgetOffline.label(context, watch.lastUpdate)"))
        assertTrue(src("PortfolioWidgetTexts.kt").contains("WidgetStatusRule.kind(!offline, outdated, snapshot.time)"))
        assertTrue(src("PulseWidgetTexts.kt").contains("WidgetStatusRule.Kind.OFFLINE -> WidgetOffline.label(context, time)"))
        // V4-01: Puls zeigt «Stand» / «Keine Verbindung · Stand» in jeder Grösse (keine Höhenbedingung mehr, wie iOS)
        val pulse = src("PulseWidgetRenderer.kt")
        assertTrue(pulse.contains("views.setViewVisibility(R.id.pulse_time, if (stand != null) View.VISIBLE else View.GONE)"))
        assertTrue(pulse.contains("PulseWidgetMath.leadLines(heightDp, headlineStyle.lines, showsTime = stand != null)"))
    }

    @Test
    fun `refresh tap without internet only redraws`() {
        val provider = src("PriceWidgetProvider.kt")
        val tap = provider.substringAfter("private fun onRefreshTap() {")
        val offline = tap.substringBefore("val tap = WidgetRefreshState.onTap(")
        assertTrue(offline.contains("if (!connectivity.online()) {"))
        assertTrue(offline.contains("widgetUpdater.update(widgetUpdater.widgetIds())"))
        assertFalse(offline.contains("refreshNow") || offline.contains("refreshBlockedUntil"))
    }

    @Test
    fun `reconnect waits for the network without a permanent service`() {
        val worker = read("src/main/java/com/cryptochecker/app/work/WidgetReconnectWorker.kt")
        assertTrue(worker.contains("setRequiredNetworkType(NetworkType.CONNECTED)"))
        assertTrue(worker.contains("enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)"))
        // Nicht bestätigtes Netz (Captive Portal): später erneut statt sofort wieder
        assertTrue(worker.contains("if (!connectivity.isOnlineNow()) return Result.retry()"))
        assertTrue(worker.contains("widgetUpdater.updateAll(fetch = false)"))
        assertTrue(worker.contains("backgroundCheck.run(CheckTrigger.WORKER, networkGuaranteed = true"))
        assertFalse(worker.contains("registerDefaultNetworkCallback") || worker.contains("Service"))
    }

    /** Erster Auftritt: Symbol verborgen (Kopf gleich hoch), Countdown aus; jedes Zeichnen setzt es wieder. */
    @Test
    fun `initial list layout hides the refresh icon until the first draw`() {
        val list = res("layout/widget_list.xml")
        val icon = Regex("""<ImageView\s+android:id="@\+id/widget_refresh"[^>]*>""").find(list)?.value
        assertTrue("Symbol fehlt", icon != null)
        assertTrue(icon!!.contains("android:visibility=\"invisible\""))
        val countdown = Regex("""<Chronometer\s+android:id="@\+id/widget_refresh_countdown"[^>]*>""").find(list)?.value
        assertTrue(countdown!!.contains("android:visibility=\"gone\""))
        val renderer = src("ListWidgetRenderer.kt")
        assertTrue(renderer.contains("views.setViewVisibility(R.id.widget_refresh, if (locked) View.GONE else View.VISIBLE)"))
        // Jeder volle Weg ruft refreshButton (die Kopf-Teilaktualisierung ListWidgetRetry ändert die Sichtbarkeit nicht)
        assertTrue(renderer.contains("refreshButton(views, appWidgetId, background, lockMillis, lockSeconds)"))
        assertFalse(src("ListWidgetRetry.kt").contains("R.id.widget_refresh,"))
        // Die anderen Start-Layouts haben weder Symbol noch Countdown
        for (layout in listOf("widget_single.xml", "widget_portfolio.xml", "widget_pulse.xml")) {
            val text = res("layout/$layout")
            assertFalse(layout, text.contains("ic_widget_refresh") || text.contains("<Chronometer"))
        }
    }

    private fun res(rel: String): String = read("src/main/res/$rel")

    private fun src(name: String): String = read("src/main/java/com/cryptochecker/app/widget/$name")

    private fun read(rel: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (prefix in listOf("", "app/", "android/app/")) {
                val f = File(dir, prefix + rel)
                if (f.isFile) return f.readText(Charsets.UTF_8)
            }
            dir = dir.parentFile
        }
        return SourceGuard.android(null, rel)
    }
}
