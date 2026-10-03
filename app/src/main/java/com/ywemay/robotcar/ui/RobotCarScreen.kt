package com.ywemay.robotcar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ywemay.robotcar.RobotCarViewModel
import com.ywemay.robotcar.control.Emotion
import com.ywemay.robotcar.usb.CommandEngine
import com.ywemay.robotcar.usb.LogEntry
import com.ywemay.robotcar.usb.LogKind
import com.ywemay.robotcar.usb.UsbConnectionState
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The onboard control dashboard.
 *
 * Fixed-height header (back-to-face + USB banner) + controls on top, and a
 * weight(1f) log panel that claims whatever vertical space is left and scrolls
 * internally. That is why the outer Column is deliberately NOT a verticalScroll:
 * nesting a scrollable LazyColumn inside one would give it an infinite height
 * and crash.
 */
@Composable
fun RobotCarScreen(
    onClose: () -> Unit,
    webUrl: String?,
    modifier: Modifier = Modifier,
    viewModel: RobotCarViewModel = viewModel(),
) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val pan by viewModel.pan.collectAsStateWithLifecycle()
    val tilt by viewModel.tilt.collectAsStateWithLifecycle()
    val emotion by viewModel.emotion.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DashboardHeader(webUrl = webUrl, onClose = onClose)

        UsbStatusBanner(connection, onRetry = viewModel::retryConnection)

        MoodStrip(current = emotion, onPick = viewModel::onEmotionChanged)

        DrivePad(onDrive = viewModel::onDrive)

        CameraControls(
            pan = pan,
            tilt = tilt,
            onPan = viewModel::onPanChanged,
            onTilt = viewModel::onTiltChanged,
        )

        DebugLogPanel(
            logs = logs,
            onClear = viewModel::clearLog,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Slim top bar: the remote-control URL (so the driver can hand it to a phone or
 * laptop on the same Wi-Fi) and the button that returns to the car's face.
 */
@Composable
private fun DashboardHeader(webUrl: String?, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "CONTROLS",
                style = MaterialTheme.typography.labelMedium,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = webUrl ?: "web server unavailable",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                color = if (webUrl != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color(0xFF8E5A5A)
                },
            )
        }
        TextButton(onClick = onClose) {
            Text("◀  FACE", fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * Horizontal mood picker — the same [Emotion] list the remote web page renders,
 * driven through the same [com.ywemay.robotcar.control.CarControl] hub, so a
 * mood picked here and one picked in a browser are indistinguishable.
 *
 * A swipe-friendly row rather than a wrapped grid: nine moods would otherwise
 * eat a third of the dashboard, and the log panel needs the height more.
 */
@Composable
private fun MoodStrip(current: Emotion, onPick: (Emotion) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(Emotion.entries, key = { it.slug }) { mood ->
            val active = mood == current
            val tint = Color(mood.tint)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (active) tint else Color.White.copy(alpha = 0.05f))
                    .border(
                        width = 1.dp,
                        color = if (active) tint else Color.White.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(50),
                    )
                    .clickable { onPick(mood) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(text = mood.emoji, fontSize = 15.sp)
                Text(
                    text = mood.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) {
                        Color(0xFF06121A)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

// ======================================================================
// 1. USB status banner — green when connected, red otherwise.
// ======================================================================

@Composable
private fun UsbStatusBanner(state: UsbConnectionState, onRetry: () -> Unit) {
    val accent = when (state) {
        is UsbConnectionState.Connected -> Color(0xFF43E97B)
        is UsbConnectionState.Disconnected -> Color(0xFFFF6B6B)
        is UsbConnectionState.Failed -> Color(0xFFFF6B6B)
        is UsbConnectionState.AwaitingPermission -> Color(0xFFFFC46B)
        UsbConnectionState.Scanning -> Color(0xFF90A4AE)
    }
    val background = when (state) {
        is UsbConnectionState.Connected -> Color(0xFF10502A)
        is UsbConnectionState.Disconnected -> Color(0xFF5C1D1A)
        is UsbConnectionState.Failed -> Color(0xFF5C1D1A)
        is UsbConnectionState.AwaitingPermission -> Color(0xFF4E3A0C)
        UsbConnectionState.Scanning -> Color(0xFF263238)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRetry),
        color = background,
        shape = RoundedCornerShape(12.dp),
        border = null,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "USB STATUS",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.65f),
                    letterSpacing = 1.6.sp,
                )
                Text(
                    text = if (state.isConnected) "CONNECTED" else state.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(
                    text = if (state.isConnected) state.label else "115200 · 8N1 · Arduino Nano",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White.copy(alpha = 0.75f),
                )
            }
        }
    }
}

// ======================================================================
// 2. Drive pad — Forward / Backward / Left / Right / Stop @ speed 180
// ======================================================================

@Composable
private fun DrivePad(onDrive: (Char) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "DRIVE",
                style = MaterialTheme.typography.labelMedium,
                letterSpacing = 1.6.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "speed ${CommandEngine.DEFAULT_SPEED}",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // Row 1 — forward
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            DriveButton(
                label = "▲  FORWARD",
                direction = CommandEngine.FORWARD,
                onDrive = onDrive,
                modifier = Modifier.weight(1.4f),
            )
            Spacer(Modifier.weight(1f))
        }

        // Row 2 — left / stop / right
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DriveButton("◀  LEFT", CommandEngine.LEFT, onDrive, Modifier.weight(1f))
            DriveButton(
                label = "■  STOP",
                direction = CommandEngine.STOP,
                onDrive = onDrive,
                modifier = Modifier.weight(1f),
                container = Color(0xFF8E2A22),
            )
            DriveButton("RIGHT  ▶", CommandEngine.RIGHT, onDrive, Modifier.weight(1f))
        }

        // Row 3 — backward
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            DriveButton(
                label = "▼  BACKWARD",
                direction = CommandEngine.BACKWARD,
                onDrive = onDrive,
                modifier = Modifier.weight(1.4f),
            )
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun DriveButton(
    label: String,
    direction: Char,
    onDrive: (Char) -> Unit,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    Button(
        onClick = { onDrive(direction) },
        modifier = modifier.heightIn(min = 54.dp),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = Color(0xFFF2F5F7),
        ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            softWrap = false,
        )
    }
}

