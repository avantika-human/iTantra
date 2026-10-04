package com.sih.itantra.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sih.itantra.viewmodel.ConnectionStatus
import com.sih.itantra.viewmodel.LogEntry
import com.sih.itantra.viewmodel.LogLevel
import com.sih.itantra.viewmodel.Role
import com.sih.itantra.viewmodel.TransceiverViewModel

// ---------------------------------------------------------------------------
// Tactical dark palette
// ---------------------------------------------------------------------------
private val TacticalBlack = Color(0xFF0A0F14)
private val PanelGray = Color(0xFF141C24)
private val ConsoleBlack = Color(0xFF0D1319)
private val NeonCyan = Color(0xFF22D3EE)
private val SignalGreen = Color(0xFF34D399)
private val AlertRed = Color(0xFFF87171)
private val AmberWarn = Color(0xFFFBBF24)
private val AudioViolet = Color(0xFFC084FC)
private val TextPrimary = Color(0xFFE6EDF3)
private val TextDim = Color(0xFF8B98A5)

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------
@Composable
fun TransceiverScreen(viewModel: TransceiverViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = NeonCyan,
            background = TacticalBlack,
            surface = PanelGray,
            onPrimary = TacticalBlack
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                StatusHeader(connectionStatus = uiState.connectionStatus)

                RoleSelector(
                    selectedRole = uiState.role,
                    onRoleSelected = viewModel::onRoleSelected
                )

                // Language Selector for switching between English (en) and Telugu (te)
                LanguageSelector(
                    currentLanguage = uiState.currentLanguage,
                    onLanguageSelected = viewModel::switchSourceLanguage
                )

                EmergencyPrioritySwitch(
                    enabled = uiState.emergencyOverride,
                    onCheckedChange = viewModel::onEmergencyOverrideChanged
                )
                PttButton(
                    isTransmitter = uiState.role == Role.TRANSMITTER,
                    isPttActive = uiState.isPttActive,
                    onPressed = viewModel::onPttPressed,
                    onReleased = viewModel::onPttReleased
                )
                ConsoleLogBox(
                    logs = uiState.logs,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Header + connection status badge
// ---------------------------------------------------------------------------
@Composable
private fun StatusHeader(connectionStatus: ConnectionStatus) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(PanelGray)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = "iTantra",
                color = NeonCyan,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Walkie-Talkie Neural Transceiver",
                color = TextDim,
                fontSize = 12.sp
            )
        }
        ConnectionBadge(status = connectionStatus)
    }
}

@Composable
private fun ConnectionBadge(status: ConnectionStatus) {
    val (badgeColor, label) = when (status) {
        ConnectionStatus.OFFLINE -> AlertRed to status.label
        ConnectionStatus.LOCAL_HOTSPOT_READY -> SignalGreen to status.label
        ConnectionStatus.CONNECTED -> SignalGreen to status.label
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(badgeColor.copy(alpha = 0.15f))
            .border(BorderStroke(1.dp, badgeColor), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(badgeColor)
        )
        Text(
            text = label,
            color = badgeColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ---------------------------------------------------------------------------
// Role selector: Transmitter (STT) vs Receiver (TTS)
// ---------------------------------------------------------------------------
@Composable
private fun RoleSelector(
    selectedRole: Role,
    onRoleSelected: (Role) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(PanelGray)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        RoleOption(
            label = "Transmitter (STT)",
            subtitle = "Speak -> transmit",
            isSelected = selectedRole == Role.TRANSMITTER,
            modifier = Modifier.weight(1f),
            onClick = { onRoleSelected(Role.TRANSMITTER) }
        )
        RoleOption(
            label = "Receiver (TTS)",
            subtitle = "Listen -> playback",
            isSelected = selectedRole == Role.RECEIVER,
            modifier = Modifier.weight(1f),
            onClick = { onRoleSelected(Role.RECEIVER) }
        )
    }
}

@Composable
private fun RoleOption(
    label: String,
    subtitle: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val tint = if (isSelected) NeonCyan else TextDim
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) NeonCyan.copy(alpha = 0.16f) else Color.Transparent)
            .border(
                BorderStroke(1.dp, if (isSelected) NeonCyan else Color(0xFF22303B)),
                RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = label, color = tint, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(text = subtitle, color = TextDim, fontSize = 11.sp)
    }
}

