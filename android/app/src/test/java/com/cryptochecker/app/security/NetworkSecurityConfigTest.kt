package com.cryptochecker.app.security

import com.cryptochecker.app.util.SourceGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Handy- und Uhr-App: nur verschlüsselte Verbindungen (Manifest und network_security_config), kein Pinning. */
class NetworkSecurityConfigTest {

    @Test
    fun `app and wear forbid cleartext traffic`() {
        for (module in listOf("app", "wear")) {
            val manifest = file(module, "src/main/AndroidManifest.xml")
            assertTrue(module, manifest.contains("android:usesCleartextTraffic=\"false\""))
            assertTrue(module, manifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
            val config = file(module, "src/main/res/xml/network_security_config.xml")
            assertTrue(module, config.contains("<base-config cleartextTrafficPermitted=\"false\" />"))
            assertFalse(module, config.contains("cleartextTrafficPermitted=\"true\""))
            assertFalse(module, config.contains("<pin-set"))
            assertFalse(module, config.contains("<domain-config"))
        }
    }

    private fun file(module: String, rel: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var found: File? = null
        while (dir != null && found == null) {
            found = listOf("$module/$rel", "android/$module/$rel").map { File(dir, it) }.firstOrNull { it.isFile }
            dir = dir.parentFile
        }
        return SourceGuard.android(found, "$module/$rel").readText(Charsets.UTF_8)
    }
}
