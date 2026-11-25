package com.ledgerflow.app.ui.screens

import android.content.Intent
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgerflow.app.ui.LedgerViewModel
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
import com.ledgerflow.app.ui.theme.SkySoft
import com.ledgerflow.app.ui.theme.SkySync
import com.ledgerflow.app.ui.theme.SlateInk
import com.ledgerflow.app.ui.theme.SlateTint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PrivacyVaultScreen(
    vm: LedgerViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val vault by vm.vaultState.collectAsState()
    val pending by vm.pendingSyncCount.collectAsState()
    val syncBusy by vm.syncInProgress.collectAsState()
    val isOnline by vm.isOnline.collectAsState()
    val updateChecking by vm.updateCheckInProgress.collectAsState()

    var showPinDialog by remember { mutableStateOf(false) }
    var showAuthDialog by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    val fmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.US) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AlabasterBg),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = SlateInk)
                    }
                    Text(
                        text = "Privacy & Vault",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateInk,
                        maxLines = 1,
                        overflow = TextOverflow.Visible,
                    )
                }
                StatusPill("Protected", EmeraldSoft, EmeraldDark, dotColor = EmeraldPrimary)
            }
        }

        // Hero Vault Security Card
        item {
            CleanCard(containerColor = EmeraldSoft, borderColor = Color(0xFFA7F3D0), padding = 16.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.Shield, contentDescription = null, tint = EmeraldDark)
                    Column {
                        Text("Local-First Encrypted Vault", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                        Text("HARDWARE BACKED KEYSTORE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = EmeraldDark)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "All accounts, transactions, budgets, and holdings stay accessible offline inside Room WAL SQLite. Auth tokens & vault keys are encrypted with AES-256-GCM.",
                    fontSize = 12.sp,
                    color = SlateInk,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusPill("AES-256-GCM", Color.White, EmeraldDark)
                    StatusPill("HTTPS Only", Color.White, EmeraldDark)
                    StatusPill("Zero Paywalls", Color.White, EmeraldDark)
                }
            }
        }

        // Access Lock Section
        item {
            Text("ACCESS LOCK", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MutedSlate)
            Spacer(Modifier.height(6.dp))
            CleanCard(padding = 14.dp) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Biometric & PIN Unlock", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = SlateInk)
                        Text(if (vault.appLockEnabled) "Lock active on background resume" else "Off — tap to set PIN", fontSize = 12.sp, color = MutedSlate)
                    }
                    Switch(
                        checked = vault.appLockEnabled,
                        onCheckedChange = { enable ->
                            if (enable) showPinDialog = true else vm.disablePinLock()
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = EmeraldPrimary),
                    )
                }
                HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 10.dp))
                Text("Auto-Lock Delay", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = SlateInk)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Immediate", 10 to "10s", 60 to "60s").forEach { (sec, label) ->
                        FilterChip(
                            selected = vault.autoLockSeconds == sec,
                            onClick = { vm.setAutoLockSeconds(sec) },
                            label = { Text(label, fontSize = 12.sp) },
                        )
                    }
                }
                HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Blur in App Switcher", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = SlateInk)
                        Text("Applies FLAG_SECURE when App Lock is enabled", fontSize = 12.sp, color = MutedSlate)
                    }
                    Switch(
                        checked = vault.blurInSwitcher,
                        onCheckedChange = { vm.setBlurInSwitcher(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = EmeraldPrimary),
                    )
                }
            }
        }

        // Supabase Cloud Sync Section
        item {
            Text("CLOUD SYNC (SUPABASE FREE TIER)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MutedSlate)
            Spacer(Modifier.height(6.dp))
            CleanCard(padding = 14.dp) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.CloudSync, contentDescription = null, tint = SkySync)
                        Column {
                            Text(
                                text = vault.userEmail ?: "Local-Only Mode (No Cloud Account)",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = SlateInk,
                            )
                            val lastSyncTxt = if (vault.lastSyncEpochMs > 0L) fmt.format(Date(vault.lastSyncEpochMs)) else "Never"
                            Text("Last sync: $lastSyncTxt • Pending: $pending", fontSize = 12.sp, color = MutedSlate)
                        }
                    }
                    StatusPill(
                        text = if (isOnline) "Online" else "Offline",
                        containerColor = if (isOnline) SkySoft else SlateTint,
                        contentColor = if (isOnline) SkySync else MutedSlate,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (vault.accessToken.isNullOrBlank()) {
                        Button(
                            onClick = { showAuthDialog = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                            shape = RoundedCornerShape(12.dp),
                        ) { Text("Sign In / Create Sync Account", fontSize = 12.sp) }
                    } else {
                        Button(
                            onClick = { vm.syncNow() },
                            enabled = !syncBusy,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                            shape = RoundedCornerShape(12.dp),
                        ) { Text(if (syncBusy) "Syncing..." else "Sync Now", fontSize = 12.sp) }
                        OutlinedButton(
                            onClick = { vm.signOutSupabase() },
                            shape = RoundedCornerShape(12.dp),
                        ) { Text("Sign Out", fontSize = 12.sp, color = SlateInk) }
                    }
                }
            }
        }

        // Data Sovereignty & Export
        item {
            Text("DATA SOVEREIGNTY & ADS POLICY", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MutedSlate)
            Spacer(Modifier.height(6.dp))
            CleanCard(padding = 14.dp) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("AdMob Network Behavior", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = SlateInk)
                        Text("Ads load ONLY when internet is available", fontSize = 12.sp, color = MutedSlate)
                    }
                    StatusPill("Online-Only", SlateTint, SlateInk)
                }
                HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Diagnostics Opt-In", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = SlateInk)
                        Text("Off by default — zero invasive SDK profiling", fontSize = 12.sp, color = MutedSlate)
                    }
                    Switch(
                        checked = vault.telemetryOptIn,
                        onCheckedChange = { vm.setTelemetryOptIn(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = EmeraldPrimary),
                    )
                }
                HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            vm.exportAndShare(asCsv = true) { intent ->
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Export CSV", fontSize = 12.sp, color = SlateInk) }
                    OutlinedButton(
                        onClick = {
                            vm.exportAndShare(asCsv = false) { intent ->
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Export JSON", fontSize = 12.sp, color = SlateInk) }
                }
                HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { showResetConfirm = true },
                        modifier = Modifier.weight(1.3f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text = "Delete Data & Start Fresh",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = RoseOutflow,
                        )
                    }
                    OutlinedButton(
                        onClick = { vm.loadDemoData() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Load Demo", fontSize = 12.sp, color = SlateInk)
                    }
                }
            }
        }

        // App Updates Section
        item {
            Text("AUTOMATIC APP UPDATES", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MutedSlate)
            Spacer(Modifier.height(6.dp))
            CleanCard(padding = 14.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.SystemUpdate, contentDescription = null, tint = EmeraldDark)
                        Column {
                            Text(
                                text = "LedgerFlow v${vm.installedVersionName()} (build ${vm.installedVersionCode()})",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = SlateInk,
                            )
                            val checkedTxt = if (vault.lastUpdateCheckEpochMs > 0L) {
                                "Checked ${fmt.format(Date(vault.lastUpdateCheckEpochMs))}"
                            } else {
                                "Auto-checks every 12h when online"
                            }
                            Text(checkedTxt, fontSize = 12.sp, color = MutedSlate)
                        }
                    }
                    StatusPill(
                        text = if (isOnline) "Verified" else "Offline Safe",
                        containerColor = if (isOnline) EmeraldSoft else SlateTint,
                        contentColor = if (isOnline) EmeraldDark else MutedSlate,
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { vm.checkForUpdates(manual = true) },
                    enabled = !updateChecking,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = if (updateChecking) "Checking for Updates..." else "Check for Updates",
                        fontSize = 12.sp,
                        color = SlateInk,
                    )
                }
            }
        }

        // Vault Specification Footer Card
        item {
            CleanCard(containerColor = SlateTint, padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Lock, contentDescription = null, tint = EmeraldDark)
                    Text("Vault Technical Specification", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = SlateInk)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "• Hardware Keystore AES-256-GCM session & PIN protection\n• Room SQLite WAL + FTS4 instant offline search\n• HTTPS-only transport (zero cleartext domains in release)\n• Supabase Row-Level Security ((select auth.uid()) = user_id)",
                    fontSize = 12.sp,
                    color = MutedSlate,
                    lineHeight = 18.sp,
                )
            }
        }
    }

    if (showPinDialog) {
        var pin by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPinDialog = false },
            title = { Text("Set App Lock PIN") },
            text = {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pin = it },
                    label = { Text("4–6 digit PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.configurePinLock(pin)
                        showPinDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                ) { Text("Enable Lock") }
            },
            dismissButton = { TextButton(onClick = { showPinDialog = false }) { Text("Cancel") } },
        )
    }

    if (showAuthDialog) {
        var email by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var createAccount by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showAuthDialog = false },
            title = { Text(if (createAccount) "Create Supabase Sync Account" else "Sign In to Supabase Sync") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, singleLine = true)
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password (min 6 chars)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    TextButton(onClick = { createAccount = !createAccount }) {
                        Text(if (createAccount) "Already have an account? Sign In" else "New here? Create free account")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (createAccount) vm.signUpSupabase(email, password) else vm.signInSupabase(email, password)
                        showAuthDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                ) { Text(if (createAccount) "Sign Up" else "Sign In") }
            },
            dismissButton = { TextButton(onClick = { showAuthDialog = false }) { Text("Cancel") } },
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Delete All Data & Start Fresh?") },
            text = {
                Text(
                    "Clears all demo or recorded accounts, transactions, budgets, recurring bills, holdings, rules, and disconnects cloud sync so you start cleanly at zero balance.",
                    fontSize = 13.sp,
                    color = MutedSlate,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.resetAllData()
                        showResetConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoseOutflow),
                ) { Text("Delete Everything") }
            },
            dismissButton = { TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") } },
        )
    }
}
