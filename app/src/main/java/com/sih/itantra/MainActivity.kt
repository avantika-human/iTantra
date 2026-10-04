package com.sih.itantra

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.sih.itantra.ui.TransceiverScreen
import com.sih.itantra.viewmodel.LogLevel
import com.sih.itantra.viewmodel.TransceiverViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: TransceiverViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val audioGranted = grants[Manifest.permission.RECORD_AUDIO] == true
        val locationGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true

        viewModel.onPermissionResult(audioGranted = audioGranted, locationGranted = locationGranted)
        if (audioGranted) {
            viewModel.initializeEngine(applicationContext)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep the screen awake during field operations (walkie-talkie mode)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            TransceiverScreen(viewModel = viewModel)
        }
    }

    override fun onResume() {
        super.onResume()
        requestRuntimePermissions()
    }

    private fun requestRuntimePermissions() {
        val pending = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pending.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pending.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (pending.isEmpty()) {
            viewModel.onPermissionResult(audioGranted = true, locationGranted = true)
            viewModel.initializeEngine(applicationContext)
        } else {
            viewModel.appendLog(
                LogLevel.INFO,
                "Requesting runtime permissions: " + pending.joinToString()
            )
            permissionLauncher.launch(pending.toTypedArray())
        }
    }
}