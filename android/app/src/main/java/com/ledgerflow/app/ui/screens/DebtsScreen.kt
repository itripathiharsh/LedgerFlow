package com.ledgerflow.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgerflow.app.data.local.DebtEntity
import com.ledgerflow.app.domain.AnalyticsCalculator
import com.ledgerflow.app.domain.MoneyFormatter
import com.ledgerflow.app.ui.LedgerViewModel
import com.ledgerflow.app.ui.components.CategoryIconBadge
import com.ledgerflow.app.ui.components.CleanCard
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
import com.ledgerflow.app.ui.theme.TabularStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebtsScreen(
    vm: LedgerViewModel,
    addTrigger: Int = 0,
    onOpenDrawer: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val debts by vm.debts.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val vault by vm.vaultState.collectAsState()

    val baseCurrency = vault.baseCurrency
    val masked = vault.hideBalances
    val accMap = remember(accounts) { accounts.associateBy { it.id } }
    val dateFmt = remember { SimpleDateFormat("MMM d, yyyy", Locale.US) }

    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Active, 1 = Closed
    var searchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    var showEditorSheet by remember { mutableStateOf(false) }
    var editingDebt by remember { mutableStateOf<DebtEntity?>(null) }
    var repaymentTarget by remember { mutableStateOf<DebtEntity?>(null) }

    LaunchedEffect(addTrigger) {
        if (addTrigger > 0) {
            editingDebt = null
            showEditorSheet = true
        }
    }

    val summary = remember(debts, baseCurrency) {
        AnalyticsCalculator.computeDebtSummary(debts, baseCurrency)
    }

    val displayedDebts = remember(debts, selectedTab, searchQuery) {
        val wantClosed = selectedTab == 1
        val q = searchQuery.trim().lowercase(Locale.US)
        debts.filter { debt ->
            val isClosed = debt.status == "CLOSED" || debt.remainingAmountMinor <= 0L
            val tabMatches = if (wantClosed) isClosed else !isClosed
            val searchMatches = q.isEmpty() ||
                debt.personName.lowercase(Locale.US).contains(q) ||
                debt.note.lowercase(Locale.US).contains(q)
            tabMatches && searchMatches
        }
    }

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
                    Text("Debts", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            searchVisible = !searchVisible
                            if (!searchVisible) searchQuery = ""
                        }
                    ) {
                        Icon(
                            imageVector = if (searchVisible) Icons.Outlined.Close else Icons.Outlined.Search,
                            contentDescription = "Search debts",
                            tint = SlateInk,
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = SlateInk)
                    }
                }
            }
        }

        item {
            AnimatedVisibility(visible = searchVisible) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search person or note...") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = MutedSlate) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Outlined.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
            }
        }

        // Top Sub-Tabs: Active | Closed
        item {
            Surface(shape = RoundedCornerShape(14.dp), color = SlateTint, modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(4.dp)) {
                    val activeCount = summary.activeLentCount + summary.activeBorrowedCount
                    listOf(
                        "Active ($activeCount)",
                        "Closed (${summary.closedCount})",
                    ).forEachIndexed { idx, label ->
                        val active = selectedTab == idx
                        Surface(
                            onClick = { selectedTab = idx },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            color = if (active) SurfaceCard else Color.Transparent,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(vertical = 8.dp),
                            ) {
                                Text(
                                    label,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (active) SlateInk else MutedSlate,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Compact 2-Pill Summary Strip: To Receive (emerald) & To Pay (rose)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    color = EmeraldSoft,
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text("TO RECEIVE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = EmeraldDark)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = MoneyFormatter.formatMinor(summary.toReceiveMinor, baseCurrency, masked = masked),
                            style = TabularStyle,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = SlateInk,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${summary.activeLentCount} active lent",
                            fontSize = 11.sp,
                            color = MutedSlate,
                        )
                    }
                }
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    color = RoseSoft,
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text("TO PAY", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = RoseOutflow)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = MoneyFormatter.formatMinor(summary.toPayMinor, baseCurrency, masked = masked),
                            style = TabularStyle,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = SlateInk,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${summary.activeBorrowedCount} active borrowed",
                            fontSize = 11.sp,
                            color = MutedSlate,
                        )
                    }
                }
            }
        }

        if (displayedDebts.isEmpty()) {
            item {
                CleanCard(padding = 22.dp) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CategoryIconBadge(
                            iconKey = "debt",
                            tintHex = if (selectedTab == 0) "#059669" else "#64748B",
                            size = 54.dp,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = if (selectedTab == 0) "Track what you lent and borrowed" else "No settled debts yet",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = SlateInk,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (selectedTab == 0) {
                                "Log friends, family, or shared tabs with optional account linking and partial repayments."
                            } else {
                                "Debts settled in full automatically move here for your archive."
                            },
                            fontSize = 13.sp,
                            color = MutedSlate,
                        )
                        if (selectedTab == 0) {
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    editingDebt = null
                                    showEditorSheet = true
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text("Add Debt Record", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        } else {
            items(displayedDebts, key = { it.id }) { debt ->
                val isLent = debt.direction != "BORROWED"
                val isClosed = debt.status == "CLOSED" || debt.remainingAmountMinor <= 0L
                val paidMinor = (debt.totalAmountMinor - debt.remainingAmountMinor).coerceAtLeast(0L)
                val paidRatio = if (debt.totalAmountMinor > 0L) {
                    (paidMinor.toFloat() / debt.totalAmountMinor.toFloat()).coerceIn(0f, 1f)
                } else 0f
                val accName = debt.accountId?.let { accMap[it]?.name }

                CleanCard(
                    padding = 16.dp,
                    onClick = {
                        editingDebt = debt
                        showEditorSheet = true
                    },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            CategoryIconBadge(
                                iconKey = "debt",
                                tintHex = if (isLent) "#059669" else "#E11D48",
                                size = 40.dp,
                            )
                            Column {
                                Text(
                                    text = debt.personName,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SlateInk,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val subtitle = listOfNotNull(
                                    if (isLent) "Lent" else "Borrowed",
                                    accName,
                                    debt.dueAtEpochMs?.let { "Due ${dateFmt.format(Date(it))}" },
                                ).joinToString(" • ")
                                Text(subtitle, fontSize = 11.sp, color = MutedSlate)
                                if (debt.note.isNotBlank()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = debt.note,
                                        fontSize = 12.sp,
                                        color = MutedSlate,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = if (isClosed) {
                                    MoneyFormatter.formatMinor(debt.totalAmountMinor, debt.currency, masked = masked)
                                } else {
                                    MoneyFormatter.formatMinor(debt.remainingAmountMinor, debt.currency, masked = masked)
                                },
                                style = TabularStyle,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isClosed) MutedSlate else if (isLent) EmeraldDark else RoseOutflow,
                            )
                            if (!isClosed && paidMinor > 0L) {
                                Text(
                                    text = "of ${MoneyFormatter.formatMinor(debt.totalAmountMinor, debt.currency, masked = masked)}",
                                    style = TabularStyle,
                                    fontSize = 11.sp,
                                    color = MutedSlate,
                                )
                            }
                        }
                    }

                    if (!isClosed && paidMinor > 0L) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { paidRatio },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(999.dp)),
                            color = if (isLent) EmeraldPrimary else RoseOutflow,
                            trackColor = SlateTint,
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusPill(
                            text = when {
                                isClosed -> "Settled"
                                isLent -> "To Receive"
                                else -> "To Pay"
                            },
                            containerColor = when {
                                isClosed -> SlateTint
                                isLent -> EmeraldSoft
                                else -> RoseSoft
                            },
                            contentColor = when {
                                isClosed -> MutedSlate
                                isLent -> EmeraldDark
                                else -> RoseOutflow
                            },
                        )

                        if (!isClosed) {
                            OutlinedButton(
                                onClick = { repaymentTarget = debt },
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, EmeraldPrimary.copy(alpha = 0.45f)),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp),
                            ) {
                                Text(
                                    text = "Record Repayment",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = EmeraldPrimary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditorSheet) {
        DebtEditorBottomSheet(
            existing = editingDebt,
            baseCurrency = editingDebt?.currency ?: baseCurrency,
            accounts = accounts,
            onDismiss = {
                showEditorSheet = false
                editingDebt = null
            },
            onSave = { personName, direction, amountMinor, accountId, dueAtEpochMs, note, createInitialTx ->
                val target = editingDebt
                if (target == null) {
                    vm.addDebt(
                        personName = personName,
                        direction = direction,
                        amountMinor = amountMinor,
                        currency = baseCurrency,
                        accountId = accountId,
                        dueAtEpochMs = dueAtEpochMs,
                        note = note,
                        createInitialTransaction = createInitialTx,
                    )
                } else {
                    vm.updateDebt(
                        debtId = target.id,
                        personName = personName,
                        direction = direction,
                        totalAmountMinor = amountMinor,
                        accountId = accountId,
                        dueAtEpochMs = dueAtEpochMs,
                        note = note,
                    )
                }
                showEditorSheet = false
                editingDebt = null
            },
            onDelete = { id ->
                vm.deleteDebt(id)
                showEditorSheet = false
                editingDebt = null
            },
        )
    }

    repaymentTarget?.let { target ->
        DebtRepaymentBottomSheet(
            debt = target,
            accounts = accounts,
            onDismiss = { repaymentTarget = null },
            onConfirm = { amountMinor, accountId, recordTx ->
                vm.recordDebtRepayment(
                    debtId = target.id,
                    amountMinor = amountMinor,
                    accountId = accountId,
                    recordTransaction = recordTx,
                )
                repaymentTarget = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DebtEditorBottomSheet(
    existing: DebtEntity?,
    baseCurrency: String,
    accounts: List<com.ledgerflow.app.data.local.AccountEntity>,
    onDismiss: () -> Unit,
    onSave: (
        personName: String,
        direction: String,
        amountMinor: Long,
        accountId: String?,
        dueAtEpochMs: Long?,
        note: String,
        createInitialTx: Boolean,
    ) -> Unit,
    onDelete: (String) -> Unit,
) {
    val isEdit = existing != null
    var direction by remember(existing) { mutableStateOf(existing?.direction ?: "LENT") }
    var personName by remember(existing) { mutableStateOf(existing?.personName ?: "") }
    var amountText by remember(existing) {
        mutableStateOf(
            if (existing != null) {
                val div = if (existing.currency.equals("JPY", ignoreCase = true)) 1.0 else 100.0
                if (existing.currency.equals("JPY", ignoreCase = true)) existing.totalAmountMinor.toString()
                else String.format(Locale.US, "%.2f", existing.totalAmountMinor / div)
            } else ""
        )
    }
    var selectedAccountId by remember(existing, accounts) {
        mutableStateOf(
            existing?.accountId?.takeIf { id -> accounts.any { it.id == id } }
                ?: if (existing == null) accounts.firstOrNull()?.id else null
        )
    }
    var createInitialTx by remember(existing) { mutableStateOf(existing == null && selectedAccountId != null) }
    var dueMode by remember(existing) {
        mutableStateOf(
            when {
                existing?.dueAtEpochMs == null -> "NONE"
                else -> "KEEP"
            }
        )
    }
    var note by remember(existing) { mutableStateOf(existing?.note ?: "") }
    val parsedAmount = MoneyFormatter.parseDecimalToMinor(amountText, baseCurrency) ?: 0L
    val dateFmt = remember { SimpleDateFormat("MMM d, yyyy", Locale.US) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SurfaceCard,
        contentColor = SlateInk,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (isEdit) "Manage Debt" else "Add Debt",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = SlateInk,
            )

            // Toggle: I Lent | I Borrowed
            Surface(shape = RoundedCornerShape(12.dp), color = SlateTint, modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(4.dp)) {
                    listOf("LENT" to "I Lent", "BORROWED" to "I Borrowed").forEach { (key, label) ->
                        val active = direction == key
                        Surface(
                            onClick = { direction = key },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            color = if (active) SurfaceCard else Color.Transparent,
                        ) {
                            Text(
                                text = label,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (active) {
                                    if (key == "LENT") EmeraldDark else RoseOutflow
                                } else MutedSlate,
                                modifier = Modifier
                                    .padding(vertical = 8.dp)
                                    .padding(horizontal = 12.dp),
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = personName,
                onValueChange = { personName = it },
                label = { Text("Person / Entity Name") },
                placeholder = { Text("e.g. Rohan, Roommate, Alex") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = MoneyFormatter.sanitizeDecimalInput(it, baseCurrency, allowNegative = false) },
                label = { Text("Amount ($baseCurrency)") },
                placeholder = { Text(if (baseCurrency == "JPY") "0" else "0.00") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            Text("Account", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MutedSlate)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = selectedAccountId == null,
                    onClick = {
                        selectedAccountId = null
                        createInitialTx = false
                    },
                    label = { Text("None / Don't affect balance", fontSize = 11.sp) },
                )
                accounts.forEach { acc ->
                    FilterChip(
                        selected = selectedAccountId == acc.id,
                        onClick = {
                            selectedAccountId = acc.id
                            if (!isEdit) createInitialTx = true
                        },
                        label = { Text(acc.name, fontSize = 11.sp) },
                    )
                }
            }

            if (!isEdit && selectedAccountId != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Create initial ledger transaction", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = SlateInk)
                        Text(
                            text = if (direction == "LENT") {
                                "Deducts from selected account balance"
                            } else {
                                "Adds cash to selected account balance"
                            },
                            fontSize = 11.sp,
                            color = MutedSlate,
                        )
                    }
                    Switch(
                        checked = createInitialTx,
                        onCheckedChange = { createInitialTx = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = EmeraldPrimary),
                    )
                }
            }

            Text("Due Date (Optional)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MutedSlate)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = dueMode == "NONE",
                    onClick = { dueMode = "NONE" },
                    label = { Text("No due date", fontSize = 11.sp) },
                )
                if (existing?.dueAtEpochMs != null) {
                    FilterChip(
                        selected = dueMode == "KEEP",
                        onClick = { dueMode = "KEEP" },
                        label = { Text(dateFmt.format(Date(existing.dueAtEpochMs)), fontSize = 11.sp) },
                    )
                }
                FilterChip(
                    selected = dueMode == "PLUS_7",
                    onClick = { dueMode = "PLUS_7" },
                    label = { Text("+7 Days", fontSize = 11.sp) },
                )
                FilterChip(
                    selected = dueMode == "PLUS_30",
                    onClick = { dueMode = "PLUS_30" },
                    label = { Text("+30 Days", fontSize = 11.sp) },
                )
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note (Optional)") },
                placeholder = { Text("Dinner split, trip tickets, short loan...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (existing != null) {
                    TextButton(onClick = { onDelete(existing.id) }) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = RoseOutflow, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Delete", color = RoseOutflow)
                    }
                } else {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }

                Button(
                    enabled = personName.isNotBlank() && parsedAmount > 0L,
                    onClick = {
                        val now = System.currentTimeMillis()
                        val dueMs = when (dueMode) {
                            "PLUS_7" -> now + 7L * 86_400_000L
                            "PLUS_30" -> now + 30L * 86_400_000L
                            "KEEP" -> existing?.dueAtEpochMs
                            else -> null
                        }
                        onSave(personName, direction, parsedAmount, selectedAccountId, dueMs, note, createInitialTx)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(if (isEdit) "Save Changes" else "Save Debt", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DebtRepaymentBottomSheet(
    debt: DebtEntity,
    accounts: List<com.ledgerflow.app.data.local.AccountEntity>,
    onDismiss: () -> Unit,
    onConfirm: (amountMinor: Long, accountId: String?, recordTransaction: Boolean) -> Unit,
) {
    var amountText by remember(debt) {
        val div = if (debt.currency.equals("JPY", ignoreCase = true)) 1.0 else 100.0
        val preset = if (debt.currency.equals("JPY", ignoreCase = true)) {
            debt.remainingAmountMinor.toString()
        } else {
            String.format(Locale.US, "%.2f", debt.remainingAmountMinor / div)
        }
        mutableStateOf(preset)
    }
    var selectedAccountId by remember(debt, accounts) {
        mutableStateOf(
            debt.accountId?.takeIf { id -> accounts.any { it.id == id } } ?: accounts.firstOrNull()?.id
        )
    }
    var recordLedgerTx by remember(selectedAccountId) { mutableStateOf(selectedAccountId != null) }
    val parsedAmount = MoneyFormatter.parseDecimalToMinor(amountText, debt.currency) ?: 0L

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
            Text(
                text = "Record Repayment • ${debt.personName}",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = SlateInk,
            )
            Text(
                text = "Remaining balance: ${MoneyFormatter.formatMinor(debt.remainingAmountMinor, debt.currency)}",
                style = TabularStyle,
                fontSize = 13.sp,
                color = MutedSlate,
            )

            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = MoneyFormatter.sanitizeDecimalInput(it, debt.currency, allowNegative = false) },
                label = { Text("Repayment Amount (${debt.currency})") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val div = if (debt.currency.equals("JPY", ignoreCase = true)) 1.0 else 100.0
                OutlinedButton(
                    onClick = {
                        val half = (debt.remainingAmountMinor / 2L).coerceAtLeast(1L)
                        amountText = if (debt.currency.equals("JPY", ignoreCase = true)) half.toString()
                        else String.format(Locale.US, "%.2f", half / div)
                    },
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("50%", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = {
                        amountText = if (debt.currency.equals("JPY", ignoreCase = true)) debt.remainingAmountMinor.toString()
                        else String.format(Locale.US, "%.2f", debt.remainingAmountMinor / div)
                    },
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("Full Amount", fontSize = 12.sp)
                }
            }

            Text("Settlement Account", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MutedSlate)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = selectedAccountId == null,
                    onClick = {
                        selectedAccountId = null
                        recordLedgerTx = false
                    },
                    label = { Text("None (Vault only)", fontSize = 11.sp) },
                )
                accounts.forEach { acc ->
                    FilterChip(
                        selected = selectedAccountId == acc.id,
                        onClick = {
                            selectedAccountId = acc.id
                            recordLedgerTx = true
                        },
                        label = { Text(acc.name, fontSize = 11.sp) },
                    )
                }
            }

            if (selectedAccountId != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Update account balance", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = SlateInk)
                        Text(
                            text = if (debt.direction == "LENT") {
                                "Records Income on selected account"
                            } else {
                                "Records Expense on selected account"
                            },
                            fontSize = 11.sp,
                            color = MutedSlate,
                        )
                    }
                    Switch(
                        checked = recordLedgerTx,
                        onCheckedChange = { recordLedgerTx = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = EmeraldPrimary),
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
                    enabled = parsedAmount > 0L,
                    onClick = { onConfirm(parsedAmount, selectedAccountId, recordLedgerTx) },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Confirm Repayment", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
