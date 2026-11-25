package com.ledgerflow.app.ui.screens

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Contactless
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Wallet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import com.ledgerflow.app.domain.FxConverter
import com.ledgerflow.app.domain.MoneyFormatter
import com.ledgerflow.app.service.PayAndTrackNotificationListenerService
import com.ledgerflow.app.ui.LedgerViewModel
import com.ledgerflow.app.ui.components.CategoryIconBadge
import com.ledgerflow.app.ui.components.CleanCard
import com.ledgerflow.app.ui.components.SectionHeader
import com.ledgerflow.app.ui.components.StatusPill
import com.ledgerflow.app.ui.theme.AlabasterBg
import com.ledgerflow.app.ui.theme.AmberSoft
import com.ledgerflow.app.ui.theme.AmberWarn
import com.ledgerflow.app.ui.theme.BorderSubtle
import com.ledgerflow.app.ui.theme.EmeraldDark
import com.ledgerflow.app.ui.theme.EmeraldPrimary
import com.ledgerflow.app.ui.theme.EmeraldSoft
import com.ledgerflow.app.ui.theme.MutedSlate
import com.ledgerflow.app.ui.theme.RoseSoft
import com.ledgerflow.app.ui.theme.RoseOutflow
import com.ledgerflow.app.ui.theme.SkySoft
import com.ledgerflow.app.ui.theme.SkySync
import com.ledgerflow.app.ui.theme.SlateInk
import com.ledgerflow.app.ui.theme.SlateTint
import com.ledgerflow.app.ui.theme.TabularStyle

