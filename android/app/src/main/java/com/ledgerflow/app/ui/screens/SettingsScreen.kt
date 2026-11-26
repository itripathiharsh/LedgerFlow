package com.ledgerflow.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.ledgerflow.app.domain.MoneyFormatter
import com.ledgerflow.app.ui.LedgerViewModel
import com.ledgerflow.app.ui.components.CleanCard
import com.ledgerflow.app.ui.components.ManageCategoriesDialog
import com.ledgerflow.app.ui.components.StatusPill
import com.ledgerflow.app.ui.components.rememberDataImportPicker
import com.ledgerflow.app.ui.theme.AlabasterBg
import com.ledgerflow.app.ui.theme.BorderSubtle
import com.ledgerflow.app.ui.theme.EmeraldDark
import com.ledgerflow.app.ui.theme.EmeraldPrimary
import com.ledgerflow.app.ui.theme.EmeraldSoft
import com.ledgerflow.app.ui.theme.MutedSlate
import com.ledgerflow.app.ui.theme.RoseOutflow
import com.ledgerflow.app.ui.theme.SkySoft
import com.ledgerflow.app.ui.theme.SkySync
import com.ledgerflow.app.ui.theme.SlateInk
import com.ledgerflow.app.ui.theme.SlateTint
import com.ledgerflow.app.ui.theme.SurfaceCard
import com.ledgerflow.app.ui.theme.ThemeModes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class ThemeOptionMeta(
    val key: String,
    val label: String,
    val subtitle: String,
    val bgSwatch: Color,
    val accentSwatch: Color,
)

