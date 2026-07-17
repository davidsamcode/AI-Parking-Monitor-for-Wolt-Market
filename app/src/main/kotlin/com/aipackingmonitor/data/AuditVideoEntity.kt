package com.aipackingmonitor.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "audit_videos")
data class AuditVideoEntity(
    @PrimaryKey val id: String,
    val filePath: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val expiresAtMillis: Long,
    val zoneIds: String,
    val zoneNames: String,
    val alertTriggered: Boolean,
    val fileSizeBytes: Long?,
    val finalized: Boolean,
    val failed: Boolean,
)
