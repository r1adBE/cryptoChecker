package com.cryptochecker.app.util

import java.io.File

/**
 * Testhilfe: liest die Texte aller 75 Sprachordner (`values`, `values-de` …) direkt aus `src/main/res`,
 * damit Tests Länge und Vollständigkeit der Übersetzungen prüfen können, ohne Android-Laufzeit.
 * Genügt für schlichte `<string>`-Einträge (Escapes `\'`, `\"` und `&amp;` werden aufgelöst).
 */
object StringResources {

    /** Sprachordner → (Name → Text); nur Sprachordner, nicht `values-v31` u. ä. */
    fun byLanguage(): Map<String, Map<String, String>> =
        resDir().listFiles { f -> f.isDirectory && LANGUAGE_FOLDER.matches(f.name) }!!
            .associate { it.name to readStrings(it) }

    private val LANGUAGE_FOLDER = Regex("values(-[a-z]{2,3}(-r[A-Z]{2})?|-b\\+[a-z]{2,3}(\\+[A-Z][a-z]{3})?)?")
    private val ENTRY = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

    private fun readStrings(folder: File): Map<String, String> =
        folder.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }!!
            .flatMap { file -> ENTRY.findAll(file.readText(Charsets.UTF_8)).map { it.groupValues[1] to it.groupValues[2] }.toList() }
            .associate { (name, text) -> name to text.replace("\\'", "'").replace("\\\"", "\"").replace("&amp;", "&") }

    /** `src/main/res`, gesucht ab dem Arbeitsordner von Gradle (Modul, Projekt oder darüber). */
    private fun resDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (prefix in listOf("", "app/", "android/app/")) {
                val res = File(dir, prefix + "src/main/res")
                if (File(res, "values").isDirectory) return res
            }
            dir = dir.parentFile
        }
        error("src/main/res nicht gefunden")
    }
}
