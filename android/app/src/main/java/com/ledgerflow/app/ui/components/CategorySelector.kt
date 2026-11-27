package com.ledgerflow.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgerflow.app.data.local.CategoryEntity
import com.ledgerflow.app.domain.CategoryTree
import com.ledgerflow.app.domain.CategoryTreeNode
import com.ledgerflow.app.ui.theme.BorderSubtle
import com.ledgerflow.app.ui.theme.EmeraldDark
import com.ledgerflow.app.ui.theme.EmeraldPrimary
import com.ledgerflow.app.ui.theme.EmeraldSoft
import com.ledgerflow.app.ui.theme.MutedSlate
import com.ledgerflow.app.ui.theme.SlateInk
import com.ledgerflow.app.ui.theme.SlateTint
import com.ledgerflow.app.ui.theme.SurfaceCard

private data class PickerRowItem(
    val node: CategoryTreeNode,
    val hasChildren: Boolean,
    val isExpanded: Boolean,
    val breadcrumb: String,
)

@Composable
fun HierarchicalCategorySelectorField(
    label: String,
    selectedCategoryId: String?,
    allCategories: List<CategoryEntity>,
    nullOptionLabel: String = "Select category",
    placeholder: String = "Choose category",
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val catMap = remember(allCategories) { allCategories.associateBy { it.id } }
    val selected = selectedCategoryId?.let { catMap[it] }
    val breadcrumb = remember(selectedCategoryId, catMap) {
        selectedCategoryId?.let { CategoryTree.breadcrumbPath(it, catMap) }.orEmpty()
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (label.isNotBlank()) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MutedSlate,
            )
        }
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = SlateTint,
            border = BorderStroke(1.dp, BorderSubtle),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (selected != null) {
                    CategoryIconBadge(
                        iconKey = selected.iconKey,
                        tintHex = selected.colorHex,
                        size = 30.dp,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = selected.name,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SlateInk,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (breadcrumb.contains("›")) {
                            Text(
                                text = breadcrumb,
                                fontSize = 11.sp,
                                color = MutedSlate,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(EmeraldSoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FolderOpen,
                            contentDescription = null,
                            tint = EmeraldPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        text = if (selectedCategoryId == null) nullOptionLabel else placeholder,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = SlateInk,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = "Choose category",
                    tint = MutedSlate,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HierarchicalCategoryPickerSheet(
    title: String = "Select Category",
    categories: List<CategoryEntity>,
    kindFilter: String? = null, // null = show both EXPENSE & INCOME
    selectedIds: Set<String> = emptySet(),
    excludedIds: Set<String> = emptySet(),
    multiSelect: Boolean = false,
    nullOptionLabel: String? = null,
    nullSelected: Boolean = false,
    onSelectNull: (() -> Unit)? = null,
    onSelectSingle: ((CategoryEntity) -> Unit)? = null,
    onConfirmMulti: ((List<String>) -> Unit)? = null,
    onQuickCreate: ((parentCategoryId: String?, resolvedKind: String) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }
    var workingMultiIds by remember(selectedIds) { mutableStateOf(selectedIds.toList()) }
    var activeKindTab by remember(kindFilter) {
        mutableStateOf(kindFilter ?: "ALL")
    }
    var expandedIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    val activeCats = remember(categories, excludedIds) {
        categories.filter { it.deletedAtEpochMs == null && it.id !in excludedIds }
    }
    val catMap = remember(activeCats) { activeCats.associateBy { it.id } }

    val effectiveKind = when {
        kindFilter != null -> kindFilter
        activeKindTab == "ALL" -> null
        else -> activeKindTab
    }

    val filteredRoots = remember(activeCats, effectiveKind, searchQuery) {
        val tree = CategoryTree.buildTree(activeCats, kindFilter = effectiveKind)
        CategoryTree.filterTree(tree, searchQuery)
    }

    val autoExpandAll = searchQuery.isNotBlank()
    val visibleRows = remember(filteredRoots, expandedIds, autoExpandAll, catMap) {
        val rows = mutableListOf<PickerRowItem>()
        fun append(nodes: List<CategoryTreeNode>) {
            nodes.forEach { node ->
                val expanded = autoExpandAll || node.category.id in expandedIds
                rows += PickerRowItem(
                    node = node,
                    hasChildren = node.children.isNotEmpty(),
                    isExpanded = expanded,
                    breadcrumb = CategoryTree.breadcrumbPath(node.category.id, catMap),
                )
                if (expanded && node.children.isNotEmpty()) {
                    append(node.children)
                }
            }
        }
        append(filteredRoots)
        rows
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
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateInk,
                    )
                    if (multiSelect) {
                        Text(
                            text = "${workingMultiIds.size} selected",
                            fontSize = 11.sp,
                            color = EmeraldPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (onQuickCreate != null) {
                        Surface(
                            onClick = {
                                val createKind = effectiveKind ?: "EXPENSE"
                                onQuickCreate(null, createKind)
                            },
                            shape = RoundedCornerShape(999.dp),
                            color = EmeraldSoft,
                            border = BorderStroke(1.dp, EmeraldPrimary.copy(alpha = 0.35f)),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(Icons.Outlined.Add, contentDescription = null, tint = EmeraldDark, modifier = Modifier.size(14.dp))
                                Text("New", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = EmeraldDark)
                            }
                        }
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close", tint = MutedSlate, modifier = Modifier.size(18.dp))
                    }
                }
            }

            if (kindFilter == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ALL" to "All", "EXPENSE" to "Expense", "INCOME" to "Income").forEach { (key, label) ->
                        val active = activeKindTab == key
                        Surface(
                            onClick = { activeKindTab = key },
                            shape = RoundedCornerShape(999.dp),
                            color = if (active) EmeraldSoft else SlateTint,
                            border = BorderStroke(1.dp, if (active) EmeraldPrimary else BorderSubtle),
                        ) {
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                color = if (active) EmeraldDark else MutedSlate,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search categories or subcategories...", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = MutedSlate, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Clear", tint = MutedSlate, modifier = Modifier.size(16.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 2.dp),
            ) {
                if (nullOptionLabel != null && onSelectNull != null) {
                    item(key = "__NULL_OPTION__") {
                        Surface(
                            onClick = {
                                onSelectNull()
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = if (nullSelected) EmeraldSoft else SlateTint,
                            border = BorderStroke(1.dp, if (nullSelected) EmeraldPrimary else BorderSubtle),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.FolderOpen,
                                    contentDescription = null,
                                    tint = if (nullSelected) EmeraldDark else MutedSlate,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = nullOptionLabel,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (nullSelected) EmeraldDark else SlateInk,
                                    modifier = Modifier.weight(1f),
                                )
                                if (nullSelected) {
                                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = EmeraldPrimary, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }

                if (visibleRows.isEmpty()) {
                    item(key = "__EMPTY__") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "No matching categories found",
                                fontSize = 13.sp,
                                color = MutedSlate,
                            )
                        }
                    }
                } else {
                    items(visibleRows, key = { it.node.category.id }) { row ->
                        val cat = row.node.category
                        val isSelected = if (multiSelect) cat.id in workingMultiIds else cat.id in selectedIds
                        val indentDp = (row.node.depth.coerceAtMost(5) * 18).dp

                        Surface(
                            onClick = {
                                if (multiSelect) {
                                    workingMultiIds = if (cat.id in workingMultiIds) {
                                        workingMultiIds - cat.id
                                    } else {
                                        workingMultiIds + cat.id
                                    }
                                } else {
                                    onSelectSingle?.invoke(cat)
                                    onDismiss()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = indentDp),
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) EmeraldSoft else SlateTint,
                            border = BorderStroke(1.dp, if (isSelected) EmeraldPrimary else BorderSubtle),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (row.hasChildren) {
                                    Box(
                                        modifier = Modifier
                                            .size(26.dp)
                                            .clip(CircleShape)
                                            .background(SurfaceCard.copy(alpha = 0.7f))
                                            .clickable {
                                                expandedIds = if (cat.id in expandedIds) {
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
                                            tint = MutedSlate,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                } else {
                                    Spacer(Modifier.width(6.dp))
                                }

                                CategoryIconBadge(
                                    iconKey = cat.iconKey,
                                    tintHex = cat.colorHex,
                                    size = 30.dp,
                                )

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            text = cat.name,
                                            fontSize = 13.sp,
                                            fontWeight = if (row.node.depth == 0) FontWeight.SemiBold else FontWeight.Medium,
                                            color = if (isSelected) EmeraldDark else SlateInk,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        if (row.node.children.isNotEmpty()) {
                                            StatusPill(
                                                text = "${row.node.children.size}",
                                                containerColor = SurfaceCard,
                                                contentColor = MutedSlate,
                                            )
                                        }
                                    }
                                    if (searchQuery.isNotBlank() && row.breadcrumb.contains("›")) {
                                        Text(
                                            text = row.breadcrumb,
                                            fontSize = 10.sp,
                                            color = MutedSlate,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    } else if (cat.description.isNotBlank()) {
                                        Text(
                                            text = cat.description,
                                            fontSize = 10.sp,
                                            color = MutedSlate,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }

                                if (onQuickCreate != null) {
                                    IconButton(
                                        onClick = { onQuickCreate(cat.id, cat.kind) },
                                        modifier = Modifier.size(26.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Add,
                                            contentDescription = "Add subcategory",
                                            tint = EmeraldPrimary,
                                            modifier = Modifier.size(15.dp),
                                        )
                                    }
                                }

                                if (isSelected) {
                                    Icon(
                                        imageVector = if (multiSelect) Icons.Outlined.Check else Icons.Outlined.CheckCircle,
                                        contentDescription = "Selected",
                                        tint = EmeraldPrimary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (multiSelect && onConfirmMulti != null) {
                HorizontalDivider(color = BorderSubtle)
                Button(
                    onClick = {
                        onConfirmMulti(workingMultiIds)
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                ) {
                    Text(
                        text = if (workingMultiIds.isEmpty()) "Done" else "Apply (${workingMultiIds.size})",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            }
        }
    }
}
