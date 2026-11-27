package com.ledgerflow.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import com.ledgerflow.app.data.local.CategoryEntity
import com.ledgerflow.app.ui.LedgerViewModel
import com.ledgerflow.app.ui.components.CategoryEditorBottomSheet
import com.ledgerflow.app.ui.components.CategoryIconBadge
import com.ledgerflow.app.ui.components.CleanCard
import com.ledgerflow.app.ui.components.EmptyPlaceholder
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoriesScreen(
    vm: LedgerViewModel,
    openedFromSettings: Boolean = false,
    onNavigateBackOrDrawer: () -> Unit,
) {
    val categories by vm.categories.collectAsState()

    var kindFilter by remember { mutableStateOf("EXPENSE") }
    var searchQuery by remember { mutableStateOf("") }
    var selectMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var collapsedParentIds by remember { mutableStateOf(emptySet<String>()) }

    var showEditorSheet by remember { mutableStateOf(false) }
    var editingCategory by remember { mutableStateOf<CategoryEntity?>(null) }
    var editorInitialParentId by remember { mutableStateOf<String?>(null) }

    val childrenByParent = remember(categories) {
        categories.filter { it.parentCategoryId != null }.groupBy { it.parentCategoryId!! }
    }

    val displayedParents = remember(categories, kindFilter, searchQuery, childrenByParent) {
        val q = searchQuery.trim()
        categories
            .filter { it.parentCategoryId == null && it.kind == kindFilter }
            .filter { parent ->
                if (q.isBlank()) {
                    true
                } else {
                    parent.name.contains(q, ignoreCase = true) ||
                        childrenByParent[parent.id].orEmpty().any { it.name.contains(q, ignoreCase = true) }
                }
            }
            .sortedBy { it.name }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AlabasterBg),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 92.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Header: Drawer or Back + "Categories" + Select icon + "+ Category" button
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
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    IconButton(
                        onClick = {
                            selectMode = !selectMode
                            if (!selectMode) selectedIds = emptySet()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Checklist,
                            contentDescription = "Select",
                            tint = if (selectMode) EmeraldPrimary else SlateInk,
                        )
                    }
                    Surface(
                        onClick = {
                            editingCategory = null
                            editorInitialParentId = null
                            showEditorSheet = true
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
                            Text("Category", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // Full-width Segmented Toggle (Expense | Income) + Search Bar
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = SlateTint,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(modifier = Modifier.padding(4.dp)) {
                        listOf("EXPENSE" to "Expense", "INCOME" to "Income").forEach { (k, label) ->
                            val active = kindFilter == k
                            Surface(
                                onClick = { kindFilter = k },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                color = if (active) SurfaceCard else Color.Transparent,
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 13.sp,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                        color = if (active) SlateInk else MutedSlate,
                                    )
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search categories or subcategories...", fontSize = 13.sp, color = MutedSlate) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = MutedSlate) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Outlined.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Bulk-edit bar when Select mode is active
        if (selectMode) {
            item {
                CleanCard(
                    padding = 12.dp,
                    borderColor = if (selectedIds.isNotEmpty()) RoseOutflow.copy(alpha = 0.45f) else BorderSubtle,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (selectedIds.isEmpty()) "Tap categories or subcategories to select" else "${selectedIds.size} selected",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = SlateInk,
                            modifier = Modifier.weight(1f),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    selectMode = false
                                    selectedIds = emptySet()
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text("Cancel", fontSize = 12.sp, color = MutedSlate)
                            }
                            if (selectedIds.isNotEmpty()) {
                                Button(
                                    onClick = {
                                        vm.deleteCategories(selectedIds)
                                        selectedIds = emptySet()
                                        selectMode = false
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = RoseOutflow),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                ) {
                                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Delete", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (displayedParents.isEmpty()) {
            item {
                EmptyPlaceholder(
                    title = "No matching categories",
                    subtitle = if (searchQuery.isNotBlank()) "Try a different search keyword." else "Tap + Category above to create one.",
                )
            }
        } else {
            items(displayedParents, key = { it.id }) { parent ->
                val allChildren = childrenByParent[parent.id].orEmpty().sortedBy { it.name }
                val q = searchQuery.trim()
                val visibleChildren = if (q.isNotBlank() && !parent.name.contains(q, ignoreCase = true)) {
                    allChildren.filter { it.name.contains(q, ignoreCase = true) }
                } else {
                    allChildren
                }
                val isExpanded = parent.id !in collapsedParentIds || q.isNotBlank()
                val parentChecked = parent.id in selectedIds

                CleanCard(
                    padding = 14.dp,
                    borderColor = if (parentChecked) RoseOutflow else BorderSubtle,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (selectMode) {
                                    selectedIds = if (parentChecked) selectedIds - parent.id else selectedIds + parent.id
                                } else {
                                    editingCategory = parent
                                    editorInitialParentId = null
                                    showEditorSheet = true
                                }
                            },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            CategoryIconBadge(parent.iconKey, parent.colorHex, size = 40.dp)
                            Column {
                                Text(parent.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                                val subCount = allChildren.size
                                val countLabel = when (subCount) {
                                    0 -> "No subcategories"
                                    1 -> "1 subcategory"
                                    else -> "$subCount subcategories"
                                }
                                Text(countLabel, fontSize = 12.sp, color = MutedSlate)
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (selectMode) {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(if (parentChecked) RoseOutflow else SlateTint),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (parentChecked) {
                                        Icon(Icons.Outlined.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                    }
                                }
                            } else {
                                Surface(
                                    onClick = {
                                        editingCategory = null
                                        editorInitialParentId = parent.id
                                        showEditorSheet = true
                                    },
                                    shape = RoundedCornerShape(999.dp),
                                    color = EmeraldSoft,
                                    border = BorderStroke(1.dp, EmeraldPrimary.copy(alpha = 0.35f)),
                                ) {
                                    Text(
                                        text = "+ Sub",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = EmeraldDark,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    )
                                }
                                if (allChildren.isNotEmpty()) {
                                    IconButton(
                                        onClick = {
                                            collapsedParentIds = if (isExpanded) {
                                                collapsedParentIds + parent.id
                                            } else {
                                                collapsedParentIds - parent.id
                                            }
                                        },
                                        modifier = Modifier.size(28.dp),
                                    ) {
                                        Icon(
                                            imageVector = if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                                            tint = MutedSlate,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    AnimatedVisibility(visible = isExpanded && visibleChildren.isNotEmpty()) {
                        Column {
                            Spacer(Modifier.height(12.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                visibleChildren.forEach { sub ->
                                    val subChecked = sub.id in selectedIds
                                    val subColor = runCatching { Color(sub.colorHex.toColorInt()) }.getOrDefault(EmeraldPrimary)
                                    Surface(
                                        onClick = {
                                            if (selectMode) {
                                                selectedIds = if (subChecked) selectedIds - sub.id else selectedIds + sub.id
                                            } else {
                                                editingCategory = sub
                                                editorInitialParentId = sub.parentCategoryId
                                                showEditorSheet = true
                                            }
                                        },
                                        shape = RoundedCornerShape(999.dp),
                                        color = if (subChecked) RoseSoft else SlateTint,
                                        border = BorderStroke(1.dp, if (subChecked) RoseOutflow else BorderSubtle),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(7.dp)
                                                    .clip(CircleShape)
                                                    .background(if (subChecked) RoseOutflow else subColor),
                                            )
                                            Text(
                                                text = sub.name,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = if (subChecked) RoseOutflow else SlateInk,
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
    }

    if (showEditorSheet) {
        CategoryEditorBottomSheet(
            editingCategory = editingCategory,
            initialKind = editingCategory?.kind ?: kindFilter,
            initialParentId = editorInitialParentId,
            allCategories = categories,
            onDismiss = { showEditorSheet = false },
            onSave = { name, kind, parentId, iconKey, colorHex ->
                val target = editingCategory
                if (target != null) {
                    vm.updateCategory(target.id, name, kind, parentId, iconKey, colorHex)
                } else {
                    vm.addCategory(name, kind, parentId, iconKey, colorHex)
                }
                showEditorSheet = false
            },
            onDelete = { toDelete ->
                vm.deleteCategories(setOf(toDelete.id))
                showEditorSheet = false
            },
        )
    }
}