private val themeOptions = listOf(
    ThemeOptionMeta(ThemeModes.SYSTEM, "System", "Auto device", Color(0xFF64748B), Color(0xFF059669)),
    ThemeOptionMeta(ThemeModes.LIGHT, "Light", "Alabaster", Color(0xFFF6F7F4), Color(0xFF059669)),
    ThemeOptionMeta(ThemeModes.FLOW_DARK, "Flow Dark", "Green dark", Color(0xFF0E1A15), Color(0xFF34D399)),
    ThemeOptionMeta(ThemeModes.PREMIUM_DARK, "Premium Black", "Pure OLED", Color(0xFF000000), Color(0xFF10B981)),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    vm: LedgerViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val vault by vm.vaultState.collectAsState()
    val categories by vm.categories.collectAsState()
    val pending by vm.pendingSyncCount.collectAsState()
    val syncBusy by vm.syncInProgress.collectAsState()
    val isOnline by vm.isOnline.collectAsState()
    val updateChecking by vm.updateCheckInProgress.collectAsState()
    val importBusy by vm.importInProgress.collectAsState()

    val launchImportPicker = rememberDataImportPicker(
        onUriSelected = { vm.importCsvUri(it) },
        onLaunchFailed = { vm.showMessage(it) },
    )

    var showProfileDialog by remember { mutableStateOf(false) }
    var showCurrencyDialog by remember { mutableStateOf(false) }
    var showCategoryManager by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }
    var showAuthDialog by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    val fmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.US) }
    val currencies = listOf("INR", "USD", "EUR", "GBP", "JPY", "CAD", "AUD", "SGD")
    val normalizedTheme = ThemeModes.normalize(vault.themeMode)
    val parentCatCount = remember(categories) { categories.count { it.parentCategoryId == null } }
    val subCatCount = remember(categories) { categories.count { it.parentCategoryId != null } }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AlabasterBg),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Header
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
                        text = "Settings",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateInk,
                        maxLines = 1,
                        overflow = TextOverflow.Visible,
                    )
                }
                StatusPill(
                    text = if (vault.appLockEnabled) "Vault Locked" else "Local Vault",
                    containerColor = if (vault.appLockEnabled) EmeraldSoft else SlateTint,
                    contentColor = if (vault.appLockEnabled) EmeraldDark else MutedSlate,
                    dotColor = if (vault.appLockEnabled) EmeraldPrimary else null,
                )
            }
        }

        // 1. Account & Currency
        item {
            SettingsGroupTitle("ACCOUNT & CURRENCY")
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
                        Icon(Icons.Outlined.Person, contentDescription = null, tint = EmeraldDark)
                        Column {
                            Text(
                                text = vault.userName.ifBlank { "Personal Profile" },
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = SlateInk,
                            )
                            Text(
                                text = vault.userEmail?.takeIf { it.isNotBlank() } ?: "No email set",
                                fontSize = 12.sp,
                                color = MutedSlate,
                            )
                        }
                    }
                    TextButton(onClick = { showProfileDialog = true }) {
                        Text("Edit", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = EmeraldPrimary)
                    }
                }
                HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Primary Currency", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = SlateInk)
                        Text("Locked across accounts, budgets & reports", fontSize = 12.sp, color = MutedSlate)
                    }
                    OutlinedButton(
                        onClick = { showCurrencyDialog = true },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        val sym = MoneyFormatter.currencySymbol(vault.baseCurrency).trim()
                        Text("$sym ${vault.baseCurrency}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = EmeraldDark)
                    }
                }
            }
        }

        // 2. Appearance (System, Light, Flow Dark Green, Premium Black)
        item {
            SettingsGroupTitle("APPEARANCE")
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
                        Icon(Icons.Outlined.DarkMode, contentDescription = null, tint = EmeraldDark)
                        Column {
                            Text("Theme Mode", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = SlateInk)
                            Text("Light, Flow Dark (Green), or Premium Black", fontSize = 12.sp, color = MutedSlate)
                        }
                    }
                    StatusPill(
                        text = ThemeModes.displayLabel(normalizedTheme),
                        containerColor = EmeraldSoft,
                        contentColor = EmeraldDark,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    themeOptions.chunked(2).forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            pair.forEach { option ->
                                val active = normalizedTheme == option.key
                                Surface(
                                    onClick = { vm.setThemeMode(option.key) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (active) EmeraldSoft else SlateTint,
                                    border = BorderStroke(if (active) 1.5.dp else 1.dp, if (active) EmeraldPrimary else BorderSubtle),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(22.dp)
                                                .clip(CircleShape)
                                                .background(option.bgSwatch)
                                                .border(2.dp, option.accentSwatch, CircleShape),
                                        )
                                        Column {
                                            Text(
                                                text = option.label,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (active) EmeraldDark else SlateInk,
                                            )
                                            Text(
                                                text = option.subtitle,
                                                fontSize = 10.sp,
                                                color = if (active) EmeraldDark else MutedSlate,
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

        // 3. Categories & Subcategories
        item {
            SettingsGroupTitle("CATEGORIES")
            CleanCard(padding = 14.dp, onClick = { showCategoryManager = true }) {
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
                        Icon(Icons.Outlined.Category, contentDescription = null, tint = EmeraldDark)
                        Column {
                            Text("Categories & Subcategories", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = SlateInk)
                            Text(
                                text = "$parentCatCount main categories • $subCatCount subcategories",
                                fontSize = 12.sp,
                                color = MutedSlate,
                            )
                        }
                    }
                    Text("Manage", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = EmeraldPrimary)
                }
            }
        }

        // 4. Privacy & Security
        item {
            SettingsGroupTitle("PRIVACY & SECURITY")
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
                if (vault.appLockEnabled) {
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
                }
                HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Hide in App Switcher", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = SlateInk)
                        Text("Applies FLAG_SECURE when App Lock is enabled", fontSize = 12.sp, color = MutedSlate)
                    }
                    Switch(
                        checked = vault.blurInSwitcher,
                        onCheckedChange = { vm.setBlurInSwitcher(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = EmeraldPrimary),
                    )
                }
                HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Mask Balances", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = SlateInk)
                        Text("Hide monetary figures across dashboards", fontSize = 12.sp, color = MutedSlate)
                    }
                    Switch(
                        checked = vault.hideBalances,
                        onCheckedChange = { if (it != vault.hideBalances) vm.toggleHideBalances() },
                        colors = SwitchDefaults.colors(checkedTrackColor = EmeraldPrimary),
                    )
                }
            }
        }

        // 5. Cloud Sync & Data Backup
        item {
            SettingsGroupTitle("SYNC & BACKUP")
            CleanCard(padding = 14.dp) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.CloudSync, contentDescription = null, tint = SkySync)
                        Column {
                            Text(
                                text = if (!vault.accessToken.isNullOrBlank()) vault.userEmail ?: "Signed in" else "Local-Only Vault",
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
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (vault.accessToken.isNullOrBlank()) {
                        Button(
                            onClick = { showAuthDialog = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                            shape = RoundedCornerShape(12.dp),
                        ) { Text("Sign In / Sync Account", fontSize = 12.sp) }
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

                HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            vm.exportAndShare(asCsv = true) { intent ->
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { context.startActivity(intent) }
                                    .onFailure { vm.showMessage("No app found to share exported CSV") }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Export CSV", fontSize = 12.sp, color = SlateInk) }
                    OutlinedButton(
                        onClick = {
                            vm.exportAndShare(asCsv = false) { intent ->
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { context.startActivity(intent) }
                                    .onFailure { vm.showMessage("No app found to share exported JSON") }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Export JSON", fontSize = 12.sp, color = SlateInk) }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { if (!importBusy) launchImportPicker() },
                    enabled = !importBusy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = if (importBusy) "Importing Data..." else "Import Data (CSV / JSON)",
                        fontSize = 12.sp,
                        color = SlateInk,
                    )
                }
            }
        }

        // 6. App Updates
        item {
            SettingsGroupTitle("UPDATES")
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
                                "Checks on launch & every 12h online"
                            }
                            Text(checkedTxt, fontSize = 12.sp, color = MutedSlate)
                        }
                    }
                    Icon(Icons.Outlined.Lock, contentDescription = null, tint = EmeraldDark)
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

        // 7. Danger Zone (Separated at bottom)
        item {
            SettingsGroupTitle("DANGER ZONE", color = RoseOutflow)
            CleanCard(
                padding = 14.dp,
                borderColor = RoseOutflow.copy(alpha = 0.35f),
            ) {
                Text(
                    text = "Resetting removes all local accounts, transactions, budgets, holdings, and disconnects cloud sync.",
                    fontSize = 12.sp,
                    color = MutedSlate,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { showResetConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, RoseOutflow.copy(alpha = 0.5f)),
                ) {
                    Text(
                        text = "Start Fresh (Delete All Data)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = RoseOutflow,
                    )
                }
            }
        }
    }

    if (showProfileDialog) {
        var nameInput by remember(vault.userName) { mutableStateOf(vault.userName) }
        AlertDialog(
            onDismissRequest = { showProfileDialog = false },
            title = { Text("Edit Profile Name") },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Full Name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    enabled = nameInput.isNotBlank(),
                    onClick = {
                        vm.updateProfileName(nameInput)
                        showProfileDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showProfileDialog = false }) { Text("Cancel") } },
        )
    }

    if (showCurrencyDialog) {
        AlertDialog(
            onDismissRequest = { showCurrencyDialog = false },
            containerColor = SurfaceCard,
            title = { Text("Select Default Currency") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "All screens, totals, budgets, and reports display in this currency.",
                        fontSize = 12.sp,
                        color = MutedSlate,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        currencies.forEach { code ->
                            val active = vault.baseCurrency == code
                            FilterChip(
                                selected = active,
                                onClick = {
                                    if (!active) vm.setBaseCurrency(code)
                                    showCurrencyDialog = false
                                },
                                label = {
                                    val sym = MoneyFormatter.currencySymbol(code).trim()
                                    Text("$sym $code", fontSize = 12.sp)
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = EmeraldSoft,
                                    selectedLabelColor = EmeraldDark,
                                ),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCurrencyDialog = false }) { Text("Close") }
            },
        )
    }

    if (showCategoryManager) {
        ManageCategoriesDialog(
            categories = categories,
            onDismiss = { showCategoryManager = false },
            onCreateCategory = { name, kind, parentId, iconKey, colorHex ->
                vm.addCategory(name, kind, parentId, iconKey, colorHex)
            },
            onDeleteCategories = { ids ->
                vm.deleteCategories(ids)
            },
        )
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
        var email by remember(vault.userEmail) { mutableStateOf(vault.userEmail.orEmpty()) }
        var password by remember { mutableStateOf("") }
        var createAccount by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showAuthDialog = false },
            title = { Text(if (createAccount) "Create Cloud Sync Account" else "Sign In to Cloud Sync") },
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
                        Text(if (createAccount) "Already have an account? Sign In" else "New here? Create account")
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
                    "Clears all recorded accounts, transactions, budgets, recurring bills, holdings, and rules, and disconnects cloud sync so your ledger starts cleanly at zero balance (${MoneyFormatter.formatMinor(0L, vault.baseCurrency)}).",
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

@Composable
private fun SettingsGroupTitle(
    title: String,
    color: Color = MutedSlate,
) {
    Text(
        text = title,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}
