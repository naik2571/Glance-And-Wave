package com.glancewave.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * High-performance Touchless Call Gestures Service.
 * Calibrated specifically for Realme 7 Pro hardware proximity sensor:
 *  - Fast Wave (80ms - 450ms pulse): Answer Call + Loudspeaker
 *  - Sustained Palm Cover (> 1000ms): Mute Ringer
 *  - Flip Face-Down (Z < -7.0): Mute Ringer
 *  - 0% Idle Battery Drain: Sensors only active when device is actively ringing.
 */
class GlanceWaveService : Service(), SensorEventListener {

    companion object {
        const val TAG = "GlanceWaveService"
        const val CHANNEL_ID = "glance_wave_channel"
        const val NOTIFICATION_ID = 4001

        const val ACTION_START = "com.glancewave.app.START"
        const val ACTION_STOP = "com.glancewave.app.STOP"
        const val ACTION_RESUME = "com.glancewave.app.RESUME"
        const val ACTION_PHONE_STATE = "com.glancewave.app.PHONE_STATE"
        const val ACTION_SIMULATE_CALL = "com.glancewave.app.SIMULATE_CALL"
        const val EXTRA_STATE = "state"

        const val PREFS_NAME = "glance_wave_prefs"
        const val KEY_ENABLED = "enabled"
        const val KEY_AUTO_SPEAKER = "auto_speaker"
        const val KEY_FLIP_SILENCE = "flip_silence"
        const val KEY_WAVE_ANSWER = "wave_answer"

        const val BROADCAST_SENSOR_UPDATE = "com.glancewave.app.SENSOR_UPDATE"
        const val EXTRA_PROXIMITY_VAL = "proximity_val"
        const val EXTRA_IS_FLIPPED = "is_flipped"
        const val EXTRA_GESTURE_EVENT = "gesture_event"

        var isServiceRunning = false
            private set
    }

    private lateinit var sensorManager: SensorManager
    private var proximitySensor: Sensor? = null
    private var accelerometer: Sensor? = null
    private lateinit var audioManager: AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private var isRinging = false
    private var isSimulating = false
    private var isMutedThisCall = false
    private var isAnsweredThisCall = false
    private var areSensorsRegistered = false

