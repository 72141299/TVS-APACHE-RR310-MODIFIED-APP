package com.rr310.navbridge.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.rr310.navbridge.ble.BleManager
import com.rr310.navbridge.parser.GoogleMapsNotificationParser
import com.rr310.navbridge.parser.ParsedNavigation
import com.rr310.navbridge.protocol.TvsProtocol
import com.rr310.navbridge.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NavBridgeService : Service() {

    companion object {
        const val CHANNEL_ID = "rr310_bridge_channel"
        const val NOTIFICATION_ID = 310
        private const val TAG = "NavBridgeService"

        @Volatile
        var instance: NavBridgeService? = null
            private set
    }

    inner class LocalBinder : Binder() {
        fun getService(): NavBridgeService = this@NavBridgeService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null

    lateinit var bleManager: BleManager
        private set

    private val _currentNav = MutableStateFlow<ParsedNavigation?>(null)
    val currentNav: StateFlow<ParsedNavigation?> = _currentNav

    private val _packetsSent = MutableStateFlow(0)
    val packetsSent: StateFlow<Int> = _packetsSent

    override fun onCreate() {
        super.onCreate()
        instance = this
        bleManager = BleManager(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification("Waiting for Google Maps navigation..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        stopHeartbeat()
        instance = null
        bleManager.disconnect()
    }

    fun dispatchNavigation(nav: ParsedNavigation) {
        _currentNav.value = nav
        Log.i(TAG, "Relaying Nav: ${nav.pictogram} in ${nav.distanceToManeuverMeters}m on ${nav.streetName}")

        val notification = buildForegroundNotification(
            "${nav.pictogram.name}: ${nav.distanceToManeuverMeters}m -> ${nav.streetName}"
        )
        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifManager.notify(NOTIFICATION_ID, notification)

        // Send immediately, then ensure the periodic heartbeat loop is running
        sendCurrentNavPacket(nav)
        startHeartbeatLoop()
    }

    fun clearNavigation() {
        stopHeartbeat()
        _currentNav.value = null
        GoogleMapsNotificationParser.resetParserState()

        val notification = buildForegroundNotification("Navigation Ended")
        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifManager.notify(NOTIFICATION_ID, notification)

        if (bleManager.connectionState.value == BleManager.ConnectionState.CONNECTED) {
            serviceScope.launch {
                val clearPacket = TvsProtocol.buildClearNavigationPacket()
                bleManager.sendPacket(clearPacket)
                _packetsSent.value += 1
            }
        }
    }

    fun sendCustomText(text: String) {
        if (bleManager.connectionState.value == BleManager.ConnectionState.CONNECTED) {
            serviceScope.launch {
                val textPacket = TvsProtocol.buildStreetNamePacket(text)
                bleManager.sendPacket(textPacket)
                _packetsSent.value += 1
            }
        }
    }

    private fun startHeartbeatLoop() {
        if (heartbeatJob?.isActive == true) return

        heartbeatJob = serviceScope.launch {
            while (isActive) {
                delay(1500) // Heartbeat interval: re-transmit every 1.5s to prevent TFT overlay timeout
                val nav = _currentNav.value
                if (nav != null && bleManager.connectionState.value == BleManager.ConnectionState.CONNECTED) {
                    sendCurrentNavPacket(nav)
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun sendCurrentNavPacket(nav: ParsedNavigation) {
        serviceScope.launch {
            if (bleManager.connectionState.value == BleManager.ConnectionState.CONNECTED) {
                val navPacket = TvsProtocol.buildNavigationPacket(
                    distanceMeters = nav.distanceToManeuverMeters,
                    remainingDistanceMeters = nav.remainingDistanceMeters,
                    remainingTimeSeconds = nav.remainingTimeSeconds,
                    pictogram = nav.pictogram,
                    isRerouting = nav.isRerouting
                )
                bleManager.sendPacket(navPacket)
                _packetsSent.value += 1

                delay(50) // Small GATT write spacing

                if (nav.streetName.isNotEmpty()) {
                    val textPacket = TvsProtocol.buildStreetNamePacket(nav.streetName)
                    bleManager.sendPacket(textPacket)
                    _packetsSent.value += 1
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "RR 310 Navigation Bridge",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps connection alive to Apache RR 310 TFT Cluster"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TVS Apache RR 310 Nav Bridge")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
