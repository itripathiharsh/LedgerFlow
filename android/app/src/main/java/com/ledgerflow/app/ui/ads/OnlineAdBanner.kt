package com.ledgerflow.app.ui.ads

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.ledgerflow.app.BuildConfig
import com.ledgerflow.app.ui.theme.BorderSubtle
import com.ledgerflow.app.ui.theme.MutedSlate
import com.ledgerflow.app.ui.theme.SurfaceCard
import com.ledgerflow.app.util.NetworkMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object AdMobGate {
    private const val SAMPLE_TEST_PREFIX = "ca-app-pub-3940256099942544"
    private val initMutex = Mutex()
    @Volatile
    private var initialized = false

    fun isConfiguredAdUnit(): Boolean {
        val unitId = BuildConfig.ADMOB_BANNER_UNIT_ID.trim()
        if (unitId.isBlank()) return false
        if (!BuildConfig.DEBUG && unitId.startsWith(SAMPLE_TEST_PREFIX)) return false
        return true
    }

    suspend fun ensureInitializedWhenOnline(context: Context): Boolean {
        if (!NetworkMonitor.isOnline(context)) return false
        if (initialized) return true
        return initMutex.withLock {
            if (!NetworkMonitor.isOnline(context)) return@withLock false
            if (initialized) return@withLock true
            withContext(Dispatchers.Main) {
                val completed = withTimeoutOrNull(8_000L) {
                    suspendCancellableCoroutine { cont ->
                        try {
                            MobileAds.initialize(context.applicationContext) {
                                if (cont.isActive) cont.resume(true)
                            }
                        } catch (_: Throwable) {
                            if (cont.isActive) cont.resume(false)
                        }
                    }
                } ?: false
                if (completed) initialized = true
                completed
            }
        }
    }
}

/**
 * Minimal, non-intrusive Google AdMob banner:
 *  - Strictly checks internet connectivity before initializing or requesting an ad.
 *  - Renders zero UI footprint when offline or when no ad fills, keeping core UX clean & unbroken.
 */
@Composable
fun OnlineOnlyAdBanner(
    modifier: Modifier = Modifier,
) {
    if (!AdMobGate.isConfiguredAdUnit()) {
        return
    }
    val context = LocalContext.current
    var isOnline by remember { mutableStateOf(false) }
    var sdkReady by remember { mutableStateOf(false) }
    var adLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(context) {
        NetworkMonitor.observeOnline(context).collect { online ->
            isOnline = online
            if (online && !sdkReady) {
                sdkReady = AdMobGate.ensureInitializedWhenOnline(context)
            }
            if (!online) {
                adLoaded = false
            }
        }
    }

    if (!isOnline || !sdkReady) {
        return
    }

    val adView = remember(context) {
        runCatching {
            AdView(context).apply {
                setAdSize(AdSize.BANNER)
                adUnitId = BuildConfig.ADMOB_BANNER_UNIT_ID
                adListener = object : AdListener() {
                    override fun onAdLoaded() {
                        adLoaded = true
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        adLoaded = false
                    }
                }
                loadAd(AdRequest.Builder().build())
            }
        }.getOrNull()
    } ?: return

    DisposableEffect(adView) {
        onDispose {
            adLoaded = false
            runCatching { adView.destroy() }
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (adLoaded) SurfaceCard else Color.Transparent,
        border = if (adLoaded) BorderStroke(1.dp, BorderSubtle) else null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (adLoaded) Modifier.padding(vertical = 6.dp, horizontal = 12.dp) else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (adLoaded) {
                Text(
                    text = "Sponsored",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = MutedSlate,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            AndroidView(
                factory = { adView },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
