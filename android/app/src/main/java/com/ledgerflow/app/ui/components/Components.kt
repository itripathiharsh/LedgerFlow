package com.ledgerflow.app.ui.components

import androidx.core.graphics.toColorInt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.DirectionsTransit
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgerflow.app.ui.theme.BorderSubtle
import com.ledgerflow.app.ui.theme.EmeraldDark
import com.ledgerflow.app.ui.theme.EmeraldPrimary
import com.ledgerflow.app.ui.theme.EmeraldSoft
import com.ledgerflow.app.ui.theme.MutedSlate
import com.ledgerflow.app.ui.theme.SkySoft
import com.ledgerflow.app.ui.theme.SkySync
import com.ledgerflow.app.ui.theme.SlateInk
import com.ledgerflow.app.ui.theme.SlateTint
import com.ledgerflow.app.ui.theme.SurfaceCard
import com.ledgerflow.app.ui.theme.AmberSoft
import com.ledgerflow.app.ui.theme.AmberWarn

@Composable
fun CleanCard(
    modifier: Modifier = Modifier,
    containerColor: Color = SurfaceCard,
    borderColor: Color = BorderSubtle,
    padding: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = containerColor,
            border = BorderStroke(1.dp, borderColor),
        ) {
            Column(modifier = Modifier.padding(padding), content = content)
        }
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = containerColor,
            border = BorderStroke(1.dp, borderColor),
        ) {
            Column(modifier = Modifier.padding(padding), content = content)
        }
    }
}

@Composable
fun StatusPill(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    dotColor: Color? = null,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (dotColor != null) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
            }
            Text(
                text = text,
                color = contentColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun SyncBadge(
    isOnline: Boolean,
    hasCloudSession: Boolean,
    pendingCount: Int,
) {
    val (label, bg, fg, dot) = when {
        !isOnline -> Quad("Offline Local", SlateTint, MutedSlate, MutedSlate)
        hasCloudSession && pendingCount == 0 -> Quad("Vault Synced", EmeraldSoft, EmeraldDark, EmeraldPrimary)
        hasCloudSession -> Quad("Syncing ($pendingCount)", SkySoft, SkySync, SkySync)
        else -> Quad("Local Vault", EmeraldSoft, EmeraldDark, EmeraldPrimary)
    }
    StatusPill(text = label, containerColor = bg, contentColor = fg, dotColor = dot)
}

@Composable
fun SourceTagBadge(sourceTag: String) {
    val (label, bg, fg) = when (sourceTag) {
        "GPAY_AUTO" -> Triple("GPay Auto", SkySoft, SkySync)
        "BANK_SYNC" -> Triple("Bank Sync", EmeraldSoft, EmeraldDark)
        "PLANNED" -> Triple("Planned", Color(0xFFEEF2FF), Color(0xFF4338CA))
        "IMPORT" -> Triple("Imported", AmberSoft, AmberWarn)
        else -> Triple("Manual", SlateTint, MutedSlate)
    }
    StatusPill(text = label, containerColor = bg, contentColor = fg)
}

fun categoryIcon(iconKey: String): ImageVector = when (iconKey) {
    "cafe" -> Icons.Outlined.Coffee
    "shopping_cart" -> Icons.Outlined.ShoppingCart
    "directions_transit" -> Icons.Outlined.DirectionsTransit
    "auto_awesome" -> Icons.Outlined.AutoAwesome
    "home" -> Icons.Outlined.Home
    "payments" -> Icons.Outlined.Payments
    "medical_services" -> Icons.Outlined.MedicalServices
    "bank" -> Icons.Outlined.AccountBalance
    else -> Icons.Outlined.Category
}

@Composable
fun CategoryIconBadge(
    iconKey: String,
    tintHex: String = "#059669",
    size: Dp = 40.dp,
) {
    val color = runCatching { Color(tintHex.toColorInt()) }.getOrDefault(EmeraldPrimary)
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = categoryIcon(iconKey),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = SlateInk,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
        if (actionText != null && onActionClick != null) {
            Surface(
                onClick = onActionClick,
                color = Color.Transparent,
            ) {
                Text(
                    text = actionText,
                    color = EmeraldPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 4.dp, horizontal = 2.dp),
                )
            }
        }
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

@Composable
fun EmptyPlaceholder(title: String, subtitle: String) {
    CleanCard {
        Text(text = title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = SlateInk)
        Spacer(Modifier.width(4.dp))
        Text(text = subtitle, fontSize = 13.sp, color = MutedSlate, modifier = Modifier.padding(top = 4.dp))
    }
}
