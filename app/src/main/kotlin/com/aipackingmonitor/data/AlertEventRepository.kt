package com.aipackingmonitor.data

import com.aipackingmonitor.domain.model.PilotSummary
import kotlinx.coroutines.flow.Flow

interface AlertEventRepository {
    fun observeEvents(): Flow<List<AlertEventEntity>>
    fun observeSummary(): Flow<PilotSummary>
    suspend fun recordAlertStart(event: AlertEventEntity)
    suspend fun finishAlert(id: String, endedAtMillis: Long, dismissed: Boolean, markedCorrect: Boolean?)
    suspend fun markFeedback(id: String, correct: Boolean)
}
