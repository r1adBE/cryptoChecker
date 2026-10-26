package com.cryptochecker.app.domain.alarm

import java.util.Locale

/**
 * Hersteller mit eigenem Akkusparen, das die App nach dem Beenden nicht wieder startet (Xiaomi «Autostart», Samsung
 * «Tiefschlaf», Huawei/Honor «App-Start», OnePlus, Oppo/Realme, Vivo): Dann kommt bis zum nächsten Öffnen gar nichts
 * mehr — weder WorkManager noch «Pünktlich prüfen» laufen. Auf diesen Geräten zeigt «Damit Alarme ankommen» eine
 * zusätzliche Zeile mit dem Weg zur Einstellung. Den Zustand kann die App nicht lesen, darum nur ein Hinweis.
 * Reines Kotlin (ohne android.*), testbar; `Build.MANUFACTURER` liest `OemBatterySettings`.
 */
object OemBattery {

    enum class Vendor(val code: String) {
        XIAOMI("xiaomi"),
        SAMSUNG("samsung"),
        HUAWEI("huawei"),
        ONEPLUS("oneplus"),
        OPPO("oppo"),
        VIVO("vivo"),
    }

    /** Systemseite eines Herstellers (Paket und Activity), explizit gestartet. */
    data class Page(val packageName: String, val className: String)

    /**
     * Hersteller aus `Build.MANUFACTURER`; null = kein bekanntes Akkusparen (keine Zeile). Redmi und Poco melden
     * «Xiaomi», iQOO meldet «vivo»; Honor nutzt dieselbe Einstellung wie Huawei, Realme dieselbe wie Oppo.
     */
    fun vendor(manufacturer: String?): Vendor? = when (manufacturer?.trim()?.lowercase(Locale.ROOT)) {
        "xiaomi", "redmi", "poco" -> Vendor.XIAOMI
        "samsung" -> Vendor.SAMSUNG
        "huawei", "honor" -> Vendor.HUAWEI
        "oneplus" -> Vendor.ONEPLUS
        "oppo", "realme" -> Vendor.OPPO
        "vivo", "iqoo" -> Vendor.VIVO
        else -> null
    }

    /**
     * Bekannte Seiten in der Reihenfolge, in der sie versucht werden. Sie fehlen je nach Version oder sind gesperrt;
     * dann folgt die nächste und am Ende die App-Info (dort «Akku»). OnePlus läuft seit OxygenOS 13 auf ColorOS-Basis,
     * darum folgen dort die Oppo-Seiten.
     */
    fun pages(vendor: Vendor): List<Page> = when (vendor) {
        Vendor.XIAOMI -> listOf(
            Page("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        )
        Vendor.SAMSUNG -> listOf(
            Page("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            Page("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        )
        Vendor.HUAWEI -> listOf(
            Page("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            Page("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            Page("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        )
        Vendor.ONEPLUS -> listOf(
            Page("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        ) + OPPO_PAGES
        Vendor.OPPO -> OPPO_PAGES
        Vendor.VIVO -> listOf(
            Page("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            Page("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        )
    }

    private val OPPO_PAGES = listOf(
        Page("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        Page("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        Page("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
    )
}
