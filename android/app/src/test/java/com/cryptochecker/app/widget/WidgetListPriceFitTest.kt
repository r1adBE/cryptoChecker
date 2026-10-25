package com.cryptochecker.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kurs der Merklisten-Zeile ([WidgetTextFit.listPriceFit]): Er bekommt zuerst seine Breite und ist immer vollständig;
 * bei vergrösserter Schrift wird er zuerst kleiner (bis 70 % der normalen Grösse), dann weicht der Paarname; bei
 * normaler Schrift weicht gleich der Name.
 */
class WidgetListPriceFitTest {

    /** Breite des Kurses bei der Grösse [fit] (gemessen [priceDp] bei 14 sp). */
    private fun shownPrice(priceDp: Float, fit: WidgetTextFit.ListPriceFit) = priceDp * fit.sp / 14f

    @Test
    fun bothFitKeepBaseSizeWithoutUpperLimit() {
        val fit = WidgetTextFit.listPriceFit(14f, 2f, priceWidthDp = 150f, nameWidthDp = 40f, availableDp = 200f)
        assertEquals(14f, fit.sp, 0f)
        assertEquals(50f, fit.nameDp, 0.01f)
        // Breite unbekannt: wie bisher
        assertEquals(14f, WidgetTextFit.listPriceFit(14f, 2f, 400f, 40f, availableDp = null).sp, 0f)
        assertEquals(14f, WidgetTextFit.listPriceFit(14f, 2f, 400f, 40f, availableDp = 0f).sp, 0f)
    }

    @Test
    fun priceShrinksFirstSoTheNameStaysWhole() {
        // 160 + 60 > 200: Kurs auf 140 dp (12.25 sp ≥ Untergrenze 4.9 sp), Name ganz
        val fit = WidgetTextFit.listPriceFit(14f, 2f, priceWidthDp = 160f, nameWidthDp = 60f, availableDp = 200f)
        assertEquals(12.25f, fit.sp, 0.001f)
        assertEquals(60f, fit.nameDp, 0.01f)
    }

    @Test
    fun tooNarrowPriceCompleteNameShortened() {
        // Schrift 2,0: Untergrenze 4.9 sp; Kurs 200 dp → 70 dp, für den Namen (100 dp) bleiben 80 dp
        val fit = WidgetTextFit.listPriceFit(14f, 2f, priceWidthDp = 200f, nameWidthDp = 100f, availableDp = 150f)
        assertEquals(4.9f, fit.sp, 0.001f)
        assertTrue("Kurs vollständig", shownPrice(200f, fit) <= 150f + 0.01f)
        assertEquals(80f, fit.nameDp, 0.01f)
        assertTrue("Name gekürzt", fit.nameDp < 100f)
    }

    @Test
    fun normalTextKeepsPriceSizeAndShortensTheName() {
        // Normale Schrift: Kurs bleibt 14 sp, der lange Name weicht (wie bisher)
        val fit = WidgetTextFit.listPriceFit(14f, 1f, priceWidthDp = 120f, nameWidthDp = 100f, availableDp = 150f)
        assertEquals(14f, fit.sp, 0f)
        assertEquals(30f, fit.nameDp, 0.01f)
        // Kurs allein zu breit: genau passend, nie abgeschnitten
        val tight = WidgetTextFit.listPriceFit(14f, 1f, priceWidthDp = 300f, nameWidthDp = 50f, availableDp = 150f)
        assertEquals(150f, shownPrice(300f, tight), 0.01f)
        assertEquals(0f, tight.nameDp, 0f)
    }

    @Test
    fun evenWithoutNameThePriceIsNeverCut() {
        // Selbst bei der Untergrenze (4.9 sp → 350 dp) zu breit: genau passend, Name leer
        val fit = WidgetTextFit.listPriceFit(14f, 2f, priceWidthDp = 1000f, nameWidthDp = 80f, availableDp = 100f)
        assertEquals(100f, shownPrice(1000f, fit), 0.01f)
        assertEquals(0f, fit.nameDp, 0f)
        assertEquals(4.9f, WidgetTextFit.listPriceFloorSp(14f, 2f), 0.001f)
    }
}
