package com.aipackingmonitor.data

import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [AlertEventEntity::class, AuditVideoEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun alertEventDao(): AlertEventDao
    abstract fun auditVideoDao(): AuditVideoDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alert_events ADD COLUMN triggerReason TEXT")
                db.execSQL("ALTER TABLE alert_events ADD COLUMN motionScore REAL")
                db.execSQL("ALTER TABLE alert_events ADD COLUMN largestChangedRegionScore REAL")
                db.execSQL("ALTER TABLE alert_events ADD COLUMN clearThreshold REAL")
                db.execSQL("ALTER TABLE alert_events ADD COLUMN leftoverThreshold REAL")
                db.execSQL("ALTER TABLE alert_events ADD COLUMN changedRegionLeft REAL")
                db.execSQL("ALTER TABLE alert_events ADD COLUMN changedRegionTop REAL")
                db.execSQL("ALTER TABLE alert_events ADD COLUMN changedRegionRight REAL")
                db.execSQL("ALTER TABLE alert_events ADD COLUMN changedRegionBottom REAL")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alert_events ADD COLUMN addedObjectScore REAL")
                db.execSQL("ALTER TABLE alert_events ADD COLUMN removedObjectScore REAL")
                db.execSQL("ALTER TABLE alert_events ADD COLUMN localVerifierDecision TEXT")
                db.execSQL("ALTER TABLE alert_events ADD COLUMN localVerifierConfidence REAL")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS audit_videos (
                        id TEXT NOT NULL,
                        filePath TEXT NOT NULL,
                        startedAtMillis INTEGER NOT NULL,
                        endedAtMillis INTEGER,
                        expiresAtMillis INTEGER NOT NULL,
                        zoneIds TEXT NOT NULL,
                        zoneNames TEXT NOT NULL,
                        alertTriggered INTEGER NOT NULL,
                        fileSizeBytes INTEGER,
                        finalized INTEGER NOT NULL,
                        failed INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
            }
        }
    }
}