@Composable
fun AccountsHubScreen(
    vm: LedgerViewModel,
    onOpenPrivacyVault: () -> Unit,
) {
    val context = LocalContext.current
    val accounts by vm.accounts.collectAsState()
    val transactions by vm.allTransactions.collectAsState()
    val vault by vm.vaultState.collectAsState()
    val masked = vault.hideBalances
    val baseCurrency = vault.baseCurrency
    val lifecycleOwner = LocalLifecycleOwner.current

    fun checkOsListener(): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        val cn = ComponentName(context, PayAndTrackNotificationListenerService::class.java)
        return flat.contains(cn.flattenToString()) || flat.contains(cn.flattenToShortString())
    }

    var enabledListeners by remember { mutableStateOf(checkOsListener()) }
    DisposableEffect(lifecycleOwner, vault.payAndTrackEnabled) {
        enabledListeners = checkOsListener()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabledListeners = checkOsListener()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val gpayCapturedCount = remember(transactions) {
        transactions.count { it.sourceTag == "GPAY_AUTO" }
    }

    val openBankingAccounts = remember(accounts) { accounts.filter { it.sourceType == "OPEN_BANKING" } }
    val manualAccounts = remember(accounts) { accounts.filter { it.sourceType != "OPEN_BANKING" } }

    var showLinkBank by remember { mutableStateOf(false) }
    var showAddWallet by remember { mutableStateOf(false) }

    val csvPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        vm.importCsvUri(uri)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AlabasterBg),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Accounts & Sync", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                    Text("${accounts.size} active accounts • Local-first Room", fontSize = 12.sp, color = MutedSlate)
                }
                Surface(
                    onClick = { showLinkBank = true },
                    shape = RoundedCornerShape(999.dp),
                    color = EmeraldPrimary,
                ) {
                    Text(
                        text = "+ Link Bank",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
        }

        // Pay & Track Auto-Capture Featured Card
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
                            Text("Pay & Track Auto-Capture", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                            Spacer(Modifier.height(2.dp))
                            StatusPill(
                                text = if (enabledListeners) "Listener Active" else "Local Simulation Ready",
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
                CleanCard(padding = 12.dp) {
                    Text(
                        text = "$gpayCapturedCount transactions captured automatically via Google Wallet listener with duplicate suppression.",
                        fontSize = 12.sp,
                        color = SlateInk,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Outlined.Contactless, contentDescription = null, tint = SkySync)
                        Spacer(Modifier.width(6.dp))
                        Text("OS Listener", fontSize = 12.sp, color = SlateInk)
                    }
                    Button(
                        onClick = { vm.simulateGooglePayTap() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SkySync),
                    ) {
                        Text("Test GPay Tap", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Open Banking Connections
        item {
            SectionHeader(title = "Connected Open Banking", actionText = "+ Manual Wallet", onActionClick = { showAddWallet = true })
        }

        items(openBankingAccounts, key = { it.id }) { acc ->
            CleanCard(padding = 14.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CategoryIconBadge("bank", "#059669", size = 38.dp)
                        Column {
                            Text(acc.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                            Text(acc.institution.ifBlank { "Open Banking" }, fontSize = 12.sp, color = MutedSlate)
                        }
                    }
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
                            Text("≈ ${MoneyFormatter.formatMinor(fx, baseCurrency, masked = masked)}", style = TabularStyle, fontSize = 11.sp, color = MutedSlate)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (acc.syncStatus == "RECONNECT") {
                    Surface(shape = RoundedCornerShape(10.dp), color = RoseSoft, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Consent refresh needed", fontSize = 12.sp, color = RoseOutflow, fontWeight = FontWeight.Medium)
                            Button(
                                onClick = { vm.reconnectAccount(acc.id) },
                                colors = ButtonDefaults.buttonColors(containerColor = RoseOutflow),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(30.dp),
                            ) { Text("Reconnect", fontSize = 11.sp) }
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusPill("Verified", EmeraldSoft, EmeraldDark)
                        StatusPill("Auto-Categorize ON", SlateTint, MutedSlate)
                    }
                }
            }
        }

        // Manual Wallets
        item {
            SectionHeader(title = "Manual Wallets & Cash")
        }
        items(manualAccounts, key = { it.id }) { acc ->
            CleanCard(padding = 14.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CategoryIconBadge("payments", "#475569", size = 36.dp)
                        Column {
                            Text(acc.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = SlateInk)
                            Text(acc.institution.ifBlank { acc.accountType }, fontSize = 12.sp, color = MutedSlate)
                        }
                    }
                    Text(
                        text = MoneyFormatter.formatMinor(acc.balanceMinor, acc.currency, masked = masked),
                        style = TabularStyle,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateInk,
                    )
                }
            }
        }

        // Import / Export & Security Vault Entry
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CleanCard(
                    modifier = Modifier.weight(1f),
                    padding = 14.dp,
                    onClick = { csvPickerLauncher.launch(arrayOf("text/*", "text/csv", "application/*")) },
                ) {
                    Icon(Icons.Outlined.FileUpload, contentDescription = null, tint = SkySync)
                    Spacer(Modifier.height(6.dp))
                    Text("Import CSV", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = SlateInk)
                    Text("Local bank file parser", fontSize = 11.sp, color = MutedSlate)
                }
                CleanCard(
                    modifier = Modifier.weight(1f),
                    padding = 14.dp,
                    onClick = {
                        vm.exportAndShare(asCsv = false) { intent ->
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        }
                    },
                ) {
                    Icon(Icons.Outlined.FileDownload, contentDescription = null, tint = EmeraldPrimary)
                    Spacer(Modifier.height(6.dp))
                    Text("Export Vault", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = SlateInk)
                    Text("Full JSON snapshot", fontSize = 11.sp, color = MutedSlate)
                }
            }
        }

        item {
            CleanCard(containerColor = EmeraldSoft, borderColor = Color(0xFFA7F3D0), padding = 14.dp, onClick = onOpenPrivacyVault) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.Security, contentDescription = null, tint = EmeraldDark)
                        Column {
                            Text("Privacy, Cloud Sync & App Lock", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                            Text(
                                text = if (vault.userEmail != null) "Supabase signed in: ${vault.userEmail}" else "Local vault • Sign in optionally for sync",
                                fontSize = 12.sp,
                                color = EmeraldDark,
                            )
                        }
                    }
                    StatusPill("Open →", Color.White, EmeraldDark)
                }
            }
        }
    }

    if (showLinkBank) {
        var provider by remember { mutableStateOf("Ally Bank") }
        var currency by remember { mutableStateOf(baseCurrency) }

        AlertDialog(
            onDismissRequest = { showLinkBank = false },
            title = { Text("Connect Sandbox Bank") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Uses our free Render Open Banking Sandbox (with zero-downtime offline fallback) and never requires paid aggregator keys.", fontSize = 12.sp, color = MutedSlate)
                    OutlinedTextField(value = provider, onValueChange = { provider = it }, label = { Text("Institution Name") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("USD", "EUR", "GBP", "INR").forEach { c ->
                            FilterChip(selected = currency == c, onClick = { currency = c }, label = { Text(c, fontSize = 11.sp) })
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.linkSandboxBank(provider, currency)
                        showLinkBank = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                ) { Text("Connect") }
            },
            dismissButton = { TextButton(onClick = { showLinkBank = false }) { Text("Cancel") } },
        )
    }

    if (showAddWallet) {
        var name by remember { mutableStateOf("") }
        var balance by remember { mutableStateOf("0") }
        var currency by remember { mutableStateOf(baseCurrency) }

        AlertDialog(
            onDismissRequest = { showAddWallet = false },
            title = { Text("Add Manual Wallet") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Wallet Name") }, singleLine = true)
                    OutlinedTextField(value = balance, onValueChange = { balance = it }, label = { Text("Opening Balance") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("USD", "EUR", "GBP", "INR").forEach { c ->
                            FilterChip(selected = currency == c, onClick = { currency = c }, label = { Text(c, fontSize = 11.sp) })
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val minor = MoneyFormatter.parseDecimalToMinor(balance) ?: 0L
                        vm.addAccount(name, "Manual Wallet", "CASH", currency, minor, "")
                        showAddWallet = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showAddWallet = false }) { Text("Cancel") } },
        )
    }
}
