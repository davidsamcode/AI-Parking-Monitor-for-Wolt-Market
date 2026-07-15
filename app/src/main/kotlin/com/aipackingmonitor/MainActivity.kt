package com.aipackingmonitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.aipackingmonitor.ui.monitoring.MonitoringRoute
import com.aipackingmonitor.ui.theme.PackingMonitorTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PackingMonitorTheme {
                MonitoringRoute()
            }
        }
    }
}
