package com.cuentivo.app.domain

/** A catalog row the file described well enough to create. */
data class ParsedProduct(
    val name: String,
    val priceCents: Long,
    val sku: String?,
    val category: String?,
    val description: String?,
)

/** A row that was left out, with the line number so the person can go fix it in their file. */
data class RejectedRow(val lineNumber: Int, val reason: String)

data class SheetImport(val accepted: List<ParsedProduct>, val rejected: List<RejectedRow>)

/**
 * Turns a spreadsheet a shop owner already keeps into products, without them typing each one.
 *
 * Pure: it decides what *would* be created and never writes, in the same spirit as
 * [LegacyMigrationPlanner]. Unlike LegacyMigrationRepository it deliberately allows a partial
 * import — someone uploading two hundred products should not lose the hundred and ninety-nine good
 * rows because of one bad one. Everything left out comes back in [SheetImport.rejected] with a
 * reason, so nothing disappears silently.
 */
object ProductSheetParser {

    /**
     * U+FEFF, the byte order mark Excel puts at the start of a UTF-8 CSV.
     *
     * Built from its code point instead of pasted in as a character: a raw BOM is invisible in an
     * editor, and any tool that re-encodes this file can eat it without anyone noticing that CSV
     * import quietly broke. Spelled this way it is also greppable.
     */
    private val BYTE_ORDER_MARK = Char(0xFEFF).toString()

    /** What a person gets when they ask the app for an example file. */
    const val TEMPLATE_CSV = "nombre,precio,sku,categoria,descripcion\n" +
        "Jabon de coco,45.50,JAB-01,Aseo,Barra de 100 g\n" +
        "Shampoo 500 ml,129.00,SHA-500,Aseo,\n" +
        "Sarten 24 cm,349.90,,Cocina,\n"

    fun parseCsv(text: String, existingNames: Set<String>): SheetImport =
        parse(readCsv(text), existingNames)

    /**
     * @param existingNames normalized names already in the catalog, from [CustomerNameNormalizer.key].
     */
    fun parse(rows: List<List<String>>, existingNames: Set<String>): SheetImport {
        val accepted = mutableListOf<ParsedProduct>()
        val rejected = mutableListOf<RejectedRow>()
        val seen = existingNames.toMutableSet()

        val headerIndex = rows.indexOfFirst { isHeader(it) }
        val columns = if (headerIndex >= 0) columnsFrom(rows[headerIndex]) else DEFAULT_COLUMNS
        val firstDataRow = headerIndex + 1

        rows.forEachIndexed { index, row ->
            if (index < firstDataRow) return@forEachIndexed
            // Spreadsheets are full of trailing empty rows; they are not mistakes worth reporting.
            if (row.all { it.isBlank() }) return@forEachIndexed

            val lineNumber = index + 1
            val name = row.cell(columns.name).trim()
            val rawPrice = row.cell(columns.price).trim()

            when {
                name.isEmpty() -> rejected += RejectedRow(lineNumber, "Sin nombre de producto")
                rawPrice.isEmpty() -> rejected += RejectedRow(lineNumber, "$name: sin precio")
                else -> {
                    val priceCents = centsFrom(rawPrice)
                    val key = CustomerNameNormalizer.key(name)
                    when {
                        priceCents == null ->
                            rejected += RejectedRow(lineNumber, "$name: el precio \"$rawPrice\" no es válido")
                        !seen.add(key) ->
                            rejected += RejectedRow(lineNumber, "$name: ya existe o está repetido en el archivo")
                        else -> accepted += ParsedProduct(
                            name = name,
                            priceCents = priceCents,
                            sku = row.cell(columns.sku).trim().ifBlank { null },
                            category = row.cell(columns.category).trim().ifBlank { null },
                            description = row.cell(columns.description).trim().ifBlank { null },
                        )
                    }
                }
            }
        }
        return SheetImport(accepted, rejected)
    }

    /**
     * Reads CSV the way a spreadsheet writes it: quoted fields, doubled quotes inside them, and
     * commas or newlines that live inside a quoted field rather than separating anything.
     */
    fun readCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        // Excel writes a byte order mark; left in place it becomes part of the first header cell, so
        // "nombre" stops matching and the whole file silently falls back to positional columns.
        // Written as the escape rather than the raw character: a literal BOM is invisible in an
        // editor and any tool that re-encodes the file can eat it without anyone noticing.
        val content = text.removePrefix(BYTE_ORDER_MARK)

        fun endField() {
            row.add(field.toString())
            field.setLength(0)
        }

        fun endRow() {
            endField()
            rows.add(row)
            row = mutableListOf()
        }

        while (index < content.length) {
            val character = content[index]
            when {
                quoted && character == '"' && content.getOrNull(index + 1) == '"' -> {
                    field.append('"')
                    index++
                }
                character == '"' -> quoted = !quoted
                !quoted && (character == ',' || character == ';') -> endField()
                !quoted && character == '\n' -> endRow()
                !quoted && character == '\r' -> Unit
                else -> field.append(character)
            }
            index++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    /**
     * Accepts prices as they really appear in a catalog — "$1,234.50", "1234,50", "349.90".
     *
     * The cleaned text still goes through [Money.positiveCentsFromPesos], so a third decimal is
     * refused rather than truncated and no value ever passes through a Double.
     */
    private fun centsFrom(raw: String): Long? {
        val digitsAndSeparators = raw.filter { it.isDigit() || it == '.' || it == ',' }
        val normalized = when {
            digitsAndSeparators.contains('.') -> digitsAndSeparators.replace(",", "")
            // "1234,50": a lone comma with at most two digits behind it is a decimal comma, not a
            // thousands separator.
            digitsAndSeparators.count { it == ',' } == 1 &&
                digitsAndSeparators.substringAfterLast(',').length <= 2 ->
                digitsAndSeparators.replace(',', '.')
            else -> digitsAndSeparators.replace(",", "")
        }
        return normalized.ifBlank { null }?.let { Money.positiveCentsFromPesos(it) }
    }

    private fun isHeader(row: List<String>): Boolean {
        val cells = row.map { CustomerNameNormalizer.key(it) }
        val namesSomething = cells.any { it.contains("nombre") || it.contains("producto") || it.contains("articulo") }
        return namesSomething && cells.any { it.contains("precio") || it.contains("costo") }
    }

    private fun columnsFrom(header: List<String>): Columns {
        val cells = header.map { CustomerNameNormalizer.key(it) }
        fun find(vararg keywords: String) = cells.indexOfFirst { cell -> keywords.any { cell.contains(it) } }
        return Columns(
            name = find("nombre", "producto", "articulo").takeIf { it >= 0 } ?: 0,
            price = find("precio", "costo").takeIf { it >= 0 } ?: 1,
            sku = find("sku", "codigo", "clave"),
            category = find("categoria", "linea"),
            description = find("descripcion", "detalle"),
        )
    }

    private fun List<String>.cell(index: Int): String =
        if (index in 0..lastIndex) this[index] else ""

    private data class Columns(
        val name: Int,
        val price: Int,
        val sku: Int,
        val category: Int,
        val description: Int,
    )

    /** No header row: assume the two columns anyone would write first, and ignore the rest. */
    private val DEFAULT_COLUMNS = Columns(name = 0, price = 1, sku = -1, category = -1, description = -1)
}
