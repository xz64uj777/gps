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

    override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity !is NavigationActivity) return

        // A foreground location service normally updates at least once every
        // few seconds. If persisted state still says ACTIVE but its last fix is
        // old, treat it as an unclean task/service shutdown rather than
        // resuming one enormous fake drive.
        val store = DriveSessionStore(activity)
        if (store.isActive()) {
            val lastUpdate = store.load().lastUpdateMillis
            val stale = lastUpdate == null ||
                System.currentTimeMillis() - lastUpdate > STALE_ACTIVE_SESSION_MS
            if (stale) {
                store.setActive(false)
                ActiveNavigationStore(activity).clear()
            }
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is NavigationActivity) return
        if (!hasDrivePermissions(activity)) return

        val store = DriveSessionStore(activity)
        if (store.isActive()) return

        // Free Drive is the default state now. Once permissions have been
        // granted, opening LaneGPS starts live GNSS/lane sensing automatically.
        val intent = Intent(activity, DriveTrackingService::class.java)
            .setAction(DriveTrackingService.ACTION_START)
            .putExtra(DriveTrackingService.EXTRA_RESET, true)
        ContextCompat.startForegroundService(activity, intent)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity !is NavigationActivity) return
        if (!activity.isFinishing || activity.isChangingConfigurations) return

        // Back/finish should finalize the session the same way the explicit
        // STOP button does. Task removal is also covered by stopWithTask=true
        // in the manifest.
        if (DriveSessionStore(activity).isActive()) {
            activity.startService(
                Intent(activity, DriveTrackingService::class.java)
                    .setAction(DriveTrackingService.ACTION_STOP)
            )
        }
    }

    private fun hasDrivePermissions(activity: Activity): Boolean {
        val locationGranted = ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!locationGranted) return false

        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private companion object {
        const val STALE_ACTIVE_SESSION_MS = 5_000L
    }
}
