package com.aipackingmonitor.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [AlertEventEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun alertEventDao(): AlertEventDao
}
