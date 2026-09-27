package com.example.gps

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.example.gps.location.DriveSessionRuntime
import com.example.gps.location.DriveSessionStore
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng

/**
 * Temporary validation screen used while LaneGPS migrates the driving map from
 * MapLibre to Google Maps. It intentionally does not own routing or lane logic.
 *
 * Once the Google map path is proven on the physical Fold test device, this
 * controller will replace the MapLibre surface in NavigationActivity.
 */
class GoogleTrafficActivity : ComponentActivity() {
    private lateinit var mapView: MapView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isConfigured(this)) {
            Toast.makeText(
                this,
                "Google Maps is not configured in this build yet.",
                Toast.LENGTH_LONG,
            ).show()
            finish()
            return
        }

        mapView = MapView(this)
        setContentView(mapView)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map ->
            map.mapType = GoogleMap.MAP_TYPE_NORMAL
            map.uiSettings.isCompassEnabled = true
            map.uiSettings.isRotateGesturesEnabled = true
            map.uiSettings.isTiltGesturesEnabled = true
            map.uiSettings.isZoomControlsEnabled = false
            map.isTrafficEnabled = true

            val state = DriveSessionRuntime.latest() ?: DriveSessionStore(this).load()
            val lat = state.latitude
            val lon = state.longitude
            if (state.fixReceived && lat != null && lon != null) {
                val target = LatLng(lat, lon)
                map.moveCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(target)
                            .zoom(13.5f)
                            .tilt(35f)
                            .build()
                    )
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (::mapView.isInitialized) mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        if (::mapView.isInitialized) mapView.onResume()
    }

    override fun onPause() {
        if (::mapView.isInitialized) mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        if (::mapView.isInitialized) mapView.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::mapView.isInitialized) mapView.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::mapView.isInitialized) mapView.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::mapView.isInitialized) mapView.onDestroy()
        super.onDestroy()
    }

    companion object {
        fun isConfigured(context: Context): Boolean {
            val info = context.packageManager.getApplicationInfo(
                context.packageName,
                PackageManager.GET_META_DATA,
            )
            return info.metaData
                ?.getString("com.google.android.geo.API_KEY")
                ?.trim()
                ?.isNotEmpty() == true
        }
    }
}
