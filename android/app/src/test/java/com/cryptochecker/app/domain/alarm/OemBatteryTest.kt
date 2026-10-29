package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.util.StringResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Zuordnung Hersteller → Akkusparen-Hinweis in «Damit Alarme ankommen» ([OemBattery]). */
class OemBatteryTest {

    @Test
    fun mapsManufacturerCaseInsensitive() {
        assertEquals(OemBattery.Vendor.XIAOMI, OemBattery.vendor("Xiaomi"))
        assertEquals(OemBattery.Vendor.XIAOMI, OemBattery.vendor("POCO"))
        assertEquals(OemBattery.Vendor.SAMSUNG, OemBattery.vendor("samsung"))
        assertEquals(OemBattery.Vendor.HUAWEI, OemBattery.vendor("HUAWEI"))
        assertEquals(OemBattery.Vendor.HUAWEI, OemBattery.vendor("HONOR"))
        assertEquals(OemBattery.Vendor.ONEPLUS, OemBattery.vendor("OnePlus"))
        assertEquals(OemBattery.Vendor.OPPO, OemBattery.vendor("OPPO"))
        assertEquals(OemBattery.Vendor.OPPO, OemBattery.vendor("realme"))
        assertEquals(OemBattery.Vendor.VIVO, OemBattery.vendor(" vivo "))
    }

    @Test
    fun unknownManufacturerHasNoRow() {
        for (m in listOf("Google", "motorola", "Fairphone", "Sony", "", null)) assertNull(m, OemBattery.vendor(m))
        val items = AlarmReadiness.rows(
            AlarmReadiness.Input(AlarmReadiness.Platform.ANDROID, AlarmReadiness.Access.GRANTED, now = 0, minuteOfDay = 0)
        )
        assertTrue(items.none { it.row == AlarmReadiness.Row.OEM_BATTERY })
    }

    @Test
    fun knownManufacturerAddsInfoRowWithButton() {
        val items = AlarmReadiness.rows(
            AlarmReadiness.Input(
                AlarmReadiness.Platform.ANDROID, AlarmReadiness.Access.GRANTED, now = 0, minuteOfDay = 0,
                oemVendor = OemBattery.Vendor.SAMSUNG,
            )
        )
        val rows = items.map { it.row }
        val oem = items.single { it.row == AlarmReadiness.Row.OEM_BATTERY }
        // Zustand nicht lesbar: nur Hinweis, nie Warnung
        assertEquals(AlarmReadiness.State.INFO, oem.state)
        assertEquals(AlarmReadiness.Action.OEM_SETTINGS, oem.action)
        assertEquals(rows.indexOf(AlarmReadiness.Row.BACKGROUND) + 1, rows.indexOf(AlarmReadiness.Row.OEM_BATTERY))
        // Nur Android
        val ios = AlarmReadiness.rows(
            AlarmReadiness.Input(
                AlarmReadiness.Platform.IOS, AlarmReadiness.Access.GRANTED, now = 0, minuteOfDay = 0,
                oemVendor = OemBattery.Vendor.SAMSUNG,
            )
        )
        assertTrue(ios.none { it.row == AlarmReadiness.Row.OEM_BATTERY })
    }

    @Test
    fun everyVendorHasPagesAndTextInAllLanguages() {
        val languages = StringResources.byLanguage()
        assertEquals(33, languages.size)
        for (vendor in OemBattery.Vendor.entries) {
            assertTrue(vendor.code, OemBattery.pages(vendor).isNotEmpty())
            // Tippfehler-Schutz: Activity liegt im Namensraum des Herstellers
            OemBattery.pages(vendor).forEach {
                assertTrue(it.className, it.className.startsWith(it.packageName.substringBeforeLast('.')))
            }
            for ((folder, strings) in languages) {
                assertTrue("$folder ${vendor.code}", strings["oem_battery_${vendor.code}"].orEmpty().isNotBlank())
                assertTrue(folder, strings["oem_battery_title"].orEmpty().isNotBlank())
            }
        }
    }
}
