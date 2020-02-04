package com.mapbox.navigation.core.telemetry

import android.content.Context
import android.location.Location
import com.mapbox.android.core.location.LocationEngine
import com.mapbox.android.core.location.LocationEngineCallback
import com.mapbox.android.core.location.LocationEngineRequest
import com.mapbox.android.core.location.LocationEngineResult
import com.mapbox.android.telemetry.Event
import com.mapbox.android.telemetry.MapboxTelemetry
import com.mapbox.android.telemetry.TelemetryUtils
import com.mapbox.navigation.base.extensions.ifNonNull
import com.mapbox.navigation.base.logger.model.Message
import com.mapbox.navigation.base.logger.model.Tag
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import com.mapbox.navigation.logger.MapboxLogger
import com.mapbox.navigation.utils.exceptions.NavigationException
import com.mapbox.navigation.utils.thread.ThreadController
import com.mapbox.navigation.utils.thread.monitorChannelWithException
import com.mapbox.navigation.utils.time.Time
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

private enum class LocationBufferCommands {
    BUFFER_ADD,
    BUFFER_REMOVE,
    BUFFER_CLEAR,
    BUFFER_READ,
}

/**
 * This data class defines an action to be taken against the location buffer. These can be one of four defined in LocationBufferCommands
 */
private data class LocationBufferControl(val command: LocationBufferCommands, val location: Location?, val predicate: ((Location) -> Unit) = { _ -> Unit })

internal object MapboxNavigationTelemetry : MapboxNavigationTelemetryInterface {
    private lateinit var context: Context
    private lateinit var mapboxToken: String

    private val TAG = Tag("MAPBOX_TELEMETRY")
    private const val MAX_LOCATION_VALUES = 20
    private const val MAX_TELEMTRY_EVENTS = 100
    private val telemetryEventsQueue = mutableListOf<TelemetryEventInterface>()
    private val jobControl = ThreadController.getIOScopeAndRootJob()
    private val locationBuffer = mutableListOf<Location>()
    private val channelLocationBuffer = Channel<LocationBufferControl>(MAX_LOCATION_VALUES)
    private val channelOnRouteProgress = Channel<RouteProgress>(Channel.CONFLATED) // we want just the last notification
    private lateinit var cleanupJob: Job
    private lateinit var mapboxTelemetry: MapboxTelemetry
    private lateinit var locationEngine: LocationEngine

    // Call back that receives
    private val routeProgressListener = object : RouteProgressObserver {
        override fun onRouteProgressChanged(routeProgress: RouteProgress) {
            channelOnRouteProgress.offer(routeProgress)
        }
    }

    // Return path of the location callback. This will offer data on a channel that serializes location requests
    private val locationCallback = object : LocationEngineCallback<LocationEngineResult> {
        override fun onSuccess(result: LocationEngineResult?) {
            ifNonNull(result?.lastLocation) { lastLocation ->
                channelLocationBuffer.offer(LocationBufferControl(LocationBufferCommands.BUFFER_ADD, lastLocation))
            }
        }

        override fun onFailure(exception: Exception) {
            MapboxLogger.i(TAG, Message("location services exception: $exception"))
        }
    }

    /**
     * The lambda that is called if the SDK client did not initialize telemetry. If telemetry is not initialized
     * than all calls to post a telemetry event will fail with this exception
     */
    private val postEventBeforeInit: (TelemetryEventInterface) -> Unit = { _: TelemetryEventInterface ->
        throw NavigationException("Telemetry must be initialized before calling this method. Call MapboxNavigationTelemetry.initialize()")
    }

    /**
     * The lambda that is called once telemetry is initialized. This call will genearate a telemetry event. The call is
     * equivalent to postEvent()
     */
    private val postEventAfterInit: (TelemetryEventInterface) -> Unit = { telemetryInterface -> postEvent(telemetryInterface) }

    /**
     * The delegate lambda that distaches either a pre or after initialization logic
     */
    private var postEventDelegate = postEventBeforeInit

    /**
     * One-time initializer. Called in response to initialize() and then replaced with a no-op lambda to prevent multiple initialize() calls
     */
    private val primaryInitializer: (Context, String, MapboxNavigation, LocationEngine, MapboxTelemetry, LocationEngineRequest) -> Boolean = { context, token, mapboxNavigation, locationEngine, telemetry, locationEngineRequest ->
        this.context = context
        mapboxToken = token
        this.locationEngine = locationEngine
        validateAccessToken(mapboxToken)
        initializer = postInitialize // prevent primaryInitializer() from being called more than once.
        postEventDelegate = postEventAfterInit // now that the object has been initialized we can post events
        registerForNotification(mapboxNavigation)
        monitorChannels()
        telemetry.enable()

        /**
         * Register a callback to receive location events. At most [MAX_LOCATION_VALUES] are stored
         */
        locationEngine.requestLocationUpdates(locationEngineRequest, locationCallback, null)
        true
    }
    private var initializer = primaryInitializer
    private var postInitialize: (Context, String, MapboxNavigation, LocationEngine, MapboxTelemetry, LocationEngineRequest) -> Boolean = { _, _, _, _, _, _ -> false }
    fun initialize(
        context: Context,
        mapboxToken: String,
        mapboxNavigation: MapboxNavigation,
        locationEngine: LocationEngine,
        telemetry: MapboxTelemetry,
        locationEngineRequest: LocationEngineRequest
    ) = initializer(context, mapboxToken, mapboxNavigation, locationEngine, telemetry, locationEngineRequest)

