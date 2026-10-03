package com.ywemay.robotcar.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ywemay.robotcar.usb.UsbConnectionState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min
import kotlin.random.Random

/**
 * The car's face — the app's first screen.
 *
 * A calm, mostly-dark face (two eyes + a smile) that blinks on its own so the
 * car looks "alive" while parked. Double-tap anywhere to open the on-device
 * control dashboard. A single tap just gets a blink (acknowledgement).
 *
 * Idle behaviour is deliberately unhurried: random blinks every few seconds,
 * the occasional double-blink, a slow sleepy blink now and then, and a small
 * wandering gaze — enough motion to read as alive without being a strobe.
 */
@Composable
fun FaceScreen(
    connection: UsbConnectionState,
    webUrl: String?,
    onOpenControls: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Conflated channel: taps collapse into "blink now" signals for the idle loop.
    val poke = remember { Channel<Unit>(Channel.CONFLATED) }

    // 1 = eyes fully open, 0 = shut. blinkMs is the per-transition duration so a
    // normal blink snaps shut and a sleepy one eases — same value, different speed.
    var eyeTarget by remember { mutableStateOf(1f) }
    var blinkMs by remember { mutableStateOf(110) }
    val eyeOpen by animateFloatAsState(
        targetValue = eyeTarget,
        animationSpec = tween(durationMillis = blinkMs, easing = LinearEasing),
        label = "eyeOpen",
    )

    // -1..1 horizontal gaze; the eyes drift a little to look around.
    var gazeTarget by remember { mutableStateOf(0f) }
    val gaze by animateFloatAsState(
        targetValue = gazeTarget,
        animationSpec = tween(durationMillis = 460, easing = FastOutSlowInEasing),
        label = "gaze",
    )

    LaunchedEffect(Unit) {
        suspend fun blink(close: Int, hold: Int, open: Int) {
            blinkMs = close
            eyeTarget = 0f
            delay((close + hold).toLong())
            blinkMs = open
            eyeTarget = 1f
            delay(open.toLong())
        }

        while (isActive) {
            val poked = withTimeoutOrNull(Random.nextLong(2800, 6400)) {
                poke.receive()
                true
            } ?: false

            if (poked) {
                blink(close = 70, hold = 30, open = 110)
                continue
            }

            blink(close = 95, hold = 55, open = 130)          // normal blink
            if (Random.nextInt(100) < 22) {                    // …now and then, twice
                delay(150)
                blink(close = 85, hold = 45, open = 115)
            }
            if (Random.nextInt(100) < 14) {                    // …or a slow, sleepy one
                delay(600)
                blink(close = 320, hold = 480, open = 360)
            }
            if (Random.nextInt(100) < 18) {                    // …or a glance to one side
                gazeTarget = if (Random.nextBoolean()) 0.4f else -0.4f
                delay(900)
                gazeTarget = 0f
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF12222B), Color(0xFF080C0F)),
                )
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { poke.trySend(Unit) },
                    onDoubleTap = { onOpenControls() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        FaceIllustration(
            eyeOpen = eyeOpen,
            gaze = gaze,
            modifier = Modifier.fillMaxSize(),
        )

        StatusChip(
            connection = connection,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .safeDrawingPadding()
                .padding(top = 18.dp),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "DOUBLE-TAP FOR CONTROLS",
                style = MaterialTheme.typography.labelMedium,
                letterSpacing = 2.sp,
                color = Color(0xFF7C8B96),
            )
            Spacer(Modifier.size(10.dp))
            Text(
                text = webUrl?.let { "web control  $it" } ?: "web server unavailable",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (webUrl != null) Color(0xFF4FC3F7).copy(alpha = 0.8f) else Color(0xFF6B4A4A),
            )
        }
    }
}

// ======================================================================
// The face itself — drawn, not image assets, so it scales to any screen.
// ======================================================================

@Composable
private fun FaceIllustration(
    eyeOpen: Float,
    gaze: Float,
    modifier: Modifier = Modifier,
) {
    // Slow breathing scale so the face is never perfectly static.
    val breath by rememberInfiniteTransition(label = "face")
        .animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(3600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "breath",
        )

    val eyeColor = Color(0xFF7FE8FF)
    val mouthColor = Color(0xFF53CDEC)

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val unit = min(w, h)
        val faceR = unit * 0.30f * (1f + breath * 0.012f)

        // Faint halo — the "light" coming off the face.
        val haloCenter = Offset(cx, cy - faceR * 0.1f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(eyeColor.copy(alpha = 0.10f), Color.Transparent),
                center = haloCenter,
                radius = faceR * 2.4f,
            ),
            radius = faceR * 2.4f,
            center = haloCenter,
        )

        // ---- eyes ----
        val eyeDx = faceR * 0.60f
        val eyeW = faceR * 0.42f
        val eyeFullH = faceR * 0.80f
        val lidLineH = eyeW * 0.16f
        val eyeH = (eyeFullH * eyeOpen).coerceAtLeast(lidLineH)
        val eyeY = cy - faceR * 0.16f
        val shift = gaze * faceR * 0.12f

        drawEye(Offset(cx - eyeDx + shift, eyeY), eyeW, eyeH, eyeColor)
        drawEye(Offset(cx + eyeDx + shift, eyeY), eyeW, eyeH, eyeColor)

        // ---- mouth: a wide, easy smile ----
        val mouthW = faceR * 1.05f
        val arcH = mouthW * 0.62f
        val mouthY = cy + faceR * 0.52f
        drawArc(
            color = mouthColor,
            startAngle = 18f,
            sweepAngle = 144f,
            useCenter = false,
            topLeft = Offset(cx - mouthW / 2f, mouthY - arcH / 2f),
            size = Size(mouthW, arcH),
            style = Stroke(width = faceR * 0.10f, cap = StrokeCap.Round),
        )
    }
}

/** A single eye: a pill that squashes to a thin lid line when closed. */
private fun DrawScope.drawEye(center: Offset, w: Float, h: Float, color: Color) {
    val r = min(w, h) / 2f
    drawRoundRect(
        color = color,
        topLeft = Offset(center.x - w / 2f, center.y - h / 2f),
        size = Size(w, h),
        cornerRadius = CornerRadius(r, r),
    )
}

// ======================================================================
// Small USB state pill, so the link status is visible without opening controls.
// ======================================================================

@Composable
private fun StatusChip(connection: UsbConnectionState, modifier: Modifier = Modifier) {
    val dot = when (connection) {
        is UsbConnectionState.Connected -> Color(0xFF43E97B)
        is UsbConnectionState.AwaitingPermission -> Color(0xFFFFC46B)
        UsbConnectionState.Scanning -> Color(0xFF90A4AE)
        else -> Color(0xFFFF6B6B)
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = Color.White.copy(alpha = 0.05f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(dot)
            )
            Text(
                text = if (connection.isConnected) "USB connected" else connection.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = Color(0xFFC6D2DA),
            )
        }
    }
}
