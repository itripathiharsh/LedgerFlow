package com.ledgerflow.app.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale
import kotlin.math.abs

data class ParsedAmount(
    val minor: Long,
    val currencyHint: String? = null,
)

data class ImportedTxDraft(
    val merchant: String,
    val amountMinor: Long,
    val txType: String,
    val currency: String,
    val note: String,
    val categoryHint: String? = null,
    val accountHint: String? = null,
    val labelsCsv: String = "",
    val occurredAtEpochMs: Long,
)

sealed class ParsedImportResult {
    data class Transactions(val drafts: List<ImportedTxDraft>, val formatLabel: String) : ParsedImportResult()
    data class Error(val message: String) : ParsedImportResult()
}

object ImportParser {
    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    private val currencySymbolToCode = listOf(
        "₹" to "INR",
        "€" to "EUR",
        "£" to "GBP",
        "¥" to "JPY",
        "$" to "USD",
    )

    private val currencyWordRegex = Regex("""(?i)(?<![a-z])(rs\.?|inr|usd|eur|gbp|jpy|cad|aud|sgd|aed|chf)(?![a-z])""")

    private val maxSafeAmountMinor = Long.MAX_VALUE / 2L

    private val dateTimeFormatters = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy/MM/dd HH:mm:ss",
        "yyyy/MM/dd HH:mm",
        "dd/MM/yyyy HH:mm:ss",
        "dd/MM/yyyy HH:mm",
        "dd-MM-yyyy HH:mm:ss",
        "dd-MM-yyyy HH:mm",
        "MM/dd/yyyy HH:mm:ss",
        "MM/dd/yyyy HH:mm",
        "yyyy-MM-dd'T'HH:mm:ss",
    ).map { pattern ->
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(Locale.US)
    }

    private val dateFormatters = listOf(
        "yyyy-MM-dd",
        "yyyy/MM/dd",
        "dd/MM/yyyy",
        "dd-MM-yyyy",
        "d/M/yyyy",
        "d-M-yyyy",
        "MM/dd/yyyy",
        "MM-dd-yyyy",
        "M/d/yyyy",
        "dd MMM yyyy",
        "d MMM yyyy",
        "dd-MMM-yyyy",
        "d-MMM-yyyy",
        "dd MMM yy",
        "dd-MMM-yy",
        "MMM dd, yyyy",
        "MMM d, yyyy",
    ).map { pattern ->
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(Locale.US)
    }

    fun detectBinaryFormatMessage(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return "Selected file is empty."
        if (bytes.size >= 4 &&
            bytes[0] == 0x25.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x44.toByte() &&
            bytes[3] == 0x46.toByte()
        ) {
            return "PDF files cannot be imported directly. Export your transactions as CSV or JSON from the other app."
        }
        if (bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() &&
            bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() &&
            bytes[3] == 0x04.toByte()
        ) {
            return "Excel (.xlsx) or ZIP files cannot be read directly. Save or export as CSV or JSON instead."
        }
        if (bytes.size >= 4 &&
            bytes[0] == 0xD0.toByte() &&
            bytes[1] == 0xCF.toByte() &&
            bytes[2] == 0x11.toByte() &&
            bytes[3] == 0xE0.toByte()
        ) {
            return "Legacy .xls files cannot be read directly. Export as CSV or JSON instead."
        }
        val hasUtf16Bom = bytes.size >= 2 && (
            (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) ||
                (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
            )
        if (!hasUtf16Bom) {
            val sampleLen = minOf(bytes.size, 512)
            var controlCount = 0
            for (i in 0 until sampleLen) {
                val b = bytes[i].toInt() and 0xFF
                if (b == 0 || (b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D)) {
                    controlCount++
                }
            }
            if (controlCount * 100 / sampleLen > 15) {
                return "Unsupported binary file format. Choose a CSV, TSV, or JSON transaction export."
            }
        }
        return null
    }

    fun decodeTextBytes(bytes: ByteArray): String {
        val raw = when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            else -> String(bytes, Charsets.UTF_8)
        }
        return raw.removePrefix("\uFEFF").replace("\u0000", "")
    }

    fun parseTransactionsCsvOrJson(
        text: String,
        defaultCurrency: String = "USD",
        nowMs: Long = System.currentTimeMillis(),
    ): ParsedImportResult {
        val cleanCur = normalizeCurrencyCode(defaultCurrency) ?: "USD"
        val trimmed = text.removePrefix("\uFEFF").trim()
        if (trimmed.isEmpty()) return ParsedImportResult.Error("Selected file is empty.")

        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return parseGenericJson(trimmed, cleanCur, nowMs)
        }

        val delimiter = detectDelimiter(trimmed)
        val rows = parseDelimitedRecords(trimmed, delimiter).filter { row -> row.any { it.isNotBlank() } }
        if (rows.size < 2) {
            return ParsedImportResult.Error("File does not contain enough rows to import.")
        }

        val headerResult = locateHeaderRow(rows)
            ?: return ParsedImportResult.Error(
                "Could not recognize transaction columns. Include headers like Amount (or Debit/Credit) and Description/Merchant/Category."
            )

        val (headerRowIndex, mapping) = headerResult
        val drafts = mutableListOf<ImportedTxDraft>()

        for (i in (headerRowIndex + 1) until rows.size) {
            val cols = rows[i]
            val parsedAmountAndType = resolveCsvRowAmountAndType(cols, mapping) ?: continue
            val (signedMinor, txType, amtCurrencyHint) = parsedAmountAndType
            if (signedMinor == 0L) continue

            val rawCurrency = mapping.currencyIdx?.let { cols.getOrNull(it) }.orEmpty()
            val currency = normalizeCurrencyCode(rawCurrency) ?: amtCurrencyHint ?: cleanCur

            val merchant = listOfNotNull(
                mapping.merchantIdx?.let { cols.getOrNull(it)?.trim()?.takeIf { s -> s.isNotEmpty() } },
                mapping.categoryIdx?.let { cols.getOrNull(it)?.trim()?.takeIf { s -> s.isNotEmpty() } },
                mapping.noteIdx?.let { cols.getOrNull(it)?.trim()?.takeIf { s -> s.isNotEmpty() } },
            ).firstOrNull() ?: "Imported Transaction"

            val note = mapping.noteIdx?.let { cols.getOrNull(it)?.trim() }.orEmpty()
            val categoryHint = mapping.categoryIdx?.let { cols.getOrNull(it)?.trim()?.takeIf { s -> s.isNotEmpty() } }
            val accountHint = mapping.accountIdx?.let { cols.getOrNull(it)?.trim()?.takeIf { s -> s.isNotEmpty() } }
            val labelsCsv = mapping.labelsIdx?.let { cols.getOrNull(it)?.trim() }.orEmpty()
            val rawDate = mapping.dateIdx?.let { cols.getOrNull(it)?.trim() }.orEmpty()
            val fallbackTime = nowMs - (i - headerRowIndex) * 1000L
            val occurredAt = parseDateEpochMs(rawDate, fallbackTime)

            drafts.add(
                ImportedTxDraft(
                    merchant = merchant.take(160),
                    amountMinor = when (txType) {
                        "EXPENSE" -> -abs(signedMinor)
                        "INCOME" -> abs(signedMinor)
                        else -> abs(signedMinor)
                    },
                    txType = txType,
                    currency = currency,
                    note = note.take(400),
                    categoryHint = categoryHint,
                    accountHint = accountHint,
                    labelsCsv = labelsCsv.take(160),
                    occurredAtEpochMs = occurredAt,
                )
            )
        }

        val formatLabel = if (delimiter == '\t') "TSV" else "CSV"
        return if (drafts.isEmpty()) {
            ParsedImportResult.Error("No valid transaction rows found inside $formatLabel file.")
        } else {
            ParsedImportResult.Transactions(drafts, formatLabel)
        }
    }

    private fun parseGenericJson(
        trimmed: String,
        defaultCurrency: String,
        nowMs: Long,
    ): ParsedImportResult {
        val root = runCatching { lenientJson.parseToJsonElement(trimmed) }.getOrNull()
            ?: return ParsedImportResult.Error("Invalid JSON file format.")

        val array: JsonArray? = when (root) {
            is JsonArray -> root
            is JsonObject -> {
                listOf("transactions", "records", "data", "expenses", "items", "entries", "history", "rows")
                    .firstNotNullOfOrNull { key -> root[key] as? JsonArray }
                    ?: root.values.firstOrNull { it is JsonArray } as? JsonArray
            }
            else -> null
        }

        if (array == null || array.isEmpty()) {
            return ParsedImportResult.Error("No transaction list found inside JSON file.")
        }

        val drafts = mutableListOf<ImportedTxDraft>()
        array.forEachIndexed { idx, el ->
            val obj = el as? JsonObject ?: return@forEachIndexed
            val amountMinorPair = extractJsonAmountMinor(obj) ?: return@forEachIndexed
            val (parsedMinor, amtCurrencyHint) = amountMinorPair
            if (parsedMinor == 0L) return@forEachIndexed

            val typeRaw = firstString(obj, "txType", "tx_type", "type", "kind", "direction", "flow")
            val isExpenseBool = (obj["isExpense"] ?: obj["is_expense"] ?: obj["expense"])?.jsonPrimitiveOrNull()?.booleanOrNull
            val txType = when {
                isExpenseBool == true -> "EXPENSE"
                isExpenseBool == false -> "INCOME"
                !typeRaw.isNullOrBlank() -> classifyTypeToken(typeRaw) ?: if (parsedMinor < 0) "EXPENSE" else "INCOME"
                parsedMinor < 0 -> "EXPENSE"
                else -> "INCOME"
            }

            val merchant = firstString(
                obj,
                "merchant", "title", "description", "payee", "name", "item", "narration", "category", "note"
            )?.takeIf { it.isNotBlank() } ?: "Imported Transaction"

            val note = firstString(obj, "note", "memo", "remarks", "comment", "details").orEmpty()
            val categoryHint = firstString(obj, "category", "categoryName", "category_name", "group")
            val accountHint = firstString(obj, "account", "accountName", "account_name", "wallet", "paymentMode")
            val labelsCsv = extractLabels(obj)
            val currency = normalizeCurrencyCode(firstString(obj, "currency", "ccy", "currencyCode"))
                ?: amtCurrencyHint
                ?: defaultCurrency
            val rawDate = firstString(obj, "occurredAt", "occurred_at", "occurredAtEpochMs", "date", "timestamp", "createdAt", "created_at", "time").orEmpty()
            val occurredAt = parseDateEpochMs(rawDate, nowMs - idx * 1000L)

            drafts.add(
                ImportedTxDraft(
                    merchant = merchant.take(160),
                    amountMinor = if (txType == "EXPENSE") -abs(parsedMinor) else abs(parsedMinor),
                    txType = txType,
                    currency = currency,
                    note = note.take(400),
                    categoryHint = categoryHint,
                    accountHint = accountHint,
                    labelsCsv = labelsCsv.take(160),
                    occurredAtEpochMs = occurredAt,
                )
            )
        }

        return if (drafts.isEmpty()) {
            ParsedImportResult.Error("No valid transaction objects found inside JSON file.")
        } else {
            ParsedImportResult.Transactions(drafts, "JSON")
        }
    }

    private fun extractJsonAmountMinor(obj: JsonObject): ParsedAmount? {
        val minorRaw = firstPrimitiveContent(obj, "amountMinor", "amount_minor", "valueMinor")
        if (minorRaw != null) {
            val minor = minorRaw.toLongOrNull()
            if (minor != null && minor != 0L && abs(minor) <= maxSafeAmountMinor) {
                return ParsedAmount(minor)
            }
        }
        val majorRaw = firstPrimitiveContent(obj, "amount", "value", "total", "price", "cost", "sum") ?: return null
        return parseAmountCell(majorRaw)
    }

    private fun extractLabels(obj: JsonObject): String {
        val el = obj["labelsCsv"] ?: obj["labels_csv"] ?: obj["labels"] ?: obj["tags"] ?: obj["tag"] ?: return ""
        return when (el) {
            is JsonArray -> el.mapNotNull { it.jsonPrimitiveOrNull()?.content?.trim() }.filter { it.isNotEmpty() }.joinToString(",")
            is JsonPrimitive -> el.content.trim()
            else -> ""
        }
    }

    private fun firstString(obj: JsonObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k ->
            obj[k]?.jsonPrimitiveOrNull()?.content?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
        }

    private fun firstPrimitiveContent(obj: JsonObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k ->
            val p = obj[k]?.jsonPrimitiveOrNull() ?: return@firstNotNullOfOrNull null
            p.content.trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
        }

    private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

    fun parseAmountCell(cell: String): ParsedAmount? {
        val trimmed = cell.trim()
        if (trimmed.isEmpty() || trimmed == "-" || trimmed == "--" || trimmed.equals("N/A", ignoreCase = true)) {
            return null
        }

        var currencyHint: String? = null
        for ((sym, code) in currencySymbolToCode) {
            if (trimmed.contains(sym)) {
                currencyHint = code
                break
            }
        }
        if (currencyHint == null) {
            val wordMatch = currencyWordRegex.find(trimmed)?.value?.uppercase(Locale.US)?.replace(".", "")
            currencyHint = when (wordMatch) {
                "RS" -> "INR"
                null -> null
                else -> normalizeCurrencyCode(wordMatch)
            }
        }

        val parensNegative = trimmed.startsWith("(") && trimmed.endsWith(")")
        val trailingMinus = trimmed.endsWith("-") && !trimmed.startsWith("-")
        val hasLeadingMinus = trimmed.startsWith("-")
        val crDrNegative = Regex("""(?i)\b(dr|debit)\b""").containsMatchIn(trimmed)

        // Remove currency symbols and words BEFORE extracting numeric punctuation (prevents "Rs. 500" -> ".500").
        val noWords = currencyWordRegex.replace(trimmed, " ")
            .replace(Regex("""(?i)\b(dr|cr|debit|credit)\b"""), " ")
        var numeric = noWords.replace(Regex("""[^\d.,\-+]"""), "")
        if (numeric.isEmpty()) return null

        val leadingSignNegative = numeric.startsWith("-") || hasLeadingMinus || parensNegative || trailingMinus || crDrNegative
        numeric = numeric.replace("-", "").replace("+", "")
        if (numeric.isEmpty()) return null

        val normalized = normalizeDecimalSeparators(numeric) ?: return null
        val parsed = runCatching {
            var bd = BigDecimal(normalized)
            if (leadingSignNegative && bd > BigDecimal.ZERO) {
                bd = bd.negate()
            }
            val minorBd = bd.multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP)
            val longVal = minorBd.longValueExact()
            if (abs(longVal) > maxSafeAmountMinor) return@runCatching null
            longVal
        }.getOrNull() ?: return null

        return ParsedAmount(parsed, currencyHint)
    }

    private fun normalizeDecimalSeparators(raw: String): String? {
        val dots = raw.count { it == '.' }
        val commas = raw.count { it == ',' }
        return when {
            dots == 0 && commas == 0 -> raw
            dots == 1 && commas == 0 -> raw
            dots == 0 && commas == 1 -> {
                val afterComma = raw.substringAfter(',')
                if (afterComma.length == 3 && raw.substringBefore(',').isNotEmpty()) {
                    // Treat "1,000" as thousands grouping
                    raw.replace(",", "")
                } else {
                    // Treat "45,50" as decimal comma
                    raw.replace(',', '.')
                }
            }
            dots > 1 && commas == 0 -> raw.replace(".", "")
            commas > 1 && dots == 0 -> raw.replace(",", "")
            else -> {
                val lastDot = raw.lastIndexOf('.')
                val lastComma = raw.lastIndexOf(',')
                if (lastComma > lastDot) {
                    // European: 1.234,56
                    raw.replace(".", "").replace(',', '.')
                } else {
                    // US/India: 1,234.56
                    raw.replace(",", "")
                }
            }
        }
    }

    fun parseDateEpochMs(raw: String, fallbackMs: Long): Long {
        val s = raw.trim()
        if (s.isEmpty()) return fallbackMs
        val numeric = s.toLongOrNull()
        if (numeric != null) {
            return when {
                numeric >= 1_000_000_000_000L -> numeric
                numeric in 1_000_000_000L..9_999_999_999L -> numeric * 1000L
                else -> fallbackMs
            }
        }
        runCatching { return OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        runCatching { return Instant.parse(s).toEpochMilli() }
        val zone = ZoneId.systemDefault()
        for (fmt in dateTimeFormatters) {
            runCatching {
                return LocalDateTime.parse(s, fmt).atZone(zone).toInstant().toEpochMilli()
            }
        }
        val dateOnly = s.substringBefore('T').substringBefore(' ').takeIf { it != s }
        for (fmt in dateFormatters) {
            runCatching {
                return LocalDate.parse(s, fmt).atStartOfDay(zone).toInstant().toEpochMilli()
            }
            if (dateOnly != null) {
                runCatching {
                    return LocalDate.parse(dateOnly, fmt).atStartOfDay(zone).toInstant().toEpochMilli()
                }
            }
        }
        return fallbackMs
    }

    fun normalizeCurrencyCode(raw: String?): String? {
        val clean = raw?.trim().orEmpty()
        if (clean.isEmpty()) return null
        for ((sym, code) in currencySymbolToCode) {
            if (clean == sym || clean.contains(sym)) return code
        }
        val upper = clean.uppercase(Locale.US)
        if (upper.length == 3 && upper.all { it in 'A'..'Z' }) return upper
        return null
    }

    private fun detectDelimiter(text: String): Char {
        val sample = text.lineSequence().take(12).joinToString("\n")
        val candidates = listOf(',', ';', '\t', '|')
        var best = ','
        var bestScore = -1
        for (delim in candidates) {
            val rows = parseDelimitedRecords(sample, delim).filter { r -> r.any { it.isNotBlank() } }
            if (rows.isEmpty()) continue
            val multiColRows = rows.count { it.size >= 2 }
            val maxCols = rows.maxOf { it.size }
            val score = multiColRows * 10 + maxCols
            if (multiColRows > 0 && score > bestScore) {
                bestScore = score
                best = delim
            }
        }
        return best
    }

    fun parseDelimitedRecords(text: String, delimiter: Char): List<List<String>> {
        val records = mutableListOf<List<String>>()
        val currentRow = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < text.length && text[i + 1] == '"') {
                        cell.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                c == delimiter && !inQuotes -> {
                    currentRow.add(cell.toString().trim())
                    cell.clear()
                }
                (c == '\n' || c == '\r') && !inQuotes -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') {
                        i++
                    }
                    currentRow.add(cell.toString().trim())
                    cell.clear()
                    records.add(currentRow.toList())
                    currentRow.clear()
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(cell.toString().trim())
            records.add(currentRow.toList())
        }
        return records
    }

    private data class CsvHeaderMapping(
        val merchantIdx: Int?,
        val amountIdx: Int?,
        val debitIdx: Int?,
        val creditIdx: Int?,
        val typeIdx: Int?,
        val currencyIdx: Int?,
        val noteIdx: Int?,
        val categoryIdx: Int?,
        val accountIdx: Int?,
        val dateIdx: Int?,
        val labelsIdx: Int?,
    )

    private fun locateHeaderRow(rows: List<List<String>>): Pair<Int, CsvHeaderMapping>? {
        val limit = minOf(rows.size - 1, 15)
        for (idx in 0 until limit) {
            val norm = rows[idx].map { it.lowercase(Locale.US).trim() }
            if (norm.size < 2) continue

            val debitIdx = findCol(norm, "debit amt", "withdrawal amt", "debit", "withdrawal", "outflow", "spent", "paid")
            val creditIdx = findCol(norm, "credit amt", "deposit amt", "credit", "deposit", "inflow", "received")
            val amountIdx = findCol(norm, "amount", "net amount", "value", "total", "sum", "price", "cost", "amt")

            if (amountIdx == null && debitIdx == null && creditIdx == null) continue

            val merchantIdx = findCol(
                norm,
                "merchant", "title", "description", "payee", "narration", "particulars",
                "name", "item", "vendor", "counterparty", "recipient", "remark", "details", "summary", "desc"
            )
            val categoryIdx = findCol(norm, "category", "cat", "group")
            val noteIdx = findCol(norm, "note", "memo", "comment", "notes", "remarks", "subcategory", "sub-category")
            val dateIdx = findCol(norm, "date", "occurred", "timestamp", "time", "created", "posted", "value date")

            // Require at least one descriptive or date/category column alongside amount(s) to avoid false banner hits.
            if (merchantIdx == null && categoryIdx == null && noteIdx == null && dateIdx == null) continue

            val mapping = CsvHeaderMapping(
                merchantIdx = merchantIdx,
                amountIdx = amountIdx,
                debitIdx = debitIdx,
                creditIdx = creditIdx,
                typeIdx = findCol(norm, "txtype", "tx_type", "transaction type", "type", "kind", "dr/cr", "d/c", "direction", "flow", "income/expense"),
                currencyIdx = findCol(norm, "currency", "curr", "ccy"),
                noteIdx = noteIdx,
                categoryIdx = categoryIdx,
                accountIdx = findCol(norm, "account", "wallet", "bank", "card", "payment mode", "mode"),
                dateIdx = dateIdx,
                labelsIdx = findCol(norm, "labels", "label", "tags", "tag"),
            )
            return idx to mapping
        }
        return null
    }

    private fun findCol(headers: List<String>, vararg aliases: String): Int? {
        for (alias in aliases) {
            val exact = headers.indexOfFirst { it == alias }
            if (exact >= 0) return exact
        }
        for (alias in aliases) {
            val partial = headers.indexOfFirst { it.contains(alias) }
            if (partial >= 0) return partial
        }
        return null
    }

    private fun resolveCsvRowAmountAndType(
        cols: List<String>,
        mapping: CsvHeaderMapping,
    ): Triple<Long, String, String?>? {
        val typeToken = mapping.typeIdx?.let { cols.getOrNull(it) }.orEmpty()
        val explicitType = classifyTypeToken(typeToken)

        val debitParsed = mapping.debitIdx?.let { idx -> cols.getOrNull(idx)?.let(::parseAmountCell) }
        val creditParsed = mapping.creditIdx?.let { idx -> cols.getOrNull(idx)?.let(::parseAmountCell) }

        if (debitParsed != null && debitParsed.minor != 0L && (creditParsed == null || creditParsed.minor == 0L)) {
            return Triple(-abs(debitParsed.minor), explicitType ?: "EXPENSE", debitParsed.currencyHint)
        }
        if (creditParsed != null && creditParsed.minor != 0L && (debitParsed == null || debitParsed.minor == 0L)) {
            return Triple(abs(creditParsed.minor), explicitType ?: "INCOME", creditParsed.currencyHint)
        }

        val amountIdx = mapping.amountIdx ?: return null
        val cell = cols.getOrNull(amountIdx) ?: return null
        val parsed = parseAmountCell(cell) ?: return null
        if (parsed.minor == 0L) return null

        val txType = explicitType ?: if (parsed.minor < 0L) "EXPENSE" else "INCOME"
        val signedMinor = when (txType) {
            "EXPENSE" -> -abs(parsed.minor)
            "INCOME" -> abs(parsed.minor)
            else -> abs(parsed.minor)
        }
        return Triple(signedMinor, txType, parsed.currencyHint)
    }

    private fun classifyTypeToken(raw: String): String? {
        val clean = raw.trim().lowercase(Locale.US)
        if (clean.isEmpty()) return null
        return when {
            clean in setOf("-", "dr", "d", "expense", "exp", "debit", "outflow", "withdrawal", "spent", "paid") ||
                clean.contains("expense") || clean.contains("debit") || clean.contains("withdraw") || clean.contains("outflow") -> "EXPENSE"
            clean in setOf("+", "cr", "c", "income", "inc", "credit", "inflow", "deposit", "received", "salary") ||
                clean.contains("income") || clean.contains("credit") || clean.contains("deposit") || clean.contains("inflow") -> "INCOME"
            clean.contains("transfer") -> "TRANSFER"
            else -> null
        }
    }
}
