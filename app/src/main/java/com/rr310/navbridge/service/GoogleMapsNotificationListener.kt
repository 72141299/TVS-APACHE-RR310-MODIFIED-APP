package com.rr310.navbridge.service

import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.rr310.navbridge.parser.GoogleMapsNotificationParser

class GoogleMapsNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "MapsNotifListener"
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "NotificationListener connected and active.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        if (sbn.packageName == "com.google.android.apps.maps") {
            val parsedNav = GoogleMapsNotificationParser.parse(sbn)
            if (parsedNav != null) {
                Log.d(TAG, "Captured Maps Nav: ${parsedNav.pictogram} (${parsedNav.distanceToManeuverMeters}m) -> ${parsedNav.streetName}")
                
                var service = NavBridgeService.instance
                if (service == null) {
                    val intent = Intent(this, NavBridgeService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                    service = NavBridgeService.instance
                }
                
                service?.dispatchNavigation(parsedNav)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn?.packageName == "com.google.android.apps.maps") {
            Log.d(TAG, "Google Maps navigation ended/dismissed")
            NavBridgeService.instance?.clearNavigation()
        }
    }
}
