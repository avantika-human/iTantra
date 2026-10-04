package com.sih.itantra.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sih.itantra.engine.SherpaEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ConnectionStatus(val label: String) {
    OFFLINE("Offline"),
    LOCAL_HOTSPOT_READY("Hotspot Ready")
}

enum class Role {
    TRANSMITTER, RECEIVER
}

enum class LogLevel {
    INFO, NET, AUDIO, WARN, ERROR
}

data class LogEntry(
    val timestamp: String,
    val level: LogLevel,
    val message: String
)

data class TransceiverUiState(
    val connectionStatus: ConnectionStatus = ConnectionStatus.LOCAL_HOTSPOT_READY,
    val role: Role = Role.TRANSMITTER,
    val emergencyOverride: Boolean = false,
    val isPttActive: Boolean = false,
    val currentLanguage: String = "en",
    val logs: List<LogEntry> = emptyList()
)

class TransceiverViewModel : ViewModel() {

    private var sherpaEngine: SherpaEngine? = null

    private val _uiState = MutableStateFlow(TransceiverUiState())
    val uiState: StateFlow<TransceiverUiState> = _uiState.asStateFlow()

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun initializeEngine(context: Context) {
        if (sherpaEngine != null) return

        sherpaEngine = SherpaEngine(context) { message ->
            appendLog(LogLevel.INFO, message)
        }

        viewModelScope.launch {
            appendLog(LogLevel.INFO, "Loading local Sherpa-ONNX models...")
            sherpaEngine?.initializeStt()
            sherpaEngine?.initializeTts()
        }
    }

    fun onPermissionResult(audioGranted: Boolean, locationGranted: Boolean) {
        if (audioGranted) {
            appendLog(LogLevel.INFO, "RECORD_AUDIO permission granted.")
        } else {
            appendLog(LogLevel.ERROR, "RECORD_AUDIO permission denied! Speech recognition will fail.")
        }
        if (locationGranted) {
            appendLog(LogLevel.INFO, "ACCESS_FINE_LOCATION permission granted.")
        } else {
            appendLog(LogLevel.WARN, "Location permission denied. P2P discovery may be limited.")
        }
    }

    fun appendLog(level: LogLevel, message: String) {
        val timeStr = dateFormat.format(Date())
        val entry = LogEntry(timestamp = timeStr, level = level, message = message)
        val updatedLogs = _uiState.value.logs + entry
        _uiState.value = _uiState.value.copy(
            logs = if (updatedLogs.size > 50) updatedLogs.takeLast(50) else updatedLogs
        )
    }

    fun onRoleSelected(role: Role) {
        _uiState.value = _uiState.value.copy(role = role)
        appendLog(LogLevel.INFO, "Role switched to: $role")
    }

    fun onEmergencyOverrideChanged(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(emergencyOverride = enabled)
        appendLog(LogLevel.WARN, "Emergency override set to: $enabled")
    }

    fun switchLanguage(lang: String) {
        _uiState.value = _uiState.value.copy(currentLanguage = lang)
        sherpaEngine?.setLanguage(lang)
        appendLog(LogLevel.AUDIO, "Language changed to: $lang")
    }

    // PTT Press Down -> Start recording & STT stream
    fun onPttPressed() {
        if (_uiState.value.role != Role.TRANSMITTER) {
            appendLog(LogLevel.WARN, "PTT ignored: Device is in Receiver mode.")
            return
        }
        _uiState.value = _uiState.value.copy(isPttActive = true)
        appendLog(LogLevel.AUDIO, "PTT Pressed -> Starting mic recording & STT...")
        sherpaEngine?.startListening()
    }

    // PTT Release Up -> Stop recording, decode STT, ready for socket transmission
    fun onPttReleased() {
        if (!_uiState.value.isPttActive) return
        _uiState.value = _uiState.value.copy(isPttActive = false)
        appendLog(LogLevel.AUDIO, "PTT Released -> Stopping recording & decoding...")

        viewModelScope.launch {
            val recognizedText = sherpaEngine?.stopListeningAndDecode() ?: ""
            if (recognizedText.isNotBlank() &&
                recognizedText != "No speech detected" &&
                recognizedText != "Recognizer error") {
                appendLog(LogLevel.INFO, "Recognized Text: \"$recognizedText\"")

                // TODO: Next step is sending this 'recognizedText' string over your local P2P socket!
            } else {
                appendLog(LogLevel.WARN, "Speech recognition result: $recognizedText")
            }
        }
    }

    // Triggered when text arrives from your friend's phone over local sockets
    fun speakIncomingText(text: String) {
        appendLog(LogLevel.AUDIO, "Incoming packet received: \"$text\" -> Synthesizing TTS...")
        sherpaEngine?.speak(text)
    }

    override fun onCleared() {
        super.onCleared()
        sherpaEngine?.shutdown()
    }


}