package com.ledgerflow.app.ui.components

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import com.ledgerflow.app.data.local.CategoryEntity
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

private val categoryColorChoices = listOf(
    "#059669",
    "#0284C7",
    "#D97706",
    "#E11D48",
    "#6366F1",
    "#EC4899",
    "#14B8A6",
    "#8B5CF6",
)

private val categoryIconChoices = listOf(
    "category" to "General",
    "cafe" to "Food",
    "shopping_cart" to "Grocery",
    "fuel" to "Fuel",
    "directions_transit" to "Transit",
    "home" to "Bills",
    "shopping_bag" to "Shop",
    "movie" to "Fun",
    "medical_services" to "Health",
    "school" to "Edu",
    "flight" to "Travel",
    "payments" to "Income",
)

@Composable
fun CreateCategoryDialog(
    initialKind: String,
    parentCategories: List<CategoryEntity>,
    initialParentId: String? = null,
    onDismiss: () -> Unit,
    onCreate: (name: String, kind: String, parentId: String?, iconKey: String, colorHex: String) -> Unit,
) {
    var kind by remember { mutableStateOf(initialKind) }
    var name by remember { mutableStateOf("") }
    var parentId by remember { mutableStateOf(initialParentId) }
    var iconKey by remember { mutableStateOf(if (kind == "INCOME") "payments" else "category") }
    var colorHex by remember { mutableStateOf("#059669") }
    val matchingParents = remember(parentCategories, kind) {
        parentCategories.filter { it.parentCategoryId == null && it.kind == kind }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceCard,
        title = { Text(if (parentId != null) "Add Subcategory" else "New Category / Subcategory", color = SlateInk) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = kind == "EXPENSE",
                        onClick = {
                            kind = "EXPENSE"
                            if (parentId != null && matchingParents.none { it.id == parentId }) parentId = null
                        },
                        label = { Text("Expense", fontSize = 11.sp) },
                    )
                    FilterChip(
                        selected = kind == "INCOME",
                        onClick = {
                            kind = "INCOME"
                            parentId = null
                        },
                        label = { Text("Income", fontSize = 11.sp) },
                    )
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(if (parentId != null) "Subcategory Name" else "Category Name") },
                    placeholder = { Text(if (parentId != null) "e.g. Highway Tolls" else "e.g. Pets / Freelance") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Parent Category (optional for subcategory)", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MutedSlate)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = parentId == null,
                        onClick = { parentId = null },
                        label = { Text("Top-Level", fontSize = 11.sp) },
                    )
                    matchingParents.forEach { p ->
                        FilterChip(
                            selected = parentId == p.id,
                            onClick = {
                                parentId = p.id
                                iconKey = p.iconKey
                                colorHex = p.colorHex
                            },
                            label = { Text(p.name, fontSize = 11.sp) },
                        )
                    }
                }

                if (parentId == null) {
                    Text("Icon & Color", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MutedSlate)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        categoryIconChoices.forEach { (key, lbl) ->
                            FilterChip(
                                selected = iconKey == key,
                                onClick = { iconKey = key },
                                label = { Text(lbl, fontSize = 11.sp) },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        categoryColorChoices.forEach { hex ->
                            val selected = colorHex == hex
                            val c = runCatching { Color(hex.toColorInt()) }.getOrDefault(EmeraldPrimary)
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(c)
                                    .clickable { colorHex = hex },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (selected) {
                                    Icon(Icons.Outlined.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank(),
                onClick = { onCreate(name, kind, parentId, iconKey, colorHex) },
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ManageCategoriesDialog(
    categories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onCreateCategory: (name: String, kind: String, parentId: String?, iconKey: String, colorHex: String) -> Unit,
    onDeleteCategories: (Set<String>) -> Unit,
) {
    var kindFilter by remember { mutableStateOf("EXPENSE") }
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var createForParentId by remember { mutableStateOf<String?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

    val parents = remember(categories, kindFilter) {
        categories.filter { it.parentCategoryId == null && it.kind == kindFilter }.sortedBy { it.name }
    }
    val childrenByParent = remember(categories) {
        categories.filter { it.parentCategoryId != null }.groupBy { it.parentCategoryId!! }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceCard,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Categories & Subcategories", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                TextButton(
                    onClick = {
                        multiSelectMode = !multiSelectMode
                        if (!multiSelectMode) selectedIds = emptySet()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(if (multiSelectMode) "Done" else "Multi-Select", fontSize = 12.sp, color = EmeraldPrimary)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = kindFilter == "EXPENSE", onClick = { kindFilter = "EXPENSE" }, label = { Text("Expense", fontSize = 11.sp) })
                        FilterChip(selected = kindFilter == "INCOME", onClick = { kindFilter = "INCOME" }, label = { Text("Income", fontSize = 11.sp) })
                    }
                    OutlinedButton(
                        onClick = {
                            createForParentId = null
                            showCreateDialog = true
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp),
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(14.dp), tint = EmeraldPrimary)
                        Spacer(Modifier.size(4.dp))
                        Text("New", fontSize = 11.sp, color = EmeraldPrimary)
                    }
                }

                if (multiSelectMode && selectedIds.isNotEmpty()) {
                    Button(
                        onClick = {
                            onDeleteCategories(selectedIds)
                            selectedIds = emptySet()
                            multiSelectMode = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RoseOutflow),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Remove ${selectedIds.size} Selected", fontSize = 12.sp)
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(parents.size, key = { parents[it].id }) { idx ->
                        val parent = parents[idx]
                        val children = childrenByParent[parent.id].orEmpty().sortedBy { it.name }
                        val parentChecked = parent.id in selectedIds
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = SlateTint,
                            border = BorderStroke(1.dp, if (parentChecked) RoseOutflow else BorderSubtle),
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (multiSelectMode) {
                                                selectedIds = if (parentChecked) selectedIds - parent.id else selectedIds + parent.id
                                            }
                                        },
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        CategoryIconBadge(parent.iconKey, parent.colorHex, size = 30.dp)
                                        Text(parent.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = SlateInk)
                                    }
                                    if (multiSelectMode) {
                                        StatusPill(
                                            text = if (parentChecked) "Selected" else "Select",
                                            containerColor = if (parentChecked) RoseSoft else SurfaceCard,
                                            contentColor = if (parentChecked) RoseOutflow else MutedSlate,
                                        )
                                    } else {
                                        TextButton(
                                            onClick = {
                                                createForParentId = parent.id
                                                showCreateDialog = true
                                            },
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                            modifier = Modifier.height(26.dp),
                                        ) {
                                            Text("+ Sub", fontSize = 11.sp, color = EmeraldPrimary)
                                        }
                                    }
                                }

                                if (children.isNotEmpty()) {
                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        children.forEach { sub ->
                                            val subChecked = sub.id in selectedIds
                                            Surface(
                                                onClick = {
                                                    if (multiSelectMode) {
                                                        selectedIds = if (subChecked) selectedIds - sub.id else selectedIds + sub.id
                                                    }
                                                },
                                                shape = RoundedCornerShape(999.dp),
                                                color = if (subChecked) RoseSoft else EmeraldSoft,
                                                border = BorderStroke(1.dp, if (subChecked) RoseOutflow else BorderSubtle),
                                            ) {
                                                Text(
                                                    text = sub.name,
                                                    fontSize = 11.sp,
                                                    color = if (subChecked) RoseOutflow else EmeraldDark,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
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
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )

    if (showCreateDialog) {
        CreateCategoryDialog(
            initialKind = kindFilter,
            parentCategories = categories,
            initialParentId = createForParentId,
            onDismiss = { showCreateDialog = false },
            onCreate = { name, kind, parentId, iconKey, colorHex ->
                onCreateCategory(name, kind, parentId, iconKey, colorHex)
                showCreateDialog = false
            },
        )
    }
}
