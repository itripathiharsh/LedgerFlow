package com.ledgerflow.app

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.app.NotificationManagerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.GridView
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
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ledgerflow.app.ui.LedgerViewModel
import com.ledgerflow.app.ui.components.AppUpdateDialog
import com.ledgerflow.app.ui.screens.AccountsHubScreen
import com.ledgerflow.app.ui.screens.AddTransactionScreen
import com.ledgerflow.app.ui.screens.BudgetsScreen
import com.ledgerflow.app.ui.screens.HomeScreen
import com.ledgerflow.app.ui.screens.InvestScreen
import com.ledgerflow.app.ui.screens.PrivacyVaultScreen
import com.ledgerflow.app.ui.screens.RecordsScreen
import com.ledgerflow.app.ui.theme.AlabasterBg
import com.ledgerflow.app.ui.theme.BorderSubtle
import com.ledgerflow.app.ui.theme.EmeraldPrimary
import com.ledgerflow.app.ui.theme.LedgerFlowTheme
import com.ledgerflow.app.ui.theme.MutedSlate
import com.ledgerflow.app.ui.theme.RoseOutflow
import com.ledgerflow.app.ui.theme.SlateInk
import com.ledgerflow.app.update.AppUpdateManager
import com.ledgerflow.app.update.UpdateInstallProgress

class MainActivity : FragmentActivity() {
    private val vm: LedgerViewModel by viewModels()

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

                val installPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult(),
                ) {
                    vm.onInstallPermissionResult(AppUpdateManager.canRequestPackageInstalls(this))
                }

                LaunchedEffect(updateInstallState) {
                    when (val st = updateInstallState) {
                        is UpdateInstallProgress.NeedsInstallPermission -> {
                            vm.markInstallPermissionLaunched()
                            runCatching {
                                installPermissionLauncher.launch(
                                    AppUpdateManager.unknownSourcesSettingsIntent(this@MainActivity)
                                )
                            }
                        }
                        is UpdateInstallProgress.AwaitingUserConfirmation -> {
                            runCatching { startActivity(st.intent) }
                            vm.consumeAwaitingConfirmationIntent()
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
        vm.onAppForegrounded()
    }

    override fun onStop() {
        super.onStop()
        vm.onAppBackgrounded()
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

    val showBottomBar = currentRoute in setOf("home", "records", "budgets", "invest", "hub")

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
        bottomBar = {
            if (showBottomBar) {
                Surface(color = Color.White) {
                    Column(modifier = Modifier.navigationBarsPadding()) {
                        HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceEvenly,
                            ) {
                                BottomNavTab("Home", Icons.Outlined.Home, currentRoute == "home") {
                                    navController.navigate("home") { launchSingleTop = true }
                                }
                                BottomNavTab("Records", Icons.AutoMirrored.Outlined.ReceiptLong, currentRoute == "records") {
                                    navController.navigate("records") { launchSingleTop = true }
                                }
                                Spacer(Modifier.size(56.dp))
                                BottomNavTab("Budgets", Icons.Outlined.PieChart, currentRoute == "budgets") {
                                    navController.navigate("budgets") { launchSingleTop = true }
                                }
                                BottomNavTab("Invest", Icons.AutoMirrored.Outlined.TrendingUp, currentRoute == "invest") {
                                    navController.navigate("invest") { launchSingleTop = true }
                                }
                                BottomNavTab("Hub", Icons.Outlined.GridView, currentRoute == "hub") {
                                    navController.navigate("hub") { launchSingleTop = true }
                                }
                            }
                            FloatingActionButton(
                                onClick = {
                                    addInitialType = "EXPENSE"
                                    showAddModal = true
                                },
                                shape = CircleShape,
                                containerColor = EmeraldPrimary,
                                contentColor = Color.White,
                                modifier = Modifier
                                    .size(50.dp)
                                    .align(Alignment.TopCenter)
                                    .offset(y = (-12).dp),
                            ) {
                                Icon(Icons.Outlined.Add, contentDescription = "Add Transaction")
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
                    onOpenRecords = { navController.navigate("records") { launchSingleTop = true } },
                    onOpenBudgets = { navController.navigate("budgets") { launchSingleTop = true } },
                    onOpenHub = { navController.navigate("hub") { launchSingleTop = true } },
                    onOpenPrivacyVault = { navController.navigate("privacy") { launchSingleTop = true } },
                )
            }
            composable("records") {
                RecordsScreen(vm = vm)
            }
            composable("budgets") {
                BudgetsScreen(vm = vm)
            }
            composable("invest") {
                InvestScreen(vm = vm)
            }
            composable("hub") {
                AccountsHubScreen(
                    vm = vm,
                    onOpenPrivacyVault = { navController.navigate("privacy") { launchSingleTop = true } },
                )
            }
            composable("privacy") {
                PrivacyVaultScreen(
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
            .padding(horizontal = 4.dp, vertical = 6.dp),
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
