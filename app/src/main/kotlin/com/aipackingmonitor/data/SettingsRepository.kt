package com.aipackingmonitor.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aipackingmonitor.domain.model.ZoneType
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
                additionalZones = decodeAdditionalZones(preferences[ADDITIONAL_ZONES]),
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

    suspend fun updateAdditionalZones(zones: List<ConfiguredMonitoringZone>) {
        context.settingsDataStore.edit {
            it[ADDITIONAL_ZONES] = encodeAdditionalZones(
                zones = zones
                    .take(MAX_ADDITIONAL_ZONES)
                    .map { zone -> zone.normalized() },
            )
        }
    }

    suspend fun addAdditionalZone(zone: ConfiguredMonitoringZone) {
        context.settingsDataStore.edit { preferences ->
            val next = (decodeAdditionalZones(preferences[ADDITIONAL_ZONES]) + zone.normalized())
                .take(MAX_ADDITIONAL_ZONES)
            preferences[ADDITIONAL_ZONES] = encodeAdditionalZones(next)
        }
    }

    suspend fun removeAdditionalZone(zoneId: String) {
        context.settingsDataStore.edit { preferences ->
            val next = decodeAdditionalZones(preferences[ADDITIONAL_ZONES])
                .filterNot { it.id == zoneId }
            preferences[ADDITIONAL_ZONES] = encodeAdditionalZones(next)
        }
    }

    suspend fun updateAdditionalZoneBounds(
        zoneId: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        context.settingsDataStore.edit { preferences ->
            val next = decodeAdditionalZones(preferences[ADDITIONAL_ZONES]).map { zone ->
                if (zone.id == zoneId) {
                    zone.copy(
                        left = left,
                        top = top,
                        right = right,
                        bottom = bottom,
                    ).normalized()
                } else {
                    zone
                }
            }
            preferences[ADDITIONAL_ZONES] = encodeAdditionalZones(next)
        }
    }

    private fun decodeAdditionalZones(raw: String?): List<ConfiguredMonitoringZone> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(ZONE_SEPARATOR)
            .mapNotNull { encoded ->
                val parts = encoded.split(FIELD_SEPARATOR)
                if (parts.size != ADDITIONAL_ZONE_FIELD_COUNT) return@mapNotNull null

                val type = runCatching { ZoneType.valueOf(parts[2]) }.getOrNull()
                    ?: return@mapNotNull null
                val left = parts[3].toFloatOrNull() ?: return@mapNotNull null
                val top = parts[4].toFloatOrNull() ?: return@mapNotNull null
                val right = parts[5].toFloatOrNull() ?: return@mapNotNull null
                val bottom = parts[6].toFloatOrNull() ?: return@mapNotNull null

                runCatching {
                    ConfiguredMonitoringZone(
                        id = parts[0],
                        name = parts[1],
                        type = type,
                        left = left,
                        top = top,
                        right = right,
                        bottom = bottom,
                    ).normalized()
                }.getOrNull()
            }
            .distinctBy { it.id }
            .take(MAX_ADDITIONAL_ZONES)
    }

    private fun encodeAdditionalZones(zones: List<ConfiguredMonitoringZone>): String =
        zones.joinToString(ZONE_SEPARATOR) { zone ->
            listOf(
                zone.id.safeField(),
                zone.name.safeField(),
                zone.type.name,
                zone.left.toString(),
                zone.top.toString(),
                zone.right.toString(),
                zone.bottom.toString(),
            ).joinToString(FIELD_SEPARATOR)
        }

    private fun ConfiguredMonitoringZone.normalized(): ConfiguredMonitoringZone {
        val normalizedLeft = left.coerceIn(0f, 1f - MIN_ZONE_SIZE)
        val normalizedTop = top.coerceIn(0f, 1f - MIN_ZONE_SIZE)
        val normalizedRight = right.coerceIn(normalizedLeft + MIN_ZONE_SIZE, 1f)
        val normalizedBottom = bottom.coerceIn(normalizedTop + MIN_ZONE_SIZE, 1f)

        return copy(
            id = id.safeField(),
            name = name.safeField().ifBlank { fallbackName(type) },
            left = normalizedLeft,
            top = normalizedTop,
            right = normalizedRight,
            bottom = normalizedBottom,
        )
    }

    private fun String.safeField(): String =
        filterNot { it == FIELD_SEPARATOR.single() || it == ZONE_SEPARATOR.single() }
            .trim()
            .take(MAX_FIELD_LENGTH)

    private fun fallbackName(type: ZoneType): String =
        when (type) {
            ZoneType.PackingTable -> "Packing table"
            ZoneType.Tote -> "Cart"
            ZoneType.DispatchShelf -> "Zone"
        }

    private companion object {
        const val MIN_ZONE_SIZE = 0.06f
        const val MAX_ADDITIONAL_ZONES = 4
        const val MAX_FIELD_LENGTH = 48
        const val FIELD_SEPARATOR = "|"
        const val ZONE_SEPARATOR = ";"
        const val ADDITIONAL_ZONE_FIELD_COUNT = 7

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
        val ADDITIONAL_ZONES = stringPreferencesKey("additional_zones")
    }
}
