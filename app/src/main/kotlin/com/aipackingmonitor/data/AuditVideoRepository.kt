package com.aipackingmonitor.data

import kotlinx.coroutines.flow.Flow

interface AuditVideoRepository {
    fun observeRecentVideos(): Flow<List<AuditVideoEntity>>

    suspend fun recordStarted(
        id: String,
        filePath: String,
        startedAtMillis: Long,
        zoneIds: List<String>,
        zoneNames: List<String>,
    )

    suspend fun updateZones(
        id: String,
        zoneIds: List<String>,
        zoneNames: List<String>,
    )

    suspend fun markAlertTriggered(id: String)

    suspend fun finish(
        id: String,
        endedAtMillis: Long,
        fileSizeBytes: Long?,
        failed: Boolean,
    )

    suspend fun cleanupExpired(nowMillis: Long)
}
