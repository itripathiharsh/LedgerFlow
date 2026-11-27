package com.ledgerflow.app.domain

import java.util.Locale

data class SupportedPaymentApp(
    val packageName: String,
    val displayName: String,
    val remoteSourceTag: String,
    val bankHint: String? = null,
)

object SupportedPaymentApps {
    private val registry: Map<String, SupportedPaymentApp> = listOf(
        // Major UPI & Wallet Apps
        SupportedPaymentApp("com.google.android.apps.nbu.paisa.user", "Google Pay", "GPAY_AUTO"),
        SupportedPaymentApp("com.google.android.apps.walletnfcrel", "Google Wallet", "GPAY_AUTO"),
        SupportedPaymentApp("com.phonepe.app", "PhonePe", "BANK_SYNC"),
        SupportedPaymentApp("com.phonepe.app.business", "PhonePe Business", "BANK_SYNC"),
        SupportedPaymentApp("net.one97.paytm", "Paytm", "BANK_SYNC"),
        SupportedPaymentApp("com.paytm.merchants", "Paytm Business", "BANK_SYNC"),
        SupportedPaymentApp("in.org.npci.upiapp", "BHIM", "BANK_SYNC"),
        SupportedPaymentApp("com.dreamplug.androidapp", "CRED", "BANK_SYNC"),

        // Supported Banking Apps
        SupportedPaymentApp("com.sbi.lotusintouch", "SBI YONO", "BANK_SYNC", bankHint = "SBI"),
        SupportedPaymentApp("com.sbi.SBIFreedomPlus", "SBI YONO Lite", "BANK_SYNC", bankHint = "SBI"),
        SupportedPaymentApp("com.sbi.yono.lite", "SBI YONO Lite", "BANK_SYNC", bankHint = "SBI"),
        SupportedPaymentApp("com.snapwork.hdfc", "HDFC Bank", "BANK_SYNC", bankHint = "HDFC"),
        SupportedPaymentApp("com.enstage.wibmo.hdfc", "HDFC PayZapp", "BANK_SYNC", bankHint = "HDFC"),
        SupportedPaymentApp("com.hdfcbank.payzapp", "HDFC PayZapp", "BANK_SYNC", bankHint = "HDFC"),
        SupportedPaymentApp("com.csam.icici.bank.imobile", "ICICI iMobile", "BANK_SYNC", bankHint = "ICICI"),
        SupportedPaymentApp("com.axis.mobile", "Axis Mobile", "BANK_SYNC", bankHint = "AXIS"),
        SupportedPaymentApp("com.msf.kbank.mobile", "Kotak Bank", "BANK_SYNC", bankHint = "KOTAK"),
        SupportedPaymentApp("com.kotak.neo", "Kotak Neo", "BANK_SYNC", bankHint = "KOTAK"),
        SupportedPaymentApp("com.kotak811.app", "Kotak 811", "BANK_SYNC", bankHint = "KOTAK"),
        SupportedPaymentApp("com.idfcfirstbank.optimus", "IDFC First Bank", "BANK_SYNC", bankHint = "IDFC"),
        SupportedPaymentApp("com.bankofbaroda.mconnect", "Bank of Baroda", "BANK_SYNC", bankHint = "BANK OF BARODA"),
        SupportedPaymentApp("com.bankofbaroda.mconnectplus", "BOB World", "BANK_SYNC", bankHint = "BANK OF BARODA"),
        SupportedPaymentApp("com.canarabank.mobility", "Canara Bank", "BANK_SYNC", bankHint = "CANARA"),
        SupportedPaymentApp("com.fss.indus", "IndusInd Bank", "BANK_SYNC", bankHint = "INDUSIND"),
        SupportedPaymentApp("com.chase.sig.android", "Chase", "BANK_SYNC", bankHint = "CHASE"),
        SupportedPaymentApp("co.uk.getmondo", "Monzo", "BANK_SYNC", bankHint = "MONZO"),
        SupportedPaymentApp("com.revolut.revolut", "Revolut", "BANK_SYNC", bankHint = "REVOLUT"),
        SupportedPaymentApp("com.transferwise.android", "Wise", "BANK_SYNC", bankHint = "WISE"),
    ).associateBy { it.packageName.lowercase(Locale.US) }

    fun resolve(packageName: String?): SupportedPaymentApp? {
        val clean = packageName?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() } ?: return null
        return registry[clean]
    }

    fun isSupported(packageName: String?): Boolean = resolve(packageName) != null

    val disclosedCategoriesSummary: List<String> = listOf(
        "Google Pay India",
        "PhonePe",
        "Paytm",
        "BHIM",
        "Google Wallet",
        "Major banking apps (SBI, HDFC, ICICI, Axis, Kotak, IDFC, BOB, Canara, IndusInd, CRED, Chase, Monzo, Revolut, Wise)",
    )
}
