package com.aipackingmonitor.data

import com.aipackingmonitor.domain.model.PilotSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomAlertEventRepository @Inject constructor(
    private val dao: AlertEventDao,
) : AlertEventRepository {
    override fun observeEvents(): Flow<List<AlertEventEntity>> = dao.observeEvents()

    override fun observeSummary(): Flow<PilotSummary> =
        dao.observeEvents().map { events ->
            val completed = events.filter { it.endedAtMillis != null }
            val responseTimes = completed.mapNotNull { event ->
                event.endedAtMillis?.let { it - event.startedAtMillis }
            }
            PilotSummary(
                sessions = 1,
                totalAlerts = events.size,
                correctAlerts = events.count { it.markedCorrect == true },
                falseAlerts = events.count { it.markedCorrect == false },
                dismissedAlerts = events.count { it.dismissed },
                averageResponseTimeMs = responseTimes.averageOrZero(),
            )
        }

    override suspend fun recordAlertStart(event: AlertEventEntity) {
        dao.upsert(event)
    }

    override suspend fun finishAlert(
        id: String,
        endedAtMillis: Long,
        dismissed: Boolean,
        markedCorrect: Boolean?,
    ) {
        dao.finish(id, endedAtMillis, dismissed, markedCorrect)
    }

    override suspend fun markFeedback(id: String, correct: Boolean) {
        dao.markFeedback(id, correct)
    }

    private fun List<Long>.averageOrZero(): Long =
        if (isEmpty()) 0 else average().toLong()
}
