package com.aipackingmonitor.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class RoomAuditVideoRepository @Inject constructor(
    private val dao: AuditVideoDao,
    @ApplicationContext private val context: Context,
) : AuditVideoRepository {
    override fun observeRecentVideos(): Flow<List<AuditVideoEntity>> = dao.observeRecent()

    override suspend fun recordStarted(
        id: String,
        filePath: String,
        startedAtMillis: Long,
        zoneIds: List<String>,
        zoneNames: List<String>,
    ) {
        dao.upsert(
            AuditVideoEntity(
                id = id,
                filePath = filePath,
                startedAtMillis = startedAtMillis,
                endedAtMillis = null,
                expiresAtMillis = startedAtMillis + RETENTION_MS,
                zoneIds = zoneIds.joinToString(FIELD_SEPARATOR),
                zoneNames = zoneNames.joinToString(FIELD_SEPARATOR),
                alertTriggered = false,
                fileSizeBytes = null,
                finalized = false,
                failed = false,
            ),
        )
    }

    override suspend fun updateZones(
        id: String,
        zoneIds: List<String>,
        zoneNames: List<String>,
    ) {
        dao.updateZones(
            id = id,
            zoneIds = zoneIds.joinToString(FIELD_SEPARATOR),
            zoneNames = zoneNames.joinToString(FIELD_SEPARATOR),
        )
    }

    override suspend fun markAlertTriggered(id: String) {
        dao.markAlertTriggered(id)
    }

    override suspend fun finish(
        id: String,
        endedAtMillis: Long,
        fileSizeBytes: Long?,
        failed: Boolean,
    ) {
        dao.finish(
            id = id,
            endedAtMillis = endedAtMillis,
            fileSizeBytes = fileSizeBytes,
            failed = failed,
        )
    }

    override suspend fun cleanupExpired(nowMillis: Long) {
        dao.expiredVideos(nowMillis).forEach { video ->
            runCatching { File(video.filePath).delete() }
            dao.deleteById(video.id)
        }
        cleanupOrphanFiles(nowMillis)
    }

    private fun cleanupOrphanFiles(nowMillis: Long) {
        val directory = File(context.filesDir, AUDIT_VIDEO_DIRECTORY)
        directory.listFiles()
            ?.filter { file -> nowMillis - file.lastModified() >= RETENTION_MS }
            ?.forEach { file -> runCatching { file.delete() } }
    }

    private companion object {
        const val FIELD_SEPARATOR = "|"
        const val AUDIT_VIDEO_DIRECTORY = "audit-videos"
        const val RETENTION_MS = 48L * 60L * 60L * 1_000L
    }
}
