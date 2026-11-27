package com.ledgerflow.app.domain

import java.util.Locale

data class ShoppingUnitGroup(
    val categoryName: String,
    val units: List<String>,
)

data class ParsedQuantityAndUnit(
    val quantityValue: Double?,
    val quantityUnit: String,
    val label: String,
)

/**
 * Structured shopping unit catalog and formatting/validation utilities.
 * Supports predefined unit groups plus free-form user custom units.
 */
object ShoppingUnitCatalog {
    const val UNIT_CUSTOM_SENTINEL = "custom"

    val unitGroups: List<ShoppingUnitGroup> = listOf(
        ShoppingUnitGroup("Weight", listOf("kg", "g", "mg")),
        ShoppingUnitGroup("Volume", listOf("litre", "ml")),
        ShoppingUnitGroup("Count", listOf("piece", "pieces", "dozen")),
        ShoppingUnitGroup("Packaging", listOf("packet", "pack", "box", "bottle")),
        ShoppingUnitGroup("Length", listOf("metre", "cm")),
        ShoppingUnitGroup("Other", listOf("set", "pair", UNIT_CUSTOM_SENTINEL)),
    )

    val allPredefinedUnits: List<String> = unitGroups.flatMap { it.units }.filter { it != UNIT_CUSTOM_SENTINEL }

    fun normalizeUnit(raw: String): String {
        val clean = raw.trim()
        return when (clean.lowercase(Locale.US)) {
            "" -> ""
            "l", "lt", "ltr", "ltrs", "liter", "liters", "litres", "litre" -> "litre"
            "gm", "gms", "gram", "grams", "g" -> "g"
            "kgs", "kilo", "kilos", "kilogram", "kilograms", "kg" -> "kg"
            "mgs", "milligram", "milligrams", "mg" -> "mg"
            "milliliter", "milliliters", "millilitre", "millilitres", "ml" -> "ml"
            "pc", "piece" -> "piece"
            "pcs", "pieces" -> "pieces"
            "doz", "dz", "dozens", "dozen" -> "dozen"
            "pkt", "pkts", "packet", "packets" -> "packet"
            "pack", "packs" -> "pack"
            "box", "boxes" -> "box"
            "bottle", "bottles" -> "bottle"
            "m", "meter", "meters", "metre", "metres" -> "metre"
            "cm", "cms", "centimeter", "centimeters", "centimetre", "centimetres" -> "cm"
            "set", "sets" -> "set"
            "pair", "pairs" -> "pair"
            else -> clean
        }
    }

    fun formatNumber(value: Double): String {
        if (!value.isFinite() || value <= 0.0) return ""
        return if (value % 1.0 == 0.0) {
            value.toLong().toString()
        } else {
            String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')
        }
    }

    fun formatQuantityLabel(
        quantityValue: Double?,
        quantityUnit: String,
        fallbackLabel: String = "",
    ): String {
        val cleanUnit = quantityUnit.trim()
        val numStr = quantityValue?.takeIf { it.isFinite() && it > 0.0 }?.let(::formatNumber).orEmpty()
        return when {
            numStr.isNotEmpty() && cleanUnit.isNotEmpty() -> {
                val displayUnit = pluralizeIfNeeded(cleanUnit, quantityValue!!)
                "$numStr $displayUnit"
            }
            numStr.isNotEmpty() -> numStr
            cleanUnit.isNotEmpty() -> cleanUnit
            else -> fallbackLabel.trim()
        }
    }

    private fun pluralizeIfNeeded(unit: String, value: Double): String {
        if (value == 1.0) return unit
        return when (unit.lowercase(Locale.US)) {
            "litre" -> "litres"
            "packet" -> "packets"
            "piece" -> "pieces"
            "bottle" -> "bottles"
            "metre" -> "metres"
            "box" -> "boxes"
            else -> unit
        }
    }

    /**
     * Parses a legacy label like `"5 kg"`, `"2 litres"`, `"12 pcs"`, or `"3 jars"` into
     * structured numeric quantity and unit strings while preserving exact compatibility.
     */
    fun parseLegacyLabel(rawLabel: String): ParsedQuantityAndUnit {
        val cleaned = rawLabel.trim()
        if (cleaned.isBlank()) {
            return ParsedQuantityAndUnit(quantityValue = null, quantityUnit = "", label = "")
        }
        val numUnitMatch = Regex("""^(\d+(?:\.\d+)?)\s*(.*)$""").find(cleaned)
        if (numUnitMatch != null) {
            val value = numUnitMatch.groupValues[1].toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
            val unitRaw = numUnitMatch.groupValues[2].trim()
            val normUnit = if (unitRaw.isNotEmpty()) normalizeUnit(unitRaw) else ""
            if (value != null) {
                return ParsedQuantityAndUnit(
                    quantityValue = value,
                    quantityUnit = normUnit,
                    label = formatQuantityLabel(value, normUnit, cleaned),
                )
            }
        }
        return ParsedQuantityAndUnit(quantityValue = null, quantityUnit = cleaned, label = cleaned)
    }

    /**
     * Validates an item quantity string typed by the user. Blank is allowed (optional quantity).
     * Negative, zero, NaN, or oversized (> 1,000,000) values return null with an error message.
     */
    fun validateQuantityInput(rawInput: String): Pair<Double?, String?> {
        val clean = rawInput.trim()
        if (clean.isEmpty()) return null to null
        if (clean.startsWith("-")) return null to "Quantity cannot be negative"
        val parsed = clean.toDoubleOrNull()
        return when {
            parsed == null || !parsed.isFinite() -> null to "Enter a valid number for quantity"
            parsed <= 0.0 -> null to "Quantity must be greater than 0"
            parsed > 1_000_000.0 -> null to "Quantity exceeds maximum allowed limit"
            else -> parsed to null
        }
    }
}