// ---------------------------------------------------------------------------
// Language selector: English (en) vs Telugu (te)
// ---------------------------------------------------------------------------
@Composable
private fun LanguageSelector(
    currentLanguage: String,
    onLanguageSelected: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(PanelGray)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        LanguageOption(
            label = "English (EN)",
            isSelected = currentLanguage == "en",
            modifier = Modifier.weight(1f),
            onClick = { onLanguageSelected("en") }
        )
        LanguageOption(
            label = "Telugu (TE)",
            isSelected = currentLanguage == "te",
            modifier = Modifier.weight(1f),
            onClick = { onLanguageSelected("te") }
        )
    }
}

@Composable
private fun LanguageOption(
    label: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val tint = if (isSelected) SignalGreen else TextDim
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) SignalGreen.copy(alpha = 0.16f) else Color.Transparent)
            .border(
                BorderStroke(1.dp, if (isSelected) SignalGreen else Color(0xFF22303B)),
                RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = tint,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
    }
}

// ---------------------------------------------------------------------------
// Emergency priority switch
// ---------------------------------------------------------------------------
@Composable
private fun EmergencyPrioritySwitch(
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(PanelGray)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Alert Override (100% Vol)",
                color = if (enabled) AlertRed else TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Text(
                text = if (enabled) {
                    "EMERGENCY traffic -> force max media volume, bypass silence"
                } else {
                    "Normal priority audio routing"
                },
                color = TextDim,
                fontSize = 11.sp
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AlertRed,
                checkedTrackColor = AlertRed.copy(alpha = 0.4f),
                uncheckedThumbColor = TextDim,
                uncheckedTrackColor = Color(0xFF22303B)
            )
        )
    }
}

// ---------------------------------------------------------------------------
// Massive push-to-talk button: press = STT start, release = send
// ---------------------------------------------------------------------------
@Composable
private fun PttButton(
    isTransmitter: Boolean,
    isPttActive: Boolean,
    onPressed: () -> Unit,
    onReleased: () -> Unit
) {
    val ringColor = when {
        isPttActive -> AlertRed
        isTransmitter -> NeonCyan
        else -> TextDim
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(210.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(190.dp)
                .clip(CircleShape)
                .background(ringColor.copy(alpha = if (isPttActive) 0.30f else 0.14f))
                .border(BorderStroke(4.dp, ringColor), CircleShape)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            onPressed()
                            tryAwaitRelease()
                            onReleased()
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = when {
                        !isTransmitter -> "RECEIVE MODE"
                        isPttActive -> "ON AIR"
                        else -> "HOLD TO TALK"
                    },
                    color = ringColor,
                    fontWeight = FontWeight.Black,
                    fontSize = 20.sp,
                    letterSpacing = 2.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = when {
                        !isTransmitter -> "Standby for incoming traffic"
                        isPttActive -> "Streaming mic -> STT"
                        else -> "Press & hold to speak"
                    },
                    color = TextDim,
                    fontSize = 11.sp
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Live scrollable console log
// ---------------------------------------------------------------------------
@Composable
private fun ConsoleLogBox(
    logs: List<LogEntry>,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ConsoleBlack)
            .border(BorderStroke(1.dp, Color(0xFF1E2A33)), RoundedCornerShape(16.dp))
    ) {
        Text(
            text = "SYSTEM CONSOLE",
            color = TextDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            letterSpacing = 1.sp,
            modifier = Modifier
                .fillMaxWidth()
                .background(PanelGray)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(logs) { entry ->
                Text(
                    text = "${entry.timestamp}  ${entry.message}",
                    color = colorForLevel(entry.level),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

private fun colorForLevel(level: LogLevel): Color = when (level) {
    LogLevel.INFO -> TextPrimary
    LogLevel.NET -> NeonCyan
    LogLevel.AUDIO -> AudioViolet
    LogLevel.WARN -> AmberWarn
    LogLevel.ERROR -> AlertRed
}