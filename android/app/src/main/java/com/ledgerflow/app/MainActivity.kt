package com.ledgerflow.app

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ledgerflow.app.ui.LedgerViewModel
import com.ledgerflow.app.ui.components.AppUpdateDialog
import com.ledgerflow.app.ui.screens.AccountsScreen
import com.ledgerflow.app.ui.screens.AddTransactionScreen
import com.ledgerflow.app.ui.screens.BudgetsScreen
import com.ledgerflow.app.ui.screens.HomeScreen
import com.ledgerflow.app.ui.screens.InvestScreen
import com.ledgerflow.app.ui.screens.OnboardingScreen
import com.ledgerflow.app.ui.screens.RecordsScreen
import com.ledgerflow.app.ui.screens.SettingsScreen
import com.ledgerflow.app.ui.theme.AlabasterBg
import com.ledgerflow.app.ui.theme.BorderSubtle
import com.ledgerflow.app.ui.theme.EmeraldPrimary
import com.ledgerflow.app.ui.theme.LedgerFlowTheme
import com.ledgerflow.app.ui.theme.MutedSlate
import com.ledgerflow.app.ui.theme.RoseOutflow
import com.ledgerflow.app.ui.theme.SlateInk
import com.ledgerflow.app.update.AppUpdateManager
import com.ledgerflow.app.update.UpdateEventBus
import com.ledgerflow.app.update.UpdateInstallProgress

class MainActivity : FragmentActivity() {
    private val vm: LedgerViewModel by viewModels()

