package com.mapbox.navigation.core

import android.content.Context
import com.mapbox.android.core.location.LocationEngine
import com.mapbox.android.core.location.LocationEngineCallback
import com.mapbox.android.core.location.LocationEngineRequest
import com.mapbox.android.core.location.LocationEngineResult
import com.mapbox.android.telemetry.MapboxTelemetry
import com.mapbox.navigation.core.telemetry.MapboxNavigationTelemetry
import com.mapbox.navigation.core.telemetry.PhoneState
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import org.junit.Test

class MapboxNavigationTelemetryTest {
    private val mockContext = mockk<Context>(relaxed = true)
    private val mockNavigation = mockk<MapboxNavigation>()
    private val mockLocationEngine = mockk<LocationEngine>()
    private val mockLocationEngineRequest = mockk<LocationEngineRequest>()
    private val mockPhoneState = mockk<PhoneState>()
    private val telemetry = mockk<MapboxTelemetry>()
    private var token = "pk.eyJ1IjoiYmxzdGFnaW5nIiwiYSI6ImNpdDF3OHpoaTAwMDcyeXA5Y3Z0Nmk2dzEifQ.0IfB7v5Qbm2MGVYt8Kb8fg"
    @Test
    fun TelemetryInitTest() {
        mockkConstructor(MapboxTelemetry::class)
        every { telemetry.enable() } returns true
        every { mockContext.applicationContext } returns mockContext
        every { mockNavigation.registerRouteProgressObserver(any()) } answers {}
        every { mockLocationEngine.requestLocationUpdates(any(), any<LocationEngineCallback<LocationEngineResult>>(), null) } just Runs
        // assert that the first call to initialize() returns true and the second returns false
        assert(MapboxNavigationTelemetry.initialize(mockContext, token, mockNavigation, mockLocationEngine, telemetry, mockLocationEngineRequest, mockPhoneState))
        assert(!MapboxNavigationTelemetry.initialize(mockContext, token, mockNavigation, mockLocationEngine, telemetry, mockLocationEngineRequest, mockPhoneState))
    }
    @Test
    fun TelemetryUserFeedbackTest() {
    }
}
