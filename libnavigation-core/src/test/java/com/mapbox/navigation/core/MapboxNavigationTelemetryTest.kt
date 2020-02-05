package com.mapbox.navigation.core

import android.content.Context
import com.mapbox.android.core.location.LocationEngine
import com.mapbox.android.core.location.LocationEngineCallback
import com.mapbox.android.core.location.LocationEngineRequest
import com.mapbox.android.core.location.LocationEngineResult
import com.mapbox.android.telemetry.MapboxTelemetry
import com.mapbox.navigation.core.telemetry.FeedbackSource
import com.mapbox.navigation.core.telemetry.FeedbackType
import com.mapbox.navigation.core.telemetry.MapboxNavigationTelemetry
import com.mapbox.navigation.core.telemetry.TelemetryEventFeedback
import com.mapbox.navigation.utils.thread.JobControl
import com.mapbox.navigation.utils.thread.ThreadController
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestCoroutineDispatcher
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Before
import org.junit.Test

class MapboxNavigationTelemetryTest {
    private val mockContext = mockk<Context>(relaxed = true)
    private val mockNavigation = mockk<MapboxNavigation>()
    private val mockLocationEngine = mockk<LocationEngine>()
    private val mockLocationEngineRequest = mockk<LocationEngineRequest>()
    private val telemetry = mockk<MapboxTelemetry>()
    private var token = "pk.1234.PABLO'S-FAKE-TOKEN"

    @Before
    fun setUp() {
        every { telemetry.enable() } returns true
        every { mockContext.applicationContext } returns mockContext
        every { mockNavigation.registerRouteProgressObserver(any()) } answers {}
        every { mockLocationEngine.requestLocationUpdates(any(), any<LocationEngineCallback<LocationEngineResult>>(), null) } just Runs
    }
    private fun mockIOScopeAndRootJob() {
        val parentJob = SupervisorJob()
        val testScope = CoroutineScope(parentJob + TestCoroutineDispatcher())
        every { ThreadController.getIOScopeAndRootJob() } returns JobControl(parentJob, testScope)
        every { ThreadController.IODispatcher } returns TestCoroutineDispatcher()
    }

    @Test
    fun TelemetryInitTest() {
        // assert that the first call to initialize() returns true and the second returns false
        MapboxNavigationTelemetry.pauseTelemetry(true)
        assert(MapboxNavigationTelemetry.initialize(mockContext, token, mockNavigation, mockLocationEngine, telemetry, mockLocationEngineRequest))
        assert(!MapboxNavigationTelemetry.initialize(mockContext, token, mockNavigation, mockLocationEngine, telemetry, mockLocationEngineRequest))
    }

    @Test
    fun TelemetryUserFeedbackTest() = runBlockingTest {
        mockkObject(ThreadController)
        mockIOScopeAndRootJob()

        MapboxNavigationTelemetry.pauseTelemetry(true)
        MapboxNavigationTelemetry.initialize(createContext(), token, mockNavigation, mockLocationEngine, telemetry, mockLocationEngineRequest)
        val userResponseEvent = TelemetryEventFeedback(FeedbackType.FEEDBACK_TYPE_ACCIDENT.name, "big bad accident", FeedbackSource.FEEDBACK_SOURCE_USER.name, "screen shot")
        MapboxNavigationTelemetry.postTelemetryEvent(userResponseEvent)
        unmockkObject(ThreadController)
    }
}
