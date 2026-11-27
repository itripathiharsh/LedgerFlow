package com.ledgerflow.app.data.remote

import com.ledgerflow.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

object IsoTime {
    fun format(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()

    fun parseOr(iso: String?, fallbackMs: Long): Long {
        if (iso.isNullOrBlank()) return fallbackMs
        return try {
            OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        } catch (_: Exception) {
            try {
                Instant.parse(iso).toEpochMilli()
            } catch (_: Exception) {
                fallbackMs
            }
        }
    }
}

@Serializable
data class SupabaseAuthUser(
    val id: String,
    val email: String? = null,
)

@Serializable
data class SupabaseAuthResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    val user: SupabaseAuthUser? = null,
    val session: SupabaseSessionObj? = null,
    val msg: String? = null,
    val error_description: String? = null,
)

@Serializable
data class SupabaseSessionObj(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    val user: SupabaseAuthUser? = null,
)

@Serializable
data class SupabaseAccountDto(
    val id: String,
    @SerialName("user_id") val userId: String,
    val name: String,
    val institution: String = "",
    @SerialName("account_type") val accountType: String,
    val currency: String,
    @SerialName("balance_minor") val balanceMinor: Long,
    @SerialName("credit_limit_minor") val creditLimitMinor: Long = 0L,
    // Exact local type; account_type only holds the coarser set the core schema accepts.
    @SerialName("account_subtype") val accountSubtype: String = "",
    @SerialName("detail_version") val detailVersion: Int = 0,
    @SerialName("last_four") val lastFour: String = "",
    @SerialName("source_type") val sourceType: String = "MANUAL",
    @SerialName("sync_status") val syncStatus: String = "OK",
    @SerialName("last_synced_at") val lastSyncedAt: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
data class SupabaseCategoryDto(
    val id: String,
    @SerialName("user_id") val userId: String,
    val name: String,
    val kind: String,
    @SerialName("icon_key") val iconKey: String,
    @SerialName("color_hex") val colorHex: String,
    @SerialName("parent_category_id") val parentCategoryId: String? = null,
    val description: String = "",
    @SerialName("detail_version") val detailVersion: Int = 0,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
data class SupabaseTransactionDto(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("account_id") val accountId: String,
    @SerialName("transfer_account_id") val transferAccountId: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("category_ids_csv") val categoryIdsCsv: String = "",
    @SerialName("spending_purpose") val spendingPurpose: String = "",
    @SerialName("detail_version") val detailVersion: Int = 0,
    @SerialName("tx_type") val txType: String,
    @SerialName("amount_minor") val amountMinor: Long,
    val currency: String,
    @SerialName("converted_minor") val convertedMinor: Long? = null,
    @SerialName("converted_currency") val convertedCurrency: String? = null,
    val merchant: String,
    val note: String = "",
    @SerialName("labels_csv") val labelsCsv: String = "",
    @SerialName("source_tag") val sourceTag: String = "MANUAL",
    @SerialName("auto_rule_applied") val autoRuleApplied: Boolean = false,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
data class SupabaseBudgetDto(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("category_id") val categoryId: String? = null,
    val name: String,
    @SerialName("limit_minor") val limitMinor: Long,
    val currency: String,
    val period: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
data class SupabasePlannedDto(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("account_id") val accountId: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    val title: String,
    @SerialName("amount_minor") val amountMinor: Long,
    val currency: String,
    @SerialName("tx_type") val txType: String,
    val cadence: String,
    @SerialName("next_due_at") val nextDueAt: String,
    @SerialName("auto_pay") val autoPay: Boolean = false,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
data class SupabaseHoldingDto(
    val id: String,
    @SerialName("user_id") val userId: String,
    val symbol: String,
    val name: String,
    @SerialName("asset_class") val assetClass: String,
    val exchange: String = "",
    val quantity: Double,
    @SerialName("avg_cost_minor") val avgCostMinor: Long,
    @SerialName("current_price_minor") val currentPriceMinor: Long,
    val currency: String,
    @SerialName("day_change_bps") val dayChangeBps: Int = 0,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
data class SupabaseMagicRuleDto(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("match_pattern") val matchPattern: String,
    @SerialName("target_category_id") val targetCategoryId: String? = null,
    @SerialName("target_label") val targetLabel: String = "",
    val enabled: Boolean = true,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
data class LiveFxResponse(
    val base: String = "USD",
    val date: String = "",
    val source: String = "frankfurter",
    val rates: Map<String, Double> = emptyMap(),
)

class SupabaseSyncClient(
    private val baseUrl: String = BuildConfig.SUPABASE_URL.trimEnd('/'),
    private val publishableKey: String = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
    private val renderUrl: String = BuildConfig.RENDER_API_URL.trimEnd('/'),
) {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mediaJson = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // Short-timeout client so Render free-tier cold-starts never stall UX.
    private val renderClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    data class AuthResult(
        val success: Boolean,
        val userId: String? = null,
        val email: String? = null,
        val accessToken: String? = null,
        val refreshToken: String? = null,
        val message: String? = null,
    )

    suspend fun signUp(email: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        authPost(
            url = "$baseUrl/auth/v1/signup",
            payload = """{"email":${jsonString(email)},"password":${jsonString(password)}}""",
        )
    }

    suspend fun signIn(email: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        authPost(
            url = "$baseUrl/auth/v1/token?grant_type=password",
            payload = """{"email":${jsonString(email)},"password":${jsonString(password)}}""",
        )
    }

    suspend fun refreshSession(refreshToken: String): AuthResult = withContext(Dispatchers.IO) {
        authPost(
            url = "$baseUrl/auth/v1/token?grant_type=refresh_token",
            payload = """{"refresh_token":${jsonString(refreshToken)}}""",
        )
    }

    private fun authPost(url: String, payload: String): AuthResult {
        if (publishableKey.isBlank()) {
            return AuthResult(success = false, message = "Supabase key missing in config")
        }
        val req = Request.Builder()
            .url(url)
            .header("apikey", publishableKey)
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(mediaJson))
            .build()
        return try {
            client.newCall(req).execute().use { res ->
                val raw = res.body?.string().orEmpty()
                val parsed = runCatching { json.decodeFromString(SupabaseAuthResponse.serializer(), raw) }.getOrNull()
                if (!res.isSuccessful) {
                    val err = parsed?.error_description ?: parsed?.msg ?: "Auth HTTP ${res.code}"
                    return AuthResult(success = false, message = err)
                }
                val token = parsed?.accessToken ?: parsed?.session?.accessToken
                val refresh = parsed?.refreshToken ?: parsed?.session?.refreshToken
                val user = parsed?.user ?: parsed?.session?.user
                if (token == null || user == null) {
                    AuthResult(
                        success = true,
                        userId = user?.id,
                        email = user?.email,
                        message = "Account created; check email verification if enabled",
                    )
                } else {
                    AuthResult(
                        success = true,
                        userId = user.id,
                        email = user.email,
                        accessToken = token,
                        refreshToken = refresh,
                    )
                }
            }
        } catch (e: Exception) {
            AuthResult(success = false, message = e.message ?: "Network unreachable")
        }
    }

    data class UpsertResult(
        val code: Int,
        /** PostgREST rejected the payload because the table lacks one of the columns sent. */
        val missingColumn: Boolean = false,
    )

    suspend fun <T> upsertTable(
        table: String,
        items: List<T>,
        serializer: kotlinx.serialization.KSerializer<T>,
        accessToken: String,
        omitKeys: Set<String> = emptySet(),
    ): UpsertResult = withContext(Dispatchers.IO) {
        if (items.isEmpty() || publishableKey.isBlank()) return@withContext UpsertResult(200)
        val rows = json.encodeToJsonElement(ListSerializer(serializer), items).jsonArray
        val body = JsonArray(rows.map { row -> JsonObject(row.jsonObject.filterKeys { it !in omitKeys }) }).toString()
        val req = Request.Builder()
            .url("$baseUrl/rest/v1/$table?on_conflict=id")
            .header("apikey", publishableKey)
            .header("Authorization", "Bearer $accessToken")
            .header("Prefer", "resolution=merge-duplicates,return=minimal")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(mediaJson))
            .build()
        client.newCall(req).execute().use { res ->
            val error = if (res.code == 400) res.body?.string().orEmpty() else ""
            // PostgREST reports PGRST204 from its schema cache; Postgres itself reports 42703.
            val missingColumn = "PGRST204" in error || "42703" in error
            UpsertResult(res.code, missingColumn)
        }
    }

    suspend fun <T> pullTableSince(
        table: String,
        sinceIso: String?,
        serializer: kotlinx.serialization.KSerializer<T>,
        accessToken: String,
    ): Pair<Int, List<T>> = withContext(Dispatchers.IO) {
        if (publishableKey.isBlank()) return@withContext 400 to emptyList()
        val filter = if (!sinceIso.isNullOrBlank()) "&updated_at=gt.${java.net.URLEncoder.encode(sinceIso, "UTF-8")}" else ""
        val pageSize = 500
        val collected = mutableListOf<T>()
        var offset = 0
        for (page in 0 until 20) {
            val req = Request.Builder()
                .url("$baseUrl/rest/v1/$table?select=*&order=updated_at.asc&limit=$pageSize&offset=$offset$filter")
                .header("apikey", publishableKey)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            val (code, items) = client.newCall(req).execute().use { res ->
                val raw = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    res.code to emptyList<T>()
                } else if (raw.isBlank()) {
                    res.code to emptyList<T>()
                } else {
                    res.code to json.decodeFromString(ListSerializer(serializer), raw)
                }
            }
            if (code !in 200..299) {
                return@withContext code to emptyList()
            }
            collected.addAll(items)
            if (items.size < pageSize) {
                return@withContext code to collected
            }
            offset += pageSize
        }
        200 to collected
    }

    suspend fun fetchLiveFxRatesOrNull(base: String = "USD"): LiveFxResponse? = withContext(Dispatchers.IO) {
        val cleanBase = base.trim().uppercase().ifBlank { "USD" }
        val candidates = buildList {
            add("https://api.frankfurter.dev/v1/latest?base=$cleanBase")
            if (renderUrl.isNotBlank()) add("$renderUrl/v1/fx/rates?base=$cleanBase")
        }
        for (url in candidates) {
            try {
                val req = Request.Builder().url(url).get().build()
                renderClient.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) continue
                    val raw = res.body?.string() ?: continue
                    val decoded = json.decodeFromString(LiveFxResponse.serializer(), raw)
                    if (decoded.rates.isNotEmpty()) return@withContext decoded
                }
            } catch (_: Exception) {
                // Try next FX source cleanly
            }
        }
        null
    }

    private fun jsonString(value: String): String =
        json.encodeToString(kotlinx.serialization.serializer<String>(), value)
}
