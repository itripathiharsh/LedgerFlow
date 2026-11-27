package com.ledgerflow.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgerflow.app.data.local.AccountEntity
import com.ledgerflow.app.data.local.CategoryEntity
import com.ledgerflow.app.data.local.TransactionEntity
import com.ledgerflow.app.domain.MoneyFormatter
import com.ledgerflow.app.domain.SupportedPaymentApps
import com.ledgerflow.app.ui.theme.AmberSoft
import com.ledgerflow.app.ui.theme.AmberWarn
import com.ledgerflow.app.ui.theme.BorderSubtle
import com.ledgerflow.app.ui.theme.EmeraldDark
import com.ledgerflow.app.ui.theme.EmeraldPrimary
import com.ledgerflow.app.ui.theme.EmeraldSoft
import com.ledgerflow.app.ui.theme.MutedSlate
import com.ledgerflow.app.ui.theme.RoseOutflow
import com.ledgerflow.app.ui.theme.RoseSoft
import com.ledgerflow.app.ui.theme.SkySoft
import com.ledgerflow.app.ui.theme.SkySync
import com.ledgerflow.app.ui.theme.SlateInk
import com.ledgerflow.app.ui.theme.SlateTint
import com.ledgerflow.app.ui.theme.SurfaceCard
import com.ledgerflow.app.ui.theme.TabularStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@Composable
fun NotificationAccessPrivacyDialog(
    onDismiss: () -> Unit,
    onContinueToSettings: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceCard,
        icon = {
            Icon(Icons.Outlined.Notifications, contentDescription = null, tint = EmeraldPrimary)
        },
        title = {
            Text("Privacy-First Payment Detection", fontWeight = FontWeight.Bold, color = SlateInk)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "If you enable Notification Access in Android Settings, LedgerFlow checks notifications locally ONLY from supported payment & banking apps:",
                    fontSize = 13.sp,
                    color = SlateInk,
                )
                SupportedPaymentApps.disclosedCategoriesSummary.forEach { line ->
                    Text("• $line", fontSize = 12.sp, color = MutedSlate)
                }
                CleanCard(containerColor = SlateTint, padding = 10.dp) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Outlined.Lock, contentDescription = null, tint = EmeraldDark, modifier = Modifier.size(18.dp))
                        Text(
                            text = "Non-payment apps (messages, chat, email, social) are ignored immediately. Raw notification text stays strictly on-device and is never uploaded to Supabase or Render. You choose every category & purpose.",
                            fontSize = 11.sp,
                            color = SlateInk,
                        )
                    }
                }
                Text(
                    text = "Share-to-Track and Smart Paste continue working even if Notification Access stays off.",
                    fontSize = 12.sp,
                    color = MutedSlate,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onContinueToSettings,
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("Open Android Settings", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Not Now", color = MutedSlate)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReviewTransactionBottomSheet(
    tx: TransactionEntity,
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    baseCurrency: String,
    onDismiss: () -> Unit,
    onDelete: (TransactionEntity) -> Unit,
    onSave: (
        txType: String,
        magnitudeMinor: Long,
        accountId: String,
        transferAccountId: String?,
        categoryId: String?,
        categoryIdsCsv: String,
        spendingPurpose: String,
        merchant: String,
        note: String,
        labelsCsv: String,
    ) -> Unit,
) {
    val isPending = tx.reviewStatus == TransactionEntity.STATUS_PENDING_REVIEW
    val dateTimeFmt = remember { SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.US) }

    fun formatMag(minor: Long, cur: String): String {
        val m = abs(minor)
        val div = if (cur.equals("JPY", ignoreCase = true)) 1.0 else 100.0
        return if (cur.equals("JPY", ignoreCase = true)) m.toString()
        else String.format(Locale.US, "%.2f", m / div)
    }

    var txType by remember(tx.id) { mutableStateOf(tx.txType) }
    var amountText by remember(tx.id) { mutableStateOf(formatMag(tx.amountMinor, tx.currency)) }
    var selectedAccountId by remember(tx.id) {
        mutableStateOf(
            tx.accountId.takeIf { id -> id.isNotBlank() && accounts.any { it.id == id } }.orEmpty()
        )
    }
    var transferTargetId by remember(tx.id) {
        mutableStateOf(tx.transferAccountId ?: accounts.firstOrNull { it.id != selectedAccountId }?.id)
    }
    // Never auto-select a category on PENDING_REVIEW transactions
    var selectedCategoryId by remember(tx.id, txType) {
        mutableStateOf(
            if (isPending) null
            else tx.categoryId?.takeIf { cid ->
                categories.any { it.id == cid && (txType == "TRANSFER" || it.kind == txType) }
            }
        )
    }
    var activeParentId by remember(tx.id, selectedCategoryId) {
        mutableStateOf(
            selectedCategoryId?.let { cid ->
                val cat = categories.firstOrNull { it.id == cid }
                cat?.parentCategoryId ?: cat?.id
            }
        )
    }
    var merchant by remember(tx.id) { mutableStateOf(tx.merchant) }
    var spendingPurpose by remember(tx.id) { mutableStateOf(tx.spendingPurpose) }
    var note by remember(tx.id) { mutableStateOf(tx.note) }
    var labelsCsv by remember(tx.id) { mutableStateOf(tx.labelsCsv) }

    val parsedMag = MoneyFormatter.parseDecimalToMinor(amountText, tx.currency) ?: 0L
    val typeOptions = if (tx.txType == "TRANSFER") {
        listOf("EXPENSE", "INCOME", "TRANSFER")
    } else {
        listOf("EXPENSE", "INCOME")
    }
    val parentCategories = remember(categories, txType) {
        categories.filter { it.kind == txType && it.parentCategoryId == null }
    }
    val childCategories = remember(categories, activeParentId) {
        activeParentId?.let { pid -> categories.filter { it.parentCategoryId == pid } }.orEmpty()
    }
    val canConfirm = parsedMag > 0L &&
        selectedAccountId.isNotBlank() &&
        (txType != "TRANSFER" || (!transferTargetId.isNullOrBlank() && transferTargetId != selectedAccountId)) &&
        (txType == "TRANSFER" || !selectedCategoryId.isNullOrBlank())

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isPending) "Review & Classify Payment" else "Edit Transaction",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateInk,
                    )
                    Text(
                        text = dateTimeFmt.format(Date(tx.occurredAtEpochMs)),
                        fontSize = 12.sp,
                        color = MutedSlate,
                    )
                }
                StatusPill(
                    text = if (isPending) "Pending Review" else "Confirmed",
                    containerColor = if (isPending) AmberSoft else EmeraldSoft,
                    contentColor = if (isPending) AmberWarn else EmeraldDark,
                )
            }

            // Sanitized notification audit strip (never contains raw notification body)
            if (tx.paymentAppName.isNotBlank() || tx.referenceId.isNotBlank() || tx.accountHint.isNotBlank() || tx.isRefundOrReversal) {
                CleanCard(containerColor = SlateTint, padding = 10.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (tx.paymentAppName.isNotBlank()) {
                            StatusPill(tx.paymentAppName, SkySoft, SkySync)
                        }
                        if (tx.isRefundOrReversal) {
                            StatusPill("Refund / Reversal", EmeraldSoft, EmeraldDark)
                        }
                        if (tx.accountHint.isNotBlank()) {
                            StatusPill(tx.accountHint, SurfaceCard, SlateInk)
                        }
                    }
                    if (tx.referenceId.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text("Ref ID: ${tx.referenceId}", fontSize = 11.sp, color = MutedSlate)
                    }
                    val orig = tx.originalParsedAmountMinor
                    if (orig != null && abs(orig) != parsedMag && parsedMag > 0L) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Original detected amount: ${MoneyFormatter.formatMinor(orig, tx.currency)}",
                            style = TabularStyle,
                            fontSize = 11.sp,
                            color = AmberWarn,
                        )
                    }
                }
            }

            // Direction toggle
            Surface(shape = RoundedCornerShape(12.dp), color = SlateTint, modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(4.dp)) {
                    typeOptions.forEach { t ->
                        val active = txType == t
                        val label = when (t) {
                            "INCOME" -> if (tx.isRefundOrReversal) "Credit / Refund" else "Income / Credit"
                            "TRANSFER" -> "Transfer"
                            else -> "Expense / Debit"
                        }
                        Surface(
                            onClick = {
                                if (txType != t) {
                                    txType = t
                                    selectedCategoryId = null
                                    activeParentId = null
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            color = if (active) SurfaceCard else Color.Transparent,
                        ) {
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                color = if (active) {
                                    if (t == "INCOME") EmeraldDark else if (t == "EXPENSE") RoseOutflow else SlateInk
                                } else MutedSlate,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = MoneyFormatter.sanitizeDecimalInput(it, tx.currency, allowNegative = false) },
                label = { Text("Amount (${tx.currency})") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
            if (tx.currency != baseCurrency && parsedMag > 0L) {
                Text(
                    text = "Original currency ${tx.currency} • Stored in ${tx.currency} with $baseCurrency equivalent",
                    fontSize = 11.sp,
                    color = MutedSlate,
                )
            }

            // Account Selection (explicit Unassigned state when empty)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Account", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = SlateInk)
                if (selectedAccountId.isBlank()) {
                    StatusPill("Unassigned Account", AmberSoft, AmberWarn)
                }
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                accounts.forEach { acc ->
                    FilterChip(
                        selected = selectedAccountId == acc.id,
                        onClick = { selectedAccountId = acc.id },
                        label = {
                            val suffix = if (acc.lastFour.isNotBlank()) " ••${acc.lastFour}" else ""
                            Text("${acc.name}$suffix", fontSize = 12.sp)
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = EmeraldSoft,
                            selectedLabelColor = EmeraldDark,
                        ),
                    )
                }
            }

            if (txType == "TRANSFER") {
                Text("Destination Account", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = SlateInk)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    accounts.filter { it.id != selectedAccountId }.forEach { acc ->
                        FilterChip(
                            selected = transferTargetId == acc.id,
                            onClick = { transferTargetId = acc.id },
                            label = { Text(acc.name, fontSize = 12.sp) },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = merchant,
                onValueChange = { merchant = it },
                label = { Text("Merchant / Payee Description") },
                placeholder = { Text("Who did you pay or receive from?") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            OutlinedTextField(
                value = spendingPurpose,
                onValueChange = { spendingPurpose = it },
                label = { Text(if (txType == "INCOME") "Income / Refund Purpose" else "Spending Purpose") },
                placeholder = { Text("Why was this spent? e.g. Client lunch, Weekly groceries") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            if (txType != "TRANSFER") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Category (Required)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = SlateInk)
                    if (selectedCategoryId == null) {
                        StatusPill("Select a category", RoseSoft, RoseOutflow)
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    parentCategories.forEach { cat ->
                        val isSelected = selectedCategoryId == cat.id || activeParentId == cat.id
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                activeParentId = cat.id
                                selectedCategoryId = cat.id
                            },
                            label = { Text(cat.name, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = EmeraldPrimary,
                                selectedLabelColor = Color.White,
                            ),
                        )
                    }
                }
                if (childCategories.isNotEmpty()) {
                    Text("Subcategory (Optional)", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MutedSlate)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        childCategories.forEach { sub ->
                            val isSelected = selectedCategoryId == sub.id
                            FilterChip(
                                selected = isSelected,
                                onClick = { selectedCategoryId = sub.id },
                                label = { Text(sub.name, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = EmeraldSoft,
                                    selectedLabelColor = EmeraldDark,
                                ),
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Notes (Optional)") },
                placeholder = { Text("Add extra details...") },
                minLines = 2,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            HorizontalDivider(color = BorderSubtle)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { onDelete(tx) },
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, RoseOutflow.copy(alpha = 0.45f)),
                ) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = RoseOutflow, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Delete", color = RoseOutflow, fontSize = 12.sp)
                }

                Button(
                    enabled = canConfirm,
                    onClick = {
                        val catCsv = selectedCategoryId.orEmpty()
                        onSave(
                            txType,
                            parsedMag,
                            selectedAccountId,
                            transferTargetId,
                            selectedCategoryId,
                            catCsv,
                            spendingPurpose,
                            merchant,
                            note,
                            labelsCsv,
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = if (isPending) "Confirm & Save" else "Save Changes",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
