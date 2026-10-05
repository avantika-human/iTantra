package com.sih.itantra.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sih.itantra.engine.SherpaEngine
import com.sih.itantra.engine.TranslatorEngine
import com.sih.itantra.network.P2PSocketManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ConnectionStatus(val label: String) {
    OFFLINE("Offline"),
    LOCAL_HOTSPOT_READY("Hotspot Ready"),
    CONNECTED("Connected")
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
    val connectionStatus: ConnectionStatus = ConnectionStatus.OFFLINE,
    val role: Role = Role.TRANSMITTER,
    val emergencyOverride: Boolean = false,
    val isPttActive: Boolean = false,
    val currentLanguage: String = "en",
    val targetLanguage: String = "te",
    val isConnected: Boolean = false,
    val logs: List<LogEntry> = emptyList()
)

class TransceiverViewModel : ViewModel() {

    private var sherpaEngine: SherpaEngine? = null
    private var translatorEngine: TranslatorEngine? = null
    private var socketManager: P2PSocketManager? = null

    private val _uiState = MutableStateFlow(TransceiverUiState())
    val uiState: StateFlow<TransceiverUiState> = _uiState.asStateFlow()

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun initializeEngine(context: Context) {
        if (sherpaEngine != null) return

        // 1. Initialize Engines
        sherpaEngine = SherpaEngine(
            context = context.applicationContext,
            onLog = { message ->
                appendLog(LogLevel.AUDIO, message)
            }
        )

        translatorEngine = TranslatorEngine(
            context = context.applicationContext,
            onLog = { message ->
                appendLog(LogLevel.INFO, message)
            }
        )

        // 2. Initialize P2P Socket Manager
        socketManager = P2PSocketManager(
            onPacketReceived = { packet ->
                appendLog(LogLevel.NET, "RX Packet: \"${packet.payload}\" (Emergency=${packet.isEmergency})")
                speakIncomingText(packet.payload)
            },
            onLog = { message ->
                appendLog(LogLevel.NET, message)
            },
            onConnectionChanged = { connected ->
                _uiState.value = _uiState.value.copy(
                    isConnected = connected,
                    connectionStatus = if (connected) ConnectionStatus.CONNECTED else ConnectionStatus.LOCAL_HOTSPOT_READY
                )
            }
        )

        // 3. Background Thread Initialization with Throwable safety
        viewModelScope.launch(Dispatchers.IO) {
            appendLog(LogLevel.INFO, "Loading local Sherpa-ONNX & Translator models...")

            runCatching {
                sherpaEngine?.initializeStt()
            }.onFailure { t ->
                appendLog(LogLevel.ERROR, "STT Load Failed: ${t.localizedMessage}")
            }

            runCatching {
                sherpaEngine?.initializeTts()
            }.onFailure { t ->
                appendLog(LogLevel.ERROR, "TTS Load Failed: ${t.localizedMessage}")
            }

            runCatching {
                translatorEngine?.initialize()
            }.onFailure { t ->
                appendLog(LogLevel.ERROR, "Translator Load Failed: ${t.localizedMessage}")
            }

            appendLog(LogLevel.INFO, "Model loading phase completed.")
        }
    }

    // Networking Actions
    fun startHostServer() {
        appendLog(LogLevel.NET, "Starting P2P Host Server...")
        socketManager?.startServer()
    }

    fun connectToPeer(ipAddress: String) {
        val targetIp = ipAddress.ifBlank { P2PSocketManager.DEFAULT_HOST }
        appendLog(LogLevel.NET, "Connecting to Peer IP: $targetIp...")
        socketManager?.connectToPeer(host = targetIp)
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
        if (role == Role.RECEIVER) {
            startHostServer()
        }
    }

    fun onEmergencyOverrideChanged(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(emergencyOverride = enabled)
        appendLog(LogLevel.WARN, "Emergency override set to: $enabled")
    }

    fun switchSourceLanguage(lang: String) {
        _uiState.value = _uiState.value.copy(currentLanguage = lang)
        sherpaEngine?.setLanguage(lang)
        appendLog(LogLevel.AUDIO, "Source language set to: $lang")
    }

    fun switchTargetLanguage(lang: String) {
        _uiState.value = _uiState.value.copy(targetLanguage = lang)
        appendLog(LogLevel.AUDIO, "Target language set to: $lang")
    }

    // PTT Press Down -> Start recording
    fun onPttPressed() {
        if (_uiState.value.role != Role.TRANSMITTER) {
            appendLog(LogLevel.WARN, "PTT ignored: Device is in Receiver mode.")
            return
        }
        _uiState.value = _uiState.value.copy(isPttActive = true)
        appendLog(LogLevel.AUDIO, "PTT Pressed -> Starting mic recording & STT...")

        sherpaEngine?.setLanguage(_uiState.value.currentLanguage)
        sherpaEngine?.startListening()
    }

    // PTT Release Up -> Decode STT -> Translate NMT -> Send over Socket
    fun onPttReleased() {
        if (!_uiState.value.isPttActive) return
        _uiState.value = _uiState.value.copy(isPttActive = false)
        appendLog(LogLevel.AUDIO, "PTT Released -> Decoding & Translating...")

        viewModelScope.launch(Dispatchers.IO) {
            val recognizedText = sherpaEngine?.stopListeningAndDecode() ?: ""
            if (recognizedText.isNotBlank() &&
                recognizedText != "No speech detected" &&
                recognizedText != "Recognizer error") {

                appendLog(LogLevel.INFO, "STT Text: \"$recognizedText\"")

                val srcLang = _uiState.value.currentLanguage
                val tgtLang = _uiState.value.targetLanguage
                val translatedText = translatorEngine?.translate(recognizedText, srcLang, tgtLang) ?: recognizedText

                appendLog(LogLevel.INFO, "NMT Translated ($srcLang->$tgtLang): \"$translatedText\"")

                val isEmergency = _uiState.value.emergencyOverride
                val sent = socketManager?.sendPacket(payload = translatedText, isEmergency = isEmergency) ?: false

                if (sent) {
                    appendLog(LogLevel.NET, "TX Success -> Packet sent across socket!")
                } else {
                    appendLog(LogLevel.ERROR, "TX Failed -> Socket not connected.")
                }

            } else {
                appendLog(LogLevel.WARN, "Speech recognition result: $recognizedText")
            }
        }
    }

    // Triggered when text arrives from peer over local socket
    fun speakIncomingText(text: String) {
        val targetLang = _uiState.value.targetLanguage
        appendLog(LogLevel.AUDIO, "Incoming packet received: \"$text\" -> Setting TTS language to [$targetLang] & synthesizing...")

        sherpaEngine?.setLanguage(targetLang)
        sherpaEngine?.speak(text)
    }

    override fun onCleared() {
        super.onCleared()
        sherpaEngine?.shutdown()
        translatorEngine?.release()
        socketManager?.shutdown()
    }
}