package com.ledgerflow.app.domain

import java.util.Locale

data class ParsedShoppingItem(
    val name: String,
    val quantityLabel: String = "1",
) {
    val displayLabel: String
        get() = formatDisplay(name, quantityLabel)

    companion object {
        fun formatDisplay(name: String, quantityLabel: String): String {
            val cleanName = name.trim()
            val cleanQty = quantityLabel.trim()
            return when {
                cleanName.isBlank() -> ""
                cleanQty.isBlank() || cleanQty == "1" -> cleanName
                else -> "$cleanQty $cleanName"
            }
        }
    }
}

/**
 * Local deterministic parser converting spoken or typed multi-item shopping sentences such as
 * `"2 kg atta, milk, 500g paneer"` or `"add 3 dozen eggs and coffee"` into structured shopping items.
 */
object VoiceShoppingParser {

    private val wordNumbers = mapOf(
        "a" to "1",
        "an" to "1",
        "one" to "1",
        "two" to "2",
        "three" to "3",
        "four" to "4",
        "five" to "5",
        "six" to "6",
        "seven" to "7",
        "eight" to "8",
        "nine" to "9",
        "ten" to "10",
        "eleven" to "11",
        "twelve" to "12",
        "half" to "0.5",
        "quarter" to "0.25",
    )

    private val unitMap = mapOf(
        "kg" to "kg",
        "kgs" to "kg",
        "kilo" to "kg",
        "kilos" to "kg",
        "kilogram" to "kg",
        "kilograms" to "kg",
        "g" to "g",
        "gm" to "g",
        "gms" to "g",
        "gram" to "g",
        "grams" to "g",
        "l" to "L",
        "lt" to "L",
        "ltr" to "L",
        "ltrs" to "L",
        "liter" to "L",
        "liters" to "L",
        "litre" to "L",
        "litres" to "L",
        "ml" to "ml",
        "milliliter" to "ml",
        "milliliters" to "ml",
        "millilitre" to "ml",
        "millilitres" to "ml",
        "pc" to "pcs",
        "pcs" to "pcs",
        "piece" to "pcs",
        "pieces" to "pcs",
        "no" to "pcs",
        "nos" to "pcs",
        "unit" to "pcs",
        "units" to "pcs",
        "dozen" to "dozen",
        "doz" to "dozen",
        "dz" to "dozen",
        "pack" to "pack",
        "packs" to "pack",
        "pkt" to "pack",
        "pkts" to "pack",
        "packet" to "pack",
        "packets" to "pack",
        "bag" to "bag",
        "bags" to "bag",
        "box" to "box",
        "boxes" to "box",
        "bottle" to "bottle",
        "bottles" to "bottle",
        "jar" to "jar",
        "jars" to "jar",
        "can" to "can",
        "cans" to "can",
        "loaf" to "loaf",
        "loaves" to "loaf",
        "bunch" to "bunch",
        "bunches" to "bunch",
        "tray" to "tray",
        "trays" to "tray",
        "sachet" to "sachet",
        "sachets" to "sachet",
    )

    private val unitAlternation = unitMap.keys.sortedByDescending { it.length }.joinToString("|")

    private val leadingFillerRegex = Regex(
        """(?i)^(?:please\s+|hey\s+ledgerflow\s+|can\s+you\s+)?(?:add|buy|get|need|pick\s+up|remember\s+to\s+buy|put)\s+"""
    )

    private val trailingFillerRegex = Regex(
        """(?i)\s+(?:to\s+(?:my\s+|the\s+)?(?:shopping\s+)?list|on\s+(?:the\s+)?list|in\s+(?:my\s+)?cart|please)$"""
    )

    private val delimiterRegex = Regex(
        """(?i)(?:[,\n;•&]+|\s+(?:and|also|plus|then)\s+)"""
    )

    private val leadingAttachedQtyRegex = Regex(
        """(?i)^(\d+(?:\.\d+)?)\s*($unitAlternation)?\b\s*(?:of\s+)?(.+)$"""
    )

    private val leadingWordQtyRegex = Regex(
        """(?i)^(a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|half|quarter)\s+($unitAlternation)?\b\s*(?:of\s+)?(.+)$"""
    )

    private val leadingStandaloneWordQtyRegex = Regex(
        """(?i)^(two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)\s+(?:of\s+)?(.+)$"""
    )

    private val trailingQtyRegex = Regex(
        """(?i)^(.+?)\s+(\d+(?:\.\d+)?)\s*($unitAlternation)?$"""
    )

    fun parse(rawTranscript: String): List<ParsedShoppingItem> {
        var cleaned = rawTranscript.trim()
        if (cleaned.isBlank()) return emptyList()

        cleaned = trailingFillerRegex.replace(cleaned, "").trim()
        // Strip outer command phrase once before splitting chunks
        cleaned = leadingFillerRegex.replace(cleaned, "").trim()
        if (cleaned.isBlank()) return emptyList()

        val rawChunks = cleaned.split(delimiterRegex)
        val result = mutableListOf<ParsedShoppingItem>()
        val seenKeys = HashSet<String>()

        for (rawChunk in rawChunks) {
            var chunk = rawChunk.trim()
            if (chunk.isBlank()) continue
            chunk = leadingFillerRegex.replace(chunk, "").trim()
            chunk = trailingFillerRegex.replace(chunk, "").trim()
            if (chunk.isBlank()) continue

            val parsed = parseSingleChunk(chunk) ?: continue
            val key = normalizeKey(parsed.name)
            if (key.isEmpty() || !seenKeys.add(key)) continue
            result.add(parsed)
        }
        return result
    }

