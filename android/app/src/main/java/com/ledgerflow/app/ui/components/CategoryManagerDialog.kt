package com.ledgerflow.app.ui.components

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import com.ledgerflow.app.data.local.LedgerDatabase
import com.ledgerflow.app.domain.CategoryTree
import com.ledgerflow.app.ui.theme.AmberSoft
import com.ledgerflow.app.ui.theme.AmberWarn
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
    "restaurant" to "Dining",
    "cafe" to "Cafe",
    "shopping_cart" to "Grocery",
    "fuel" to "Fuel",
    "directions_car" to "Car",
    "directions_transit" to "Transit",
    "home" to "Bills",
    "utilities" to "Utility",
    "build" to "Repair",
    "shopping_bag" to "Shop",
    "movie" to "Fun",
    "medical_services" to "Health",
    "security" to "Cover",
    "school" to "Edu",
    "menu_book" to "Books",
    "flight" to "Travel",
    "hotel" to "Stay",
    "fitness_center" to "Fitness",
    "spa" to "Care",
    "loan" to "Debt",
    "gift" to "Gifts",
    "savings" to "Save",
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
    onSave: (
        name: String,
        kind: String,
        parentId: String?,
        iconKey: String,
        colorHex: String,
        description: String,
    ) -> Unit,
    onDelete: ((CategoryEntity) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val catMap = remember(allCategories) { allCategories.associateBy { it.id } }

    val seedParent = remember(editingCategory, initialParentId, catMap) {
        (editingCategory?.parentCategoryId ?: initialParentId)?.let { catMap[it] }
    }

    var name by remember(editingCategory) { mutableStateOf(editingCategory?.name.orEmpty()) }
    var parentId by remember(editingCategory, initialParentId) {
        mutableStateOf(editingCategory?.parentCategoryId ?: initialParentId)
    }
    var kind by remember(editingCategory, initialKind, seedParent) {
        mutableStateOf(
            editingCategory?.kind
                ?: seedParent?.kind
                ?: if (initialKind.equals("INCOME", ignoreCase = true)) "INCOME" else "EXPENSE"
        )
    }
    var iconKey by remember(editingCategory, seedParent) {
        mutableStateOf(editingCategory?.iconKey ?: seedParent?.iconKey ?: "category")
    }
    var colorHex by remember(editingCategory, seedParent) {
        mutableStateOf(editingCategory?.colorHex ?: seedParent?.colorHex ?: "#059669")
    }
    var description by remember(editingCategory) {
        mutableStateOf(editingCategory?.description.orEmpty())
    }
    var showAdvanced by remember(editingCategory) { mutableStateOf(false) }
    var showParentPicker by remember { mutableStateOf(false) }

    val selectedParent = parentId?.let { catMap[it] }
    val effectiveKind = selectedParent?.kind ?: kind

    val excludedParentIds = remember(editingCategory, allCategories) {
        if (editingCategory == null) emptySet()
        else CategoryTree.collectDescendantIds(editingCategory.id, allCategories, includeSelf = true)
    }

    val cleanName = name.trim()
    val isDuplicateName = remember(cleanName, effectiveKind, parentId, editingCategory, allCategories) {
        CategoryTree.hasDuplicateSibling(
            name = cleanName,
            kind = effectiveKind,
            parentId = parentId,
            excludeCategoryId = editingCategory?.id,
            categories = allCategories,
        )
    }
    val canSave = cleanName.isNotEmpty() && !isDuplicateName

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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CategoryIconBadge(iconKey = iconKey, tintHex = colorHex, size = 40.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (editingCategory == null) "New Category" else "Edit Category",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateInk,
                    )
                    val parentTrail = selectedParent?.let {
                        CategoryTree.breadcrumbPath(it.id, catMap)
                    }
                    Text(
                        text = parentTrail?.let { "$effectiveKind • inside $it" }
                            ?: "$effectiveKind • Top-Level Category",
                        fontSize = 12.sp,
                        color = MutedSlate,
                    )
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Category Name") },
                placeholder = { Text("e.g. Tuition Fees, Coffee, Transit") },
                singleLine = true,
                isError = isDuplicateName,
                supportingText = if (isDuplicateName) {
                    { Text("\"$cleanName\" already exists at this level", color = RoseOutflow, fontSize = 11.sp) }
                } else null,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            HierarchicalCategorySelectorField(
                label = "Parent Category",
                selectedCategoryId = parentId,
                allCategories = allCategories,
                nullOptionLabel = "Top-Level Category",
                onClick = { showParentPicker = true },
            )

            if (parentId == null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Tree", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MutedSlate)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("EXPENSE" to "Expense", "INCOME" to "Income").forEach { (k, label) ->
                            val selected = kind == k
                            FilterChip(
                                selected = selected,
                                onClick = { kind = k },
                                label = { Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = EmeraldSoft,
                                    selectedLabelColor = EmeraldDark,
                                    labelColor = SlateInk,
                                ),
                            )
                        }
                    }
                }
            }

            // Collapsed-by-default Advanced Customization
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SlateTint,
                border = BorderStroke(1.dp, BorderSubtle),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAdvanced = !showAdvanced }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Outlined.Tune, contentDescription = null, tint = EmeraldPrimary, modifier = Modifier.size(16.dp))
                            Column {
                                Text("Advanced Customization", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = SlateInk)
                                Text("Icon, accent color & note (optional)", fontSize = 11.sp, color = MutedSlate)
                            }
                        }
                        Icon(
                            imageVector = if (showAdvanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = if (showAdvanced) "Collapse" else "Expand",
                            tint = MutedSlate,
                        )
                    }

                    AnimatedVisibility(visible = showAdvanced) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            HorizontalDivider(color = BorderSubtle)

                            OutlinedTextField(
                                value = description,
                                onValueChange = { description = it },
                                label = { Text("Description (Optional)") },
                                placeholder = { Text("Short note about what belongs here") },
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth(),
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Icon", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MutedSlate)
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    categoryIconChoices.forEach { (key, label) ->
                                        val selected = iconKey == key
                                        Surface(
                                            onClick = { iconKey = key },
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (selected) EmeraldSoft else SurfaceCard,
                                            border = BorderStroke(1.dp, if (selected) EmeraldPrimary else BorderSubtle),
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                            ) {
                                                Icon(
                                                    imageVector = categoryIcon(key),
                                                    contentDescription = label,
                                                    tint = if (selected) EmeraldDark else SlateInk,
                                                    modifier = Modifier.size(14.dp),
                                                )
                                                Text(
                                                    text = label,
                                                    fontSize = 11.sp,
                                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                    color = if (selected) EmeraldDark else SlateInk,
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Accent Color", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MutedSlate)
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    categoryColorChoices.forEach { hex ->
                                        val selected = colorHex.equals(hex, ignoreCase = true)
                                        val swatch = Color(hex.toColorInt())
                                        Box(
                                            modifier = Modifier
                                                .size(30.dp)
                                                .clip(CircleShape)
                                                .background(swatch)
                                                .then(
                                                    if (selected) Modifier.border(2.5.dp, SlateInk, CircleShape)
                                                    else Modifier
                                                )
                                                .clickable { colorHex = hex },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            if (selected) {
                                                Icon(Icons.Outlined.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(2.dp))
            Button(
                enabled = canSave,
                onClick = {
                    onSave(cleanName, effectiveKind, parentId, iconKey, colorHex, description.trim())
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
            ) {
                Text(
                    text = if (editingCategory == null) "Create Category" else "Save Changes",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
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
                    Text("Delete Category", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = RoseOutflow)
                }
            }
        }
    }

    if (showParentPicker) {
        HierarchicalCategoryPickerSheet(
            title = "Choose Parent Category",
            categories = allCategories,
            kindFilter = if (editingCategory == null) null else effectiveKind,
            selectedIds = listOfNotNull(parentId).toSet(),
            excludedIds = excludedParentIds,
            multiSelect = false,
            nullOptionLabel = "Top-Level Category (Root)",
            nullSelected = parentId == null,
            onSelectNull = { parentId = null },
            onSelectSingle = { chosenParent ->
                parentId = chosenParent.id
                kind = chosenParent.kind
                if (editingCategory == null) {
                    iconKey = chosenParent.iconKey
                    colorHex = chosenParent.colorHex
                }
            },
            onDismiss = { showParentPicker = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryDeleteSafeguardSheet(
    impact: LedgerDatabase.CategoryDeletionImpact,
    allCategories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onConfirmDelete: (
        childStrategy: LedgerDatabase.ChildDeleteStrategy,
        moveChildrenTargetParentId: String?,
        usageStrategy: LedgerDatabase.UsageDeleteStrategy,
        reassignCategoryId: String?,
    ) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val cat = impact.category
    val activeAll = remember(allCategories) { allCategories.filter { it.deletedAtEpochMs == null } }
    val excludedSubtree = remember(cat, activeAll) {
        CategoryTree.collectDescendantIds(cat.id, activeAll, includeSelf = true)
    }
    val fallbackReplacement = remember(cat, activeAll, excludedSubtree) {
        val sameKind = activeAll.filter { it.id !in excludedSubtree && it.kind.equals(cat.kind, ignoreCase = true) }
        sameKind.firstOrNull { it.name.equals("General", ignoreCase = true) || it.name.equals("Miscellaneous", ignoreCase = true) }
            ?: sameKind.firstOrNull()
    }

    var childStrategy by remember {
        mutableStateOf(LedgerDatabase.ChildDeleteStrategy.MOVE_CHILDREN_TO_PARENT_OR_TARGET)
    }
    var moveChildrenTargetId by remember(cat) {
        mutableStateOf(cat.parentCategoryId)
    }
    var usageStrategy by remember(impact, fallbackReplacement) {
        mutableStateOf(
            if (impact.hasLinkedUsages && fallbackReplacement != null) {
                LedgerDatabase.UsageDeleteStrategy.REASSIGN_TO_TARGET
            } else {
                LedgerDatabase.UsageDeleteStrategy.PRESERVE_HISTORICAL_REFERENCE
            }
        )
    }
    var reassignTargetId by remember(fallbackReplacement) {
        mutableStateOf(fallbackReplacement?.id)
    }

    var pickingMoveTarget by remember { mutableStateOf(false) }
    var pickingReassignTarget by remember { mutableStateOf(false) }

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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (impact.hasChildren || impact.hasLinkedUsages) AmberSoft else RoseSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (impact.hasChildren || impact.hasLinkedUsages) Icons.Outlined.WarningAmber else Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                        tint = if (impact.hasChildren || impact.hasLinkedUsages) AmberWarn else RoseOutflow,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Delete \"${cat.name}\"?", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                    Text(
                        text = buildString {
                            val parts = mutableListOf<String>()
                            if (impact.totalDescendantsCount > 0) {
                                parts += "${impact.totalDescendantsCount} subcategorie${if (impact.totalDescendantsCount == 1) "" else "s"}"
                            }
                            if (impact.linkedTransactionsCount > 0) {
                                parts += "${impact.linkedTransactionsCount} transaction${if (impact.linkedTransactionsCount == 1) "" else "s"}"
                            }
                            val configRefs = impact.linkedBudgetsCount + impact.linkedPlannedCount + impact.linkedMagicRulesCount + impact.linkedShoppingListsCount
                            if (configRefs > 0) parts += "$configRefs budget/rule link${if (configRefs == 1) "" else "s"}"
                            append(if (parts.isEmpty()) "No subcategories or records depend on this category." else "Impacts ${parts.joinToString(", ")}.")
                        },
                        fontSize = 12.sp,
                        color = MutedSlate,
                    )
                }
            }

            if (impact.hasChildren) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SlateTint,
                    border = BorderStroke(1.dp, BorderSubtle),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Subcategories (${impact.totalDescendantsCount})", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { childStrategy = LedgerDatabase.ChildDeleteStrategy.MOVE_CHILDREN_TO_PARENT_OR_TARGET },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = childStrategy == LedgerDatabase.ChildDeleteStrategy.MOVE_CHILDREN_TO_PARENT_OR_TARGET,
                                onClick = { childStrategy = LedgerDatabase.ChildDeleteStrategy.MOVE_CHILDREN_TO_PARENT_OR_TARGET },
                                colors = RadioButtonDefaults.colors(selectedColor = EmeraldPrimary),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Move subcategories to another parent", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = SlateInk)
                                Text("Keeps child categories & hierarchy intact", fontSize = 11.sp, color = MutedSlate)
                            }
                        }
                        if (childStrategy == LedgerDatabase.ChildDeleteStrategy.MOVE_CHILDREN_TO_PARENT_OR_TARGET) {
                            HierarchicalCategorySelectorField(
                                label = "Destination Parent",
                                selectedCategoryId = moveChildrenTargetId,
                                allCategories = activeAll.filter { it.id !in excludedSubtree },
                                nullOptionLabel = "Top-Level Category",
                                onClick = { pickingMoveTarget = true },
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { childStrategy = LedgerDatabase.ChildDeleteStrategy.DELETE_CHILDREN_SUBTREE },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = childStrategy == LedgerDatabase.ChildDeleteStrategy.DELETE_CHILDREN_SUBTREE,
                                onClick = { childStrategy = LedgerDatabase.ChildDeleteStrategy.DELETE_CHILDREN_SUBTREE },
                                colors = RadioButtonDefaults.colors(selectedColor = RoseOutflow),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Delete category & all subcategories", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = RoseOutflow)
                                Text("Archives the full nested branch", fontSize = 11.sp, color = MutedSlate)
                            }
                        }
                    }
                }
            }

            if (impact.hasLinkedUsages) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SlateTint,
                    border = BorderStroke(1.dp, BorderSubtle),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Transactions & Linked Items", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                        if (fallbackReplacement != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { usageStrategy = LedgerDatabase.UsageDeleteStrategy.REASSIGN_TO_TARGET },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = usageStrategy == LedgerDatabase.UsageDeleteStrategy.REASSIGN_TO_TARGET,
                                    onClick = { usageStrategy = LedgerDatabase.UsageDeleteStrategy.REASSIGN_TO_TARGET },
                                    colors = RadioButtonDefaults.colors(selectedColor = EmeraldPrimary),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Reassign records & budgets to another category", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = SlateInk)
                                    Text("Updates existing transactions, budgets, rules & lists cleanly", fontSize = 11.sp, color = MutedSlate)
                                }
                            }
                            if (usageStrategy == LedgerDatabase.UsageDeleteStrategy.REASSIGN_TO_TARGET) {
                                HierarchicalCategorySelectorField(
                                    label = "Replacement Category",
                                    selectedCategoryId = reassignTargetId,
                                    allCategories = activeAll.filter { it.id !in excludedSubtree },
                                    nullOptionLabel = "Choose replacement",
                                    onClick = { pickingReassignTarget = true },
                                )
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { usageStrategy = LedgerDatabase.UsageDeleteStrategy.PRESERVE_HISTORICAL_REFERENCE },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = usageStrategy == LedgerDatabase.UsageDeleteStrategy.PRESERVE_HISTORICAL_REFERENCE,
                                onClick = { usageStrategy = LedgerDatabase.UsageDeleteStrategy.PRESERVE_HISTORICAL_REFERENCE },
                                colors = RadioButtonDefaults.colors(selectedColor = EmeraldPrimary),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Preserve historical transaction labels", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = SlateInk)
                                Text("Past records keep \"${cat.name}\" for accurate history", fontSize = 11.sp, color = MutedSlate)
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BorderSubtle),
                ) {
                    Text("Cancel", color = SlateInk, fontWeight = FontWeight.SemiBold)
                }
                Button(
                    enabled = usageStrategy != LedgerDatabase.UsageDeleteStrategy.REASSIGN_TO_TARGET || reassignTargetId != null,
                    onClick = {
                        onConfirmDelete(
                            childStrategy,
                            moveChildrenTargetId,
                            usageStrategy,
                            reassignTargetId,
                        )
                    },
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = RoseOutflow),
                ) {
                    Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (pickingMoveTarget) {
        HierarchicalCategoryPickerSheet(
            title = "Move Subcategories To",
            categories = activeAll,
            kindFilter = cat.kind,
            selectedIds = listOfNotNull(moveChildrenTargetId).toSet(),
            excludedIds = excludedSubtree,
            multiSelect = false,
            nullOptionLabel = "Top-Level Category (Root)",
            nullSelected = moveChildrenTargetId == null,
            onSelectNull = { moveChildrenTargetId = null },
            onSelectSingle = { moveChildrenTargetId = it.id },
            onDismiss = { pickingMoveTarget = false },
        )
    }

    if (pickingReassignTarget) {
        HierarchicalCategoryPickerSheet(
            title = "Reassign Linked Items To",
            categories = activeAll,
            kindFilter = cat.kind,
            selectedIds = listOfNotNull(reassignTargetId).toSet(),
            excludedIds = excludedSubtree,
            multiSelect = false,
            onSelectSingle = { reassignTargetId = it.id },
            onDismiss = { pickingReassignTarget = false },
        )
    }
}
