package com.ledgerflow.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import com.ledgerflow.app.data.local.CategoryEntity
import com.ledgerflow.app.data.local.LedgerDatabase
import com.ledgerflow.app.domain.CategoryTree
import com.ledgerflow.app.domain.CategoryTreeNode
import com.ledgerflow.app.ui.LedgerViewModel
import com.ledgerflow.app.ui.components.CategoryDeleteSafeguardSheet
import com.ledgerflow.app.ui.components.CategoryEditorBottomSheet
import com.ledgerflow.app.ui.components.CategoryIconBadge
import com.ledgerflow.app.ui.components.CleanCard
import com.ledgerflow.app.ui.components.EmptyPlaceholder
import com.ledgerflow.app.ui.components.StatusPill
import com.ledgerflow.app.ui.theme.AlabasterBg
import com.ledgerflow.app.ui.theme.BorderSubtle
import com.ledgerflow.app.ui.theme.EmeraldDark
import com.ledgerflow.app.ui.theme.EmeraldPrimary
import com.ledgerflow.app.ui.theme.EmeraldSoft
import com.ledgerflow.app.ui.theme.MutedSlate
import com.ledgerflow.app.ui.theme.RoseOutflow
import com.ledgerflow.app.ui.theme.RoseSoft
import com.ledgerflow.app.ui.theme.SlateInk
import com.ledgerflow.app.ui.theme.SlateTint
import com.ledgerflow.app.ui.theme.SurfaceCard
import kotlinx.coroutines.launch

private data class VisibleCategoryRow(
    val node: CategoryTreeNode,
    val hasChildren: Boolean,
    val isExpanded: Boolean,
)