    // Register at Activity lifecycle level rather than inside Compose DisposableEffect so
    // Settings / install-permission jumps cannot fail due to uninitialized Compose holders.
    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        vm.onInstallPermissionResult(AppUpdateManager.canRequestPackageInstalls(this))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                AndroidColor.TRANSPARENT,
                AndroidColor.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.light(
                AndroidColor.TRANSPARENT,
                AndroidColor.TRANSPARENT,
            ),
        )

        if (savedInstanceState == null) {
            handleIncomingShareIntent(intent)
        }

        setContent {
            LedgerFlowTheme {
                val vault by vm.vaultState.collectAsState()
                val isLocked by vm.isLocked.collectAsState()
                val isOnline by vm.isOnline.collectAsState()
                val availableUpdate by vm.availableUpdate.collectAsState()
                val updateInstallState by vm.updateInstallState.collectAsState()

                LaunchedEffect(updateInstallState) {
                    when (val st = updateInstallState) {
                        is UpdateInstallProgress.NeedsInstallPermission -> {
                            launchUnknownSourcesSettings()
                        }
                        is UpdateInstallProgress.AwaitingUserConfirmation -> {
                            val foregroundIntent = Intent(st.intent).apply {
                                flags = flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv()
                            }
                            val launched = runCatching {
                                startActivity(foregroundIntent)
                                true
                            }.getOrElse {
                                runCatching {
                                    startActivity(st.intent)
                                    true
                                }.getOrDefault(false)
                            }
                            if (launched) {
                                vm.consumeAwaitingConfirmationIntent()
                            } else {
                                val fallback = vm.fallbackSystemInstallerIntent()
                                val fallbackLaunched = fallback != null && runCatching {
                                    startActivity(
                                        Intent(fallback).apply {
                                            flags = flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv()
                                        }
                                    )
                                    true
                                }.getOrDefault(false)
                                if (fallbackLaunched) {
                                    vm.consumeAwaitingConfirmationIntent()
                                } else {
                                    vm.onInstallLaunchFailed(
                                        "Unable to open Android's package installer prompt. Tap Retry or Open System Installer."
                                    )
                                }
                            }
                        }
                        else -> Unit
                    }
                }

                LaunchedEffect(vault.appLockEnabled, vault.blurInSwitcher) {
                    if (vault.appLockEnabled && vault.blurInSwitcher) {
                        window.setFlags(
                            WindowManager.LayoutParams.FLAG_SECURE,
                            WindowManager.LayoutParams.FLAG_SECURE,
                        )
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }

                if (isLocked && vault.appLockEnabled) {
                    VaultLockOverlay(
                        onTryPin = { vm.unlockWithPin(it) },
                        onRequestBiometric = { promptBiometric() },
                    )
                } else if (!vault.onboardingCompleted) {
                    OnboardingScreen(
                        onComplete = { name, email, password, currency ->
                            vm.completeOnboarding(name, email, password, currency)
                        },
                    )
                } else {
                    LedgerFlowMainShell(vm = vm)

                    val updateManifest = availableUpdate
                    if (updateManifest != null && (isOnline || updateInstallState != null)) {
                        AppUpdateDialog(
                            manifest = updateManifest,
                            currentVersionName = vm.installedVersionName(),
                            currentVersionCode = vm.installedVersionCode(),
                            installState = updateInstallState,
                            onUpdateNow = { vm.startUpdateNow() },
                            onOpenSystemInstaller = {
                                val fallback = vm.fallbackSystemInstallerIntent()
                                if (fallback != null) {
                                    runCatching {
                                        startActivity(
                                            Intent(fallback).apply {
                                                flags = flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv()
                                            }
                                        )
                                    }.onFailure { err ->
                                        vm.onInstallLaunchFailed(
                                            err.message ?: "Unable to launch system APK installer"
                                        )
                                    }
                                } else {
                                    vm.startUpdateNow()
                                }
                            },
                            onOpenInstallSettings = { launchUnknownSourcesSettings() },
                            showOpenInstallSettings = updateInstallState is UpdateInstallProgress.Failed &&
                                !AppUpdateManager.canRequestPackageInstalls(this@MainActivity),
                            onLater = { vm.dismissUpdateForLater() },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        intent ?: return
        if (intent.action != Intent.ACTION_SEND) return
        val launchedFromHistory = (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (launchedFromHistory) return
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (shared.isNotBlank() && !vm.isLocked.value) {
            vm.capturePaymentAlertText(shared, originNote = "Captured via Share-to-Track")
            intent.removeExtra(Intent.EXTRA_TEXT)
        }
    }

    override fun onResume() {
        super.onResume()
        runCatching { NotificationManagerCompat.from(this).cancel(AppUpdateManager.UPDATE_NOTIFICATION_ID) }
    }

    override fun onStart() {
        super.onStart()
        UpdateEventBus.isForegroundActive = true
        vm.onAppForegrounded()
    }

    override fun onStop() {
        UpdateEventBus.isForegroundActive = false
        super.onStop()
        vm.onAppBackgrounded()
    }

    internal fun launchUnknownSourcesSettings() {
        val intents = AppUpdateManager.unknownSourcesSettingsIntents(this)
        var lastError: Throwable? = null
        val launched = intents.any { candidate ->
            runCatching {
                installPermissionLauncher.launch(Intent(candidate))
                true
            }.recoverCatching { err ->
                lastError = err
                startActivity(Intent(candidate))
                true
            }.recoverCatching { err ->
                lastError = err
                startActivity(Intent(candidate).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
        }
        if (launched) {
            vm.markInstallPermissionLaunched()
        } else {
            val detail = lastError?.let { " (${it.javaClass.simpleName})" }.orEmpty()
            vm.onInstallPermissionLaunchFailed(
                "Could not open Android 'Install unknown apps' Settings automatically$detail. Open Settings > Apps > LedgerFlow > Install unknown apps, then tap Retry."
            )
        }
    }

    private fun promptBiometric() {
        val bm = BiometricManager.from(this)
        val canAuth = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) return

        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    vm.unlockViaBiometric()
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock LedgerFlow Vault")
            .setSubtitle("Confirm identity to view your financial ledger")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(info)
    }
}

@Composable
private fun LedgerFlowMainShell(vm: LedgerViewModel) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: "home"
    val snackbarHost = remember { SnackbarHostState() }
    val statusMsg by vm.statusMessage.collectAsState()

    var showAddModal by remember { mutableStateOf(false) }
    var addInitialType by remember { mutableStateOf("EXPENSE") }
    var budgetsInitialTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(statusMsg) {
        statusMsg?.let { msg ->
            snackbarHost.showSnackbar(msg)
            vm.clearMessage()
        }
    }

    if (showAddModal) {
        BackHandler(onBack = { showAddModal = false })
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AlabasterBg)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            AddTransactionScreen(
                vm = vm,
                initialType = addInitialType,
                onDismiss = { showAddModal = false },
            )
        }
        return
    }

    val primaryTabs = setOf("home", "records", "budgets", "invest", "accounts")
    val showBottomBar = currentRoute in primaryTabs

    val navigateTab: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo("home") { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = AlabasterBg,
        snackbarHost = {
            SnackbarHost(snackbarHost) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = SlateInk,
                    contentColor = Color.White,
                    actionColor = EmeraldPrimary,
                )
            }
        },
        floatingActionButton = {
            if (showBottomBar) {
                FloatingActionButton(
                    onClick = {
                        addInitialType = "EXPENSE"
                        showAddModal = true
                    },
                    shape = RoundedCornerShape(16.dp),
                    containerColor = EmeraldPrimary,
                    contentColor = Color.White,
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = "Add Transaction")
                }
            }
        },
        bottomBar = {
            if (showBottomBar) {
                Surface(color = Color.White) {
                    Column(modifier = Modifier.navigationBarsPadding()) {
                        HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            BottomNavTab("Home", Icons.Outlined.Home, currentRoute == "home") {
                                navigateTab("home")
                            }
                            BottomNavTab("Records", Icons.AutoMirrored.Outlined.ReceiptLong, currentRoute == "records") {
                                navigateTab("records")
                            }
                            BottomNavTab("Budgets", Icons.Outlined.PieChart, currentRoute == "budgets") {
                                budgetsInitialTab = 0
                                navigateTab("budgets")
                            }
                            BottomNavTab("Invest", Icons.AutoMirrored.Outlined.TrendingUp, currentRoute == "invest") {
                                navigateTab("invest")
                            }
                            BottomNavTab("Accounts", Icons.Outlined.AccountBalanceWallet, currentRoute == "accounts") {
                                navigateTab("accounts")
                            }
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            composable("home") {
                HomeScreen(
                    vm = vm,
                    onOpenQuickAdd = { kind ->
                        addInitialType = kind
                        showAddModal = true
                    },
                    onOpenRecords = { navigateTab("records") },
                    onOpenBudgets = {
                        budgetsInitialTab = 0
                        navigateTab("budgets")
                    },
                    onOpenAnalytics = {
                        budgetsInitialTab = 1
                        navigateTab("budgets")
                    },
                    onOpenAccounts = { navigateTab("accounts") },
                    onOpenSettings = { navController.navigate("settings") { launchSingleTop = true } },
                )
            }
            composable("records") {
                RecordsScreen(
                    vm = vm,
                    onOpenSettings = { navController.navigate("settings") { launchSingleTop = true } },
                )
            }
            composable("budgets") {
                BudgetsScreen(
                    vm = vm,
                    initialTab = budgetsInitialTab,
                    onOpenSettings = { navController.navigate("settings") { launchSingleTop = true } },
                )
            }
            composable("invest") {
                InvestScreen(
                    vm = vm,
                    onOpenSettings = { navController.navigate("settings") { launchSingleTop = true } },
                )
            }
            composable("accounts") {
                AccountsScreen(
                    vm = vm,
                    onOpenSettings = { navController.navigate("settings") { launchSingleTop = true } },
                )
            }
            composable("settings") {
                SettingsScreen(
                    vm = vm,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

@Composable
private fun BottomNavTab(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val color = if (selected) EmeraldPrimary else MutedSlate
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = color, modifier = Modifier.size(21.dp))
        Text(
            text = label,
            color = color,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

@Composable
private fun VaultLockOverlay(
    onTryPin: (String) -> Boolean,
    onRequestBiometric: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        onRequestBiometric()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AlabasterBg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Lock, contentDescription = null, tint = EmeraldPrimary, modifier = Modifier.size(44.dp))
            Text("LedgerFlow Vault Locked", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = SlateInk)
            Text("Enter your vault PIN or use biometrics to unlock", fontSize = 13.sp, color = MutedSlate)
            OutlinedTextField(
                value = pin,
                onValueChange = {
                    if (it.length <= 6 && it.all(Char::isDigit)) {
                        pin = it
                        wrong = false
                    }
                },
                label = { Text("Vault PIN") },
                visualTransformation = PasswordVisualTransformation(),
                isError = wrong,
                singleLine = true,
            )
            if (wrong) {
                Text("Incorrect PIN", fontSize = 12.sp, color = RoseOutflow)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onRequestBiometric) {
                    Text("Use Biometrics", color = SlateInk)
                }
                Button(
                    onClick = {
                        if (!onTryPin(pin)) wrong = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                ) {
                    Text("Unlock")
                }
            }
        }
    }
}
