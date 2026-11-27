package com.ledgerflow.app.data.sync

import com.ledgerflow.app.data.local.SeedData
import java.util.UUID

/**
 * Starter rows carry the same fixed id on every install, but cloud tables key rows by a globally
 * unique `id`. Those ids are therefore swapped for a per-user id on the way up and swapped back on
 * the way down. All other ids are already random and pass through unchanged, and local rows,
 * backups and [SeedData] constants keep using the original starter ids.
 */
class CloudIdMapper(private val userId: String) {
    private val localByCloud: Map<String, String> =
        SeedData.fixedStarterIds.associateBy { derive(it) }

    fun toCloud(localId: String): String =
        if (localId in SeedData.fixedStarterIds) derive(localId) else localId

    fun toLocal(cloudId: String): String = localByCloud[cloudId] ?: cloudId

    fun csvToCloud(csv: String): String = mapCsv(csv, ::toCloud)

    fun csvToLocal(csv: String): String = mapCsv(csv, ::toLocal)

    private fun derive(localId: String): String =
        UUID.nameUUIDFromBytes("ledgerflow:$userId:$localId".toByteArray(Charsets.UTF_8)).toString()

    private fun mapCsv(csv: String, map: (String) -> String): String =
        if (csv.isBlank()) csv else csv.split(",").joinToString(",") { map(it.trim()) }
}
