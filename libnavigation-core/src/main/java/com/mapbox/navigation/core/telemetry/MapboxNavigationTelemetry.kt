package com.mapbox.navigation.core.telemetry

import android.content.Context
import android.location.Location
import com.mapbox.android.core.location.*
import com.mapbox.android.telemetry.Event
import com.mapbox.android.telemetry.MapboxTelemetry
import com.mapbox.navigation.base.exceptions.NavigationException
import com.mapbox.navigation.base.extensions.ifNonNull
import com.mapbox.navigation.base.logger.model.Message
import com.mapbox.navigation.base.logger.model.Tag
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.core.BuildConfig
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.MapboxNavigationOptions
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import com.mapbox.navigation.logger.MapboxLogger
import com.mapbox.navigation.utils.thread.ThreadController
import com.mapbox.navigation.utils.thread.monitorChannelWithException
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
    private const val MAPBOX_NAVIGATION_USER_AGENT_BASE = "mapbox-navigation-android"
    private const val MAPBOX_NAVIGATION_UI_USER_AGENT_BASE = "mapbox-navigation-ui-android"

    private val TAG = Tag("MAPBOX_TELEMETRY")
    private const val MAX_LOCATION_VALUES = 20
    private const val MAX_TELEMTRY_EVENTS = 100
    private const val ONE_SECOND = 1000L
    private val telemetryEventsQueue = mutableListOf<TelemetryEventInterface>()
    private val jobControl = ThreadController.getIOScopeAndRootJob()
    private val locationBuffer = mutableListOf<Location>()
    private val channelLocationBuffer = Channel<LocationBufferControl>(MAX_LOCATION_VALUES)
    private val channelOnRouteProgress = Channel<RouteProgress>(Channel.CONFLATED) // we want just the last notification
    private lateinit var cleanupJob: Job
    private lateinit var mapboxTelemetry: MapboxTelemetry
    private lateinit var locationEngine: LocationEngine

    // Location request settings
    private val locationEngineRequest: LocationEngineRequest = LocationEngineRequest.Builder(ONE_SECOND)
            .setPriority(LocationEngineRequest.PRIORITY_HIGH_ACCURACY)
            .build()

    // Call back that receives
    private val routeProgressListener = object : RouteProgressObserver {
        override fun onRouteProgressChanged(routeProgress: RouteProgress) {
            channelOnRouteProgress.offer(routeProgress)
        }
    }

    // Return path of the loction callback. This will offer data on a channel that serializes location requests
    private val locationCallback = object : LocationEngineCallback<LocationEngineResult> {
        override fun onSuccess(result: LocationEngineResult?) {
            ifNonNull(result, result?.lastLocation) { _, lastLocation ->
                channelLocationBuffer.offer(LocationBufferControl(LocationBufferCommands.BUFFER_ADD, lastLocation))
            }
        }

        override fun onFailure(exception: Exception) {
            MapboxLogger.i(TAG, Message("location services exception: $exception"))
        }
    }

    /**
     * Obtains a user agent string based on where this code is being called from
     */
    private fun obtainUserAgent(options: MapboxNavigationOptions): String? {
        return if (options.isFromNavigationUi) {
            MAPBOX_NAVIGATION_UI_USER_AGENT_BASE + BuildConfig.MAPBOX_NAVIGATION_VERSION_NAME
        } else {
            MAPBOX_NAVIGATION_USER_AGENT_BASE + BuildConfig.MAPBOX_NAVIGATION_VERSION_NAME
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
     * One-time initializer. Called in responce to initialize() and then replaced with a no-op lambda to prevent multiple initialize() calls
     */
    private val primaryInitializer: (Context, String, MapboxNavigation, LocationEngine) -> Unit = { context, token, mapboxNavigation, locationEngine ->
        this.context = context
        mapboxToken = token
        this.locationEngine = locationEngine
        val options = MapboxNavigationOptions.Builder().build()
        validateAccessToken(mapboxToken)
        initializer = postInitialize // prevent primaryInitializer() from being called more than once.
        postEventDelegate = postEventAfterInit // now that the object has been initialized we can post events

        registerForNotification(mapboxNavigation)
        monitorChannels()
        val mapboxTelemetry = MapboxTelemetry(context, token, obtainUserAgent(options))
        mapboxTelemetry.enable()

        /**
         * Register a callback to receive location events. At most [MAX_LOCATION_VALUES] are stored
         */
        locationEngine.requestLocationUpdates(locationEngineRequest, locationCallback, null)
    }
    private var initializer = primaryInitializer
    private var postInitialize: (Context, String, MapboxNavigation, LocationEngine) -> Unit = { _, _, _, _ -> }
    fun initialize(
        context: Context,
        mapboxToken: String,
        mapboxNavigation: MapboxNavigation,
        locationEngine: LocationEngine
    ) {
        initializer(context, mapboxToken, mapboxNavigation, locationEngine)
    }

    /**
     * This method is used to post all types of telemetry events to the back-end server.
     * The [event] parameter represents one of several Telemetry events avialable
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
        cleanupJob = jobControl.scope.monitorChannelWithException(channelLocationBuffer) { locationAction ->
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
        }

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

    private fun parseRouteProgressAsync(): Deferred<TelemetryStep> {
        return jobControl.scope.async {
            val telemetryStep = TelemetryStep() // Default initialization
            val routeProgress = channelOnRouteProgress.receive()
            routeProgress.currentLegProgress()?.upcomingStep()?.let { legStep ->
                telemetryStep.distance = legStep.distance().toInt()
                telemetryStep.distanceRemaining = routeProgress.distanceRemaining().toInt()
                telemetryStep.duration = legStep.duration().toInt()
                telemetryStep.durationRemaining = routeProgress.durationRemaining().toInt()
                telemetryStep.previousModifier = routeProgress.currentLegProgress()?.currentStepProgress()?.step()?.maneuver()?.modifier()
                        ?: ""
                telemetryStep.previousName = routeProgress.currentLegProgress()?.currentStepProgress()?.step()?.name()
                        ?: ""
                telemetryStep.previousType = routeProgress.currentLegProgress()?.currentStepProgress()?.step()?.maneuver()?.type()
                        ?: ""
                telemetryStep.upcomingType = legStep.maneuver().type() ?: ""
                telemetryStep.upcomingModifier = legStep.maneuver().modifier() ?: ""
                telemetryStep.upcomingName = legStep.name() ?: ""
            }
            telemetryStep
        }
    }

    /**
     * TODO:OZ add one of these for each telemetry event
     * Generates a well-formed Feedback event.
     */
    private suspend fun populateUserFeedbackEvent(event: TelemetryEventFeedback): TelemetryEventInterface {
        val phoneState = PhoneState(context)

        val feedbackEvent = TelemetryUserFeedbackWrapper(event,
                phoneState.userId,
                phoneState.audioType,
                getDatePartitionedLocations { location -> location.time < System.currentTimeMillis() },
                getDatePartitionedLocations { location -> location.time > System.currentTimeMillis() },
                UUID.randomUUID().toString(),
                event.screenShot,
                parseRouteProgressAsync().await()
        )
        return feedbackEvent
    }

    /**
     * TODO:OZ add code to handle all other event types. Once implmented, instead of throwing an exception the code
     * should log an error
     */
    private suspend fun populateTelemetryEventWrapper(event: TelemetryEventInterface): TelemetryEventInterface? =
            when (event) {
                is TelemetryEventFeedback -> populateUserFeedbackEvent(event)
                else -> throw(NavigationException("Unsupported telemetry event"))
            }

    private fun validateAccessToken(token: String?) {
        token?.let { accessToken ->
            if (accessToken.isEmpty() || !accessToken.toLowerCase(Locale.US).startsWith("pk.") && !accessToken.toLowerCase(
                            Locale.US
                    ).startsWith("sk.")
            ) {
                throw NavigationException("A valid access token must be passed in when first initializing MapboxNavigation")
            }
        }
                ?: throw NavigationException("A valid access token must be passed in when first initializing MapboxNavigation")
    }
}