    fun normalizeKey(name: String): String =
        name.trim()
            .lowercase(Locale.US)
            .replace(Regex("""[^\p{L}\p{N}\s]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun parseSingleChunk(chunk: String): ParsedShoppingItem? {
        // Handle special phrase "half kg <item>" -> "500 g <item>" or "0.5 kg <item>"
        val halfUnitMatch = Regex("""(?i)^half\s+(?:a\s+)?($unitAlternation)\b\s*(?:of\s+)?(.+)$""").find(chunk)
        if (halfUnitMatch != null) {
            val rawUnit = halfUnitMatch.groupValues[1].lowercase(Locale.US)
            val normUnit = unitMap[rawUnit] ?: rawUnit
            val itemName = cleanItemName(halfUnitMatch.groupValues[2])
            if (itemName.isBlank()) return null
            val qty = when (normUnit) {
                "kg" -> "500 g"
                "L" -> "500 ml"
                else -> "0.5 $normUnit"
            }
            return ParsedShoppingItem(name = itemName, quantityLabel = qty)
        }

        // 1. Leading numeric qty + optional unit ("2 kg atta", "500g paneer", "2 milk")
        val leadNumMatch = leadingAttachedQtyRegex.find(chunk)
        if (leadNumMatch != null) {
            val num = normalizeNumberToken(leadNumMatch.groupValues[1])
            val rawUnit = leadNumMatch.groupValues[2].lowercase(Locale.US)
            val rest = leadNumMatch.groupValues[3]
            val itemName = cleanItemName(rest)
            if (itemName.isNotBlank()) {
                val qty = if (rawUnit.isNotBlank()) {
                    val unit = unitMap[rawUnit] ?: rawUnit
                    "$num $unit"
                } else {
                    num
                }
                return ParsedShoppingItem(name = itemName, quantityLabel = qty)
            }
        }

        // 2. Leading word number + unit ("two packets of biscuits", "three dozen eggs")
        val leadWordUnitMatch = leadingWordQtyRegex.find(chunk)
        if (leadWordUnitMatch != null && leadWordUnitMatch.groupValues[2].isNotBlank()) {
            val num = wordNumbers[leadWordUnitMatch.groupValues[1].lowercase(Locale.US)] ?: "1"
            val unit = unitMap[leadWordUnitMatch.groupValues[2].lowercase(Locale.US)]
                ?: leadWordUnitMatch.groupValues[2]
            val itemName = cleanItemName(leadWordUnitMatch.groupValues[3])
            if (itemName.isNotBlank()) {
                return ParsedShoppingItem(name = itemName, quantityLabel = "$num $unit")
            }
        }

        // 3. Leading standalone plural word number without unit ("two apples", "three bananas")
        val leadWordMatch = leadingStandaloneWordQtyRegex.find(chunk)
        if (leadWordMatch != null) {
            val num = wordNumbers[leadWordMatch.groupValues[1].lowercase(Locale.US)] ?: "1"
            val itemName = cleanItemName(leadWordMatch.groupValues[2])
            if (itemName.isNotBlank()) {
                return ParsedShoppingItem(name = itemName, quantityLabel = num)
            }
        }

        // 4. Trailing numeric qty ("tomatoes 1 kg", "paneer 500g", "eggs 6")
        val trailMatch = trailingQtyRegex.find(chunk)
        if (trailMatch != null) {
            val itemName = cleanItemName(trailMatch.groupValues[1])
            val num = normalizeNumberToken(trailMatch.groupValues[2])
            val rawUnit = trailMatch.groupValues[3].lowercase(Locale.US)
            if (itemName.isNotBlank()) {
                val qty = if (rawUnit.isNotBlank()) {
                    val unit = unitMap[rawUnit] ?: rawUnit
                    "$num $unit"
                } else {
                    num
                }
                return ParsedShoppingItem(name = itemName, quantityLabel = qty)
            }
        }

        // 5. Plain item without explicit quantity ("milk", "bread")
        val plain = cleanItemName(chunk)
        return if (plain.isNotBlank()) ParsedShoppingItem(name = plain, quantityLabel = "1") else null
    }

    private fun normalizeNumberToken(raw: String): String {
        val d = raw.toDoubleOrNull() ?: return raw
        return if (d % 1.0 == 0.0) d.toLong().toString() else raw.trimEnd('0').trimEnd('.')
    }

    private fun cleanItemName(raw: String): String {
        val stripped = raw
            .replace(Regex("""^(?:of|for)\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^[\s\-:.]+|[\s\-:.]+$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (stripped.isBlank()) return ""
        return stripped.split(' ').joinToString(" ") { token ->
            token.replaceFirstChar { ch ->
                if (ch.isLowerCase()) ch.titlecase(Locale.US) else ch.toString()
            }
        }
    }
}
