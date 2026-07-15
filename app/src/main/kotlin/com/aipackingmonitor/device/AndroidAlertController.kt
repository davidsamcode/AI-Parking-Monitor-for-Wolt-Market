package com.aipackingmonitor.device

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidAlertController @Inject constructor(
    @ApplicationContext private val context: Context,
) : AlertController {
    private var toneGenerator: ToneGenerator? = null

    override fun pulse(volumePercent: Int, vibrate: Boolean) {
        val tone = toneGenerator ?: ToneGenerator(
            AudioManager.STREAM_ALARM,
            volumePercent.coerceIn(10, 100),
        ).also { toneGenerator = it }
        tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 450)

        if (vibrate) {
            vibrator()?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 180, 120, 180), -1),
            )
        }
    }

    override fun stop() {
        toneGenerator?.stopTone()
        vibrator()?.cancel()
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
}
