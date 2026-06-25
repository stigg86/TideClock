package com.tideclock.widget

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

class TideClockConfig : AppCompatActivity() {
    
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var prefs: SharedPreferences
    
    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        when {
            permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false) -> {
                getLocationAndFinish()
            }
            permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false) -> {
                getLocationAndFinish()
            }
            else -> {
                // Use default location (Tenerife)
                finishWithLocation(28.37, -16.71)
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.config_layout)
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        prefs = getSharedPreferences("tideclock", MODE_PRIVATE)
        
        // Check if we already have a location
        if (prefs.contains("lat") && prefs.contains("lon")) {
            // Already configured, just finish
            finish()
            return
        }
        
        requestLocationPermission()
    }
    
    private fun requestLocationPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED -> {
                getLocationAndFinish()
            }
            else -> {
                locationPermissionRequest.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        }
    }
    
    private fun getLocationAndFinish() {
        try {
            val cancellationToken = CancellationTokenSource()
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                cancellationToken.token
            ).addOnSuccessListener { location: Location? ->
                if (location != null) {
                    finishWithLocation(location.latitude, location.longitude)
                } else {
                    // Try last known location
                    fusedLocationClient.lastLocation.addOnSuccessListener { loc: Location? ->
                        if (loc != null) {
                            finishWithLocation(loc.latitude, loc.longitude)
                        } else {
                            // Default to Tenerife
                            finishWithLocation(28.37, -16.71)
                        }
                    }.addOnFailureListener {
                        finishWithLocation(28.37, -16.71)
                    }
                }
            }.addOnFailureListener {
                finishWithLocation(28.37, -16.71)
            }
        } catch (e: SecurityException) {
            finishWithLocation(28.37, -16.71)
        }
    }
    
    private fun finishWithLocation(lat: Double, lon: Double) {
        prefs.edit()
            .putFloat("lat", lat.toFloat())
            .putFloat("lon", lon.toFloat())
            .apply()
        
        // Return the widget ID and close
        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val updateIntent = Intent(this, TideClockWidget::class.java).apply {
                action = TideClockWidget.ACTION_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            startService(updateIntent)
        }
        
        setResult(RESULT_OK)
        finish()
    }
}
