package com.aipackingmonitor.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AuditVideoDao {
    @Query("SELECT * FROM audit_videos ORDER BY startedAtMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int = 8): Flow<List<AuditVideoEntity>>

    @Query("SELECT * FROM audit_videos WHERE expiresAtMillis <= :nowMillis OR failed = 1")
    suspend fun expiredVideos(nowMillis: Long): List<AuditVideoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(video: AuditVideoEntity)

    @Query(
        """
        UPDATE audit_videos
        SET zoneIds = :zoneIds,
            zoneNames = :zoneNames
        WHERE id = :id
        """,
    )
    suspend fun updateZones(
        id: String,
        zoneIds: String,
        zoneNames: String,
    )

    @Query("UPDATE audit_videos SET alertTriggered = 1 WHERE id = :id")
    suspend fun markAlertTriggered(id: String)

    @Query(
        """
        UPDATE audit_videos
        SET endedAtMillis = :endedAtMillis,
            fileSizeBytes = :fileSizeBytes,
            finalized = 1,
            failed = :failed
        WHERE id = :id
        """,
    )
    suspend fun finish(
        id: String,
        endedAtMillis: Long,
        fileSizeBytes: Long?,
        failed: Boolean,
    )

    @Query("DELETE FROM audit_videos WHERE id = :id")
    suspend fun deleteById(id: String)
}
