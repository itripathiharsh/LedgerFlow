package com.ledgerflow.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import com.ledgerflow.app.ui.theme.SlateInk
import com.ledgerflow.app.ui.theme.SlateTint
import com.ledgerflow.app.ui.theme.SurfaceCard

private val categoryColorChoices = listOf(
    "#059669",
    "#10B981",
    "#0284C7",
    "#D97706",
    "#E11D48",
    "#14B8A6",
    "#6366F1",
    "#EC4899",
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
    "spa" to "Care",
    "loan" to "Debt",
    "gift" to "Gifts",
    "payments" to "Income",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CategoryEditorBottomSheet(
    editingCategory: CategoryEntity? = null,
    initialKind: String = "EXPENSE",
    initialParentId: String? = null,
    allCategories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onSave: (name: String, kind: String, parentId: String?, iconKey: String, colorHex: String) -> Unit,
    onDelete: ((CategoryEntity) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var kind by remember(editingCategory, initialKind) {
        mutableStateOf(editingCategory?.kind ?: initialKind)
    }
    var name by remember(editingCategory) {
        mutableStateOf(editingCategory?.name.orEmpty())
    }
    var parentId by remember(editingCategory, initialParentId) {
        mutableStateOf(editingCategory?.parentCategoryId ?: initialParentId)
    }
    val initialParent = remember(parentId, allCategories) {
        parentId?.let { id -> allCategories.firstOrNull { it.id == id } }
    }
    var iconKey by remember(editingCategory, initialParentId) {
        mutableStateOf(
            editingCategory?.iconKey
                ?: initialParent?.iconKey
                ?: if ((editingCategory?.kind ?: initialKind) == "INCOME") "payments" else "category"
        )
    }
    var colorHex by remember(editingCategory, initialParentId) {
        mutableStateOf(
            editingCategory?.colorHex
                ?: initialParent?.colorHex
                ?: "#059669"
        )
    }
    var parentQuery by remember { mutableStateOf("") }

    val matchingParents = remember(allCategories, kind, editingCategory, parentQuery) {
        allCategories
            .filter {
                it.deletedAtEpochMs == null &&
                    it.parentCategoryId == null &&
                    it.kind == kind &&
                    it.id != editingCategory?.id
            }
            .filter { parentQuery.isBlank() || it.name.contains(parentQuery.trim(), ignoreCase = true) }
            .sortedBy { it.name }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceCard,
        contentColor = SlateInk,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = when {
                    editingCategory != null -> "Edit ${if (parentId != null) "Subcategory" else "Category"}"
                    parentId != null -> "New Subcategory"
                    else -> "New Category"
                },
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = SlateInk,
            )

            // Expense / Income Segmented Toggle
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SlateTint,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(modifier = Modifier.padding(4.dp)) {
                    listOf("EXPENSE" to "Expense", "INCOME" to "Income").forEach { (k, label) ->
                        val active = kind == k
                        Surface(
                            onClick = {
                                if (kind != k) {
                                    kind = k
                                    parentId = null
                                    parentQuery = ""
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(9.dp),
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
                                    color = if (active) EmeraldDark else MutedSlate,
                                )
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(if (parentId != null) "Subcategory Name" else "Category Name") },
                placeholder = { Text(if (parentId != null) "e.g. Fastag & Tolls" else "e.g. Pets & Vet") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // Icon Picker Row
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Icon", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MutedSlate)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    categoryIconChoices.forEach { (key, lbl) ->
                        val selected = iconKey == key
                        Surface(
                            onClick = { iconKey = key },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) EmeraldSoft else SlateTint,
                            border = BorderStroke(1.dp, if (selected) EmeraldPrimary else BorderSubtle),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Icon(
                                    imageVector = categoryIcon(key),
                                    contentDescription = lbl,
                                    tint = if (selected) EmeraldDark else MutedSlate,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = lbl,
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (selected) EmeraldDark else SlateInk,
                                )
                            }
                        }
                    }
                }
            }

            // Accent Color Picker Row
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Accent Color", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MutedSlate)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    categoryColorChoices.forEach { hex ->
                        val selected = colorHex.equals(hex, ignoreCase = true)
                        val c = runCatching { Color(hex.toColorInt()) }.getOrDefault(EmeraldPrimary)
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(c)
                                .then(
                                    if (selected) Modifier.border(2.5.dp, SlateInk, CircleShape)
                                    else Modifier
                                )
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

            // Parent Category Selector (wrapped & searchable)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Parent Category", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MutedSlate)
                OutlinedTextField(
                    value = parentQuery,
                    onValueChange = { parentQuery = it },
                    placeholder = { Text("Filter parent categories...", fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(16.dp), tint = MutedSlate) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val topActive = parentId == null
                    Surface(
                        onClick = { parentId = null },
                        shape = RoundedCornerShape(999.dp),
                        color = if (topActive) EmeraldSoft else SlateTint,
                        border = BorderStroke(1.dp, if (topActive) EmeraldPrimary else BorderSubtle),
                    ) {
                        Text(
                            text = "Top-Level",
                            fontSize = 12.sp,
                            fontWeight = if (topActive) FontWeight.Bold else FontWeight.Medium,
                            color = if (topActive) EmeraldDark else SlateInk,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                    matchingParents.forEach { p ->
                        val active = parentId == p.id
                        Surface(
                            onClick = {
                                parentId = p.id
                                if (editingCategory == null) {
                                    iconKey = p.iconKey
                                    colorHex = p.colorHex
                                }
                            },
                            shape = RoundedCornerShape(999.dp),
                            color = if (active) EmeraldSoft else SlateTint,
                            border = BorderStroke(1.dp, if (active) EmeraldPrimary else BorderSubtle),
                        ) {
                            Text(
                                text = p.name,
                                fontSize = 12.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                color = if (active) EmeraldDark else SlateInk,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Button(
                enabled = name.isNotBlank(),
                onClick = { onSave(name, kind, parentId, iconKey, colorHex) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
            ) {
                Text("Save", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }

            if (editingCategory != null && onDelete != null && !editingCategory.name.equals("General", ignoreCase = true)) {
                OutlinedButton(
                    onClick = { onDelete(editingCategory) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, RoseOutflow.copy(alpha = 0.45f)),
                ) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = RoseOutflow, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Delete", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = RoseOutflow)
                }
            }
        }
    }
}
