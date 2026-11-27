package com.ledgerflow.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgerflow.app.domain.AnalyticsCalculator
import com.ledgerflow.app.domain.MoneyFormatter
import com.ledgerflow.app.domain.ParsedShoppingItem
import com.ledgerflow.app.ui.LedgerViewModel
import com.ledgerflow.app.ui.components.CategoryIconBadge
import com.ledgerflow.app.ui.components.CleanCard
import com.ledgerflow.app.ui.theme.AlabasterBg
import com.ledgerflow.app.ui.theme.BorderSubtle
import com.ledgerflow.app.ui.theme.EmeraldDark
import com.ledgerflow.app.ui.theme.EmeraldPrimary
import com.ledgerflow.app.ui.theme.EmeraldSoft
import com.ledgerflow.app.ui.theme.MutedSlate
import com.ledgerflow.app.ui.theme.RoseOutflow
import com.ledgerflow.app.ui.theme.SlateInk
import com.ledgerflow.app.ui.theme.SlateTint
import com.ledgerflow.app.ui.theme.SurfaceCard
import com.ledgerflow.app.ui.theme.TabularStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingListsScreen(
    vm: LedgerViewModel,
    addTrigger: Int = 0,
    onOpenDrawer: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val lists by vm.shoppingLists.collectAsState()
    val allItems by vm.shoppingItems.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val categories by vm.categories.collectAsState()
    val vault by vm.vaultState.collectAsState()

    val baseCurrency = vault.baseCurrency
    val masked = vault.hideBalances
    val catMap = remember(categories) { categories.associateBy { it.id } }

    var activeListId by remember { mutableStateOf<String?>(null) }
    var showNewListSheet by remember { mutableStateOf(false) }
    var showAddItemSheet by remember { mutableStateOf(false) }
    var showConvertSheet by remember { mutableStateOf(false) }
    var checkedExpanded by remember { mutableStateOf(true) }

    val activeList = remember(lists, activeListId) {
        lists.firstOrNull { it.id == activeListId }
    }

    LaunchedEffect(addTrigger) {
        if (addTrigger > 0) {
            if (activeList != null) {
                showAddItemSheet = true
            } else {
                showNewListSheet = true
            }
        }
    }

    BackHandler(enabled = activeList != null) {
        activeListId = null
    }

    if (activeList == null) {
        // ---- Checklist Overview Screen ----
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(AlabasterBg),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 92.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        IconButton(onClick = onOpenDrawer) {
                            Icon(Icons.Outlined.Menu, contentDescription = "Open menu", tint = SlateInk)
                        }
                        Text("Shopping Lists", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = SlateInk)
                    }
                }
            }

            if (lists.isEmpty()) {
                item {
                    CleanCard(padding = 22.dp) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CategoryIconBadge("checklist", "#059669", size = 54.dp)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "Plan before you shop",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = SlateInk,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Create reusable checklists with estimated prices and turn your checked basket into an expense in one tap.",
                                fontSize = 13.sp,
                                color = MutedSlate,
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = { showNewListSheet = true },
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text("Create Checklist", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            } else {
                items(lists, key = { it.id }) { list ->
                    val summary = remember(list, allItems) {
                        AnalyticsCalculator.summarizeShoppingList(list, allItems)
                    }
                    val ratio = if (summary.totalItems > 0) {
                        (summary.checkedItems.toFloat() / summary.totalItems.toFloat()).coerceIn(0f, 1f)
                    } else 0f
                    val defaultCat = list.defaultCategoryId?.let { catMap[it] }

                    CleanCard(
                        padding = 16.dp,
                        onClick = { activeListId = list.id },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                CategoryIconBadge(
                                    iconKey = defaultCat?.iconKey ?: "checklist",
                                    tintHex = defaultCat?.colorHex ?: "#059669",
                                    size = 40.dp,
                                )
                                Column {
                                    Text(
                                        text = list.title,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SlateInk,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val sub = listOfNotNull(
                                        "${summary.checkedItems} / ${summary.totalItems} items",
                                        defaultCat?.name,
                                    ).joinToString(" • ")
                                    Text(sub, fontSize = 12.sp, color = MutedSlate)
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = MoneyFormatter.formatMinor(summary.estimatedTotalMinor, baseCurrency, masked = masked),
                                    style = TabularStyle,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SlateInk,
                                )
                                Text("Est. total", fontSize = 10.sp, color = MutedSlate)
                            }
                        }
                        if (summary.totalItems > 0) {
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { ratio },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(999.dp)),
                                color = EmeraldPrimary,
                                trackColor = SlateTint,
                            )
                        }
                    }
                }
            }
        }
    } else {
        // ---- Checklist Detail View ----
        val listItems = remember(allItems, activeList.id) {
            allItems.filter { it.listId == activeList.id }
        }
        val uncheckedItems = remember(listItems) { listItems.filter { !it.checked } }
        val checkedItems = remember(listItems) { listItems.filter { it.checked } }
        val checkedTotalMinor = remember(checkedItems) {
            checkedItems.sumOf { kotlin.math.abs(it.estimatedPriceMinor) }
        }

        var quickItemName by remember(activeList.id) { mutableStateOf("") }
        var quickItemPrice by remember(activeList.id) { mutableStateOf("") }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AlabasterBg),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = if (checkedItems.isNotEmpty()) 156.dp else 92.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            IconButton(onClick = { activeListId = null }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                    contentDescription = "Back to lists",
                                    tint = SlateInk,
                                )
                            }
                            Column {
                                Text(
                                    text = activeList.title,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SlateInk,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${checkedItems.size} of ${listItems.size} checked",
                                    fontSize = 12.sp,
                                    color = MutedSlate,
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                val targetId = activeList.id
                                activeListId = null
                                vm.deleteShoppingList(targetId)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = "Delete checklist",
                                tint = RoseOutflow,
                            )
                        }
                    }
                }

                // Quick-Add Item Bar at top
                item {
                    CleanCard(padding = 12.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = quickItemName,
                                onValueChange = { quickItemName = it },
                                label = { Text("Item name", fontSize = 12.sp) },
                                placeholder = { Text("Milk, Bread...") },
                                singleLine = true,
                                modifier = Modifier.weight(1.4f),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                shape = RoundedCornerShape(12.dp),
                            )
                            OutlinedTextField(
                                value = quickItemPrice,
                                onValueChange = {
                                    quickItemPrice = MoneyFormatter.sanitizeDecimalInput(it, baseCurrency, allowNegative = false)
                                },
                                label = { Text("Est. ${MoneyFormatter.currencySymbol(baseCurrency)}", fontSize = 12.sp) },
                                placeholder = { Text("0") },
                                singleLine = true,
                                modifier = Modifier.weight(0.9f),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Decimal,
                                    imeAction = ImeAction.Done,
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        if (quickItemName.isNotBlank()) {
                                            val minor = MoneyFormatter.parseDecimalToMinor(quickItemPrice, baseCurrency) ?: 0L
                                            vm.addShoppingItem(activeList.id, quickItemName, minor)
                                            quickItemName = ""
                                            quickItemPrice = ""
                                        }
                                    }
                                ),
                                shape = RoundedCornerShape(12.dp),
                            )
                            Surface(
                                onClick = {
                                    if (quickItemName.isNotBlank()) {
                                        val minor = MoneyFormatter.parseDecimalToMinor(quickItemPrice, baseCurrency) ?: 0L
                                        vm.addShoppingItem(activeList.id, quickItemName, minor)
                                        quickItemName = ""
                                        quickItemPrice = ""
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = EmeraldPrimary,
                                modifier = Modifier.size(48.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.Add, contentDescription = "Add item", tint = Color.White)
                                }
                            }
                        }
                    }
                }

                if (listItems.isEmpty()) {
                    item {
                        CleanCard(padding = 20.dp) {
                            Text("Your checklist is empty", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = SlateInk)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Add items above with optional estimated prices as you plan your trip.",
                                fontSize = 13.sp,
                                color = MutedSlate,
                            )
                        }
                    }
                } else {
                    items(uncheckedItems, key = { it.id }) { item ->
                        CleanCard(padding = 8.dp, onClick = { vm.toggleShoppingItem(item.id) }) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Checkbox(
                                        checked = false,
                                        onCheckedChange = { vm.toggleShoppingItem(item.id) },
                                        colors = CheckboxDefaults.colors(checkedColor = EmeraldPrimary),
                                    )
                                    Text(
                                        text = ParsedShoppingItem.formatDisplay(item.name, item.quantityLabel),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = SlateInk,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (item.estimatedPriceMinor > 0L) {
                                        Text(
                                            text = MoneyFormatter.formatMinor(item.estimatedPriceMinor, baseCurrency, masked = masked),
                                            style = TabularStyle,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = SlateInk,
                                        )
                                    }
                                    IconButton(onClick = { vm.deleteShoppingItem(item.id) }) {
                                        Icon(
                                            Icons.Outlined.Close,
                                            contentDescription = "Remove item",
                                            tint = MutedSlate,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (checkedItems.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { checkedExpanded = !checkedExpanded }
                                    .padding(horizontal = 4.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Checked (${checkedItems.size}) • ${MoneyFormatter.formatMinor(checkedTotalMinor, baseCurrency, masked = masked)}",
                                    style = TabularStyle,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MutedSlate,
                                )
                                Icon(
                                    imageVector = if (checkedExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                    contentDescription = "Toggle checked items",
                                    tint = MutedSlate,
                                )
                            }
                        }

                        if (checkedExpanded) {
                            items(checkedItems, key = { it.id }) { item ->
                                Surface(
                                    onClick = { vm.toggleShoppingItem(item.id) },
                                    shape = RoundedCornerShape(14.dp),
                                    color = SlateTint,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Checkbox(
                                                checked = true,
                                                onCheckedChange = { vm.toggleShoppingItem(item.id) },
                                                colors = CheckboxDefaults.colors(checkedColor = EmeraldPrimary),
                                            )
                                            Text(
                                                text = ParsedShoppingItem.formatDisplay(item.name, item.quantityLabel),
                                                fontSize = 14.sp,
                                                color = MutedSlate,
                                                textDecoration = TextDecoration.LineThrough,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (item.estimatedPriceMinor > 0L) {
                                                Text(
                                                    text = MoneyFormatter.formatMinor(item.estimatedPriceMinor, baseCurrency, masked = masked),
                                                    style = TabularStyle,
                                                    fontSize = 12.sp,
                                                    color = MutedSlate,
                                                    textDecoration = TextDecoration.LineThrough,
                                                )
                                            }
                                            IconButton(onClick = { vm.deleteShoppingItem(item.id) }) {
                                                Icon(
                                                    Icons.Outlined.Close,
                                                    contentDescription = "Remove checked item",
                                                    tint = MutedSlate,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Sticky Bottom Bar: Convert checked items into real Expense transaction
            AnimatedVisibility(
                visible = checkedItems.isNotEmpty(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = SurfaceCard,
                    border = BorderStroke(1.dp, EmeraldPrimary.copy(alpha = 0.35f)),
                    shadowElevation = 4.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                text = "${checkedItems.size} item(s) ready",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MutedSlate,
                            )
                            Text(
                                text = if (checkedTotalMinor > 0L) {
                                    MoneyFormatter.formatMinor(checkedTotalMinor, baseCurrency, masked = masked)
                                } else {
                                    "Enter total on checkout"
                                },
                                style = TabularStyle,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = SlateInk,
                            )
                        }
                        Button(
                            onClick = { showConvertSheet = true },
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Log as Expense", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            if (showAddItemSheet) {
                QuickAddItemBottomSheet(
                    baseCurrency = baseCurrency,
                    onDismiss = { showAddItemSheet = false },
                    onAdd = { name, priceMinor ->
                        vm.addShoppingItem(activeList.id, name, priceMinor)
                        showAddItemSheet = false
                    },
                )
            }

            if (showConvertSheet && checkedItems.isNotEmpty()) {
                ConvertShoppingExpenseBottomSheet(
                    listTitle = activeList.title,
                    defaultCategoryId = activeList.defaultCategoryId,
                    initialTotalMinor = checkedTotalMinor,
                    checkedCount = checkedItems.size,
                    baseCurrency = baseCurrency,
                    accounts = accounts,
                    categories = categories.filter { it.kind == "EXPENSE" },
                    onDismiss = { showConvertSheet = false },
                    onConfirm = { accountId, categoryId, totalMinor, merchantTitle ->
                        vm.convertCheckedShoppingToExpense(
                            listId = activeList.id,
                            itemIds = checkedItems.map { it.id },
                            accountId = accountId,
                            categoryId = categoryId,
                            totalMinor = totalMinor,
                            currency = baseCurrency,
                            merchantTitle = merchantTitle,
                            onSuccess = { showConvertSheet = false },
                        )
                    },
                )
            }
        }
    }

    if (showNewListSheet) {
        NewShoppingListBottomSheet(
            categories = categories.filter { it.kind == "EXPENSE" && it.parentCategoryId == null },
            onDismiss = { showNewListSheet = false },
            onCreate = { title, defaultCatId ->
                vm.createShoppingList(title, defaultCatId) { createdId ->
                    activeListId = createdId
                }
                showNewListSheet = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewShoppingListBottomSheet(
    categories: List<com.ledgerflow.app.data.local.CategoryEntity>,
    onDismiss: () -> Unit,
    onCreate: (title: String, defaultCategoryId: String?) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var selectedCatId by remember(categories) {
        mutableStateOf(
            categories.firstOrNull { it.name.equals("Groceries", ignoreCase = true) }?.id
                ?: categories.firstOrNull { it.name.equals("Shopping", ignoreCase = true) }?.id
                ?: categories.firstOrNull()?.id
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SurfaceCard,
        contentColor = SlateInk,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("New Shopping List", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = SlateInk)
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Checklist Name") },
                placeholder = { Text("e.g. Weekly Groceries, Weekend Hardware") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
            Text("Default Expense Category", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MutedSlate)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                categories.forEach { cat ->
                    FilterChip(
                        selected = selectedCatId == cat.id,
                        onClick = { selectedCatId = cat.id },
                        label = { Text(cat.name, fontSize = 11.sp) },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = title.isNotBlank(),
                    onClick = { onCreate(title, selectedCatId) },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Create Checklist", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickAddItemBottomSheet(
    baseCurrency: String,
    onDismiss: () -> Unit,
    onAdd: (name: String, estimatedPriceMinor: Long) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SurfaceCard,
        contentColor = SlateInk,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Add Shopping Item", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = SlateInk)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Item Name") },
                placeholder = { Text("e.g. Organic Milk 1L") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
            OutlinedTextField(
                value = price,
                onValueChange = { price = MoneyFormatter.sanitizeDecimalInput(it, baseCurrency, allowNegative = false) },
                label = { Text("Estimated Price ($baseCurrency, optional)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = name.isNotBlank(),
                    onClick = {
                        val minor = MoneyFormatter.parseDecimalToMinor(price, baseCurrency) ?: 0L
                        onAdd(name, minor)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Add Item", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConvertShoppingExpenseBottomSheet(
    listTitle: String,
    defaultCategoryId: String?,
    initialTotalMinor: Long,
    checkedCount: Int,
    baseCurrency: String,
    accounts: List<com.ledgerflow.app.data.local.AccountEntity>,
    categories: List<com.ledgerflow.app.data.local.CategoryEntity>,
    onDismiss: () -> Unit,
    onConfirm: (accountId: String, categoryId: String?, totalMinor: Long, merchantTitle: String) -> Unit,
) {
    var merchant by remember(listTitle) { mutableStateOf(listTitle) }
    var totalText by remember(initialTotalMinor) {
        mutableStateOf(
            if (initialTotalMinor > 0L) {
                val div = if (baseCurrency.equals("JPY", ignoreCase = true)) 1.0 else 100.0
                if (baseCurrency.equals("JPY", ignoreCase = true)) initialTotalMinor.toString()
                else String.format(Locale.US, "%.2f", initialTotalMinor / div)
            } else ""
        )
    }
    var selectedAccountId by remember(accounts) { mutableStateOf(accounts.firstOrNull()?.id) }
    var selectedCategoryId by remember(defaultCategoryId, categories) {
        mutableStateOf(
            defaultCategoryId?.takeIf { id -> categories.any { it.id == id } }
                ?: categories.firstOrNull { it.name.equals("Groceries", ignoreCase = true) }?.id
                ?: categories.firstOrNull { it.name.equals("Shopping", ignoreCase = true) }?.id
                ?: categories.firstOrNull()?.id
        )
    }
    val parsedTotal = MoneyFormatter.parseDecimalToMinor(totalText, baseCurrency) ?: 0L

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SurfaceCard,
        contentColor = SlateInk,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Log Checked Items as Expense", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = SlateInk)
            Text(
                text = "Converts $checkedCount checked item(s) into a single ledger expense and clears them from this list.",
                fontSize = 12.sp,
                color = MutedSlate,
            )

            OutlinedTextField(
                value = merchant,
                onValueChange = { merchant = it },
                label = { Text("Merchant / Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
            OutlinedTextField(
                value = totalText,
                onValueChange = { totalText = MoneyFormatter.sanitizeDecimalInput(it, baseCurrency, allowNegative = false) },
                label = { Text("Total Spent ($baseCurrency)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            Text("Pay From Account", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MutedSlate)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                accounts.forEach { acc ->
                    FilterChip(
                        selected = selectedAccountId == acc.id,
                        onClick = { selectedAccountId = acc.id },
                        label = { Text(acc.name, fontSize = 11.sp) },
                    )
                }
            }

            Text("Expense Category", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MutedSlate)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                categories.filter { it.parentCategoryId == null }.forEach { cat ->
                    FilterChip(
                        selected = selectedCategoryId == cat.id,
                        onClick = { selectedCategoryId = cat.id },
                        label = { Text(cat.name, fontSize = 11.sp) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = selectedAccountId != null && parsedTotal > 0L,
                    onClick = {
                        selectedAccountId?.let { accId ->
                            onConfirm(accId, selectedCategoryId, parsedTotal, merchant)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Save Expense", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
