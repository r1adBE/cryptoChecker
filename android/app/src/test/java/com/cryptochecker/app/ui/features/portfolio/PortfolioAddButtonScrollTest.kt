package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.util.SourceGuard
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * «+»-Knopf des Portfolios verdeckt beim Lesen keine Beträge: Er blendet beim Scrollen nach unten aus und nach oben
 * wieder ein (Regel: `AddButtonScrollParityTest`), und unter der letzten Zeile bleibt Platz. Quelltext-Wächter für die
 * Verdrahtung auf beiden Plattformen.
 */
class PortfolioAddButtonScrollTest {

    @Test
    fun `android portfolio and coin page hide the button while scrolling down`() {
        val overview = android("ui/features/portfolio/PortfolioScreen.kt")
        assertTrue(overview.contains("val fabScroll = rememberFabScroll(listState)"))
        assertTrue(overview.contains("modifier = Modifier.nestedScroll(fabScroll.connection),"))
        assertTrue(overview.contains("contentDescription = stringResource(R.string.portfolio_add_tx), scroll = fabScroll)"))
        val coin = android("ui/features/portfolio/PortfolioDetailScreen.kt")
        assertTrue(coin.contains("val fabScroll = rememberFabScroll(listState)"))
        assertTrue(coin.contains("modifier = Modifier.nestedScroll(fabScroll.connection),"))
        assertTrue(coin.contains("LazyColumn(\n                        state = listState,"))
        assertTrue(coin.contains("contentDescription = stringResource(R.string.portfolio_add_tx), scroll = fabScroll)"))
        // Platz unter der letzten Zeile: am Ende der Liste steht der Knopf frei
        for (source in listOf(android("ui/features/portfolio/PortfolioScreenBody.kt"), coin)) {
            assertTrue(source.contains("bottom = Spacing.fabClearance)"))
        }
        val fab = android("ui/components/AppFab.kt")
        assertTrue(fab.contains("val visible: Boolean get() = state.visible || !list.canScrollBackward || !list.canScrollForward"))
        assertTrue(fab.contains("delta = (-consumed.y / density).toDouble(),"))
    }

    @Test
    fun `ios slot hides the button while scrolling down and keeps its space`() {
        val ui = ios("App/Features/Portfolio/PortfolioUi.swift")
        assertTrue(ui.contains("scroll = AddButtonScroll.next(scroll, delta: delta, atTop: atTop, atEnd: atEnd)"))
        assertTrue(ui.contains("content.onScrollGeometryChange(for: PortfolioAddScrollPosition.self) { geometry in"))
        assertTrue(ui.contains("PortfolioAddSlot.shared.scrolled(delta: new.offset - old.offset, atTop: new.atTop, atEnd: new.atEnd)"))
        assertTrue(ui.contains(".opacity(slot.visible ? 1 : 0)"))
        assertTrue(ui.contains(".allowsHitTesting(slot.visible)"))
        assertTrue(ui.contains(".accessibilityHidden(!slot.visible)"))
    }

    private fun android(rel: String): String {
        val path = "src/main/java/com/cryptochecker/app/$rel"
        return SourceGuard.android(findUp(listOf("", "app/", "android/app/").map { it + path }), path).readText(Charsets.UTF_8)
    }

    private fun ios(rel: String): String =
        SourceGuard.ios(findUp(listOf("ios/$rel", "../ios/$rel", "../../ios/$rel")), "ios/$rel").readText(Charsets.UTF_8)

    private fun findUp(candidates: List<String>): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (rel in candidates) {
                val f = File(dir, rel)
                if (f.isFile) return f
            }
            dir = dir.parentFile
        }
        return null
    }
}
