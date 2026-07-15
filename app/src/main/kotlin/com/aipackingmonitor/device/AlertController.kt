package com.aipackingmonitor.device

interface AlertController {
    fun pulse(volumePercent: Int, vibrate: Boolean)
    fun stop()
}
