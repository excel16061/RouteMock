package com.routemock.app.mock

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** A read-only view of what mock location needs. RouteMock never changes these settings. */
data class DeviceReadiness(
    val developerOptionsEnabled: Boolean = false,
    val mockAppSelected: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val locationEnabled: Boolean = false,
    /** Optional. Only the real-location marker and delivery observation need it. */
    val locationPermissionGranted: Boolean = false,
) {
    companion object {
        fun hasLocationPermission(context: Context): Boolean =
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }

        fun read(context: Context): DeviceReadiness {
            val appOps = context.getSystemService(AppOpsManager::class.java)
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_MOCK_LOCATION,
                Process.myUid(),
                context.packageName,
            )
            return DeviceReadiness(
                developerOptionsEnabled = Settings.Global.getInt(
                    context.contentResolver,
                    Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                    0,
                ) != 0,
                mockAppSelected = mode == AppOpsManager.MODE_ALLOWED,
                notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
                locationEnabled = context.getSystemService(LocationManager::class.java).isLocationEnabled,
                locationPermissionGranted = hasLocationPermission(context),
            )
        }
    }
}
