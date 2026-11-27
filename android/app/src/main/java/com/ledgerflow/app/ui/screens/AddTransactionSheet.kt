package com.ledgerflow.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgerflow.app.domain.AccountTypeCatalog
import com.ledgerflow.app.domain.CalculatorEngine
import com.ledgerflow.app.domain.CategoryTree
import com.ledgerflow.app.domain.MagicRuleEngine
import com.ledgerflow.app.domain.MoneyFormatter
import kotlin.math.abs
import com.ledgerflow.app.ui.LedgerViewModel
import com.ledgerflow.app.ui.components.CategoryEditorBottomSheet
import com.ledgerflow.app.ui.components.CleanCard
import com.ledgerflow.app.ui.components.HierarchicalCategoryPickerSheet
import com.ledgerflow.app.ui.components.HierarchicalCategorySelectorField
import com.ledgerflow.app.ui.components.StatusPill
import com.ledgerflow.app.ui.theme.AlabasterBg
import com.ledgerflow.app.ui.theme.BorderSubtle
import com.ledgerflow.app.ui.theme.EmeraldDark
import com.ledgerflow.app.ui.theme.EmeraldPrimary
import com.ledgerflow.app.ui.theme.EmeraldSoft
import com.ledgerflow.app.ui.theme.LedgerThemeColors
import com.ledgerflow.app.ui.theme.MutedSlate
import com.ledgerflow.app.ui.theme.RoseOutflow
import com.ledgerflow.app.ui.theme.RoseSoft
import com.ledgerflow.app.ui.theme.SlateInk
import com.ledgerflow.app.ui.theme.SlateTint
import com.ledgerflow.app.ui.theme.SurfaceCard
import com.ledgerflow.app.ui.theme.TabularStyle
import java.util.Locale