    // Proximity wave temporal state machine
    private var nearStartTimestamp: Long = 0
    private var wasNear = false
    private var lastWaveTimestamp: Long = 0

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        createNotificationChannel()
        isServiceRunning = true
        Log.i(TAG, "GlanceWaveService initialized successfully")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_STOP -> {
                Log.i(TAG, "Master STOP action received from notification.")
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_ENABLED, false).apply()
                stopAllMonitoring()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                isServiceRunning = false
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                Log.i(TAG, "Master RESUME action received.")
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_ENABLED, true).apply()
                startForeground(NOTIFICATION_ID, buildNotification("Glance & Wave: Active", "Ready for incoming calls"))
            }
            ACTION_PHONE_STATE -> {
                val state = intent?.getStringExtra(EXTRA_STATE)
                handlePhoneState(state)
            }
            ACTION_SIMULATE_CALL -> {
                startSimulatedCall()
            }
            else -> {
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                if (!prefs.getBoolean(KEY_ENABLED, false)) {
                    Log.i(TAG, "Glance & Wave is disabled in preferences. Staying dormant.")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, buildNotification("Glance & Wave: Standby", "Dormant: 0% battery. Sensors wake on call."))
            }
        }

        return START_NOT_STICKY
    }

    private fun handlePhoneState(state: String?) {
        Log.i(TAG, "Phone State event received: $state")
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                isRinging = true
                isMutedThisCall = false
                isAnsweredThisCall = false
                registerSensors(SensorManager.SENSOR_DELAY_FASTEST)
                updateNotification("Incoming Call Ringing!", "Wave hand to answer • Cover/Flip to silence")
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                isRinging = false
                unregisterSensors()
                updateNotification("Call in Progress", "Sensors dormant • Glance & Wave active")
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                isRinging = false
                isSimulating = false
                isMutedThisCall = false
                isAnsweredThisCall = false
                unregisterSensors()
                updateNotification("Glance & Wave: Standby", "Sensors dormant • Ready for incoming calls")
            }
        }
    }

    private fun startSimulatedCall() {
        isSimulating = true
        isRinging = true
        isMutedThisCall = false
        isAnsweredThisCall = false
        registerSensors(SensorManager.SENSOR_DELAY_FASTEST)
        updateNotification("Simulating Incoming Call", "Wave hand to answer • Cover/Flip to silence (20s test)")
        vibrateFeedback(longArrayOf(0, 150, 100, 150))

        handler.postDelayed({
            if (isSimulating) {
                isSimulating = false
                isRinging = false
                unregisterSensors()
                updateNotification("Glance & Wave: Standby", "Simulation ended. Ready for incoming calls.")
                broadcastGesture("Test Call Timeout")
            }
        }, 20000)
    }

    private fun registerSensors(delay: Int) {
        if (areSensorsRegistered) return
        proximitySensor?.let { sensorManager.registerListener(this, it, delay) }
        accelerometer?.let { sensorManager.registerListener(this, it, delay) }
        areSensorsRegistered = true
        Log.i(TAG, "High-frequency sensors registered for active call")
    }

    private fun unregisterSensors() {
        if (!areSensorsRegistered) return
        sensorManager.unregisterListener(this)
        areSensorsRegistered = false
        Log.i(TAG, "Sensors unregistered (0% idle battery consumption)")
    }

    private fun stopAllMonitoring() {
        try {
            unregisterSensors()
            handler.removeCallbacksAndMessages(null)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping monitoring: ${e.message}")
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return

        when (event.sensor.type) {
            Sensor.TYPE_PROXIMITY -> {
                val distance = event.values[0]
                val maxRange = event.sensor.maximumRange
                // Binary proximity sensor check (Realme 7 Pro outputs 0.0cm for near and 5.0cm for far)
                val isNear = distance < 3.5f && distance < maxRange

                val updateIntent = Intent(BROADCAST_SENSOR_UPDATE).apply {
                    putExtra(EXTRA_PROXIMITY_VAL, distance)
                }
                sendBroadcast(updateIntent)

                if (isRinging && !isAnsweredThisCall) {
                    processProximityTemporalEvent(isNear)
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val z = event.values[2]
                val isFlipped = z < -7.0f

                val updateIntent = Intent(BROADCAST_SENSOR_UPDATE).apply {
                    putExtra(EXTRA_IS_FLIPPED, isFlipped)
                }
                sendBroadcast(updateIntent)

                if (isRinging && !isMutedThisCall && isFlipped) {
                    processFlipToSilence()
                }
            }
        }
    }

    /**
     * Temporal pulse classifier:
     * - Fast wave (80ms - 450ms): User swiped hand across the top sensor -> ANSWER
     * - Sustained palm cover (> 1000ms): User held hand over screen -> SILENCE
     */
    private fun processProximityTemporalEvent(isNear: Boolean) {
        val now = System.currentTimeMillis()

        if (isNear) {
            if (!wasNear) {
                wasNear = true
                nearStartTimestamp = now
            } else {
                // Ongoing near: check if user is holding palm over phone to silence
                val duration = now - nearStartTimestamp
                if (duration > 1000L && !isMutedThisCall) {
                    Log.i(TAG, "Sustained palm cover detected (${duration}ms). Muting ringer.")
                    processCoverToSilence()
                }
            }
        } else {
            if (wasNear) {
                val pulseDuration = now - nearStartTimestamp
                wasNear = false
                Log.i(TAG, "Proximity pulse ended: duration = ${pulseDuration}ms")

                // Valid natural air wave: 80ms to 450ms
                if (pulseDuration in 80..450 && (now - lastWaveTimestamp > 600L)) {
                    lastWaveTimestamp = now
                    isAnsweredThisCall = true
                    Log.i(TAG, "Natural Wave gesture validated! Answering incoming call.")
                    triggerAnswerCall()
                }
            }
        }
    }

    private fun processCoverToSilence() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_FLIP_SILENCE, true)) return

        isMutedThisCall = true
        vibrateFeedback(longArrayOf(0, 80))
        silenceRingerStream()
        broadcastGesture("Silenced (Palm Cover)")
        updateNotification("Call Silenced", "Ringer muted via Palm Cover")
    }

    private fun processFlipToSilence() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_FLIP_SILENCE, true)) return

        isMutedThisCall = true
        Log.i(TAG, "Flip face-down detected! Silencing ringer.")
        vibrateFeedback(longArrayOf(0, 80))
        silenceRingerStream()
        broadcastGesture("Silenced (Flip Face-Down)")
        updateNotification("Call Silenced", "Ringer muted via Flip Face-Down")
    }

    private fun silenceRingerStream() {
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to silence ringer stream: ${e.message}")
        }
    }

    private fun triggerAnswerCall() {
        vibrateFeedback(longArrayOf(0, 100, 80, 150))
        broadcastGesture("Answered (Wave Gesture)")

        if (isSimulating) {
            updateNotification("Call Answered!", "Simulation Success: Call answered by waving!")
            handler.postDelayed({
                isSimulating = false
                isRinging = false
                unregisterSensors()
                updateNotification("Glance & Wave: Standby", "Ready for incoming calls")
            }, 3000)
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                telecomManager.acceptRingingCall()
                Log.i(TAG, "telecomManager.acceptRingingCall() invoked successfully")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing ANSWER_PHONE_CALLS permission: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error answering call: ${e.message}")
        }

        // Auto-enable speakerphone for true hands-free call
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_AUTO_SPEAKER, true)) {
            handler.postDelayed({
                try {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    audioManager.isSpeakerphoneOn = true
                    Log.i(TAG, "Speakerphone auto-enabled for hands-free conversation")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to enable speakerphone: ${e.message}")
                }
            }, 600)
        }
    }

    private fun broadcastGesture(event: String) {
        val intent = Intent(BROADCAST_SENSOR_UPDATE).apply {
            putExtra(EXTRA_GESTURE_EVENT, event)
        }
        sendBroadcast(intent)
    }

    private fun vibrateFeedback(pattern: LongArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    v.vibrate(pattern, -1)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration feedback failed: ${e.message}")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Glance & Wave Touchless Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing call gesture answer and flip to silence monitor"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, message: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, GlanceWaveService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val resumeIntent = Intent(this, GlanceWaveService::class.java).apply {
            action = ACTION_RESUME
        }
        val resumePendingIntent = PendingIntent.getService(
            this, 2, resumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "🛑 STOP / CLOSE", stopPendingIntent)
            .addAction(android.R.drawable.ic_media_play, "▶ RESUME", resumePendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(title: String, message: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(title, message))
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        stopAllMonitoring()
        Log.i(TAG, "GlanceWaveService destroyed.")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
