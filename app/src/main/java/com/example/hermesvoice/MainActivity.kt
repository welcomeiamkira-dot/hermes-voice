package com.example.hermesvoice

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private var serviceRunning = false
    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvHeard: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            tvHeard.text = if (VoiceService.isRunning) VoiceService.status else ""
            handler.postDelayed(this, 500)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms[Manifest.permission.RECORD_AUDIO] == true
        if (granted) {
            startVoiceService()
        } else {
            tvStatus.text = "Permission denied"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnToggle = findViewById(R.id.btnToggleService)
        tvStatus = findViewById(R.id.tvStatus)
        tvHeard = findViewById(R.id.tvHeard)

        btnToggle.setOnClickListener {
            if (serviceRunning) {
                stopVoiceService()
            } else {
                ensurePermissionsAndStart()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        serviceRunning = VoiceService.isRunning
        updateUi()
        handler.post(poll)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
    }

    private fun ensurePermissionsAndStart() {
        val needsRecord = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) != PackageManager.PERMISSION_GRANTED

        val needsPostNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED

        val toRequest = mutableListOf<String>()
        if (needsRecord) toRequest.add(Manifest.permission.RECORD_AUDIO)
        if (needsPostNotifications) toRequest.add(Manifest.permission.POST_NOTIFICATIONS)

        if (toRequest.isEmpty()) {
            startVoiceService()
        } else {
            permissionLauncher.launch(toRequest.toTypedArray())
        }
    }

    private fun startVoiceService() {
        val intent = Intent(this, VoiceService::class.java).apply {
            action = VoiceService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        serviceRunning = true
        updateUi()
    }

    private fun stopVoiceService() {
        val intent = Intent(this, VoiceService::class.java)
        stopService(intent)
        serviceRunning = false
        updateUi()
    }

    private fun updateUi() {
        btnToggle.text = if (serviceRunning) "Stop Listening" else "Start Listening"
        tvStatus.text = if (serviceRunning) "Status: Running" else "Status: Stopped"
    }
}
