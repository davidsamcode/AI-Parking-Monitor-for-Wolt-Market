package com.aipackingmonitor.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "pilot_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val settings: Flow<PilotSettings> =
        context.settingsDataStore.data.map { preferences ->
            PilotSettings(
                entryThreshold = preferences[ENTRY_THRESHOLD] ?: 0.07f,
                clearThreshold = preferences[CLEAR_THRESHOLD] ?: 0.03f,
                alertDelayMs = preferences[ALERT_DELAY_MS] ?: 3_000,
                alarmVolumePercent = preferences[ALARM_VOLUME] ?: 80,
                vibrationEnabled = preferences[VIBRATION_ENABLED] ?: true,
                zoneLeft = preferences[ZONE_LEFT] ?: 0.12f,
                zoneTop = preferences[ZONE_TOP] ?: 0.18f,
                zoneRight = preferences[ZONE_RIGHT] ?: 0.88f,
                zoneBottom = preferences[ZONE_BOTTOM] ?: 0.82f,
                cartZoneLeft = preferences[CART_ZONE_LEFT] ?: 0.08f,
                cartZoneTop = preferences[CART_ZONE_TOP] ?: 0.46f,
                cartZoneRight = preferences[CART_ZONE_RIGHT] ?: 0.94f,
                cartZoneBottom = preferences[CART_ZONE_BOTTOM] ?: 0.96f,
            )
        }

    suspend fun updateEntryThreshold(value: Float) {
        context.settingsDataStore.edit { it[ENTRY_THRESHOLD] = value.coerceIn(0.01f, 0.35f) }
    }

    suspend fun updateClearThreshold(value: Float) {
        context.settingsDataStore.edit { it[CLEAR_THRESHOLD] = value.coerceIn(0.005f, 0.2f) }
    }

    suspend fun updateAlertDelay(valueMs: Long) {
        context.settingsDataStore.edit { it[ALERT_DELAY_MS] = valueMs.coerceIn(3_000, 60_000) }
    }

    suspend fun updateAlarmVolume(value: Int) {
        context.settingsDataStore.edit { it[ALARM_VOLUME] = value.coerceIn(10, 100) }
    }

    suspend fun updateVibrationEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[VIBRATION_ENABLED] = enabled }
    }

    suspend fun updateZoneBounds(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        val normalizedLeft = left.coerceIn(0f, 0.94f)
        val normalizedTop = top.coerceIn(0f, 0.94f)
        val normalizedRight = right.coerceIn(normalizedLeft + MIN_ZONE_SIZE, 1f)
        val normalizedBottom = bottom.coerceIn(normalizedTop + MIN_ZONE_SIZE, 1f)

        context.settingsDataStore.edit {
            it[ZONE_LEFT] = normalizedLeft
            it[ZONE_TOP] = normalizedTop
            it[ZONE_RIGHT] = normalizedRight
            it[ZONE_BOTTOM] = normalizedBottom
        }
    }

    suspend fun updateCartZoneBounds(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        val normalizedLeft = left.coerceIn(0f, 0.94f)
        val normalizedTop = top.coerceIn(0f, 0.94f)
        val normalizedRight = right.coerceIn(normalizedLeft + MIN_ZONE_SIZE, 1f)
        val normalizedBottom = bottom.coerceIn(normalizedTop + MIN_ZONE_SIZE, 1f)

        context.settingsDataStore.edit {
            it[CART_ZONE_LEFT] = normalizedLeft
            it[CART_ZONE_TOP] = normalizedTop
            it[CART_ZONE_RIGHT] = normalizedRight
            it[CART_ZONE_BOTTOM] = normalizedBottom
        }
    }

    private companion object {
        const val MIN_ZONE_SIZE = 0.06f

        val ENTRY_THRESHOLD = floatPreferencesKey("entry_threshold")
        val CLEAR_THRESHOLD = floatPreferencesKey("clear_threshold")
        val ALERT_DELAY_MS = longPreferencesKey("alert_delay_ms")
        val ALARM_VOLUME = intPreferencesKey("alarm_volume")
        val VIBRATION_ENABLED = booleanPreferencesKey("vibration_enabled")
        val ZONE_LEFT = floatPreferencesKey("zone_left")
        val ZONE_TOP = floatPreferencesKey("zone_top")
        val ZONE_RIGHT = floatPreferencesKey("zone_right")
        val ZONE_BOTTOM = floatPreferencesKey("zone_bottom")
        val CART_ZONE_LEFT = floatPreferencesKey("cart_zone_left")
        val CART_ZONE_TOP = floatPreferencesKey("cart_zone_top")
        val CART_ZONE_RIGHT = floatPreferencesKey("cart_zone_right")
        val CART_ZONE_BOTTOM = floatPreferencesKey("cart_zone_bottom")
    }
}