@Composable
fun CategoriesScreen(
    vm: LedgerViewModel,
    openedFromSettings: Boolean = false,
    onNavigateBackOrDrawer: () -> Unit,
) {
    val categories by vm.categories.collectAsState()
    val scope = rememberCoroutineScope()

    var kindFilter by remember { mutableStateOf("EXPENSE") }
    var searchQuery by remember { mutableStateOf("") }
    var selectMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var expandedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }

    var showEditor by remember { mutableStateOf(false) }
    var editingCategory by remember { mutableStateOf<CategoryEntity?>(null) }
    var editorInitialParentId by remember { mutableStateOf<String?>(null) }
    var editorInitialKind by remember { mutableStateOf("EXPENSE") }
    var pendingDeleteImpact by remember { mutableStateOf<LedgerDatabase.CategoryDeletionImpact?>(null) }

    val fullKindTree = remember(categories, kindFilter) {
        CategoryTree.buildTree(categories, kindFilter = kindFilter)
    }
    val filteredTree = remember(fullKindTree, searchQuery) {
        CategoryTree.filterTree(fullKindTree, searchQuery)
    }

    val autoExpandOnSearch = searchQuery.isNotBlank()
    val visibleRows = remember(filteredTree, expandedIds, autoExpandOnSearch) {
        val out = mutableListOf<VisibleCategoryRow>()
        fun visit(nodes: List<CategoryTreeNode>) {
            nodes.forEach { node ->
                val expanded = autoExpandOnSearch || node.category.id in expandedIds
                out += VisibleCategoryRow(
                    node = node,
                    hasChildren = node.children.isNotEmpty(),
                    isExpanded = expanded,
                )
                if (expanded && node.children.isNotEmpty()) {
                    visit(node.children)
                }
            }
        }
        visit(filteredTree)
        out
    }

    val allExpandableIds = remember(fullKindTree) {
        val ids = HashSet<String>()
        fun collect(nodes: List<CategoryTreeNode>) {
            nodes.forEach { n ->
                if (n.children.isNotEmpty()) {
                    ids.add(n.category.id)
                    collect(n.children)
                }
            }
        }
        collect(fullKindTree)
        ids
    }
    val anyExpanded = allExpandableIds.any { it in expandedIds }

    val expenseCount = remember(categories) {
        categories.count { it.deletedAtEpochMs == null && it.kind.equals("EXPENSE", ignoreCase = true) }
    }
    val incomeCount = remember(categories) {
        categories.count { it.deletedAtEpochMs == null && it.kind.equals("INCOME", ignoreCase = true) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AlabasterBg),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 92.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
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
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(onClick = onNavigateBackOrDrawer) {
                        Icon(
                            imageVector = if (openedFromSettings) Icons.AutoMirrored.Outlined.ArrowBack else Icons.Outlined.Menu,
                            contentDescription = if (openedFromSettings) "Back" else "Open menu",
                            tint = SlateInk,
                        )
                    }
                    Text("Categories", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (allExpandableIds.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                expandedIds = if (anyExpanded) emptySet() else allExpandableIds
                            },
                        ) {
                            Icon(
                                imageVector = if (anyExpanded) Icons.Outlined.UnfoldLess else Icons.Outlined.UnfoldMore,
                                contentDescription = if (anyExpanded) "Collapse all" else "Expand all",
                                tint = SlateInk,
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            selectMode = !selectMode
                            if (!selectMode) selectedIds = emptySet()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Checklist,
                            contentDescription = "Batch select",
                            tint = if (selectMode) EmeraldPrimary else SlateInk,
                        )
                    }
                    Surface(
                        onClick = {
                            editingCategory = null
                            editorInitialParentId = null
                            editorInitialKind = kindFilter
                            showEditor = true
                        },
                        shape = RoundedCornerShape(999.dp),
                        color = EmeraldPrimary,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Text("New", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Batch Delete Bar
        if (selectMode) {
            item {
                CleanCard(padding = 12.dp, containerColor = SlateTint) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (selectedIds.isEmpty()) "Tap categories to select for removal" else "${selectedIds.size} selected (includes nested subcategories)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = SlateInk,
                            modifier = Modifier.weight(1f),
                        )
                        if (selectedIds.isNotEmpty()) {
                            Surface(
                                onClick = { showBatchDeleteConfirm = true },
                                shape = RoundedCornerShape(10.dp),
                                color = RoseSoft,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = RoseOutflow, modifier = Modifier.size(15.dp))
                                    Text("Delete", color = RoseOutflow, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Expense vs Income Tree Toggle
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    Triple("EXPENSE", "Expense Tree", expenseCount),
                    Triple("INCOME", "Income Tree", incomeCount),
                ).forEach { (kind, label, count) ->
                    val active = kindFilter == kind
                    Surface(
                        onClick = {
                            kindFilter = kind
                            selectedIds = emptySet()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        color = if (active) EmeraldSoft else SurfaceCard,
                        border = BorderStroke(1.dp, if (active) EmeraldPrimary else BorderSubtle),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = label,
                                fontSize = 13.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                color = if (active) EmeraldDark else MutedSlate,
                            )
                            Spacer(Modifier.width(6.dp))
                            StatusPill(
                                text = "$count",
                                containerColor = if (active) EmeraldPrimary.copy(alpha = 0.16f) else SlateTint,
                                contentColor = if (active) EmeraldDark else MutedSlate,
                            )
                        }
                    }
                }
            }
        }

        // Multi-Level Search Bar
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search categories & nested subcategories...", fontSize = 13.sp, color = MutedSlate) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = MutedSlate, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Outlined.Clear, contentDescription = "Clear search", tint = MutedSlate, modifier = Modifier.size(18.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (visibleRows.isEmpty()) {
            item {
                EmptyPlaceholder(
                    title = if (searchQuery.isBlank()) "No $kindFilter categories" else "No matching categories",
                    subtitle = if (searchQuery.isBlank()) {
                        "Tap + New to create a top-level category or nested subcategory."
                    } else {
                        "No categories or subcategories match \"$searchQuery\"."
                    },
                )
            }
        } else {
            items(visibleRows, key = { it.node.category.id }) { row ->
                val cat = row.node.category
                val depth = row.node.depth
                val indentDp = (depth.coerceAtMost(5) * 16).dp
                val isSelected = cat.id in selectedIds
                val isProtected = cat.name.equals("General", ignoreCase = true) && cat.parentCategoryId == null
                val accentColor = runCatching { Color(cat.colorHex.toColorInt()) }.getOrDefault(EmeraldPrimary)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = indentDp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (depth > 0) {
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(40.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(accentColor.copy(alpha = 0.45f)),
                        )
                        Spacer(Modifier.width(8.dp))
                    }

                    CleanCard(
                        padding = if (depth == 0) 12.dp else 10.dp,
                        containerColor = when {
                            isSelected -> EmeraldSoft
                            depth == 0 -> SurfaceCard
                            else -> SlateTint.copy(alpha = 0.72f)
                        },
                        borderColor = if (isSelected) EmeraldPrimary else BorderSubtle,
                        onClick = {
                            if (selectMode && !isProtected) {
                                selectedIds = if (isSelected) selectedIds - cat.id else selectedIds + cat.id
                            } else if (!selectMode) {
                                editingCategory = cat
                                editorInitialParentId = cat.parentCategoryId
                                editorInitialKind = cat.kind
                                showEditor = true
                            }
                        },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (row.hasChildren) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(SlateTint)
                                        .clickable {
                                            expandedIds = if (row.isExpanded) {
                                                expandedIds - cat.id
                                            } else {
                                                expandedIds + cat.id
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = if (row.isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                        contentDescription = if (row.isExpanded) "Collapse" else "Expand",
                                        tint = SlateInk,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            } else {
                                Spacer(Modifier.width(4.dp))
                            }

                            if (selectMode && !isProtected) {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(if (isSelected) EmeraldPrimary else SlateTint),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isSelected) {
                                        Icon(Icons.Outlined.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                                    }
                                }
                            }

                            CategoryIconBadge(
                                iconKey = cat.iconKey,
                                tintHex = cat.colorHex,
                                size = if (depth == 0) 36.dp else 30.dp,
                            )

                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = cat.name,
                                        fontSize = if (depth == 0) 15.sp else 14.sp,
                                        fontWeight = if (depth == 0) FontWeight.Bold else FontWeight.SemiBold,
                                        color = SlateInk,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    if (row.node.totalDescendantCount > 0) {
                                        StatusPill(
                                            text = "${row.node.totalDescendantCount}",
                                            containerColor = SurfaceCard,
                                            contentColor = MutedSlate,
                                        )
                                    }
                                }
                                val subline = when {
                                    cat.description.isNotBlank() -> cat.description
                                    depth > 0 && row.node.children.isNotEmpty() -> if (row.node.children.size == 1) "1 nested subcategory" else "${row.node.children.size} nested subcategories"
                                    depth == 0 && row.node.children.isNotEmpty() -> if (row.node.children.size == 1) "1 subcategory" else "${row.node.children.size} subcategories"
                                    else -> "Level ${depth + 1}"
                                }
                                Text(
                                    text = subline,
                                    fontSize = 11.sp,
                                    color = MutedSlate,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            if (!selectMode) {
                                IconButton(
                                    onClick = {
                                        editingCategory = null
                                        editorInitialParentId = cat.id
                                        editorInitialKind = cat.kind
                                        showEditor = true
                                    },
                                    modifier = Modifier.size(30.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Add,
                                        contentDescription = "Add child under ${cat.name}",
                                        tint = EmeraldPrimary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        editingCategory = cat
                                        editorInitialParentId = cat.parentCategoryId
                                        editorInitialKind = cat.kind
                                        showEditor = true
                                    },
                                    modifier = Modifier.size(30.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Edit,
                                        contentDescription = "Edit ${cat.name}",
                                        tint = MutedSlate,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditor) {
        CategoryEditorBottomSheet(
            editingCategory = editingCategory,
            initialKind = editorInitialKind,
            initialParentId = editorInitialParentId,
            allCategories = categories,
            onDismiss = {
                showEditor = false
                editingCategory = null
                editorInitialParentId = null
            },
            onSave = { name, kind, parentId, iconKey, colorHex, description ->
                val target = editingCategory
                if (target == null) {
                    vm.addCategory(
                        name = name,
                        kind = kind,
                        parentCategoryId = parentId,
                        iconKey = iconKey,
                        colorHex = colorHex,
                        description = description,
                        onResult = { ok, _ ->
                            if (ok) {
                                if (parentId != null) {
                                    expandedIds = expandedIds + parentId
                                }
                                showEditor = false
                                editingCategory = null
                                editorInitialParentId = null
                            }
                        },
                    )
                } else {
                    vm.updateCategory(
                        categoryId = target.id,
                        name = name,
                        kind = kind,
                        parentCategoryId = parentId,
                        iconKey = iconKey,
                        colorHex = colorHex,
                        description = description,
                        onResult = { ok, _ ->
                            if (ok) {
                                showEditor = false
                                editingCategory = null
                                editorInitialParentId = null
                            }
                        },
                    )
                }
            },
            onDelete = { toDelete ->
                showEditor = false
                editingCategory = null
                scope.launch {
                    pendingDeleteImpact = vm.inspectCategoryDeletionImpact(toDelete.id)
                }
            },
        )
    }

    pendingDeleteImpact?.let { impact ->
        CategoryDeleteSafeguardSheet(
            impact = impact,
            allCategories = categories,
            onDismiss = { pendingDeleteImpact = null },
            onConfirmDelete = { childStrategy, moveChildrenTargetId, usageStrategy, reassignTargetId ->
                vm.deleteCategoryWithSafeguards(
                    categoryId = impact.category.id,
                    childStrategy = childStrategy,
                    moveChildrenTargetParentId = moveChildrenTargetId,
                    usageStrategy = usageStrategy,
                    reassignCategoryId = reassignTargetId,
                    onComplete = { pendingDeleteImpact = null },
                )
            },
        )
    }

    if (showBatchDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            containerColor = SurfaceCard,
            title = { Text("Delete ${selectedIds.size} selected categories?", color = SlateInk) },
            text = {
                Text(
                    "Any nested subcategories under selected items will also be removed. Past transactions will keep their historical category labels.",
                    color = MutedSlate,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.deleteCategories(selectedIds)
                        selectedIds = emptySet()
                        selectMode = false
                        showBatchDeleteConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoseOutflow),
                ) {
                    Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }
}