// ======================================================================
// 3. Camera gimbal — two 0..180 sliders, transmitted on every drag frame
// ======================================================================

@Composable
private fun CameraControls(
    pan: Int,
    tilt: Int,
    onPan: (Int) -> Unit,
    onTilt: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "CAMERA GIMBAL",
            style = MaterialTheme.typography.labelMedium,
            letterSpacing = 1.6.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AngleSlider(label = "Camera Pan", value = pan, onChange = onPan)
        AngleSlider(label = "Camera Tilt", value = tilt, onChange = onTilt)
    }
}

@Composable
private fun AngleSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "$value°",
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = CommandEngine.ANGLE_MIN.toFloat()..CommandEngine.ANGLE_MAX.toFloat(),
            // 181 discrete servo positions → integer degrees only.
            steps = CommandEngine.ANGLE_MAX - CommandEngine.ANGLE_MIN - 1,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ======================================================================
// 4. Debug log — echoes every frame sent (TX) and every line received (RX)
// ======================================================================

@Composable
private fun DebugLogPanel(
    logs: List<LogEntry>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Follow the tail, but only while the user is not reading further up.
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()
            lastVisible == null || lastVisible.index >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty() && atBottom) listState.scrollToItem(logs.lastIndex)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xFF080B0E),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF121A20))
                    .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "SERIAL DEBUG LOG",
                    style = MaterialTheme.typography.labelMedium,
                    letterSpacing = 1.4.sp,
                    color = Color(0xFF8FA3B0),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (logs.size == 1) "1 line" else "${logs.size} lines",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF5A6C78),
                )
                TextButton(onClick = onClear) { Text("CLEAR") }
            }

            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "idle — no serial traffic yet",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF4A5A66),
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 4.dp),
                ) {
                    items(logs, key = { it.id }) { entry -> LogLine(entry) }
                }
            }
        }
    }
}

@Composable
private fun LogLine(entry: LogEntry) {
    val color = when (entry.kind) {
        LogKind.TX -> Color(0xFF64B5F6)
        LogKind.RX -> Color(0xFF81C784)
        LogKind.SYS -> Color(0xFFFFB74D)
    }
    val marker = when (entry.kind) {
        LogKind.TX -> "» TX"
        LogKind.RX -> "« RX"
        LogKind.SYS -> "· SYS"
    }

    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            text = TIME_FORMAT.format(entry.timestampMs),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = Color(0xFF4A5A66),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = marker,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = entry.text,
            // Fill the remaining Row width so a wrapped line breaks flush with
            // the message column instead of hanging past the timestamp gutter.
            modifier = Modifier.weight(1f),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = Color(0xFFD6DEE4),
        )
    }
}

/** Main-thread only (LazyColumn composition), so a single instance is safe. */
private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