@Composable
fun AddTransactionScreen(
    vm: LedgerViewModel,
    initialType: String,
    onDismiss: () -> Unit,
) {
    val accounts by vm.accounts.collectAsState()
    val categories by vm.categories.collectAsState()
    val rules by vm.magicRules.collectAsState()
    val vault by vm.vaultState.collectAsState()

    val lockedCurrency = vault.baseCurrency
    var txType by remember { mutableStateOf(initialType) }
    var expr by remember { mutableStateOf("0") }
    var selectedAccountId by remember(accounts) {
        mutableStateOf(
            accounts.firstOrNull { AccountTypeCatalog.normalize(it.accountType) != "LOAN" }?.id
                ?: accounts.firstOrNull()?.id
                ?: ""
        )
    }
    var transferTargetId by remember(accounts) { mutableStateOf(accounts.getOrNull(1)?.id ?: accounts.firstOrNull()?.id ?: "") }
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedCategoryIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var activeParentId by remember { mutableStateOf<String?>(null) }
    var showCreateCategoryDialog by remember { mutableStateOf(false) }
    var showCategoryPicker by remember { mutableStateOf(false) }
    var createInitialParentId by remember { mutableStateOf<String?>(null) }
    var createInitialKind by remember { mutableStateOf(initialType) }
    var merchant by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var recurring by remember { mutableStateOf(false) }

    val evaluatedMinor = remember(expr, lockedCurrency) { CalculatorEngine.evaluateToMinor(expr, lockedCurrency) ?: 0L }
    val autoMatch = remember(merchant, note, rules) { MagicRuleEngine.apply(merchant, note, rules) }
    val selectableCategories = remember(categories, txType) {
        val filtered = categories.filter { it.kind == txType || it.name.equals("General", ignoreCase = true) }
        filtered.ifEmpty { categories }
    }
    val parentCategories = remember(selectableCategories) {
        selectableCategories.filter { it.parentCategoryId == null }
    }
    val childrenByParent = remember(selectableCategories) {
        selectableCategories.filter { it.parentCategoryId != null }.groupBy { it.parentCategoryId!! }
    }
    val catById = remember(categories) { categories.associateBy { it.id } }
    val defaultCategoryId = remember(selectableCategories, txType) {
        selectableCategories.firstOrNull { it.name.equals("General", ignoreCase = true) }?.id
            ?: selectableCategories.firstOrNull()?.id
    }
    val effectivePrimaryId = selectedCategoryIds.firstOrNull() ?: autoMatch.categoryId ?: defaultCategoryId
    val effectiveCatIds = if (selectedCategoryIds.isNotEmpty()) selectedCategoryIds.toSet() else setOfNotNull(effectivePrimaryId)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AlabasterBg)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // Top modal bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(SlateTint),
            ) {
                Icon(Icons.Outlined.Close, contentDescription = "Close", tint = SlateInk)
            }

            Surface(shape = RoundedCornerShape(999.dp), color = SlateTint) {
                Row(modifier = Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf("EXPENSE" to "Expense", "INCOME" to "Income", "TRANSFER" to "Transfer").forEach { (key, title) ->
                        val active = txType == key
                        Surface(
                            onClick = {
                                if (txType != key) {
                                    txType = key
                                    selectedCategoryIds = emptyList()
                                    activeParentId = null
                                }
                            },
                            shape = RoundedCornerShape(999.dp),
                            color = if (active) EmeraldPrimary else Color.Transparent,
                        ) {
                            Text(
                                text = title,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (active) Color.White else MutedSlate,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }

            StatusPill(lockedCurrency, EmeraldSoft, EmeraldDark)
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            // Hero Amount Card
            CleanCard(padding = 16.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("AMOUNT ($lockedCurrency)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MutedSlate)
                    StatusPill("Locked", SlateTint, MutedSlate)
                }
                Spacer(Modifier.height(6.dp))
                val signPrefix = when (txType) {
                    "EXPENSE" -> "-"
                    "INCOME" -> "+"
                    else -> ""
                }
                Text(
                    text = "$signPrefix${MoneyFormatter.currencySymbol(lockedCurrency)}$expr",
                    style = TabularStyle,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (txType == "EXPENSE") RoseOutflow else if (txType == "INCOME") EmeraldDark else SlateInk,
                )
                Spacer(Modifier.height(10.dp))
                val sym = MoneyFormatter.currencySymbol(lockedCurrency)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val divisor = if (lockedCurrency.equals("JPY", ignoreCase = true)) 1.0 else 100.0
                    listOf("10", "25", "50", "100").forEach { inc ->
                        Surface(
                            onClick = {
                                val next = (evaluatedMinor / divisor) + inc.toDouble()
                                expr = if (next % 1.0 == 0.0) next.toLong().toString() else String.format(Locale.US, "%.2f", next)
                            },
                            shape = RoundedCornerShape(999.dp),
                            color = SlateTint,
                        ) {
                            Text("+$sym$inc", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = SlateInk, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                        }
                    }
                    Surface(
                        onClick = { expr = "0" },
                        shape = RoundedCornerShape(999.dp),
                        color = RoseSoft,
                    ) {
                        Text("Clear", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = RoseOutflow, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                    }
                }
            }

            // Details Card
            CleanCard(padding = 14.dp) {
                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text("Merchant / Payee") },
                    placeholder = { Text("Merchant or payee name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))

                Text("Account", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MutedSlate)
                Spacer(Modifier.height(4.dp))
                if (accounts.isEmpty()) {
                    Text(
                        text = "No accounts available — add an account in the Hub tab.",
                        fontSize = 12.sp,
                        color = RoseOutflow,
                    )
                } else {
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        accounts.forEach { acc ->
                            val balText = if (acc.balanceMinor < 0L && AccountTypeCatalog.isLiability(acc.accountType)) {
                                "Owed ${MoneyFormatter.formatMinor(abs(acc.balanceMinor), acc.currency)}"
                            } else {
                                MoneyFormatter.formatMinor(acc.balanceMinor, acc.currency)
                            }
                            FilterChip(
                                selected = selectedAccountId == acc.id,
                                onClick = { selectedAccountId = acc.id },
                                label = { Text("${acc.name} ($balText)", fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = EmeraldSoft, selectedLabelColor = EmeraldDark),
                            )
                        }
                    }
                }

                if (txType == "TRANSFER") {
                    Spacer(Modifier.height(8.dp))
                    Text("Transfer To", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MutedSlate)
                    Spacer(Modifier.height(4.dp))
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        accounts.forEach { acc ->
                            FilterChip(
                                selected = transferTargetId == acc.id,
                                onClick = { transferTargetId = acc.id },
                                label = { Text(acc.name, fontSize = 12.sp) },
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = if (selectedCategoryIds.size > 1) "Categories (${selectedCategoryIds.size})" else "Category",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MutedSlate,
                            )
                            if (selectedCategoryIds.isEmpty() && autoMatch.matched) {
                                StatusPill("Auto-Rule", EmeraldSoft, EmeraldDark)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                onClick = {
                                    multiSelectMode = !multiSelectMode
                                    if (!multiSelectMode && selectedCategoryIds.size > 1) {
                                        selectedCategoryIds = listOf(selectedCategoryIds.first())
                                    }
                                },
                                shape = RoundedCornerShape(999.dp),
                                color = if (multiSelectMode) EmeraldSoft else SlateTint,
                                border = BorderStroke(1.dp, if (multiSelectMode) EmeraldPrimary else BorderSubtle),
                            ) {
                                Text(
                                    text = if (multiSelectMode) "Multi: ON" else "Multi",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (multiSelectMode) EmeraldDark else MutedSlate,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                )
                            }
                            Surface(
                                onClick = {
                                    createInitialParentId = activeParentId
                                    createInitialKind = if (txType == "INCOME") "INCOME" else "EXPENSE"
                                    showCreateCategoryDialog = true
                                },
                                shape = RoundedCornerShape(999.dp),
                                color = SlateTint,
                                border = BorderStroke(1.dp, BorderSubtle),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    Icon(Icons.Outlined.Add, contentDescription = null, tint = EmeraldPrimary, modifier = Modifier.size(13.dp))
                                    Text("New", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = EmeraldPrimary)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))

                    val displaySelectedId = effectiveCatIds.firstOrNull()
                    HierarchicalCategorySelectorField(
                        label = "",
                        selectedCategoryId = displaySelectedId,
                        allCategories = selectableCategories,
                        nullOptionLabel = "Tap to browse full category tree",
                        onClick = { showCategoryPicker = true },
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        parentCategories.forEach { cat ->
                            val hasSelectedDescendant = effectiveCatIds.any { cid ->
                                CategoryTree.isDescendantOrSelf(cid, cat.id, catById)
                            }
                            val isSelected = cat.id in effectiveCatIds || (activeParentId == cat.id && hasSelectedDescendant)
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    activeParentId = cat.id
                                    selectedCategoryIds = if (multiSelectMode) {
                                        if (cat.id in selectedCategoryIds) selectedCategoryIds - cat.id
                                        else selectedCategoryIds + cat.id
                                    } else {
                                        listOf(cat.id)
                                    }
                                },
                                label = { Text(cat.name, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = EmeraldSoft, selectedLabelColor = EmeraldDark),
                            )
                        }
                    }

                    val branchParentId = activeParentId
                        ?: selectedCategoryIds.firstOrNull()?.let { catById[it]?.parentCategoryId ?: it }
                        ?: effectivePrimaryId?.let { catById[it]?.parentCategoryId ?: it }
                    val branchSubs = branchParentId?.let { childrenByParent[it] }.orEmpty()
                    if (branchSubs.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        val parentTitle = branchParentId?.let { CategoryTree.breadcrumbPath(it, catById) }.orEmpty()
                        Text(
                            text = if (parentTitle.isNotBlank()) "Subcategories • $parentTitle" else "Subcategories",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MutedSlate,
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            branchSubs.forEach { sub ->
                                val subSelected = sub.id in effectiveCatIds || effectiveCatIds.any { cid ->
                                    CategoryTree.isDescendantOrSelf(cid, sub.id, catById)
                                }
                                FilterChip(
                                    selected = subSelected,
                                    onClick = {
                                        val hasGrandchildren = childrenByParent[sub.id].orEmpty().isNotEmpty()
                                        if (!multiSelectMode && hasGrandchildren) {
                                            activeParentId = sub.id
                                        }
                                        selectedCategoryIds = if (multiSelectMode) {
                                            if (sub.id in selectedCategoryIds) {
                                                selectedCategoryIds - sub.id
                                            } else {
                                                val trimmed = branchParentId?.let { selectedCategoryIds - it } ?: selectedCategoryIds
                                                trimmed + sub.id
                                            }
                                        } else {
                                            listOf(sub.id)
                                        }
                                    },
                                    label = { Text(sub.name, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = EmeraldSoft, selectedLabelColor = EmeraldDark),
                                )
                            }
                        }
                    }

                    if (multiSelectMode && selectedCategoryIds.size > 1) {
                        Spacer(Modifier.height(4.dp))
                        val names = selectedCategoryIds.mapNotNull { CategoryTree.breadcrumbPath(it, catById).ifBlank { catById[it]?.name } }.joinToString(", ")
                        Text(
                            text = "Split evenly: $names",
                            fontSize = 11.sp,
                            color = EmeraldDark,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Note") },
                        singleLine = true,
                        modifier = Modifier.weight(1.3f),
                    )
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("#Label") },
                        placeholder = { Text("optional") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                if (txType != "TRANSFER") {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Recurring monthly payment", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = SlateInk)
                        Switch(
                            checked = recurring,
                            onCheckedChange = { recurring = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = EmeraldPrimary),
                        )
                    }
                }
            }
        }

        // Docked Calculator Keypad & Primary CTA
        Spacer(Modifier.height(8.dp))
        CalculatorPad(
            onKey = { key ->
                val ops = setOf("÷", "×", "-", "+")
                expr = when {
                    key == "DEL" -> if (expr.length <= 1) "0" else expr.dropLast(1)
                    key == "." -> {
                        val lastSegment = expr.split('÷', '×', '-', '+').lastOrNull().orEmpty()
                        when {
                            lastSegment.contains('.') -> expr
                            expr.lastOrNull()?.toString() in ops -> "${expr}0."
                            else -> "$expr."
                        }
                    }
                    key in ops -> {
                        when {
                            expr.lastOrNull()?.toString() in ops -> expr.dropLast(1) + key
                            else -> expr + key
                        }
                    }
                    expr == "0" -> key
                    else -> expr + key
                }
            },
        )
        Spacer(Modifier.height(10.dp))
        Button(
            enabled = evaluatedMinor > 0L && selectedAccountId.isNotBlank(),
            onClick = {
                val ok = vm.addTransaction(
                    txType = txType,
                    amountExpr = expr,
                    currency = lockedCurrency,
                    accountId = selectedAccountId,
                    transferAccountId = transferTargetId,
                    manualCategoryId = selectedCategoryIds.firstOrNull(),
                    merchant = merchant,
                    note = note,
                    labelsCsv = label,
                    recurringMonthly = recurring,
                    fallbackCategoryId = if (txType == "TRANSFER") null else defaultCategoryId,
                    selectedCategoryIds = if (txType == "TRANSFER") emptyList() else selectedCategoryIds,
                )
                if (ok) onDismiss()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
        ) {
            val transferTarget = accounts.firstOrNull { it.id == transferTargetId }
            val actionLabel = when {
                txType == "INCOME" -> "Save Income"
                txType == "TRANSFER" && transferTarget != null && AccountTypeCatalog.isLiability(transferTarget.accountType) ->
                    "Repay ${AccountTypeCatalog.displayLabel(transferTarget.accountType)}"
                txType == "TRANSFER" -> "Save Transfer"
                else -> "Save Expense"
            }
            Text(
                text = "$actionLabel (${MoneyFormatter.formatMinor(evaluatedMinor, lockedCurrency)})",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
        }
    }

    if (showCategoryPicker && txType != "TRANSFER") {
        HierarchicalCategoryPickerSheet(
            title = if (multiSelectMode) "Select Categories" else "Select Category",
            categories = categories,
            kindFilter = if (txType == "INCOME") "INCOME" else "EXPENSE",
            selectedIds = effectiveCatIds,
            multiSelect = multiSelectMode,
            onSelectSingle = { chosen ->
                selectedCategoryIds = listOf(chosen.id)
                activeParentId = chosen.parentCategoryId ?: chosen.id
            },
            onConfirmMulti = { ids ->
                selectedCategoryIds = ids
            },
            onQuickCreate = { parentCatId, kindResolved ->
                showCategoryPicker = false
                createInitialParentId = parentCatId
                createInitialKind = kindResolved
                showCreateCategoryDialog = true
            },
            onDismiss = { showCategoryPicker = false },
        )
    }

    if (showCreateCategoryDialog) {
        CategoryEditorBottomSheet(
            editingCategory = null,
            initialKind = createInitialKind.ifBlank { if (txType == "INCOME") "INCOME" else "EXPENSE" },
            initialParentId = createInitialParentId,
            allCategories = categories,
            onDismiss = { showCreateCategoryDialog = false },
            onSave = { name, kind, parentId, iconKey, colorHex, description ->
                vm.addCategory(
                    name = name,
                    kind = kind,
                    parentCategoryId = parentId,
                    iconKey = iconKey,
                    colorHex = colorHex,
                    description = description,
                    onResult = { ok, _ ->
                        if (ok) {
                            showCreateCategoryDialog = false
                        }
                    },
                    onCreated = { created ->
                        if (created.kind == txType) {
                            selectedCategoryIds = if (multiSelectMode) {
                                (selectedCategoryIds + created.id).distinct()
                            } else {
                                listOf(created.id)
                            }
                            activeParentId = created.parentCategoryId ?: created.id
                        }
                    },
                )
            },
        )
    }
}

@Composable
private fun CalculatorPad(onKey: (String) -> Unit) {
    val rows = listOf(
        listOf("1", "2", "3", "÷"),
        listOf("4", "5", "6", "×"),
        listOf("7", "8", "9", "-"),
        listOf(".", "0", "DEL", "+"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { key ->
                    val isOp = key in listOf("÷", "×", "-", "+")
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .clickable { onKey(key) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isOp) LedgerThemeColors.calcOpContainer else SurfaceCard,
                        border = BorderStroke(1.dp, BorderSubtle),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (key == "DEL") {
                                Icon(Icons.AutoMirrored.Outlined.Backspace, contentDescription = "Backspace", tint = SlateInk, modifier = Modifier.size(18.dp))
                            } else {
                                Text(key, style = TabularStyle, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = SlateInk)
                            }
                        }
                    }
                }
            }
        }
    }
}
