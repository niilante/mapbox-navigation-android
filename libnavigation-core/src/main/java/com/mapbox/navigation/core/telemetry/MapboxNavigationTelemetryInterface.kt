package com.mapbox.navigation.core.telemetry

interface MapboxNavigationTelemetryInterface {
    fun postTelemetryEvent(event: TelemetryEventInterface)
}
