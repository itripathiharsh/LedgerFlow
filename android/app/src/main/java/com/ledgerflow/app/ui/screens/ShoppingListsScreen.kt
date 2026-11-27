package com.ledgerflow.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
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
import com.ledgerflow.app.data.local.AccountEntity
import com.ledgerflow.app.data.local.CategoryEntity
import com.ledgerflow.app.data.local.ShoppingItemEntity
import com.ledgerflow.app.data.local.ShoppingListEntity
import com.ledgerflow.app.domain.AnalyticsCalculator
import com.ledgerflow.app.domain.CategoryTree
import com.ledgerflow.app.domain.MoneyFormatter
import com.ledgerflow.app.domain.ShoppingUnitCatalog
import com.ledgerflow.app.ui.LedgerViewModel
import com.ledgerflow.app.ui.components.CategoryIconBadge
import com.ledgerflow.app.ui.components.CleanCard
import com.ledgerflow.app.ui.components.HierarchicalCategoryPickerSheet
import com.ledgerflow.app.ui.components.HierarchicalCategorySelectorField
import com.ledgerflow.app.ui.components.StatusPill
import com.ledgerflow.app.ui.theme.AlabasterBg
import com.ledgerflow.app.ui.theme.AmberSoft
import com.ledgerflow.app.ui.theme.AmberWarn
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
    initialListId: String? = null,
    onConsumeInitialList: () -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val lists by vm.shoppingLists.collectAsState()
    val allItems by vm.shoppingItems.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val categories by vm.categories.collectAsState()
    val allCategoriesLookup by vm.categoriesIncludingDeleted.collectAsState()
    val vault by vm.vaultState.collectAsState()

    val baseCurrency = vault.baseCurrency
    val masked = vault.hideBalances
    val catMap = remember(allCategoriesLookup) { allCategoriesLookup.associateBy { it.id } }

    var activeListId by remember { mutableStateOf<String?>(null) }
    var showNewListSheet by remember { mutableStateOf(false) }
    var showAddItemSheet by remember { mutableStateOf(false) }
    var editingItem by remember { mutableStateOf<ShoppingItemEntity?>(null) }
    var convertScopeCheckedOnly by remember { mutableStateOf(true) }
    var showConvertSheet by remember { mutableStateOf(false) }
    var checkedExpanded by remember { mutableStateOf(true) }
    var listPendingDelete by remember { mutableStateOf<ShoppingListEntity?>(null) }
    var showClearChecklistConfirm by remember { mutableStateOf(false) }

    val activeList = remember(lists, activeListId) {
        lists.firstOrNull { it.id == activeListId }
    }

    LaunchedEffect(initialListId, lists) {
        if (!initialListId.isNullOrBlank()) {
            if (lists.any { it.id == initialListId }) {
                activeListId = initialListId
            }
            onConsumeInitialList()
        }
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (lists.isNotEmpty()) {
                            Surface(
                                onClick = { showNewListSheet = true },
                                shape = RoundedCornerShape(999.dp),
                                color = EmeraldPrimary,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(Icons.Outlined.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                                    Text("New List", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                            }
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = SlateInk)
                        }
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
                                text = "Track items with structured quantities, units, and estimated total prices, then log completed items as an expense without losing your checklist.",
                                fontSize = 13.sp,
                                color = MutedSlate,
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = { showNewListSheet = true },
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
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
                    val categoryTrail = remember(list.defaultCategoryId, catMap) {
                        list.defaultCategoryId?.let { CategoryTree.breadcrumbPath(it, catMap) }.orEmpty()
                    }
                    val totalEstText = if (masked) "••••" else MoneyFormatter.formatMinor(summary.estimatedTotalMinor, baseCurrency)

                    CleanCard(padding = 14.dp, onClick = { activeListId = list.id }) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                CategoryIconBadge(
                                    iconKey = defaultCat?.iconKey ?: "checklist",
                                    tintHex = defaultCat?.colorHex ?: "#059669",
                                    size = 38.dp,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            text = list.title,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = SlateInk,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        if (!list.lastConvertedTxId.isNullOrBlank()) {
                                            StatusPill("Logged", EmeraldSoft, EmeraldDark)
                                        }
                                    }
                                    Text(
                                        text = if (categoryTrail.isNotBlank()) categoryTrail else "${summary.totalItems} item(s)",
                                        fontSize = 11.sp,
                                        color = MutedSlate,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = totalEstText,
                                        style = TabularStyle,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SlateInk,
                                    )
                                    Text(
                                        text = "${summary.checkedItems}/${summary.totalItems} done",
                                        style = TabularStyle,
                                        fontSize = 11.sp,
                                        color = EmeraldPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                                IconButton(
                                    onClick = { listPendingDelete = list },
                                    modifier = Modifier.size(34.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.DeleteOutline,
                                        contentDescription = "Delete checklist",
                                        tint = MutedSlate,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
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
        // ---- Checklist Detail Screen ----
        val listItems = remember(allItems, activeList.id) {
            allItems.filter { it.listId == activeList.id }
        }
        val remainingItems = remember(listItems) { listItems.filter { !it.checked } }
        val checkedItems = remember(listItems) { listItems.filter { it.checked } }
        val summary = remember(activeList, listItems) {
            AnalyticsCalculator.summarizeShoppingList(activeList, listItems)
        }
        val progress = if (summary.totalItems > 0) {
            (summary.checkedItems.toFloat() / summary.totalItems.toFloat()).coerceIn(0f, 1f)
        } else 0f

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(AlabasterBg)
                .imePadding(),
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Header
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f),
                        ) {
                            IconButton(onClick = { activeListId = null }) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = SlateInk)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = activeList.title,
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SlateInk,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                activeList.defaultCategoryId?.let { cid ->
                                    val trail = CategoryTree.breadcrumbPath(cid, catMap)
                                    if (trail.isNotBlank()) {
                                        Text(
                                            text = trail,
                                            fontSize = 11.sp,
                                            color = MutedSlate,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (checkedItems.isNotEmpty()) {
                                TextButton(onClick = { vm.clearCompletedShoppingItems(activeList.id) }) {
                                    Text("Clear Done", fontSize = 11.sp, color = MutedSlate, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            if (listItems.isNotEmpty()) {
                                IconButton(
                                    onClick = { showClearChecklistConfirm = true },
                                    modifier = Modifier.size(34.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.ClearAll,
                                        contentDescription = "Clear checklist",
                                        tint = MutedSlate,
                                        modifier = Modifier.size(19.dp),
                                    )
                                }
                            }
                            Surface(
                                onClick = { showAddItemSheet = true },
                                shape = RoundedCornerShape(999.dp),
                                color = EmeraldPrimary,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Icon(Icons.Outlined.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                                    Text("Add", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                            }
                        }
                    }
                }

                // Live Summary Card
                item {
                    CleanCard(padding = 14.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text("ESTIMATED TOTAL", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MutedSlate, letterSpacing = 0.6.sp)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = if (masked) "••••" else MoneyFormatter.formatMinor(summary.estimatedTotalMinor, baseCurrency),
                                    style = TabularStyle,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SlateInk,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                StatusPill(
                                    text = "${summary.checkedItems}/${summary.totalItems} Completed",
                                    containerColor = EmeraldSoft,
                                    contentColor = EmeraldDark,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "${summary.uncheckedItems} remaining",
                                    style = TabularStyle,
                                    fontSize = 11.sp,
                                    color = MutedSlate,
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(999.dp)),
                            color = EmeraldPrimary,
                            trackColor = SlateTint,
                        )
                        if (summary.checkedTotalMinor > 0L && summary.remainingTotalMinor > 0L) {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "Done: ${if (masked) "••••" else MoneyFormatter.formatMinor(summary.checkedTotalMinor, baseCurrency)}",
                                    style = TabularStyle,
                                    fontSize = 11.sp,
                                    color = EmeraldDark,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "Remaining: ${if (masked) "••••" else MoneyFormatter.formatMinor(summary.remainingTotalMinor, baseCurrency)}",
                                    style = TabularStyle,
                                    fontSize = 11.sp,
                                    color = MutedSlate,
                                )
                            }
                        }
                    }
                }

                // Empty State or Structured Item Lists
                if (listItems.isEmpty()) {
                    item {
                        CleanCard(padding = 20.dp) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("No items in this checklist", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "Add items with quantity, unit (kg, litre, pack, custom), and estimated total price.",
                                    fontSize = 12.sp,
                                    color = MutedSlate,
                                )
                                Spacer(Modifier.height(12.dp))
                                Button(
                                    onClick = { showAddItemSheet = true },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                                ) {
                                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Add First Item", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                } else {
                    if (remainingItems.isNotEmpty()) {
                        item {
                            Text(
                                text = "TO BUY (${remainingItems.size})",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.6.sp,
                                color = MutedSlate,
                            )
                        }
                        items(remainingItems, key = { it.id }) { item ->
                            ShoppingItemRow(
                                item = item,
                                baseCurrency = baseCurrency,
                                masked = masked,
                                onToggle = { vm.toggleShoppingItem(item.id) },
                                onEdit = { editingItem = item },
                                onDelete = { vm.deleteShoppingItem(item.id) },
                            )
                        }
                    }

                    if (checkedItems.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "COMPLETED (${checkedItems.size})",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.6.sp,
                                    color = MutedSlate,
                                )
                                TextButton(onClick = { checkedExpanded = !checkedExpanded }) {
                                    Text(if (checkedExpanded) "Hide" else "Show", fontSize = 11.sp, color = MutedSlate)
                                    Icon(
                                        imageVector = if (checkedExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                        contentDescription = null,
                                        tint = MutedSlate,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                        if (checkedExpanded) {
                            items(checkedItems, key = { it.id }) { item ->
                                ShoppingItemRow(
                                    item = item,
                                    baseCurrency = baseCurrency,
                                    masked = masked,
                                    onToggle = { vm.toggleShoppingItem(item.id) },
                                    onEdit = { editingItem = item },
                                    onDelete = { vm.deleteShoppingItem(item.id) },
                                )
                            }
                        }
                    }
                }
            }

            // Bottom Action Bar (Convert to Expense + Preserve Checklist)
            if (listItems.isNotEmpty()) {
                Surface(
                    color = SurfaceCard,
                    border = BorderStroke(1.dp, BorderSubtle),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val hasChecked = checkedItems.isNotEmpty()
                        Button(
                            onClick = {
                                convertScopeCheckedOnly = hasChecked
                                showConvertSheet = true
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.ReceiptLong, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(8.dp))
                            val estToLog = if (hasChecked) summary.checkedTotalMinor else summary.estimatedTotalMinor
                            val label = if (hasChecked) {
                                if (estToLog > 0L) {
                                    "Convert ${checkedItems.size} Completed (${if (masked) "••••" else MoneyFormatter.formatMinor(estToLog, baseCurrency)})"
                                } else {
                                    "Convert ${checkedItems.size} Completed to Expense"
                                }
                            } else {
                                "Convert Entire List (${listItems.size} items)"
                            }
                            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        if (hasChecked && remainingItems.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    convertScopeCheckedOnly = false
                                    showConvertSheet = true
                                },
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                            ) {
                                Text(
                                    text = "Or convert entire list (${listItems.size} items • ${if (masked) "••••" else MoneyFormatter.formatMinor(summary.estimatedTotalMinor, baseCurrency)})",
                                    fontSize = 11.sp,
                                    color = EmeraldDark,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Delete List Confirmation
    listPendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { listPendingDelete = null },
            containerColor = SurfaceCard,
            title = { Text("Delete \"${target.title}\"?", color = SlateInk) },
            text = {
                Text(
                    "This removes the checklist and its items from Shopping Lists. Logged expenses in Records are kept safely.",
                    color = MutedSlate,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.deleteShoppingList(target.id)
                        if (activeListId == target.id) activeListId = null
                        listPendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoseOutflow),
                ) {
                    Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { listPendingDelete = null }) { Text("Cancel") }
            },
        )
    }

    // Clear Entire Checklist Confirmation
    if (showClearChecklistConfirm && activeList != null) {
        AlertDialog(
            onDismissRequest = { showClearChecklistConfirm = false },
            containerColor = SurfaceCard,
            title = { Text("Clear all items in \"${activeList.title}\"?", color = SlateInk) },
            text = {
                Text(
                    "Removes all items from this checklist. The checklist container stays ready to reuse.",
                    color = MutedSlate,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.clearAllShoppingItems(activeList.id)
                        showClearChecklistConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoseOutflow),
                ) {
                    Text("Clear All", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearChecklistConfirm = false }) { Text("Cancel") }
            },
        )
    }

    if (showNewListSheet) {
        NewShoppingListBottomSheet(
            categories = categories,
            onDismiss = { showNewListSheet = false },
            onSave = { title, catId ->
                vm.createShoppingList(title, catId) { newId ->
                    activeListId = newId
                    showNewListSheet = false
                }
            },
        )
    }

    if (showAddItemSheet && activeList != null) {
        ShoppingItemEditorBottomSheet(
            editingItem = null,
            baseCurrency = baseCurrency,
            onDismiss = { showAddItemSheet = false },
            onSave = { name, qtyVal, qtyUnit, priceMinor, keepOpen, onComplete ->
                vm.addShoppingItem(
                    listId = activeList.id,
                    name = name,
                    estimatedPriceMinor = priceMinor,
                    quantityValue = qtyVal,
                    quantityUnit = qtyUnit,
                    onAdded = { ok ->
                        onComplete(ok)
                        if (ok && !keepOpen) {
                            showAddItemSheet = false
                        }
                    },
                )
            },
        )
    }

    editingItem?.let { targetItem ->
        ShoppingItemEditorBottomSheet(
            editingItem = targetItem,
            baseCurrency = baseCurrency,
            onDismiss = { editingItem = null },
            onSave = { name, qtyVal, qtyUnit, priceMinor, _, onComplete ->
                vm.updateShoppingItem(
                    itemId = targetItem.id,
                    name = name,
                    quantityValue = qtyVal,
                    quantityUnit = qtyUnit,
                    estimatedPriceMinor = priceMinor,
                    onUpdated = { ok ->
                        onComplete(ok)
                        if (ok) {
                            editingItem = null
                        }
                    },
                )
            },
        )
    }

    if (showConvertSheet && activeList != null) {
        val currentListItems = allItems.filter { it.listId == activeList.id }
        val itemsToConvert = if (convertScopeCheckedOnly) {
            currentListItems.filter { it.checked }.ifEmpty { currentListItems }
        } else {
            currentListItems
        }
        ConvertShoppingExpenseBottomSheet(
            list = activeList,
            itemsToConvert = itemsToConvert,
            accounts = accounts,
            categories = categories,
            baseCurrency = baseCurrency,
            onDismiss = { showConvertSheet = false },
            onConfirm = { accId, catId, totalMinor, merchant, allowDup, onDupBlocked ->
                vm.convertCheckedShoppingToExpense(
                    listId = activeList.id,
                    itemIds = itemsToConvert.map { it.id },
                    accountId = accId,
                    categoryId = catId,
                    totalMinor = totalMinor,
                    currency = baseCurrency,
                    merchantTitle = merchant,
                    removeConvertedItems = false,
                    allowDuplicate = allowDup,
                    onDuplicateBlocked = onDupBlocked,
                    onSuccess = { showConvertSheet = false },
                )
            },
        )
    }
}

@Composable
private fun ShoppingItemRow(
    item: ShoppingItemEntity,
    baseCurrency: String,
    masked: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val qtyText = remember(item.quantityValue, item.quantityUnit, item.quantityLabel) {
        ShoppingUnitCatalog.formatQuantityLabel(item.quantityValue, item.quantityUnit, item.quantityLabel)
    }
    val hasPrice = item.estimatedPriceMinor > 0L

    CleanCard(
        padding = 10.dp,
        containerColor = if (item.checked) SlateTint.copy(alpha = 0.65f) else SurfaceCard,
        onClick = onToggle,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(
                checked = item.checked,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = EmeraldPrimary,
                    uncheckedColor = MutedSlate,
                ),
                modifier = Modifier.size(26.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    fontSize = 14.sp,
                    fontWeight = if (item.checked) FontWeight.Normal else FontWeight.SemiBold,
                    color = if (item.checked) MutedSlate else SlateInk,
                    textDecoration = if (item.checked) TextDecoration.LineThrough else TextDecoration.None,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (qtyText.isNotEmpty() || hasPrice) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (qtyText.isNotEmpty()) {
                            StatusPill(
                                text = qtyText,
                                containerColor = if (item.checked) SurfaceCard else SlateTint,
                                contentColor = if (item.checked) MutedSlate else SlateInk,
                            )
                        }
                        if (hasPrice) {
                            val priceFormatted = if (masked) "••••" else MoneyFormatter.formatMinor(item.estimatedPriceMinor, baseCurrency)
                            StatusPill(
                                text = priceFormatted,
                                containerColor = if (item.checked) SurfaceCard else EmeraldSoft,
                                contentColor = if (item.checked) MutedSlate else EmeraldDark,
                            )
                        }
                    }
                }
            }
            IconButton(
                onClick = onEdit,
                modifier = Modifier.size(30.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = "Edit ${item.name}",
                    tint = MutedSlate,
                    modifier = Modifier.size(16.dp),
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(30.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = "Remove ${item.name}",
                    tint = RoseOutflow.copy(alpha = 0.8f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewShoppingListBottomSheet(
    categories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onSave: (title: String, categoryId: String?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val expenseCats = remember(categories) { categories.filter { it.kind == "EXPENSE" } }
    var title by remember { mutableStateOf("") }
    var selectedCategoryId by remember(expenseCats) {
        mutableStateOf(
            expenseCats.firstOrNull { it.name.contains("Groceries", ignoreCase = true) }?.id
                ?: expenseCats.firstOrNull()?.id
        )
    }
    var showCatPicker by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceCard,
        contentColor = SlateInk,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("New Shopping List", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = SlateInk)
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("List Name") },
                placeholder = { Text("e.g. Weekly Groceries, Home Hardware") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
            HierarchicalCategorySelectorField(
                label = "Default Expense Category",
                selectedCategoryId = selectedCategoryId,
                allCategories = expenseCats,
                nullOptionLabel = "None selected",
                onClick = { showCatPicker = true },
            )
            Button(
                enabled = title.isNotBlank(),
                onClick = { onSave(title, selectedCategoryId) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
            ) {
                Text("Create Checklist", fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }

    if (showCatPicker) {
        HierarchicalCategoryPickerSheet(
            title = "Default Checklist Category",
            categories = expenseCats,
            kindFilter = "EXPENSE",
            selectedIds = listOfNotNull(selectedCategoryId).toSet(),
            multiSelect = false,
            nullOptionLabel = "No default category",
            nullSelected = selectedCategoryId == null,
            onSelectNull = { selectedCategoryId = null },
            onSelectSingle = { selectedCategoryId = it.id },
            onDismiss = { showCatPicker = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ShoppingItemEditorBottomSheet(
    editingItem: ShoppingItemEntity?,
    baseCurrency: String,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        qtyVal: Double?,
        qtyUnit: String,
        priceMinor: Long,
        keepOpen: Boolean,
        onComplete: (Boolean) -> Unit,
    ) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val initialParsed = remember(editingItem) {
        if (editingItem == null) null
        else if (editingItem.quantityValue != null || editingItem.quantityUnit.isNotBlank()) {
            Triple(
                editingItem.quantityValue?.let { ShoppingUnitCatalog.formatNumber(it) }.orEmpty(),
                editingItem.quantityUnit,
                MoneyFormatter.formatEditableDecimal(editingItem.estimatedPriceMinor, baseCurrency),
            )
        } else {
            val legacy = ShoppingUnitCatalog.parseLegacyLabel(editingItem.quantityLabel)
            Triple(
                legacy.quantityValue?.let { ShoppingUnitCatalog.formatNumber(it) }.orEmpty(),
                legacy.quantityUnit,
                MoneyFormatter.formatEditableDecimal(editingItem.estimatedPriceMinor, baseCurrency),
            )
        }
    }

    var name by remember(editingItem) { mutableStateOf(editingItem?.name.orEmpty()) }
    var qtyText by remember(editingItem) { mutableStateOf(initialParsed?.first.orEmpty()) }
    val initUnit = initialParsed?.second.orEmpty()
    val isInitCustom = initUnit.isNotBlank() && initUnit !in ShoppingUnitCatalog.allPredefinedUnits
    var selectedUnit by remember(editingItem) {
        mutableStateOf(if (isInitCustom) ShoppingUnitCatalog.UNIT_CUSTOM_SENTINEL else initUnit)
    }
    var customUnitText by remember(editingItem) {
        mutableStateOf(if (isInitCustom) initUnit else "")
    }
    var priceText by remember(editingItem) {
        val seed = initialParsed?.third.orEmpty()
        mutableStateOf(if (editingItem != null && editingItem.estimatedPriceMinor > 0L) seed else "")
    }
    var showUnitSheet by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    val (parsedQty, qtyError) = remember(qtyText) { ShoppingUnitCatalog.validateQuantityInput(qtyText) }
    val resolvedUnit = remember(selectedUnit, customUnitText) {
        when (selectedUnit) {
            ShoppingUnitCatalog.UNIT_CUSTOM_SENTINEL -> customUnitText.trim()
            else -> selectedUnit.trim()
        }
    }
    val parsedPriceMinor = remember(priceText, baseCurrency) {
        if (priceText.isBlank()) 0L
        else (MoneyFormatter.parseDecimalToMinor(priceText, baseCurrency) ?: -1L)
    }
    val priceError = if (priceText.isNotBlank() && parsedPriceMinor < 0L) "Enter a valid price" else null
    val canSubmit = !isSaving && name.isNotBlank() && qtyError == null && priceError == null

    fun submit(keepOpen: Boolean) {
        if (!canSubmit) return
        isSaving = true
        onSave(
            name.trim(),
            parsedQty,
            resolvedUnit,
            parsedPriceMinor.coerceAtLeast(0L),
            keepOpen,
        ) { ok ->
            isSaving = false
            if (ok && keepOpen) {
                name = ""
                qtyText = ""
                priceText = ""
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceCard,
        contentColor = SlateInk,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (editingItem == null) "Add Shopping Item" else "Edit Item",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = SlateInk,
            )

            // A. Item Name
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Item Name") },
                placeholder = { Text("Milk, Bread, Rice...") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            // B. Quantity + Unit Selector
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Quantity & Unit", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MutedSlate)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    OutlinedTextField(
                        value = qtyText,
                        onValueChange = { raw ->
                            val filtered = buildString {
                                var seenDot = false
                                raw.forEach { ch ->
                                    if (ch.isDigit()) append(ch)
                                    else if (ch == '.' && !seenDot) {
                                        seenDot = true
                                        append(ch)
                                    }
                                }
                            }
                            qtyText = filtered
                        },
                        label = { Text("Qty") },
                        placeholder = { Text("1") },
                        isError = qtyError != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(0.95f),
                    )
                    Surface(
                        onClick = { showUnitSheet = true },
                        modifier = Modifier
                            .weight(1.25f)
                            .height(56.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = SlateTint,
                        border = BorderStroke(1.dp, BorderSubtle),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text("Unit", fontSize = 10.sp, color = MutedSlate)
                                Text(
                                    text = when {
                                        selectedUnit == ShoppingUnitCatalog.UNIT_CUSTOM_SENTINEL ->
                                            customUnitText.ifBlank { "Custom unit" }
                                        selectedUnit.isNotBlank() -> selectedUnit
                                        else -> "None"
                                    },
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = SlateInk,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(Icons.Outlined.ExpandMore, contentDescription = "Choose unit", tint = MutedSlate, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                if (qtyError != null) {
                    Text(qtyError, fontSize = 11.sp, color = RoseOutflow)
                }

                // Quick 1-tap unit pills
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf("kg", "g", "litre", "ml", "piece", "packet", "pack", "dozen", ShoppingUnitCatalog.UNIT_CUSTOM_SENTINEL).forEach { unitOption ->
                        val active = selectedUnit == unitOption
                        FilterChip(
                            selected = active,
                            onClick = {
                                selectedUnit = if (active && unitOption != ShoppingUnitCatalog.UNIT_CUSTOM_SENTINEL) "" else unitOption
                            },
                            label = {
                                Text(
                                    text = if (unitOption == ShoppingUnitCatalog.UNIT_CUSTOM_SENTINEL) "Custom" else unitOption,
                                    fontSize = 11.sp,
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = EmeraldSoft,
                                selectedLabelColor = EmeraldDark,
                            ),
                        )
                    }
                }

                AnimatedVisibility(visible = selectedUnit == ShoppingUnitCatalog.UNIT_CUSTOM_SENTINEL) {
                    OutlinedTextField(
                        value = customUnitText,
                        onValueChange = { customUnitText = it },
                        label = { Text("Custom Unit") },
                        placeholder = { Text("e.g. jar, bunch, tray, tin") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // C. Estimated Total Price (Optional)
            OutlinedTextField(
                value = priceText,
                onValueChange = { priceText = MoneyFormatter.sanitizeDecimalInput(it, baseCurrency, allowNegative = false) },
                label = { Text("Estimated Total Price ($baseCurrency, Optional)") },
                placeholder = { Text(if (baseCurrency == "JPY") "0" else "0.00") },
                supportingText = {
                    Text(
                        text = priceError ?: "Total price for the entered quantity (not multiplied automatically)",
                        fontSize = 11.sp,
                        color = if (priceError != null) RoseOutflow else MutedSlate,
                    )
                },
                isError = priceError != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit(keepOpen = editingItem == null) }),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (editingItem == null) {
                    OutlinedButton(
                        enabled = canSubmit,
                        onClick = { submit(keepOpen = true) },
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, EmeraldPrimary.copy(alpha = 0.45f)),
                    ) {
                        Text("Add & Next", color = EmeraldDark, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Button(
                    enabled = canSubmit,
                    onClick = { submit(keepOpen = false) },
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                ) {
                    Text(
                        text = if (editingItem == null) "Add Item" else "Save Item",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }

    if (showUnitSheet) {
        ModalBottomSheet(
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            onDismissRequest = { showUnitSheet = false },
            containerColor = SurfaceCard,
            contentColor = SlateInk,
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Select Unit", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                    TextButton(
                        onClick = {
                            selectedUnit = ""
                            customUnitText = ""
                            showUnitSheet = false
                        },
                    ) {
                        Text("No Unit", fontSize = 12.sp, color = MutedSlate)
                    }
                }
                ShoppingUnitCatalog.unitGroups.forEach { group ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = group.categoryName.uppercase(Locale.US),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            color = MutedSlate,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            group.units.forEach { u ->
                                val active = selectedUnit == u
                                FilterChip(
                                    selected = active,
                                    onClick = {
                                        selectedUnit = u
                                        showUnitSheet = false
                                    },
                                    label = {
                                        Text(
                                            text = if (u == ShoppingUnitCatalog.UNIT_CUSTOM_SENTINEL) "Custom..." else u,
                                            fontSize = 12.sp,
                                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = EmeraldSoft,
                                        selectedLabelColor = EmeraldDark,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConvertShoppingExpenseBottomSheet(
    list: ShoppingListEntity,
    itemsToConvert: List<ShoppingItemEntity>,
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    baseCurrency: String,
    onDismiss: () -> Unit,
    onConfirm: (
        accountId: String,
        categoryId: String?,
        totalMinor: Long,
        merchant: String,
        allowDuplicate: Boolean,
        onDuplicateBlocked: () -> Unit,
    ) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val expenseCats = remember(categories) { categories.filter { it.kind == "EXPENSE" } }
    val estimatedSum = remember(itemsToConvert) { itemsToConvert.sumOf { it.estimatedPriceMinor.coerceAtLeast(0L) } }

    var merchant by remember(list) { mutableStateOf(list.title) }
    var totalText by remember(estimatedSum) {
        mutableStateOf(
            if (estimatedSum > 0L) MoneyFormatter.formatEditableDecimal(estimatedSum, baseCurrency) else ""
        )
    }
    var selectedAccountId by remember(accounts) { mutableStateOf(accounts.firstOrNull()?.id) }
    var selectedCategoryId by remember(list, expenseCats) {
        mutableStateOf(list.defaultCategoryId ?: expenseCats.firstOrNull()?.id)
    }
    var showCatPicker by remember { mutableStateOf(false) }
    var duplicateDetected by remember { mutableStateOf(false) }

    val parsedTotal = MoneyFormatter.parseDecimalToMinor(totalText, baseCurrency) ?: 0L

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceCard,
        contentColor = SlateInk,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(EmeraldSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = EmeraldPrimary)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Convert Basket to Expense", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                    Text(
                        text = "${itemsToConvert.size} item(s) • Original checklist will stay saved",
                        fontSize = 12.sp,
                        color = MutedSlate,
                    )
                }
            }

            // Itemized Review Box
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SlateTint,
                border = BorderStroke(1.dp, BorderSubtle),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 150.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsToConvert.forEach { item ->
                        val qLabel = ShoppingUnitCatalog.formatQuantityLabel(item.quantityValue, item.quantityUnit, item.quantityLabel)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (qLabel.isNotBlank()) "${item.name} ($qLabel)" else item.name,
                                fontSize = 12.sp,
                                color = SlateInk,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (item.estimatedPriceMinor > 0L) {
                                    MoneyFormatter.formatMinor(item.estimatedPriceMinor, baseCurrency)
                                } else "—",
                                style = TabularStyle,
                                fontSize = 12.sp,
                                color = MutedSlate,
                            )
                        }
                    }
                    if (estimatedSum > 0L) {
                        HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 2.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Estimated Sum", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                            Text(
                                text = MoneyFormatter.formatMinor(estimatedSum, baseCurrency),
                                style = TabularStyle,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = EmeraldDark,
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = merchant,
                onValueChange = { merchant = it },
                label = { Text("Merchant / Expense Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            OutlinedTextField(
                value = totalText,
                onValueChange = {
                    totalText = MoneyFormatter.sanitizeDecimalInput(it, baseCurrency, allowNegative = false)
                    duplicateDetected = false
                },
                label = { Text("Actual Paid Amount ($baseCurrency)") },
                placeholder = { Text(if (baseCurrency == "JPY") "0" else "0.00") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = EmeraldSoft,
                                selectedLabelColor = EmeraldDark,
                            ),
                        )
                    }
                }
            }

            HierarchicalCategorySelectorField(
                label = "Expense Category",
                selectedCategoryId = selectedCategoryId,
                allCategories = expenseCats,
                nullOptionLabel = "Select expense category",
                onClick = { showCatPicker = true },
            )

            if (duplicateDetected) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = AmberSoft,
                    border = BorderStroke(1.dp, AmberWarn.copy(alpha = 0.45f)),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "This exact checklist conversion is already saved in Records.",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AmberWarn,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onDismiss) { Text("Close", color = SlateInk) }
                            Button(
                                onClick = {
                                    selectedAccountId?.let { accId ->
                                        onConfirm(accId, selectedCategoryId, parsedTotal, merchant, true) {}
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AmberWarn),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Text("Log Again Anyway", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
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
                            onConfirm(accId, selectedCategoryId, parsedTotal, merchant, false) {
                                duplicateDetected = true
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Save Expense", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }

    if (showCatPicker) {
        HierarchicalCategoryPickerSheet(
            title = "Expense Category",
            categories = expenseCats,
            kindFilter = "EXPENSE",
            selectedIds = listOfNotNull(selectedCategoryId).toSet(),
            multiSelect = false,
            onSelectSingle = { selectedCategoryId = it.id },
            onDismiss = { showCatPicker = false },
        )
    }
}
