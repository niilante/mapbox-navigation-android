package com.mapbox.navigation.core.telemetry

import android.content.Context
import com.mapbox.android.telemetry.TelemetryUtils

/**
 * Class that will hold the current states of the device phone.
 */
internal data class PhoneState(val context: Context) {
    val volumeLevel: Int by lazy { NavigationUtils.obtainVolumeLevel(context) }
    val batteryLevel: Int by lazy { TelemetryUtils.obtainBatteryLevel(context) }
    val screenBrightness: Int by lazy { NavigationUtils.obtainScreenBrightness(context) }
    val isBatteryPluggedIn: Boolean by lazy { TelemetryUtils.isPluggedIn(context) }
    val connectivity: String? by lazy { TelemetryUtils.obtainCellularNetworkType(context) }
    val audioType: String by lazy { NavigationUtils.obtainAudioType(context) }
    val applicationState: String by lazy { TelemetryUtils.obtainApplicationState(context) }
    val created: String by lazy { TelemetryUtils.obtainCurrentDate() }
    val feedbackId: String by lazy { TelemetryUtils.obtainUniversalUniqueIdentifier() }
    val userId: String by lazy { TelemetryUtils.retrieveVendorId() }
}
