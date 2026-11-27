package com.ledgerflow.app.ui.screens

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Contactless
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ledgerflow.app.data.local.AccountEntity
import com.ledgerflow.app.domain.AccountTypeCatalog
import com.ledgerflow.app.domain.AnalyticsCalculator
import com.ledgerflow.app.domain.AnalyticsEngine
import com.ledgerflow.app.domain.FxConverter
import com.ledgerflow.app.domain.MoneyFormatter
import com.ledgerflow.app.service.PayAndTrackCapture
import com.ledgerflow.app.ui.LedgerViewModel
import com.ledgerflow.app.ui.components.BalanceTrendLineChart
import com.ledgerflow.app.ui.components.CategoryIconBadge
import com.ledgerflow.app.ui.components.CleanCard
import com.ledgerflow.app.ui.components.EmptyPlaceholder
import com.ledgerflow.app.ui.components.SectionHeader
import com.ledgerflow.app.ui.components.StatusPill
import com.ledgerflow.app.ui.components.rememberDataImportPicker
import com.ledgerflow.app.ui.theme.AlabasterBg
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
import com.ledgerflow.app.ui.theme.TabularStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    vm: LedgerViewModel,
    openAddOnStart: Boolean = false,
    onConsumeOpenAdd: () -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val accounts by vm.accounts.collectAsState()
    val transactions by vm.allTransactions.collectAsState()
    val vault by vm.vaultState.collectAsState()
    val masked = vault.hideBalances
    val baseCurrency = vault.baseCurrency
    val lifecycleOwner = LocalLifecycleOwner.current

    val supportsOsListener = remember(context) { PayAndTrackCapture.isOsListenerAvailable(context) }
    var enabledListeners by remember { mutableStateOf(PayAndTrackCapture.isOsListenerEnabled(context)) }
    DisposableEffect(lifecycleOwner, vault.payAndTrackEnabled) {
        enabledListeners = PayAndTrackCapture.isOsListenerEnabled(context)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabledListeners = PayAndTrackCapture.isOsListenerEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val gpayCapturedCount = remember(transactions) {
        transactions.count { it.sourceTag == "GPAY_AUTO" }
    }

    var showAddAccount by remember { mutableStateOf(false) }
    var selectedDetailAccountId by remember { mutableStateOf<String?>(null) }
    var pendingDeleteAccount by remember { mutableStateOf<AccountEntity?>(null) }
    var pendingRepayAccount by remember { mutableStateOf<AccountEntity?>(null) }
    var pendingEditAccount by remember { mutableStateOf<AccountEntity?>(null) }
    var pendingEditLimitAccount by remember { mutableStateOf<AccountEntity?>(null) }
    var showSmartPaste by remember { mutableStateOf(false) }
    val importBusy by vm.importInProgress.collectAsState()

    LaunchedEffect(openAddOnStart) {
        if (openAddOnStart) {
            showAddAccount = true
            onConsumeOpenAdd()
        }
    }

    val accountSummary = remember(accounts, baseCurrency) {
        AnalyticsCalculator.computeAccountSummary(accounts, baseCurrency)
    }
    val assetAccounts = remember(accounts) {
        accounts.filter { !AccountTypeCatalog.isLiability(it.accountType) }
    }
    val dateFmt = remember { SimpleDateFormat("MMM d, h:mm a", Locale.US) }

    val launchImportPicker = rememberDataImportPicker(
        onUriSelected = { vm.importCsvUri(it) },
        onLaunchFailed = { vm.showMessage(it) },
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AlabasterBg),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 92.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
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
                    Column {
                        Text("Accounts", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                        Text("${accounts.size} active • $baseCurrency", fontSize = 12.sp, color = MutedSlate)
                    }
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = SlateInk)
                }
            }
        }

        if (accounts.isNotEmpty() && accountSummary.liabilitiesMinor > 0L) {
            item {
                CleanCard(padding = 14.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text("ASSETS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MutedSlate)
                            Text(
                                text = MoneyFormatter.formatMinor(accountSummary.assetsMinor, baseCurrency, masked = masked),
                                style = TabularStyle,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = EmeraldDark,
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("CARDS & LOANS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MutedSlate)
                            Text(
                                text = MoneyFormatter.formatMinor(accountSummary.liabilitiesMinor, baseCurrency, masked = masked),
                                style = TabularStyle,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = RoseOutflow,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("NET WORTH", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MutedSlate)
                            Text(
                                text = MoneyFormatter.formatMinor(accountSummary.netWorthMinor, baseCurrency, masked = masked),
                                style = TabularStyle,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = SlateInk,
                            )
                        }
                    }
                }
            }
        }

        item {
            SectionHeader(
                title = "Wallets, Cards, Loans & Banks",
                actionText = "+ Add Account",
                onActionClick = { showAddAccount = true },
            )
        }

        if (accounts.isEmpty()) {
            item {
                EmptyPlaceholder("No active accounts", "Tap + Add Account above to create a wallet, debit/credit card, loan, or bank account.")
            }
        } else {
            items(accounts, key = { it.id }) { acc ->
                val normType = AccountTypeCatalog.normalize(acc.accountType)
                val isCreditCard = normType == "CREDIT_CARD" || normType == "CARD"
                val isDebtType = AccountTypeCatalog.isLiability(normType) || acc.balanceMinor < 0L
                val tintHex = when (normType) {
                    "CREDIT_CARD" -> "#E11D48"
                    "LOAN" -> "#D97706"
                    "DEBIT_CARD", "CARD" -> "#059669"
                    "CASH" -> "#475569"
                    else -> "#059669"
                }
                CleanCard(
                    padding = 14.dp,
                    onClick = { selectedDetailAccountId = acc.id },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            CategoryIconBadge(
                                iconKey = AccountTypeCatalog.iconKey(normType),
                                tintHex = tintHex,
                                size = 38.dp,
                            )
                            Column {
                                Text(acc.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                                val sub = listOfNotNull(
                                    acc.institution.takeIf { it.isNotBlank() },
                                    AccountTypeCatalog.displayLabel(normType),
                                    acc.lastFour.takeIf { it.isNotBlank() }?.let { "••$it" },
                                ).joinToString(" • ")
                                Text(sub, fontSize = 12.sp, color = MutedSlate)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = if (isDebtType && acc.balanceMinor < 0L) {
                                        "Owed ${MoneyFormatter.formatMinor(abs(acc.balanceMinor), acc.currency, masked = masked)}"
                                    } else {
                                        MoneyFormatter.formatMinor(acc.balanceMinor, acc.currency, masked = masked)
                                    },
                                    style = TabularStyle,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (acc.balanceMinor < 0L) RoseOutflow else SlateInk,
                                )
                                if (isDebtType && acc.balanceMinor == 0L) {
                                    Text("Settled", fontSize = 11.sp, color = EmeraldDark)
                                } else if (acc.currency != baseCurrency) {
                                    val fx = FxConverter.convertMinor(acc.balanceMinor, acc.currency, baseCurrency)
                                    Text(
                                        text = "≈ ${MoneyFormatter.formatMinor(fx, baseCurrency, masked = masked)}",
                                        style = TabularStyle,
                                        fontSize = 11.sp,
                                        color = MutedSlate,
                                    )
                                }
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                contentDescription = "View details",
                                tint = MutedSlate,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    if (isCreditCard && acc.creditLimitMinor > 0L) {
                        Spacer(Modifier.height(8.dp))
                        val usedMinor = if (acc.balanceMinor < 0L) abs(acc.balanceMinor) else 0L
                        val utilRatio = (usedMinor.toFloat() / acc.creditLimitMinor.toFloat()).coerceIn(0f, 1.5f)
                        val utilPct = (utilRatio * 100f).toInt()
                        val barColor = when {
                            utilPct >= 70 -> RoseOutflow
                            utilPct >= 30 -> AmberWarn
                            else -> EmeraldPrimary
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "Credit Used: ${MoneyFormatter.formatMinor(usedMinor, acc.currency, masked = masked)} / ${MoneyFormatter.formatMinor(acc.creditLimitMinor, acc.currency, masked = masked)}",
                                style = TabularStyle,
                                fontSize = 11.sp,
                                color = MutedSlate,
                            )
                            Text(
                                text = "$utilPct% used",
                                style = TabularStyle,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = barColor,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { utilRatio.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(999.dp)),
                            color = barColor,
                            trackColor = SlateTint,
                        )
                    }
                }
            }
        }

        // Restyled Dark-Emerald Payment Capture Card
        item {
            CleanCard(
                containerColor = EmeraldSoft,
                borderColor = EmeraldPrimary.copy(alpha = 0.30f),
                padding = 16.dp,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CategoryIconBadge("directions_transit", "#059669", size = 38.dp)
                        Column {
                            Text("Payment Capture", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                            Spacer(Modifier.height(2.dp))
                            StatusPill(
                                text = when {
                                    enabledListeners -> "Listener Active"
                                    supportsOsListener -> "Listener Ready"
                                    else -> "Share & Smart Paste ($gpayCapturedCount)"
                                },
                                containerColor = SurfaceCard,
                                contentColor = EmeraldDark,
                                dotColor = EmeraldPrimary,
                            )
                        }
                    }
                    Switch(
                        checked = vault.payAndTrackEnabled,
                        onCheckedChange = { vm.setPayAndTrackEnabled(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = EmeraldPrimary),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (supportsOsListener) {
                        "Automatically log payment notifications locally with duplicate suppression."
                    } else {
                        "Share any payment SMS or alert to LedgerFlow, or paste one directly below."
                    },
                    fontSize = 12.sp,
                    color = SlateInk,
                )
                Spacer(Modifier.height(10.dp))
                if (supportsOsListener) {
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, EmeraldPrimary.copy(alpha = 0.45f)),
                    ) {
                        Icon(Icons.Outlined.Contactless, contentDescription = null, tint = EmeraldPrimary)
                        Spacer(Modifier.width(6.dp))
                        Text("Notification Access", fontSize = 12.sp, color = SlateInk)
                    }
                } else {
                    Button(
                        onClick = { showSmartPaste = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                    ) {
                        Icon(Icons.Outlined.Contactless, contentDescription = null, tint = Color.White)
                        Spacer(Modifier.width(6.dp))
                        Text("Smart Paste Alert", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                }
            }
        }

        // Import Data Card (CSV / JSON)
        item {
            CleanCard(
                padding = 14.dp,
                onClick = { if (!importBusy) launchImportPicker() },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Outlined.FileUpload, contentDescription = null, tint = EmeraldPrimary)
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Import Data (CSV / JSON)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = SlateInk)
                        Text("Import transactions from other apps or bank statements", fontSize = 12.sp, color = MutedSlate)
                    }
                    StatusPill(if (importBusy) "Importing..." else "Browse", SlateTint, SlateInk)
                }
            }
        }
    }

    // Tap-to-open Account Detail Sheet (Balance Trendline + Account Transactions + Edit/Repay/Delete)
    val detailAccount = selectedDetailAccountId?.let { id -> accounts.firstOrNull { it.id == id } }
    if (detailAccount != null) {
        val normType = AccountTypeCatalog.normalize(detailAccount.accountType)
        val isCreditCard = normType == "CREDIT_CARD" || normType == "CARD"
        val isDebtType = AccountTypeCatalog.isLiability(normType) || detailAccount.balanceMinor < 0L
        val trend = remember(detailAccount, transactions) {
            AnalyticsEngine.computeAccountBalanceTrend(detailAccount, transactions, days = 30)
        }
        val accountTxs = remember(detailAccount.id, transactions) {
            transactions
                .filter { it.deletedAtEpochMs == null && (it.accountId == detailAccount.id || it.transferAccountId == detailAccount.id) }
                .take(15)
        }
        val positiveChange = trend.changeMinor >= 0L
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        ModalBottomSheet(
            onDismissRequest = { selectedDetailAccountId = null },
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
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        CategoryIconBadge(AccountTypeCatalog.iconKey(normType), if (isDebtType && detailAccount.balanceMinor < 0L) "#E11D48" else "#059669", size = 42.dp)
                        Column {
                            Text(detailAccount.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                            val sub = listOfNotNull(
                                detailAccount.institution.takeIf { it.isNotBlank() },
                                AccountTypeCatalog.displayLabel(normType),
                                detailAccount.lastFour.takeIf { it.isNotBlank() }?.let { "••$it" },
                            ).joinToString(" • ")
                            Text(sub, fontSize = 12.sp, color = MutedSlate)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = if (isDebtType && detailAccount.balanceMinor < 0L) {
                                "Owed ${MoneyFormatter.formatMinor(abs(detailAccount.balanceMinor), detailAccount.currency, masked = masked)}"
                            } else {
                                MoneyFormatter.formatMinor(detailAccount.balanceMinor, detailAccount.currency, masked = masked)
                            },
                            style = TabularStyle,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (detailAccount.balanceMinor < 0L) RoseOutflow else EmeraldDark,
                        )
                        if (isCreditCard && detailAccount.creditLimitMinor > 0L) {
                            Text(
                                text = "Limit ${MoneyFormatter.formatMinor(detailAccount.creditLimitMinor, detailAccount.currency, masked = masked)}",
                                fontSize = 11.sp,
                                color = MutedSlate,
                            )
                        }
                    }
                }

                // Balance Trendline Card
                CleanCard(containerColor = SlateTint, padding = 14.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("30-Day Balance Trend", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                        StatusPill(
                            text = MoneyFormatter.formatMinor(trend.changeMinor, detailAccount.currency, showPlus = true, masked = masked),
                            containerColor = if (positiveChange) EmeraldSoft else RoseSoft,
                            contentColor = if (positiveChange) EmeraldDark else RoseOutflow,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    BalanceTrendLineChart(
                        points = trend.points,
                        lineColor = if (positiveChange) EmeraldPrimary else RoseOutflow,
                        chartHeight = 104.dp,
                    )
                }

                // Actions inside Detail Sheet: Edit / Repay / Limit / Delete
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { pendingEditAccount = detailAccount },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp), tint = EmeraldPrimary)
                        Spacer(Modifier.width(5.dp))
                        Text("Edit", fontSize = 12.sp, color = SlateInk)
                    }
                    if (isCreditCard) {
                        OutlinedButton(
                            onClick = { pendingEditLimitAccount = detailAccount },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Limit", fontSize = 12.sp, color = EmeraldDark, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (isDebtType && detailAccount.balanceMinor < 0L && assetAccounts.isNotEmpty()) {
                        Button(
                            onClick = { pendingRepayAccount = detailAccount },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                        ) {
                            Text("Repay", fontSize = 12.sp)
                        }
                    }
                    OutlinedButton(
                        onClick = { pendingDeleteAccount = detailAccount },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, RoseOutflow.copy(alpha = 0.4f)),
                    ) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete account", tint = RoseOutflow, modifier = Modifier.size(16.dp))
                    }
                }

                // Account-specific transactions
                Text("Recent Activity (${accountTxs.size})", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                if (accountTxs.isEmpty()) {
                    Text("No transactions recorded on this account yet.", fontSize = 12.sp, color = MutedSlate)
                } else {
                    CleanCard(containerColor = SlateTint, padding = 4.dp) {
                        accountTxs.forEachIndexed { idx, tx ->
                            if (idx > 0) HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(horizontal = 10.dp))
                            val incomingTransfer = tx.txType == "TRANSFER" && tx.transferAccountId == detailAccount.id
                            val signedForAcc = when {
                                incomingTransfer -> abs(FxConverter.convertMinor(tx.amountMinor, tx.currency, detailAccount.currency))
                                tx.txType == "INCOME" -> abs(FxConverter.convertMinor(tx.amountMinor, tx.currency, detailAccount.currency))
                                else -> -abs(FxConverter.convertMinor(tx.amountMinor, tx.currency, detailAccount.currency))
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 9.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(tx.merchant, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = SlateInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(dateFmt.format(Date(tx.occurredAtEpochMs)), fontSize = 11.sp, color = MutedSlate)
                                }
                                Text(
                                    text = MoneyFormatter.formatMinor(signedForAcc, detailAccount.currency, showPlus = signedForAcc > 0L, masked = masked),
                                    style = TabularStyle,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (signedForAcc >= 0L) EmeraldDark else RoseOutflow,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    pendingEditAccount?.let { editAcc ->
        val normType = AccountTypeCatalog.normalize(editAcc.accountType)
        val isCash = normType == "CASH"
        val isCredit = normType == "CREDIT_CARD" || normType == "CARD"
        val supportsLastFour = AccountTypeCatalog.supportsLastFour(normType)
        val isJpy = editAcc.currency.equals("JPY", ignoreCase = true)

        var editName by remember(editAcc.id) { mutableStateOf(editAcc.name) }
        var editInst by remember(editAcc.id) { mutableStateOf(editAcc.institution) }
        var editLast4 by remember(editAcc.id) { mutableStateOf(editAcc.lastFour) }
        var editLimit by remember(editAcc.id) {
            mutableStateOf(
                if (editAcc.creditLimitMinor > 0L) {
                    if (isJpy) editAcc.creditLimitMinor.toString()
                    else String.format(Locale.US, "%.2f", editAcc.creditLimitMinor / 100.0)
                } else ""
            )
        }
        val parsedLimit = if (editLimit.isBlank()) 0L else MoneyFormatter.parseDecimalToMinor(editLimit, editAcc.currency)

        AlertDialog(
            onDismissRequest = { pendingEditAccount = null },
            title = { Text("Edit Account") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = editName, onValueChange = { editName = it }, label = { Text("Name") }, singleLine = true)
                    if (!isCash) {
                        OutlinedTextField(value = editInst, onValueChange = { editInst = it }, label = { Text("Institution / Bank") }, singleLine = true)
                    }
                    if (supportsLastFour) {
                        OutlinedTextField(
                            value = editLast4,
                            onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) editLast4 = it },
                            label = { Text("Last 4 digits") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                    }
                    if (isCredit) {
                        OutlinedTextField(
                            value = editLimit,
                            onValueChange = { editLimit = MoneyFormatter.sanitizeDecimalInput(it, editAcc.currency, allowNegative = false) },
                            label = { Text("Credit Limit (${editAcc.currency})") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = editName.isNotBlank() && (!isCredit || parsedLimit != null),
                    onClick = {
                        vm.updateAccount(
                            accountId = editAcc.id,
                            name = editName,
                            institution = editInst,
                            lastFour = editLast4,
                            creditLimitMinor = (parsedLimit ?: 0L).coerceAtLeast(0L),
                        )
                        pendingEditAccount = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { pendingEditAccount = null }) { Text("Cancel") } },
        )
    }

    pendingDeleteAccount?.let { acc ->
        AlertDialog(
            onDismissRequest = { pendingDeleteAccount = null },
            title = { Text("Remove \"${acc.name}\"?") },
            text = {
                Text(
                    "Removes this account from active balances and syncs deletion if cloud sync is enabled.",
                    fontSize = 13.sp,
                    color = MutedSlate,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.deleteAccount(acc.id)
                        if (selectedDetailAccountId == acc.id) selectedDetailAccountId = null
                        pendingDeleteAccount = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoseOutflow),
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteAccount = null }) { Text("Cancel") } },
        )
    }

    pendingRepayAccount?.let { debtAcc ->
        var fromAccId by remember(debtAcc.id, assetAccounts) {
            mutableStateOf(assetAccounts.firstOrNull()?.id ?: "")
        }
        val initialOwed = abs(debtAcc.balanceMinor)
        var repayAmountText by remember(debtAcc.id) {
            val decimals = if (baseCurrency == "JPY") 0 else 2
            val div = if (decimals == 0) 1.0 else 100.0
            val defaultVal = FxConverter.convertMinor(initialOwed, debtAcc.currency, baseCurrency)
            val txt = if (decimals == 0) defaultVal.toString() else String.format(Locale.US, "%.2f", defaultVal / div)
            mutableStateOf(txt)
        }
        val parsedRepay = MoneyFormatter.parseDecimalToMinor(repayAmountText, baseCurrency) ?: 0L

        AlertDialog(
            onDismissRequest = { pendingRepayAccount = null },
            title = { Text("Repay ${debtAcc.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusPill(
                        text = "Owed: ${MoneyFormatter.formatMinor(initialOwed, debtAcc.currency)}",
                        containerColor = RoseSoft,
                        contentColor = RoseOutflow,
                    )
                    Text("Pay From", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MutedSlate)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        assetAccounts.forEach { src ->
                            FilterChip(
                                selected = fromAccId == src.id,
                                onClick = { fromAccId = src.id },
                                label = {
                                    Text("${src.name} (${MoneyFormatter.formatMinor(src.balanceMinor, src.currency)})", fontSize = 11.sp)
                                },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = repayAmountText,
                        onValueChange = { repayAmountText = MoneyFormatter.sanitizeDecimalInput(it, baseCurrency, allowNegative = false) },
                        label = { Text("Repayment Amount ($baseCurrency)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = fromAccId.isNotBlank() && parsedRepay > 0L,
                    onClick = {
                        vm.repayDebtAccount(fromAccId, debtAcc.id, parsedRepay)
                        pendingRepayAccount = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                ) { Text("Record Payment") }
            },
            dismissButton = { TextButton(onClick = { pendingRepayAccount = null }) { Text("Cancel") } },
        )
    }

    if (pendingEditLimitAccount != null) {
        val cardAcc = pendingEditLimitAccount!!
        val isJpy = cardAcc.currency.equals("JPY", ignoreCase = true)
        var limitText by remember(cardAcc.id) {
            mutableStateOf(
                if (cardAcc.creditLimitMinor > 0L) {
                    if (isJpy) cardAcc.creditLimitMinor.toString()
                    else String.format(Locale.US, "%.2f", cardAcc.creditLimitMinor / 100.0)
                } else ""
            )
        }
        val parsedLimit = if (limitText.isBlank()) 0L else MoneyFormatter.parseDecimalToMinor(limitText, cardAcc.currency)

        AlertDialog(
            onDismissRequest = { pendingEditLimitAccount = null },
            title = { Text("Credit Limit • ${cardAcc.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Enter the card's total credit limit in ${cardAcc.currency} (or leave blank to clear).",
                        fontSize = 12.sp,
                        color = MutedSlate,
                    )
                    OutlinedTextField(
                        value = limitText,
                        onValueChange = { limitText = MoneyFormatter.sanitizeDecimalInput(it, cardAcc.currency, allowNegative = false) },
                        label = { Text("Credit Limit (${cardAcc.currency})") },
                        placeholder = { Text(if (isJpy) "100000" else "50000.00") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = parsedLimit != null,
                    onClick = {
                        vm.updateAccountCreditLimit(cardAcc.id, (parsedLimit ?: 0L).coerceAtLeast(0L))
                        pendingEditLimitAccount = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                ) { Text("Save Limit") }
            },
            dismissButton = { TextButton(onClick = { pendingEditLimitAccount = null }) { Text("Cancel") } },
        )
    }

    if (showAddAccount) {
        var accountType by remember { mutableStateOf("CASH") }
        var name by remember { mutableStateOf("") }
        var institution by remember { mutableStateOf("") }
        var balance by remember { mutableStateOf("") }
        var lastFour by remember { mutableStateOf("") }
        var creditLimit by remember { mutableStateOf("") }

        val isCash = accountType == "CASH"
        val isDebit = accountType == "DEBIT_CARD"
        val isCredit = accountType == "CREDIT_CARD"
        val isLoan = accountType == "LOAN"
        val supportsLastFour = AccountTypeCatalog.supportsLastFour(accountType)
        val parsedBalance = if (balance.isBlank()) 0L else MoneyFormatter.parseDecimalToMinor(balance, baseCurrency)
        val parsedCreditLimit = if (creditLimit.isBlank()) 0L else MoneyFormatter.parseDecimalToMinor(creditLimit, baseCurrency)
        val canSave = (if (isCash) true else name.isNotBlank()) &&
            parsedBalance != null &&
            (!isCredit || parsedCreditLimit != null)

        AlertDialog(
            onDismissRequest = { showAddAccount = false },
            title = { Text("Add Account") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AccountTypeCatalog.creatableTypes.forEach { t ->
                            FilterChip(
                                selected = accountType == t,
                                onClick = {
                                    accountType = t
                                    balance = MoneyFormatter.sanitizeDecimalInput(balance, baseCurrency, allowNegative = false)
                                },
                                label = { Text(AccountTypeCatalog.displayLabel(t), fontSize = 11.sp) },
                            )
                        }
                    }

                    StatusPill("Currency locked: $baseCurrency", EmeraldSoft, EmeraldDark)

                    if (isCash) {
                        OutlinedTextField(
                            value = balance,
                            onValueChange = { balance = MoneyFormatter.sanitizeDecimalInput(it, baseCurrency, allowNegative = false) },
                            label = { Text("Cash Amount ($baseCurrency)") },
                            placeholder = { Text(if (baseCurrency == "JPY") "0" else "0.00") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Label (optional)") },
                            placeholder = { Text("Cash Wallet") },
                            singleLine = true,
                        )
                    } else {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = {
                                Text(
                                    when {
                                        isDebit || isCredit -> "Card Name"
                                        isLoan -> "Loan Name"
                                        else -> "Account Name"
                                    }
                                )
                            },
                            placeholder = {
                                Text(
                                    when {
                                        isDebit -> "e.g. HDFC Debit Card"
                                        isCredit -> "e.g. SBI Cashback Card"
                                        isLoan -> "e.g. Home Loan / Car Loan"
                                        else -> "e.g. Main Bank Account"
                                    }
                                )
                            },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = institution,
                            onValueChange = { institution = it },
                            label = {
                                Text(
                                    when {
                                        isLoan -> "Lender / Bank (optional)"
                                        isDebit || isCredit -> "Bank / Issuer (optional)"
                                        else -> "Bank / Institution (optional)"
                                    }
                                )
                            },
                            singleLine = true,
                        )
                        if (supportsLastFour) {
                            OutlinedTextField(
                                value = lastFour,
                                onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) lastFour = it },
                                label = { Text("Last 4 digits (optional)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                            )
                        }
                        OutlinedTextField(
                            value = balance,
                            onValueChange = { balance = MoneyFormatter.sanitizeDecimalInput(it, baseCurrency, allowNegative = false) },
                            label = {
                                Text(
                                    when {
                                        isCredit -> "Current Amount Owed ($baseCurrency)"
                                        isLoan -> "Remaining Loan Owed ($baseCurrency)"
                                        isDebit -> "Available Card Balance ($baseCurrency)"
                                        else -> "Current Balance ($baseCurrency)"
                                    }
                                )
                            },
                            placeholder = { Text(if (baseCurrency == "JPY") "0" else "0.00") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                        )
                        if (isCredit) {
                            OutlinedTextField(
                                value = creditLimit,
                                onValueChange = { creditLimit = MoneyFormatter.sanitizeDecimalInput(it, baseCurrency, allowNegative = false) },
                                label = { Text("Credit Limit (optional, $baseCurrency)") },
                                placeholder = { Text(if (baseCurrency == "JPY") "100000" else "50000.00") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                            )
                        }
                        Text(
                            text = when {
                                isCredit -> "Purchases increase amount owed; card repayments from bank/cash reduce it."
                                isLoan -> "Tracked as debt owed; repayments reduce your remaining loan balance."
                                isDebit -> "Spending deducts directly from your available debit balance."
                                else -> "Tracked as a positive cash/bank asset in net worth."
                            },
                            fontSize = 11.sp,
                            color = MutedSlate,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = canSave,
                    onClick = {
                        val rawMinor = parsedBalance ?: 0L
                        val normalizedMinor = AccountTypeCatalog.normalizeOpeningBalanceMinor(accountType, rawMinor)
                        val finalName = if (isCash) name.ifBlank { "Cash Wallet" } else name
                        vm.addAccount(
                            name = finalName,
                            institution = if (isCash) "" else institution,
                            type = accountType,
                            currency = baseCurrency,
                            balanceMinor = normalizedMinor,
                            lastFour = if (supportsLastFour) lastFour else "",
                            creditLimitMinor = if (isCredit) (parsedCreditLimit ?: 0L).coerceAtLeast(0L) else 0L,
                        )
                        showAddAccount = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showAddAccount = false }) { Text("Cancel") } },
        )
    }

    if (showSmartPaste) {
        var pasteText by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showSmartPaste = false },
            title = { Text("Smart Paste Payment Alert") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Paste a payment SMS or notification alert (or use Android Share → LedgerFlow directly from any app).",
                        fontSize = 12.sp,
                        color = MutedSlate,
                    )
                    OutlinedTextField(
                        value = pasteText,
                        onValueChange = { pasteText = it },
                        label = { Text("Payment notification or SMS") },
                        placeholder = { Text("Paste text containing amount & merchant") },
                        minLines = 2,
                        maxLines = 4,
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = pasteText.isNotBlank(),
                    onClick = {
                        vm.capturePaymentAlertText(pasteText, originNote = "Captured via Smart Paste")
                        showSmartPaste = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                ) { Text("Extract & Save") }
            },
            dismissButton = { TextButton(onClick = { showSmartPaste = false }) { Text("Cancel") } },
        )
    }
}
