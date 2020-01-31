package com.mapbox.navigation.core.telemetry

interface TelemetryEventInterface

class TelemetryEventRouteProgress : TelemetryEventInterface

class TelemetryEventoArrival : TelemetryEventInterface

class TelemetryEventProgressUpdate : TelemetryEventInterface

class TelemetryEventOffRoute : TelemetryEventInterface

class TelemetryEventNavigationCancel : TelemetryEventInterface

class TelemetryEventNewRoute : TelemetryEventInterface

class TelemetryEventUpdateLocation : TelemetryEventInterface

class TelemetryEventFeedback(val feedbackType: String, val description: String, val source: String, val screenShot: String) : TelemetryEventInterface
