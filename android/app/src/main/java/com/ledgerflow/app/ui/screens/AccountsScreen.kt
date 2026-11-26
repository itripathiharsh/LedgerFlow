package com.ledgerflow.app.ui.screens

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.material.icons.outlined.Contactless
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ledgerflow.app.data.local.AccountEntity
import com.ledgerflow.app.domain.AccountTypeCatalog
import com.ledgerflow.app.domain.AnalyticsCalculator
import com.ledgerflow.app.domain.FxConverter
import com.ledgerflow.app.domain.MoneyFormatter
import com.ledgerflow.app.service.PayAndTrackCapture
import com.ledgerflow.app.ui.LedgerViewModel
import com.ledgerflow.app.ui.components.CategoryIconBadge
import com.ledgerflow.app.ui.components.CleanCard
import com.ledgerflow.app.ui.components.EmptyPlaceholder
import com.ledgerflow.app.ui.components.SectionHeader
import com.ledgerflow.app.ui.components.StatusPill
import com.ledgerflow.app.ui.components.rememberDataImportPicker
import com.ledgerflow.app.ui.theme.AlabasterBg
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
import com.ledgerflow.app.ui.theme.TabularStyle
import kotlin.math.abs

@Composable
fun AccountsScreen(
    vm: LedgerViewModel,
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
    var pendingDeleteAccount by remember { mutableStateOf<AccountEntity?>(null) }
    var pendingRepayAccount by remember { mutableStateOf<AccountEntity?>(null) }
    var showSmartPaste by remember { mutableStateOf(false) }
    val importBusy by vm.importInProgress.collectAsState()
    val accountSummary = remember(accounts, baseCurrency) {
        AnalyticsCalculator.computeAccountSummary(accounts, baseCurrency)
    }
    val assetAccounts = remember(accounts) {
        accounts.filter { !AccountTypeCatalog.isLiability(it.accountType) }
    }

    val launchImportPicker = rememberDataImportPicker(
        onUriSelected = { vm.importCsvUri(it) },
        onLaunchFailed = { vm.showMessage(it) },
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AlabasterBg),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Accounts", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                    Text("${accounts.size} active • $baseCurrency", fontSize = 12.sp, color = MutedSlate)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Surface(
                        onClick = { showAddAccount = true },
                        shape = RoundedCornerShape(999.dp),
                        color = EmeraldPrimary,
                    ) {
                        Text(
                            text = "+ Add Account",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = SlateInk)
                    }
                }
            }
        }

        // Accounts List
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
            SectionHeader(title = "Wallets, Cards, Loans & Banks")
        }
        if (accounts.isEmpty()) {
            item {
                EmptyPlaceholder("No active accounts", "Tap + Add Account to create a wallet, debit/credit card, loan, or bank account.")
            }
        } else {
            items(accounts, key = { it.id }) { acc ->
                val normType = AccountTypeCatalog.normalize(acc.accountType)
                val isDebtType = AccountTypeCatalog.isLiability(normType) || acc.balanceMinor < 0L
                val tintHex = when (normType) {
                    "CREDIT_CARD" -> "#E11D48"
                    "LOAN" -> "#D97706"
                    "DEBIT_CARD", "CARD" -> "#0284C7"
                    "CASH" -> "#475569"
                    else -> "#059669"
                }
                CleanCard(padding = 14.dp) {
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
                            if (isDebtType && acc.balanceMinor < 0L && assetAccounts.isNotEmpty()) {
                                TextButton(
                                    onClick = { pendingRepayAccount = acc },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                ) {
                                    Text("Pay", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = EmeraldPrimary)
                                }
                            }
                            IconButton(
                                onClick = { pendingDeleteAccount = acc },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteOutline,
                                    contentDescription = "Delete account",
                                    tint = MutedSlate,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Pay & Track Capture Card
        item {
            CleanCard(containerColor = SkySoft, borderColor = Color(0xFFBAE6FD), padding = 16.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CategoryIconBadge("directions_transit", "#0284C7", size = 38.dp)
                        Column {
                            Text("Payment Capture", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                            Spacer(Modifier.height(2.dp))
                            StatusPill(
                                text = when {
                                    enabledListeners -> "Listener Active"
                                    supportsOsListener -> "Listener Ready"
                                    else -> "Share & Smart Paste ($gpayCapturedCount)"
                                },
                                containerColor = EmeraldSoft,
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
                    ) {
                        Icon(Icons.Outlined.Contactless, contentDescription = null, tint = SkySync)
                        Spacer(Modifier.width(6.dp))
                        Text("Notification Access", fontSize = 12.sp, color = SlateInk)
                    }
                } else {
                    Button(
                        onClick = { showSmartPaste = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SkySync),
                    ) {
                        Icon(Icons.Outlined.Contactless, contentDescription = null, tint = Color.White)
                        Spacer(Modifier.width(6.dp))
                        Text("Smart Paste Alert", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Import Data Card (CSV / TSV / JSON)
        item {
            CleanCard(
                padding = 14.dp,
                onClick = { if (!importBusy) launchImportPicker() },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Outlined.FileUpload, contentDescription = null, tint = SkySync)
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Import Data (CSV / JSON)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = SlateInk)
                        Text("Import transactions from other apps or bank statements", fontSize = 12.sp, color = MutedSlate)
                    }
                    StatusPill(if (importBusy) "Importing..." else "Browse", SlateTint, SlateInk)
                }
            }
        }
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
            val txt = if (decimals == 0) defaultVal.toString() else String.format(java.util.Locale.US, "%.2f", defaultVal / div)
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

    if (showAddAccount) {
        var accountType by remember { mutableStateOf("CASH") }
        var name by remember { mutableStateOf("") }
        var institution by remember { mutableStateOf("") }
        var balance by remember { mutableStateOf("") }
        var lastFour by remember { mutableStateOf("") }

        val isCash = accountType == "CASH"
        val isDebit = accountType == "DEBIT_CARD"
        val isCredit = accountType == "CREDIT_CARD"
        val isLoan = accountType == "LOAN"
        val supportsLastFour = AccountTypeCatalog.supportsLastFour(accountType)
        val parsedBalance = if (balance.isBlank()) 0L else MoneyFormatter.parseDecimalToMinor(balance, baseCurrency)
        val canSave = (if (isCash) true else name.isNotBlank()) && parsedBalance != null

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
