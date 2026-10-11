package com.rr310.navbridge.ui

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.rr310.navbridge.R
import com.rr310.navbridge.ble.BleManager
import com.rr310.navbridge.parser.ParsedNavigation
import com.rr310.navbridge.protocol.TvsProtocol.Pictogram
import com.rr310.navbridge.service.NavBridgeService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener, LocationListener {

    private var navService: NavBridgeService? = null
    private var isBound = false
    private var tts: TextToSpeech? = null
    private var lastSpokenManeuver: String = ""

    // GPS & Telemetry
    private lateinit var locationManager: LocationManager
    private var currentSpeedKmH: Int = 0
    private var topSpeedKmH: Int = 0
    private var speedLimitThresholdKmH: Int = 80
    private var isSpeedAlertActive = false

    // Trip Computer
    private var tripStartTimeMs: Long = System.currentTimeMillis()
    private var turnsCount: Int = 0
    private var lastGpsLocation: Location? = null
    private val tripHandler = Handler(Looper.getMainLooper())

    private lateinit var cardPermission: CardView
    private lateinit var btnGrantPermission: Button
    private lateinit var tvLiveStatusBadge: TextView
    private lateinit var tvConnectionStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvPacketStats: TextView
    private lateinit var btnConnect: Button

    // Digital Twin Cluster Views
    private lateinit var tvHeartbeatIndicator: TextView
    private lateinit var tvClusterIcon: TextView
    private lateinit var tvClusterManeuver: TextView
    private lateinit var tvClusterStreet: TextView
    private lateinit var tvClusterEta: TextView

    // Speedometer & Telemetry Views
    private lateinit var tvTopSpeedText: TextView
    private lateinit var tvGpsSpeedValue: TextView
    private lateinit var tvSpeedLimitLabel: TextView
    private lateinit var btnSpeedLimit60: Button
    private lateinit var btnSpeedLimit80: Button
    private lateinit var btnSpeedLimit100: Button

    // Trip Computer Views
    private lateinit var tvTripDuration: TextView
    private lateinit var tvTurnsCount: TextView
    private lateinit var tvPhoneBattery: TextView
    private lateinit var btnResetTrip: Button

    // Custom Msg Views
    private lateinit var etCustomMessage: EditText
    private lateinit var btnSendCustomText: Button
    private lateinit var btnPresetRace: Button
    private lateinit var btnPresetPit: Button
    private lateinit var btnPresetPro: Button

    // Quick Tools
    private lateinit var btnShareLocation: Button
    private lateinit var btnFindFuel: Button

    // Test Suite Buttons
    private lateinit var btnTestTurnRight: Button
    private lateinit var btnTestTurnLeft: Button
    private lateinit var btnTestUturn: Button
    private lateinit var btnTestArrived: Button
    private lateinit var btnClearOverlay: Button

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as NavBridgeService.LocalBinder
            navService = binder.getService()
            isBound = true
            observeServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            navService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tts = TextToSpeech(this, this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        initViews()
        checkPermissions()
        startAndBindService()
        setupCallListener()
        setupBatteryReceiver()
        startGpsUpdates()
        startTripTimer()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionBanner()
        updateBatteryLevel()
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
        locationManager.removeUpdates(this)
        tripHandler.removeCallbacksAndMessages(null)
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
        }
    }

    private fun initViews() {
        cardPermission = findViewById(R.id.cardPermission)
        btnGrantPermission = findViewById(R.id.btnGrantPermission)
        tvLiveStatusBadge = findViewById(R.id.tvLiveStatusBadge)
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        tvLog = findViewById(R.id.tvLog)
        tvPacketStats = findViewById(R.id.tvPacketStats)
        btnConnect = findViewById(R.id.btnConnect)

        tvHeartbeatIndicator = findViewById(R.id.tvHeartbeatIndicator)
        tvClusterIcon = findViewById(R.id.tvClusterIcon)
        tvClusterManeuver = findViewById(R.id.tvClusterManeuver)
        tvClusterStreet = findViewById(R.id.tvClusterStreet)
        tvClusterEta = findViewById(R.id.tvClusterEta)

        // Speedometer
        tvTopSpeedText = findViewById(R.id.tvTopSpeedText)
        tvGpsSpeedValue = findViewById(R.id.tvGpsSpeedValue)
        tvSpeedLimitLabel = findViewById(R.id.tvSpeedLimitLabel)
        btnSpeedLimit60 = findViewById(R.id.btnSpeedLimit60)
        btnSpeedLimit80 = findViewById(R.id.btnSpeedLimit80)
        btnSpeedLimit100 = findViewById(R.id.btnSpeedLimit100)

        // Trip Computer
        tvTripDuration = findViewById(R.id.tvTripDuration)
        tvTurnsCount = findViewById(R.id.tvTurnsCount)
        tvPhoneBattery = findViewById(R.id.tvPhoneBattery)
        btnResetTrip = findViewById(R.id.btnResetTrip)

        // Custom Msg
        etCustomMessage = findViewById(R.id.etCustomMessage)
        btnSendCustomText = findViewById(R.id.btnSendCustomText)
        btnPresetRace = findViewById(R.id.btnPresetRace)
        btnPresetPit = findViewById(R.id.btnPresetPit)
        btnPresetPro = findViewById(R.id.btnPresetPro)

        // Quick Tools
        btnShareLocation = findViewById(R.id.btnShareLocation)
        btnFindFuel = findViewById(R.id.btnFindFuel)

        // Test Suite
        btnTestTurnRight = findViewById(R.id.btnTestTurnRight)
        btnTestTurnLeft = findViewById(R.id.btnTestTurnLeft)
        btnTestUturn = findViewById(R.id.btnTestUturn)
        btnTestArrived = findViewById(R.id.btnTestArrived)
        btnClearOverlay = findViewById(R.id.btnClearOverlay)

        btnGrantPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        btnConnect.setOnClickListener {
            val service = navService ?: return@setOnClickListener
            val ble = service.bleManager

            if (ble.connectionState.value == BleManager.ConnectionState.CONNECTED) {
                ble.disconnect()
            } else {
                ble.startScanAndConnect()
            }
        }

        // Speed Limit Controls
        btnSpeedLimit60.setOnClickListener {
            setSpeedLimit(60)
        }
        btnSpeedLimit80.setOnClickListener {
            setSpeedLimit(80)
        }
        btnSpeedLimit100.setOnClickListener {
            setSpeedLimit(100)
        }

        // Reset Trip
        btnResetTrip.setOnClickListener {
            tripStartTimeMs = System.currentTimeMillis()
            turnsCount = 0
            topSpeedKmH = 0
            tvTurnsCount.text = "0"
            tvTopSpeedText.text = "Top Speed: 0 km/h"
            Toast.makeText(this, "Trip stats reset!", Toast.LENGTH_SHORT).show()
        }

        btnSendCustomText.setOnClickListener {
            val text = etCustomMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                navService?.sendCustomText(text)
                Toast.makeText(this, "Sent to cluster: \"$text\"", Toast.LENGTH_SHORT).show()
            }
        }

        btnPresetRace.setOnClickListener {
            etCustomMessage.setText("RACE MODE")
            navService?.sendCustomText("RACE MODE")
        }
        btnPresetPit.setOnClickListener {
            etCustomMessage.setText("PIT STOP")
            navService?.sendCustomText("PIT STOP")
        }
        btnPresetPro.setOnClickListener {
            etCustomMessage.setText("RR 310 PRO")
            navService?.sendCustomText("RR 310 PRO")
        }

        // Quick Tools
        btnShareLocation.setOnClickListener {
            shareCurrentLocation()
        }
        btnFindFuel.setOnClickListener {
            findNearbyFuel()
        }

        // Manual test simulation buttons
        btnTestTurnRight.setOnClickListener {
            simulateNavigation(Pictogram.TURN_RIGHT, 300, "MG Road", 600, 4500)
        }
        btnTestTurnLeft.setOnClickListener {
            simulateNavigation(Pictogram.TURN_LEFT, 150, "Brigade Road", 300, 2200)
        }
        btnTestUturn.setOnClickListener {
            simulateNavigation(Pictogram.U_TURN, 50, "U-Turn", 120, 1000)
        }
        btnTestArrived.setOnClickListener {
            simulateNavigation(Pictogram.ARRIVED, 0, "Arrived", 0, 0)
        }
        btnClearOverlay.setOnClickListener {
            navService?.clearNavigation()
            Toast.makeText(this, "Cleared cluster display", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setSpeedLimit(limit: Int) {
        speedLimitThresholdKmH = limit
        tvSpeedLimitLabel.text = "Speed Alert: $limit km/h"
        Toast.makeText(this, "Speed Alert set to $limit km/h", Toast.LENGTH_SHORT).show()
    }

    private fun startGpsUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, this)
                } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 1f, this)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onLocationChanged(location: Location) {
        lastGpsLocation = location
        if (location.hasSpeed()) {
            val speedKmH = (location.speed * 3.6f).toInt()
            currentSpeedKmH = speedKmH
            tvGpsSpeedValue.text = speedKmH.toString()

            if (speedKmH > topSpeedKmH) {
                topSpeedKmH = speedKmH
                tvTopSpeedText.text = "Top Speed: $topSpeedKmH km/h"
            }

            if (speedKmH > speedLimitThresholdKmH && !isSpeedAlertActive) {
                isSpeedAlertActive = true
                tvGpsSpeedValue.setTextColor(Color.parseColor("#FF5252"))
                navService?.sendCustomText("SPEED ALERT!")
                speakManeuver("Warning: Speed limit exceeded!")
                tripHandler.postDelayed({ isSpeedAlertActive = false }, 5000)
            } else if (speedKmH <= speedLimitThresholdKmH) {
                tvGpsSpeedValue.setTextColor(Color.parseColor("#00E5FF"))
            }
        }
    }

    private fun startTripTimer() {
        tripHandler.post(object : Runnable {
            override fun run() {
                val elapsedSec = (System.currentTimeMillis() - tripStartTimeMs) / 1000
                val hrs = elapsedSec / 3600
                val mins = (elapsedSec % 3600) / 60
                val secs = elapsedSec % 60
                tvTripDuration.text = String.format(Locale.ROOT, "%02d:%02d:%02d", hrs, mins, secs)
                tripHandler.postDelayed(this, 1000)
            }
        })
    }

    private fun setupCallListener() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            telephonyManager?.listen(object : PhoneStateListener() {
                @Deprecated("Deprecated in API 31")
                override fun onCallStateChanged(state: Int, incomingNumber: String?) {
                    if (state == TelephonyManager.CALL_STATE_RINGING) {
                        val callerText = if (!incomingNumber.isNullOrEmpty()) "CALL:$incomingNumber" else "INCOMING CALL"
                        navService?.sendCustomText(callerText)
                        speakManeuver("Incoming phone call")
                    }
                }
            }, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    private fun setupBatteryReceiver() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent != null) {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val batteryPct = (level * 100 / scale.toFloat()).toInt()
                    tvPhoneBattery.text = "$batteryPct%"

                    if (batteryPct <= 15) {
                        tvPhoneBattery.setTextColor(Color.parseColor("#FF5252"))
                    } else {
                        tvPhoneBattery.setTextColor(Color.parseColor("#00C853"))
                    }
                }
            }
        }, filter)
    }

    private fun updateBatteryLevel() {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (batteryIntent != null) {
            val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val batteryPct = (level * 100 / scale.toFloat()).toInt()
            tvPhoneBattery.text = "$batteryPct%"
        }
    }

    private fun shareCurrentLocation() {
        val loc = lastGpsLocation
        val url = if (loc != null) {
            "https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
        } else {
            "https://maps.google.com"
        }
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "My TVS Apache RR 310 Ride Location")
            putExtra(Intent.EXTRA_TEXT, "I'm riding my TVS Apache RR 310! Track my location: $url")
        }
        startActivity(Intent.createChooser(shareIntent, "Share Location"))
    }

    private fun findNearbyFuel() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=gas+station"))
        intent.setPackage("com.google.android.apps.maps")
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Opening Google Maps...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun simulateNavigation(
        picto: Pictogram,
        dist: Int,
        street: String,
        eta: Int,
        remDist: Int
    ) {
        val testNav = ParsedNavigation(
            distanceToManeuverMeters = dist,
            remainingDistanceMeters = remDist,
            remainingTimeSeconds = eta,
            pictogram = picto,
            streetName = street,
            rawTitle = "Test ${picto.name}"
        )
        turnsCount += 1
        tvTurnsCount.text = turnsCount.toString()
        navService?.dispatchNavigation(testNav)
        Toast.makeText(this, "Sent: ${picto.name} ($dist m)", Toast.LENGTH_SHORT).show()
    }

    private fun startAndBindService() {
        val intent = Intent(this, NavBridgeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun observeServiceState() {
        val service = navService ?: return

        lifecycleScope.launch {
            service.bleManager.connectionState.collectLatest { state ->
                when (state) {
                    BleManager.ConnectionState.DISCONNECTED -> {
                        tvLiveStatusBadge.text = "OFFLINE"
                        tvLiveStatusBadge.setTextColor(Color.parseColor("#FF5252"))
                        tvConnectionStatus.text = "Status: DISCONNECTED"
                        tvConnectionStatus.setTextColor(Color.parseColor("#FF5252"))
                        btnConnect.text = "CONNECT TO RR 310"
                        btnConnect.setBackgroundColor(Color.parseColor("#00C853"))
                        tvHeartbeatIndicator.text = "● IDLE"
                        tvHeartbeatIndicator.setTextColor(Color.parseColor("#8E8E93"))
                    }
                    BleManager.ConnectionState.SCANNING -> {
                        tvLiveStatusBadge.text = "SCANNING"
                        tvLiveStatusBadge.setTextColor(Color.parseColor("#FFD54F"))
                        tvConnectionStatus.text = "Status: SCANNING..."
                        tvConnectionStatus.setTextColor(Color.parseColor("#FFD54F"))
                        btnConnect.text = "STOP SCAN"
                        btnConnect.setBackgroundColor(Color.parseColor("#FF5252"))
                    }
                    BleManager.ConnectionState.CONNECTING -> {
                        tvLiveStatusBadge.text = "CONNECTING"
                        tvLiveStatusBadge.setTextColor(Color.parseColor("#FFD54F"))
                        tvConnectionStatus.text = "Status: CONNECTING..."
                        tvConnectionStatus.setTextColor(Color.parseColor("#FFD54F"))
                        btnConnect.text = "CANCEL"
                        btnConnect.setBackgroundColor(Color.parseColor("#FF5252"))
                    }
                    BleManager.ConnectionState.CONNECTED -> {
                        tvLiveStatusBadge.text = "CONNECTED"
                        tvLiveStatusBadge.setTextColor(Color.parseColor("#00C853"))
                        tvConnectionStatus.text = "Status: CONNECTED TO CLUSTER"
                        tvConnectionStatus.setTextColor(Color.parseColor("#00C853"))
                        btnConnect.text = "DISCONNECT"
                        btnConnect.setBackgroundColor(Color.parseColor("#FF5252"))
                        tvHeartbeatIndicator.text = "🟢 ACTIVE HEARTBEAT"
                        tvHeartbeatIndicator.setTextColor(Color.parseColor("#00E5FF"))
                    }
                }
            }
        }

        lifecycleScope.launch {
            service.bleManager.lastLog.collectLatest { log ->
                tvLog.text = log
            }
        }

        lifecycleScope.launch {
            service.packetsSent.collectLatest { count ->
                tvPacketStats.text = "Packets: $count"
            }
        }

        lifecycleScope.launch {
            service.currentNav.collectLatest { nav ->
                if (nav != null) {
                    turnsCount += 1
                    tvTurnsCount.text = turnsCount.toString()

                    val symbol = getPictogramSymbol(nav.pictogram)
                    tvClusterIcon.text = symbol

                    val maneuverText = if (nav.isArrived) "Destination Arrived" else "${nav.pictogram.name} in ${nav.distanceToManeuverMeters} m"
                    tvClusterManeuver.text = maneuverText
                    tvClusterStreet.text = "Road: ${nav.streetName}"
                    tvClusterEta.text = "ETA: ${nav.remainingTimeSeconds / 60} min | Rem: ${nav.remainingDistanceMeters} m"

                    speakManeuver(maneuverText)
                } else {
                    tvClusterIcon.text = "⬆"
                    tvClusterManeuver.text = "Ready for Google Maps Navigation"
                    tvClusterStreet.text = "Road: --"
                    tvClusterEta.text = "ETA: -- | Distance: --"
                }
            }
        }
    }

    private fun getPictogramSymbol(picto: Pictogram): String {
        return when (picto) {
            Pictogram.TURN_RIGHT -> "↱"
            Pictogram.TURN_LEFT -> "↰"
            Pictogram.SLIGHT_RIGHT -> "↗"
            Pictogram.SLIGHT_LEFT -> "↖"
            Pictogram.SHARP_RIGHT -> "➔"
            Pictogram.SHARP_LEFT -> "⬅"
            Pictogram.U_TURN -> "↰↱"
            Pictogram.U_TURN_LEFT -> "↰↱"
            Pictogram.ROUNDABOUT -> "🔄"
            Pictogram.ROUNDABOUT_LEFT -> "🔄"
            Pictogram.ARRIVED -> "🏁"
            else -> "⬆"
        }
    }

    private fun speakManeuver(phrase: String) {
        if (phrase != lastSpokenManeuver) {
            lastSpokenManeuver = phrase
            tts?.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "NavAudio")
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()

        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        permissions.add(Manifest.permission.READ_PHONE_STATE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 100)
        }
    }

    private fun updatePermissionBanner() {
        val hasListenerAccess = isNotificationServiceEnabled()
        cardPermission.visibility = if (hasListenerAccess) View.GONE else View.VISIBLE
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val pkg = packageName
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(pkg)
    }
}
