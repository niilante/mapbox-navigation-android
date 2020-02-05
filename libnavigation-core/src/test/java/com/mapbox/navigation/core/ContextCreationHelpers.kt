package com.mapbox.navigation.core

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.location.LocationManager
import android.media.AudioManager
import com.mapbox.android.core.location.LocationEngine
import com.mapbox.android.core.location.LocationEngineProvider
import com.mapbox.android.telemetry.MapboxTelemetryConstants.MAPBOX_SHARED_PREFERENCES
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.util.Locale

fun createContext(): Context {
    val localLocationEngine: LocationEngine = mockk(relaxed = true)
    val mockedContext = mockk<Context>()
    val mockedBroadcastReceiverIntent = mockk<Intent>()
    val mockedConfiguration = Configuration()
    mockedConfiguration.locale = Locale("en")
    val mockedResources = mockk<Resources>(relaxed = true)
    every { mockedResources.configuration } returns (mockedConfiguration)
    every { mockedContext.resources } returns (mockedResources)
    val mockedPackageManager = mockk<PackageManager>(relaxed = true)
    every { mockedContext.packageManager } returns (mockedPackageManager)
    every { mockedContext.packageName } returns ("com.mapbox.navigation.trip.notification")
    every { mockedContext.getString(any()) } returns "FORMAT_STRING"
    val notificationManager = mockk<NotificationManager>(relaxed = true)
    every { mockedContext.getSystemService(Context.NOTIFICATION_SERVICE) } returns (notificationManager)
    every { mockedContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager } returns mockk(relaxed = true)
    every { mockedContext.getSystemService(Context.LOCATION_SERVICE) as LocationEngine } returns mockk(relaxed = true)
    every { LocationEngineProvider.getBestLocationEngine(mockedContext) } returns mockk(relaxed = true)
    every {
        mockedContext.registerReceiver(
                any(),
                any()
        )
    } returns (mockedBroadcastReceiverIntent)
    every { mockedContext.unregisterReceiver(any()) } just Runs
    every { mockedContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager } returns mockk(relaxed = true)
    every { mockedContext.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager } returns mockk(relaxed = true)
    every { mockedContext.getSharedPreferences(MAPBOX_SHARED_PREFERENCES, Context.MODE_PRIVATE) } returns mockk(relaxed = true)
    every { mockedContext.applicationContext.getSharedPreferences(MAPBOX_SHARED_PREFERENCES, Context.MODE_PRIVATE) } returns mockk(relaxed = true)
    every { mockedContext.applicationContext.getString(any()) } returns "some string"
    every { mockedContext.applicationContext.getResources() } returns mockk(relaxed = true)
    every { localLocationEngine.requestLocationUpdates(mockk(), any(), null) } returns mockk(relaxed = true)

    every { mockedContext.getMainLooper() } returns mockk(relaxed = true)
    every { mockedContext.applicationContext.getMainLooper() } returns mockk(relaxed = true)
    every { mockedContext.applicationContext.contentResolver } returns mockk(relaxed = true)
    every { mockedContext.applicationContext.getPackageManager() } returns mockk(relaxed = true)
    every { mockedContext.applicationContext.getPackageName() } returns "MyPackageName"
    every { mockedContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager } returns mockk(relaxed = true)
    return mockedContext
}
