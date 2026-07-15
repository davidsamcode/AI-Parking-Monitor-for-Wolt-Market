package com.aipackingmonitor.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AlertEventDao {
    @Query("SELECT * FROM alert_events ORDER BY startedAtMillis DESC")
    fun observeEvents(): Flow<List<AlertEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(event: AlertEventEntity)

    @Query(
        """
        UPDATE alert_events
        SET endedAtMillis = :endedAtMillis,
            dismissed = :dismissed,
            markedCorrect = COALESCE(:markedCorrect, markedCorrect)
        WHERE id = :id
        """,
    )
    suspend fun finish(
        id: String,
        endedAtMillis: Long,
        dismissed: Boolean,
        markedCorrect: Boolean?,
    )

    @Query("UPDATE alert_events SET markedCorrect = :correct WHERE id = :id")
    suspend fun markFeedback(id: String, correct: Boolean)
}
