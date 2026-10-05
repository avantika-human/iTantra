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
import androidx.compose.material3.lightColorScheme
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
// Premium Minimalist Palette
// ---------------------------------------------------------------------------
private val Ivory = Color(0xFFF3F1EC) // Background
private val Midnight = Color(0xFF3C3E4A) // Primary text / actions
private val Garden = Color(0xFFE0DFD2) // Cards
private val Moss = Color(0xFFB6B8AB) // Secondary accents
private val Smoke = Color(0xFF9FA3AD) // Muted elements

private val HeartRateBlue = Color(0x269FA3AD) // Smoke tinted
private val StepsMoss = Color(0x40B6B8AB) // Moss tinted

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------
@Composable
fun TransceiverScreen(viewModel: TransceiverViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Midnight,
            background = Ivory,
            surface = Garden,
            onPrimary = Ivory
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatusHeader(connectionStatus = uiState.connectionStatus, role = uiState.role)

                // Mocking the grid from the web UI
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Network Card (Role)
                    RoleSelectorCard(
                        modifier = Modifier.weight(1f),
                        selectedRole = uiState.role,
                        onRoleSelected = viewModel::onRoleSelected
                    )

                    // Alert Override Card
                    EmergencyPriorityCard(
                        modifier = Modifier.weight(1f),
                        enabled = uiState.emergencyOverride,
                        onCheckedChange = viewModel::onEmergencyOverrideChanged
                    )
                }
                
                LanguageSelectorCard(
                    currentLanguage = uiState.currentLanguage,
                    onLanguageSelected = viewModel::switchSourceLanguage
                )

                PttButtonCard(
                    isTransmitter = uiState.role == Role.TRANSMITTER,
                    isPttActive = uiState.isPttActive,
                    onPressed = viewModel::onPttPressed,
                    onReleased = viewModel::onPttReleased
                )

                ConsoleLogList(
                    logs = uiState.logs,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------
@Composable
private fun StatusHeader(connectionStatus: ConnectionStatus, role: Role) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = "Neural Walkie-Talkie",
                color = Midnight,
                fontSize = 24.sp,
                fontWeight = FontWeight.W600,
                lineHeight = 28.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (connectionStatus == ConnectionStatus.OFFLINE) Smoke else Midnight)
                )
                Text(
                    text = "${connectionStatus.label} • Device B (${role.name})",
                    color = Smoke,
                    fontSize = 13.sp
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Network / Role Selector Card
// ---------------------------------------------------------------------------
@Composable
private fun RoleSelectorCard(
    modifier: Modifier = Modifier,
    selectedRole: Role,
    onRoleSelected: (Role) -> Unit
) {
    Column(
        modifier = modifier
            .height(140.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(HeartRateBlue)
            .padding(20.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(text = "Mode", color = Midnight, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(text = "Current Role", color = Smoke, fontSize = 12.sp)
        }
        
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoleOption(
                label = "TX",
                isSelected = selectedRole == Role.TRANSMITTER,
                modifier = Modifier.weight(1f),
                onClick = { onRoleSelected(Role.TRANSMITTER) }
            )
            RoleOption(
                label = "RX",
                isSelected = selectedRole == Role.RECEIVER,
                modifier = Modifier.weight(1f),
                onClick = { onRoleSelected(Role.RECEIVER) }
            )
        }
    }
}

@Composable
private fun RoleOption(label: String, isSelected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bg = if (isSelected) Midnight else Color.Transparent
    val textCol = if (isSelected) Ivory else Smoke
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(BorderStroke(1.dp, if (isSelected) Midnight else Moss.copy(alpha=0.3f)), RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
         Text(text = label, color = textCol, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

// ---------------------------------------------------------------------------
// Language Selector Card (Horizontal)
// ---------------------------------------------------------------------------
@Composable
private fun LanguageSelectorCard(
    currentLanguage: String,
    onLanguageSelected: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Garden)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(text = "Language", color = Midnight, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(text = "Speech Model", color = Midnight.copy(alpha=0.6f), fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoleOption(
                label = "EN",
                isSelected = currentLanguage == "en",
                modifier = Modifier.size(48.dp, 36.dp),
                onClick = { onLanguageSelected("en") }
            )
            RoleOption(
                label = "TE",
                isSelected = currentLanguage == "te",
                modifier = Modifier.size(48.dp, 36.dp),
                onClick = { onLanguageSelected("te") }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Emergency Priority Card
// ---------------------------------------------------------------------------
@Composable
private fun EmergencyPriorityCard(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Column(
        modifier = modifier
            .height(140.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(StepsMoss)
            .padding(20.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(text = if(enabled) "Active" else "Standby", color = Midnight, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(text = "Alert Override", color = Smoke, fontSize = 12.sp)
        }
        
        Switch(
            checked = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Ivory,
                checkedTrackColor = Midnight,
                uncheckedThumbColor = Ivory,
                uncheckedTrackColor = Moss.copy(alpha=0.5f),
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

// ---------------------------------------------------------------------------
// PTT Big Card
// ---------------------------------------------------------------------------
@Composable
private fun PttButtonCard(
    isTransmitter: Boolean,
    isPttActive: Boolean,
    onPressed: () -> Unit,
    onReleased: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Garden)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onPressed()
                        tryAwaitRelease()
                        onReleased()
                    }
                )
            }
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(if (isPttActive) Midnight else Ivory),
                contentAlignment = Alignment.Center
            ) {
                // Outer ring representation
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape).border(2.dp, if (isPttActive) Ivory else Midnight, CircleShape)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = when {
                    !isTransmitter -> "RECEIVE MODE"
                    isPttActive -> "ON AIR"
                    else -> "Hold to Speak"
                },
                color = Midnight,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp
            )
            Text(
                text = when {
                    !isTransmitter -> "Standby for incoming traffic"
                    isPttActive -> "Streaming mic -> STT"
                    else -> "Release to Transmit"
                },
                color = Midnight.copy(alpha = 0.6f),
                fontSize = 12.sp
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Live scrollable console log
// ---------------------------------------------------------------------------
@Composable
private fun ConsoleLogList(
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
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Recent Transmissions", color = Midnight, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
        
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(logs) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Ivory)
                        // A very subtle shadow simulation or border
                        .border(1.dp, Moss.copy(alpha = 0.2f), RoundedCornerShape(24.dp))
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Icon
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(HeartRateBlue),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Midnight))
                    }
                    // Content
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.message,
                            color = Midnight,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 18.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = entry.timestamp,
                            color = Smoke,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}