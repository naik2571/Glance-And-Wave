package com.glancewave.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.glancewave.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        checkPermissions()
        if (results.all { it.value }) {
            Toast.makeText(this, "All permissions granted!", Toast.LENGTH_SHORT).show()
            startGlanceWaveService()
        }
    }

    private val sensorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            if (intent.action == GlanceWaveService.BROADCAST_SENSOR_UPDATE) {
                if (intent.hasExtra(GlanceWaveService.EXTRA_PROXIMITY_VAL)) {
                    val prox = intent.getFloatExtra(GlanceWaveService.EXTRA_PROXIMITY_VAL, 5f)
                    val label = if (prox < 4.0f) "Wave Close ($prox cm)" else "Clear ($prox cm)"
                    binding.tvProximityValue.text = label
                    binding.tvProximityValue.setTextColor(if (prox < 4.0f) Color.parseColor("#10B981") else Color.parseColor("#38BDF8"))
                }

                if (intent.hasExtra(GlanceWaveService.EXTRA_IS_FLIPPED)) {
                    val isFlipped = intent.getBooleanExtra(GlanceWaveService.EXTRA_IS_FLIPPED, false)
                    val label = if (isFlipped) "Flipped Face-Down 🛑" else "Screen Up 📱"
                    binding.tvOrientationValue.text = label
                    binding.tvOrientationValue.setTextColor(if (isFlipped) Color.parseColor("#EF4444") else Color.parseColor("#38BDF8"))
                }

                if (intent.hasExtra(GlanceWaveService.EXTRA_GESTURE_EVENT)) {
                    val event = intent.getStringExtra(GlanceWaveService.EXTRA_GESTURE_EVENT) ?: ""
                    binding.tvLastGesture.text = "⚡ Detected: $event"
                    binding.tvLastGesture.setBackgroundColor(Color.parseColor("#1E3A8A"))
                    binding.tvLastGesture.setTextColor(Color.parseColor("#FDE047"))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(GlanceWaveService.PREFS_NAME, Context.MODE_PRIVATE)

        setupUI()
        checkPermissions()

        if (prefs.getBoolean(GlanceWaveService.KEY_ENABLED, false)) {
            startGlanceWaveService()
        }
    }

    private fun setupUI() {
        val isEnabled = prefs.getBoolean(GlanceWaveService.KEY_ENABLED, false)
        binding.switchMaster.isChecked = isEnabled
        if (isEnabled) {
            binding.tvServiceStatus.text = "Service: Active & Ready"
            binding.tvServiceStatus.setTextColor(Color.parseColor("#10B981"))
        } else {
            binding.tvServiceStatus.text = "Service: Stopped (Dormant)"
            binding.tvServiceStatus.setTextColor(Color.parseColor("#94A3B8"))
        }
        binding.switchWaveAnswer.isChecked = prefs.getBoolean(GlanceWaveService.KEY_WAVE_ANSWER, true)
        binding.switchAutoSpeaker.isChecked = prefs.getBoolean(GlanceWaveService.KEY_AUTO_SPEAKER, false)
        binding.switchFlipSilence.isChecked = prefs.getBoolean(GlanceWaveService.KEY_FLIP_SILENCE, true)

        binding.switchMaster.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(GlanceWaveService.KEY_ENABLED, isChecked).apply()
            if (isChecked) {
                startGlanceWaveService()
                binding.tvServiceStatus.text = "Service: Active & Ready"
                binding.tvServiceStatus.setTextColor(Color.parseColor("#10B981"))
            } else {
                stopGlanceWaveService()
                binding.tvServiceStatus.text = "Service: Stopped"
                binding.tvServiceStatus.setTextColor(Color.parseColor("#EF4444"))
            }
        }

        binding.switchWaveAnswer.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(GlanceWaveService.KEY_WAVE_ANSWER, isChecked).apply()
        }

        binding.switchAutoSpeaker.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(GlanceWaveService.KEY_AUTO_SPEAKER, isChecked).apply()
        }

        binding.switchFlipSilence.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(GlanceWaveService.KEY_FLIP_SILENCE, isChecked).apply()
        }

        binding.btnSimulateCall.setOnClickListener {
            val intent = Intent(this, GlanceWaveService::class.java).apply {
                action = GlanceWaveService.ACTION_SIMULATE_CALL
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Toast.makeText(this, "Simulating incoming call! Wave or flip now.", Toast.LENGTH_SHORT).show()
        }

        binding.btnGrantPermissions.setOnClickListener {
            requestRequiredPermissions()
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        permissions.add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissions.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            binding.cardPermissions.strokeColor = Color.parseColor("#10B981")
            binding.btnGrantPermissions.text = "✓ Permissions Granted"
            binding.btnGrantPermissions.setBackgroundColor(Color.parseColor("#065F46"))
            binding.btnGrantPermissions.isEnabled = false
        } else {
            binding.cardPermissions.strokeColor = Color.parseColor("#EF4444")
            binding.btnGrantPermissions.text = "Grant Permissions"
            binding.btnGrantPermissions.setBackgroundColor(Color.parseColor("#334155"))
            binding.btnGrantPermissions.isEnabled = true
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf<String>()
        permissions.add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissions.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun startGlanceWaveService() {
        val intent = Intent(this, GlanceWaveService::class.java).apply {
            action = GlanceWaveService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopGlanceWaveService() {
        val intent = Intent(this, GlanceWaveService::class.java).apply {
            action = GlanceWaveService.ACTION_STOP
        }
        startService(intent)
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
        val filter = IntentFilter(GlanceWaveService.BROADCAST_SENSOR_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(sensorReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(sensorReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(sensorReceiver)
        } catch (_: Exception) {}
    }
}
