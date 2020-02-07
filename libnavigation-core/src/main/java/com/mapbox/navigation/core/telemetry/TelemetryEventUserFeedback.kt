package com.mapbox.navigation.core.telemetry

import android.location.Location
import com.google.gson.Gson

internal enum class FeedbackType(val feedbackType: String) {
    FEEDBACK_TYPE_GENERAL("general"),
    FEEDBACK_TYPE_ACCIDENT("accident"),
    FEEDBACK_TYPE_HAZARD("hazard"),
    FEEDBACK_TYPE_ROAD_CLOSED("road_closed"),
    FEEDBACK_TYPE_NOT_ALLOWED("not_allowed"),
    FEEDBACK_TYPE_ROUTING_ERROR("routing_error"),
    FEEDBACK_TYPE_MISSING_ROAD("missing_road"),
    FEEDBACK_TYPE_MISSING_EXIT("missing_exit"),
    FEEDBACK_TYPE_CONFUSING_INSTRUCTION("confusing_instruction"),
    FEEDBACK_TYPE_INACCURATE_GPS("inaccurate_gps"),
    FEEDBACK_SOURCE_REROUTE("reroute"),
    FEEDBACK_SOURCE_UI("user")
}

internal enum class FeedbackSource(val feedbackSource: String) {
    FEEDBACK_SOURCE_USER("user"),
    FEEDBACK_SOURCE_REROUTE("reroute"),
    FEEDBACK_SOURCE_UNKNOWN("unkknown")
}

internal data class TelemetryStep(
    var upcomingType: String = "",
    var upcomingModifier: String = "",
    var upcomingName: String = "",
    var previousType: String = "",
    var previousModifier: String = "",
    var previousName: String = "",
    var distance: Int = 0,
    var duration: Int = 0,
    var distanceRemaining: Int = 0,
    var durationRemaining: Int = 0
)

/**
 * This class contains required data for a feedback event. The caller posting a feedback event
 * does not need to provide it.
 */
internal data class TelemetryUserFeedbackWrapper(
    val userFeedback: TelemetryEventFeedback,
    val userId: String,
    val audio: String,
    val locationsBefore: Array<Location>,
    val locationsAfter: Array<Location>,
    val feedbackId: String,
    val screenshot: String,
    val step: TelemetryStep
) : TelemetryEventInterface, MetricEvent {
    fun toMetricEvent(): MetricEvent {
        return this
    }
    override val metricName = NavigationMetrics.FEEDBACK

    override fun toJson(gson: Gson): String =
            gson.toJson(this)
}