    /**
     * This method is used to post all types of telemetry events to the back-end server.
     * The [event] parameter represents one of several Telemetry events available
     */
    override fun postTelemetryEvent(event: TelemetryEventInterface) {
        postEventDelegate(event)
    }

    private fun postEvent(event: TelemetryEventInterface) {
        jobControl.scope.launch {
            withContext(ThreadController.IODispatcher) { populateTelemetryEventWrapper(event) }?.let { telemetryEvent ->
                updateEventsQueue(telemetryEvent)
            }
        }
    }

    private fun updateEventsQueue(telemetryEvent: TelemetryEventInterface) {
        telemetryEventsQueue.add(telemetryEvent)
        if (telemetryEventsQueue.size >= MAX_TELEMTRY_EVENTS) {
            telemetryEventsQueue.forEach { event ->
                mapboxTelemetry.push(event as Event)
            }
            telemetryEventsQueue.clear()
        }
        telemetryEventsQueue.add(telemetryEvent)
    }

    private fun registerForNotification(mapboxNavigation: MapboxNavigation) {
        mapboxNavigation.registerRouteProgressObserver(routeProgressListener)
    }

    /**
     * Monitors all channels used in this class. This is done so that access to the containers these channels act on is serialized.
     * By serializing all calls through this choke point, we can guaranty that this class is thread safe.
     */
    private fun monitorChannels() {
        cleanupJob = jobControl.scope.monitorChannelWithException(channelLocationBuffer, { locationAction ->
            when (locationAction.command) {
                LocationBufferCommands.BUFFER_ADD -> {
                    ifNonNull(locationAction.location) { location ->
                        locationBuffer.add(location)
                        if (locationBuffer.size > MAX_LOCATION_VALUES) {
                            locationBuffer.removeAt(0)
                        }
                    }
                }
                LocationBufferCommands.BUFFER_REMOVE -> {
                    if (locationBuffer.size > 0) {
                        locationBuffer.removeAt(0)
                    }
                }
                LocationBufferCommands.BUFFER_CLEAR -> {
                    locationBuffer.clear()
                }
                LocationBufferCommands.BUFFER_READ -> {
                    locationBuffer.forEach { location ->
                        locationAction.predicate(location)
                    }
                }
            }
        })

        /**
         * This monitors the life time of the job that handles the channels. If that job is terminated, we cleanup.
         */
        jobControl.scope.launch {
            select<Unit> {
                cleanupJob.onJoin {
                    locationEngine.removeLocationUpdates(locationCallback)
                }
            }
        }
    }

    private suspend fun getDatePartitionedLocations(predicate: (Location) -> Boolean): Array<Location> {
        val retVal = mutableListOf<Location>()
        channelLocationBuffer.send(LocationBufferControl(LocationBufferCommands.BUFFER_READ, null) { location ->
            if (predicate(location)) {
                retVal.add(location)
            }
        })
        return retVal.toTypedArray()
    }

    private suspend fun parseRouteProgress(): TelemetryStep {
        return TelemetryStep().apply {
            val routeProgress = channelOnRouteProgress.receive().also { routeProgress ->
                distanceRemaining = routeProgress.distanceRemaining().toInt()
                durationRemaining = routeProgress.durationRemaining().toInt()
            }

            ifNonNull(routeProgress.currentLegProgress()?.upcomingStep()) { upcomingStep ->
                distance = upcomingStep.distance().toInt()
                duration = upcomingStep.duration().toInt()
                upcomingType = upcomingStep.maneuver().type() ?: ""
                upcomingModifier = upcomingStep.maneuver().modifier() ?: ""
                upcomingName = upcomingStep.name() ?: ""
            }

            ifNonNull(routeProgress.currentLegProgress()?.currentStepProgress()?.step()) { currentStep ->
                previousModifier = currentStep.maneuver().modifier() ?: ""
                previousName = currentStep.name() ?: ""
                previousType = currentStep.maneuver().type() ?: ""
            }
        }
    }

    /**
     * TODO:OZ add one of these for each telemetry event
     * Generates a well-formed Feedback event.
     */
    private suspend fun populateUserFeedbackEvent(event: TelemetryEventFeedback): TelemetryEventInterface =
            TelemetryUserFeedbackWrapper(event,
                    TelemetryUtils.retrieveVendorId(),
                    NavigationUtils.obtainAudioType(context),
                    getDatePartitionedLocations { location -> location.time < Time.SystemImpl.millis() },
                    getDatePartitionedLocations { location -> location.time > Time.SystemImpl.millis() },
                    UUID.randomUUID().toString(),
                    event.screenShot,
                    parseRouteProgress()
            )

    /**
     * TODO:OZ add code to handle all other event types. Once implemented, instead of throwing an exception the code
     * should log an error
     */
    private suspend fun populateTelemetryEventWrapper(event: TelemetryEventInterface): TelemetryEventInterface? =
            when (event) {
                is TelemetryEventFeedback -> populateUserFeedbackEvent(event)
                else -> throw(NavigationException("Unsupported telemetry event"))
            }

    private fun validateAccessToken(accessToken: String?) {
        if (accessToken.isNullOrEmpty() ||
                !accessToken.toLowerCase(Locale.US).startsWith("pk.") &&
                !accessToken.toLowerCase(Locale.US).startsWith("sk.")
        ) {
            throw NavigationException("A valid access token must be passed in when first initializing MapboxNavigation")
        }
    }
}
