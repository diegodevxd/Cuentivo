package com.cuentivo.app.domain

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Reads the first sheet of an .xlsx into plain rows of text.
 *
 * An .xlsx is a zip of XML, so `java.util.zip` plus a small tag scanner is enough. That matters:
 * Apache POI would add megabytes to the APK and fight R8, and the shop owners this is for keep their
 * catalog in Excel — telling them to "save as CSV" first is exactly the manual work being removed.
 *
 * Pure JVM on purpose (no XmlPullParser), so it runs under testDebugUnitTest.
 *
 * Values come back as Excel stored them: a price typed as 45.50 is stored as "45.5". Turning text
 * into money stays with [ProductSheetParser], which routes everything through [Money] and so never
 * lets a fractional cent through.
 */
object XlsxReader {

    fun read(bytes: ByteArray): List<List<String>> {
        val entries = unzip(bytes)
        val sharedStrings = entries[SHARED_STRINGS]
            ?.let(::stripNamespacePrefixes)
            ?.let(::readSharedStrings)
            ?: emptyList()
        val sheet = entries.entries
            .filter { it.key.startsWith(WORKSHEETS) && it.key.endsWith(".xml") }
            .minByOrNull { it.key }
            ?.value
            ?: return emptyList()
        return readSheet(stripNamespacePrefixes(sheet), sharedStrings)
    }

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val files = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) files[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
            }
        }
        return files
    }

    /**
     * `<x:row>` and `<row>` are the same element: whoever wrote the file decides whether to put the
     * namespace on the default or on a prefix, and both are valid OOXML. Without this the tag scanner
     * below matches nothing at all on a prefixed file and the import reports zero rows — no bad rows,
     * no error, just an empty catalog, which is exactly how it looks when someone's real file fails.
     *
     * Only element names are touched, never attribute values: `r:id="rId1"` must survive intact.
     */
    private fun stripNamespacePrefixes(xml: String): String =
        xml.replace(NAMESPACE_PREFIX, "<$1")

    /** Every `<si>` is one string; a styled cell splits it across several `<t>` runs to be joined. */
    private fun readSharedStrings(xml: String): List<String> =
        blocks(xml, "si").map { item -> blocks(item, "t").joinToString("") { unescape(it) } }

    private fun readSheet(xml: String, sharedStrings: List<String>): List<List<String>> =
        blocks(xml, "row").map { row ->
            val cells = mutableListOf<String>()
            var index = 0
            while (index < row.length) {
                val start = row.indexOf("<c", index).takeIf { it >= 0 } ?: break
                val openEnd = row.indexOf('>', start).takeIf { it >= 0 } ?: break
                val attributes = row.substring(start, openEnd)
                val selfClosing = row[openEnd - 1] == '/'
                val body: String
                if (selfClosing) {
                    body = ""
                    index = openEnd + 1
                } else {
                    val close = row.indexOf("</c>", openEnd).takeIf { it >= 0 } ?: break
                    body = row.substring(openEnd + 1, close)
                    index = close + 4
                }

                // The `r` reference ("C7") is what keeps a row with gaps aligned: skipped cells are
                // simply absent from the XML, so counting <c> tags would shift every later column.
                val column = attribute(attributes, "r")?.let(::columnIndex) ?: cells.size
                while (cells.size < column) cells.add("")

                val value = when (attribute(attributes, "t")) {
                    "s" -> blocks(body, "v").firstOrNull()?.trim()?.toIntOrNull()
                        ?.let { sharedStrings.getOrNull(it) }.orEmpty()
                    "inlineStr" -> blocks(body, "t").joinToString("") { unescape(it) }
                    else -> blocks(body, "v").firstOrNull()?.let(::unescape).orEmpty()
                }
                cells.add(value)
            }
            cells
        }

    /** Contents of every `<tag ...>…</tag>`, ignoring self-closing ones, which hold nothing. */
    private fun blocks(xml: String, tag: String): List<String> {
        val found = mutableListOf<String>()
        var index = 0
        while (index < xml.length) {
            val start = xml.indexOf("<$tag", index).takeIf { it >= 0 } ?: break
            val afterName = xml.getOrNull(start + tag.length + 1)
            if (afterName != null && afterName != '>' && afterName != ' ' && afterName != '/') {
                index = start + 1
                continue
            }
            val openEnd = xml.indexOf('>', start).takeIf { it >= 0 } ?: break
            if (xml[openEnd - 1] == '/') {
                index = openEnd + 1
                continue
            }
            val close = xml.indexOf("</$tag>", openEnd).takeIf { it >= 0 } ?: break
            found.add(xml.substring(openEnd + 1, close))
            index = close + tag.length + 3
        }
        return found
    }

    private fun attribute(tag: String, name: String): String? {
        val marker = "$name=\""
        val start = tag.indexOf(marker).takeIf { it >= 0 } ?: return null
        val valueStart = start + marker.length
        val end = tag.indexOf('"', valueStart).takeIf { it >= 0 } ?: return null
        return tag.substring(valueStart, end)
    }

    /** "C7" -> 2. Column letters are base-26 with A as 1. */
    private fun columnIndex(reference: String): Int {
        var index = 0
        for (character in reference) {
            if (!character.isLetter()) break
            index = index * 26 + (character.uppercaseChar() - 'A' + 1)
        }
        return (index - 1).coerceAtLeast(0)
    }

    private fun unescape(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        // Ampersand last: doing it first would re-expand the escapes above.
        .replace("&amp;", "&")

    /** An opening or closing tag whose name carries a namespace prefix: `<x:row`, `</x:row`. */
    private val NAMESPACE_PREFIX = Regex("<(/?)[A-Za-z_][A-Za-z0-9_.-]*:")

    private const val SHARED_STRINGS = "xl/sharedStrings.xml"
    private const val WORKSHEETS = "xl/worksheets/sheet"
}
