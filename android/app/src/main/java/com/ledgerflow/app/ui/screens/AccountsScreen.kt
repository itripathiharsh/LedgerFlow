package com.ledgerflow.app.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ledgerflow.app.data.local.AccountEntity
import com.ledgerflow.app.domain.FxConverter
import com.ledgerflow.app.domain.MoneyFormatter
import com.ledgerflow.app.service.PayAndTrackCapture
import com.ledgerflow.app.ui.LedgerViewModel
import com.ledgerflow.app.ui.components.CategoryIconBadge
import com.ledgerflow.app.ui.components.CleanCard
import com.ledgerflow.app.ui.components.EmptyPlaceholder
import com.ledgerflow.app.ui.components.SectionHeader
import com.ledgerflow.app.ui.components.StatusPill
import com.ledgerflow.app.ui.theme.AlabasterBg
import com.ledgerflow.app.ui.theme.EmeraldDark
import com.ledgerflow.app.ui.theme.EmeraldPrimary
import com.ledgerflow.app.ui.theme.EmeraldSoft
import com.ledgerflow.app.ui.theme.MutedSlate
import com.ledgerflow.app.ui.theme.RoseOutflow
import com.ledgerflow.app.ui.theme.SkySoft
import com.ledgerflow.app.ui.theme.SkySync
import com.ledgerflow.app.ui.theme.SlateInk
import com.ledgerflow.app.ui.theme.SlateTint
import com.ledgerflow.app.ui.theme.TabularStyle

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
    var showSmartPaste by remember { mutableStateOf(false) }

    val csvPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        vm.importCsvUri(uri)
    }

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
        item {
            SectionHeader(title = "Wallets, Cards & Bank Accounts")
        }
        if (accounts.isEmpty()) {
            item {
                EmptyPlaceholder("No active accounts", "Tap + Add Account to create a wallet, card, or bank account.")
            }
        } else {
            items(accounts, key = { it.id }) { acc ->
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
                                iconKey = if (acc.accountType == "CASH") "payments" else "bank",
                                tintHex = if (acc.accountType == "CASH") "#475569" else "#059669",
                                size = 38.dp,
                            )
                            Column {
                                Text(acc.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                                val sub = listOfNotNull(
                                    acc.institution.takeIf { it.isNotBlank() },
                                    acc.accountType,
                                    acc.lastFour.takeIf { it.isNotBlank() }?.let { "••$it" },
                                ).joinToString(" • ")
                                Text(sub, fontSize = 12.sp, color = MutedSlate)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = MoneyFormatter.formatMinor(acc.balanceMinor, acc.currency, masked = masked),
                                    style = TabularStyle,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SlateInk,
                                )
                                if (acc.currency != baseCurrency) {
                                    val fx = FxConverter.convertMinor(acc.balanceMinor, acc.currency, baseCurrency)
                                    Text(
                                        text = "≈ ${MoneyFormatter.formatMinor(fx, baseCurrency, masked = masked)}",
                                        style = TabularStyle,
                                        fontSize = 11.sp,
                                        color = MutedSlate,
                                    )
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

        // Import CSV Card
        item {
            CleanCard(
                padding = 14.dp,
                onClick = { csvPickerLauncher.launch(arrayOf("text/*", "text/csv", "application/*")) },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Outlined.FileUpload, contentDescription = null, tint = SkySync)
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Import Bank CSV", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = SlateInk)
                        Text("Parse CSV statements directly on your device", fontSize = 12.sp, color = MutedSlate)
                    }
                    StatusPill("Browse", SlateTint, SlateInk)
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

    if (showAddAccount) {
        var accountType by remember { mutableStateOf("CASH") }
        var name by remember { mutableStateOf("") }
        var institution by remember { mutableStateOf("") }
        var balance by remember { mutableStateOf("") }
        var lastFour by remember { mutableStateOf("") }

        val isCash = accountType == "CASH"
        val isCard = accountType == "CARD"
        val canSave = if (isCash) true else name.isNotBlank()

        AlertDialog(
            onDismissRequest = { showAddAccount = false },
            title = { Text("Add Account") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf("CASH", "CHECKING", "SAVINGS", "CARD").forEach { t ->
                            FilterChip(
                                selected = accountType == t,
                                onClick = { accountType = t },
                                label = { Text(t, fontSize = 11.sp) },
                            )
                        }
                    }

                    StatusPill("Currency locked: $baseCurrency", EmeraldSoft, EmeraldDark)

                    if (isCash) {
                        OutlinedTextField(
                            value = balance,
                            onValueChange = { balance = it },
                            label = { Text("Cash Amount ($baseCurrency)") },
                            placeholder = { Text("0.00") },
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
                            label = { Text(if (isCard) "Card Name" else "Account Name") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = institution,
                            onValueChange = { institution = it },
                            label = { Text(if (isCard) "Bank / Issuer (optional)" else "Bank / Institution (optional)") },
                            singleLine = true,
                        )
                        if (isCard) {
                            OutlinedTextField(
                                value = lastFour,
                                onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) lastFour = it },
                                label = { Text("Last 4 digits (optional)") },
                                singleLine = true,
                            )
                        }
                        OutlinedTextField(
                            value = balance,
                            onValueChange = { balance = it },
                            label = { Text("Opening Balance ($baseCurrency)") },
                            placeholder = { Text("0.00") },
                            singleLine = true,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = canSave,
                    onClick = {
                        val minor = MoneyFormatter.parseDecimalToMinor(balance) ?: 0L
                        val finalName = if (isCash) name.ifBlank { "Cash Wallet" } else name
                        vm.addAccount(
                            name = finalName,
                            institution = if (isCash) "" else institution,
                            type = accountType,
                            currency = baseCurrency,
                            balanceMinor = minor,
                            lastFour = if (isCard) lastFour else "",
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
