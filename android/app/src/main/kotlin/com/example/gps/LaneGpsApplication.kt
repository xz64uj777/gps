package com.example.gps

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.example.gps.location.DriveSessionStore
import com.example.gps.location.DriveTrackingService
import com.example.gps.route.ActiveNavigationStore

/**
 * App-level drive lifecycle guard.
 *
 * LaneGPS should feel live as soon as the driving screen opens, but a drive
 * must not survive after the user closes the app task and later become a long
 * "ghost" session. This keeps those two behaviors together in one place.
 */
class LaneGpsApplication : Application(), Application.ActivityLifecycleCallbacks {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity !is NavigationActivity) return
        if (!activity.isFinishing || activity.isChangingConfigurations) return
        if (com.example.gps.location.DriveSessionRuntime.hasVisibleScreen()) return

        // Back/finish should finalize the session the same way the explicit
        // STOP button does. Task removal is finalized by the service callback.
        if (DriveSessionStore(activity).isActive()) {
            activity.startService(
                Intent(activity, DriveTrackingService::class.java)
                    .setAction(DriveTrackingService.ACTION_STOP)
            )
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityResumed(activity: Activity) = Unit
}
