package com.ledgerflow.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.ledgerflow.app.domain.UpdateManifest
import com.ledgerflow.app.ui.theme.BorderSubtle
import com.ledgerflow.app.ui.theme.EmeraldDark
import com.ledgerflow.app.ui.theme.EmeraldPrimary
import com.ledgerflow.app.ui.theme.EmeraldSoft
import com.ledgerflow.app.ui.theme.MutedSlate
import com.ledgerflow.app.ui.theme.RoseOutflow
import com.ledgerflow.app.ui.theme.RoseSoft
import com.ledgerflow.app.ui.theme.SlateInk
import com.ledgerflow.app.ui.theme.SlateTint
import com.ledgerflow.app.update.UpdateInstallProgress
import java.util.Locale

@Composable
fun AppUpdateDialog(
    manifest: UpdateManifest,
    currentVersionName: String,
    currentVersionCode: Long,
    installState: UpdateInstallProgress?,
    onUpdateNow: () -> Unit,
    onLater: () -> Unit,
) {
    val busy = installState is UpdateInstallProgress.CheckingPermission ||
        installState is UpdateInstallProgress.Downloading ||
        installState is UpdateInstallProgress.VerifyingSignature

    val installLaunched = installState is UpdateInstallProgress.Installing ||
        installState is UpdateInstallProgress.AwaitingUserConfirmation

    AlertDialog(
        onDismissRequest = { if (!busy) onLater() },
        properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy),
        containerColor = Color.White,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = EmeraldSoft,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SystemUpdate,
                        contentDescription = null,
                        tint = EmeraldDark,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                Column {
                    Text(
                        text = "New Version Available",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateInk,
                    )
                    Text(
                        text = "v${manifest.versionName} (build ${manifest.versionCode})",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = EmeraldDark,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Installed: v$currentVersionName ($currentVersionCode)",
                        fontSize = 12.sp,
                        color = MutedSlate,
                    )
                    if (manifest.apkSizeBytes > 0L) {
                        val sizeMb = String.format(Locale.US, "%.1f MB", manifest.apkSizeBytes / 1_048_576.0)
                        StatusPill(sizeMb, SlateTint, SlateInk)
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = EmeraldSoft,
                    border = BorderStroke(1.dp, Color(0xFFA7F3D0)),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Shield,
                            contentDescription = null,
                            tint = EmeraldDark,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "Verified HTTPS download + SHA-256 & APK signing key check before install.",
                            fontSize = 11.sp,
                            color = EmeraldDark,
                            lineHeight = 15.sp,
                        )
                    }
                }

                if (manifest.releaseNotes.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SlateTint, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "WHAT'S NEW",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MutedSlate,
                        )
                        Spacer(Modifier.height(2.dp))
                        manifest.releaseNotes.forEach { note ->
                            Text(
                                text = "• $note",
                                fontSize = 12.sp,
                                color = SlateInk,
                                lineHeight = 17.sp,
                            )
                        }
                    }
                }

                when (installState) {
                    is UpdateInstallProgress.CheckingPermission -> {
                        ProgressStatusBlock("Checking install permission...", progress = null)
                    }
                    is UpdateInstallProgress.NeedsInstallPermission -> {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = SlateTint,
                            border = BorderStroke(1.dp, BorderSubtle),
                        ) {
                            Text(
                                text = "Grant 'Install unknown apps' permission in Android Settings to install verified LedgerFlow updates.",
                                fontSize = 12.sp,
                                color = SlateInk,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }
                    is UpdateInstallProgress.Downloading -> {
                        val total = installState.totalBytes
                        val ratio = if (total > 0L) (installState.bytesRead.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null
                        val dlMb = String.format(Locale.US, "%.1f", installState.bytesRead / 1_048_576.0)
                        val label = if (total > 0L) {
                            val totalMb = String.format(Locale.US, "%.1f", total / 1_048_576.0)
                            "Downloading APK... $dlMb / $totalMb MB"
                        } else {
                            "Downloading APK... $dlMb MB"
                        }
                        ProgressStatusBlock(label, progress = ratio)
                    }
                    is UpdateInstallProgress.VerifyingSignature -> {
                        ProgressStatusBlock("Verifying SHA-256 digest & APK signature...", progress = null)
                    }
                    is UpdateInstallProgress.Installing,
                    is UpdateInstallProgress.AwaitingUserConfirmation -> {
                        ProgressStatusBlock("Installing update & preparing auto-reopen...", progress = null)
                    }
                    is UpdateInstallProgress.Failed -> {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = RoseSoft,
                        ) {
                            Text(
                                text = installState.message,
                                fontSize = 12.sp,
                                color = RoseOutflow,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                            )
                        }
                    }
                    null -> Unit
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onUpdateNow,
                enabled = !busy && !installLaunched,
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                shape = RoundedCornerShape(12.dp),
            ) {
                val buttonText = when (installState) {
                    is UpdateInstallProgress.Downloading,
                    is UpdateInstallProgress.VerifyingSignature,
                    is UpdateInstallProgress.CheckingPermission -> "Updating..."
                    is UpdateInstallProgress.Installing,
                    is UpdateInstallProgress.AwaitingUserConfirmation -> "Awaiting OS..."
                    is UpdateInstallProgress.Failed -> "Retry"
                    else -> "Update Now"
                }
                Text(buttonText, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onLater,
                enabled = !busy,
            ) {
                Text("Later", color = MutedSlate, fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

@Composable
private fun ProgressStatusBlock(
    label: String,
    progress: Float?,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = EmeraldPrimary,
                trackColor = EmeraldSoft,
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = EmeraldPrimary,
                trackColor = EmeraldSoft,
            )
        }
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = EmeraldDark,
        )
    }
}
