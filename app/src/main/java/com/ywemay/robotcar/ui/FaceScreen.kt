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
import com.ywemay.robotcar.control.Emotion
import com.ywemay.robotcar.control.EyeShape
import com.ywemay.robotcar.control.MouthShape
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
 * The [emotion] decides the expression — eye shape, brows, mouth and colour —
 * and is driven by [com.ywemay.robotcar.control.CarControl], so the remote web
 * page can change the car's mood and watch this face follow along.
 *
 * Idle behaviour is layered on top of the mood and is deliberately unhurried:
 * random blinks every few seconds, the occasional double-blink, a slow sleepy
 * blink now and then, and a small wandering gaze.
 */
@Composable
fun FaceScreen(
    emotion: Emotion,
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
            emotion = emotion,
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
            Spacer(Modifier.size(8.dp))
            // Which mood is on, spelled out — several of them (sleepy especially)
            // are subtle enough that a label is the only unambiguous confirmation.
            Text(
                text = "mood: " + emotion.label,
                style = MaterialTheme.typography.labelSmall,
                color = Color(emotion.tint).copy(alpha = 0.55f),
            )
        }
    }
}

// ======================================================================
// The face itself — drawn, not image assets, so it scales to any screen.
//
// Everything mood-specific is data: [Emotion] carries shape/openness/tilt and
// this renderer just reads it. There is deliberately no per-emotion `when` here
// — a new mood is a new row in the enum, not new drawing code.
// ======================================================================

private val PUPIL = Color(0xFF0A1319)

@Composable
private fun FaceIllustration(
    emotion: Emotion,
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

    val tint = Color(emotion.tint)
    val mouthColor = tint.copy(alpha = 0.88f)

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val unit = min(w, h)
        val faceR = unit * 0.32f * (1f + breath * 0.012f)

        // Faint halo — the "light" coming off the face, tinted by the mood.
        val haloCenter = Offset(cx, cy - faceR * 0.1f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(tint.copy(alpha = 0.12f), Color.Transparent),
                center = haloCenter,
                radius = faceR * 2.4f,
            ),
            radius = faceR * 2.4f,
            center = haloCenter,
        )

        // ---- shared geometry ------------------------------------------
        // Proportions tuned so the pair reads as a face: eyes ~1.25:1 tall, one
        // eye-width apart, mouth a comfortable gap below them.
        val eyeDx = faceR * 0.48f
        val eyeW = faceR * 0.48f
        val eyeBaseH = faceR * 0.60f
        val lidLineH = eyeW * 0.16f
        val eyeY = cy - faceR * 0.16f
        val shift = gaze * faceR * 0.10f
        val leftC = Offset(cx - eyeDx + shift, eyeY)
        val rightC = Offset(cx + eyeDx + shift, eyeY)

        // ---- eyes ------------------------------------------------------
        if (emotion.wink) {
            drawEye(rightC, emotion, eyeW, eyeBaseH, lidLineH, eyeOpen, tint)
            drawShutLid(leftC, eyeW * 0.92f, mouthColor)
        } else {
            drawEye(leftC, emotion, eyeW, eyeBaseH, lidLineH, eyeOpen, tint)
            drawEye(rightC, emotion, eyeW, eyeBaseH, lidLineH, eyeOpen, tint)
        }

        // ---- brows -----------------------------------------------------
        if (emotion.browTilt != 0f || emotion.browLift != 0f) {
            val browY = eyeY - eyeBaseH * 0.5f - faceR * 0.20f - emotion.browLift * faceR * 0.30f
            val browLen = eyeW * 1.10f
            // >0 drops the inner ends (angry); <0 lifts them (worried).
            val innerDrop = emotion.browTilt * faceR * 0.10f
            val browStroke = faceR * 0.055f
            drawLine(
                color = mouthColor,
                start = Offset(leftC.x - browLen / 2f, browY),
                end = Offset(leftC.x + browLen / 2f, browY + innerDrop),
                strokeWidth = browStroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = mouthColor,
                start = Offset(rightC.x - browLen / 2f, browY + innerDrop),
                end = Offset(rightC.x + browLen / 2f, browY),
                strokeWidth = browStroke,
                cap = StrokeCap.Round,
            )
        }

        // ---- mouth -----------------------------------------------------
        val mouthW = faceR * 1.05f
        val arcH = faceR * 0.58f
        val mouthY = cy + faceR * 0.35f
        val mouthStroke = faceR * 0.085f

        when (emotion.mouth) {
            MouthShape.SMILE -> drawArc(
                color = mouthColor,
                startAngle = 18f,
                sweepAngle = 144f,
                useCenter = false,
                topLeft = Offset(cx - mouthW / 2f, mouthY - arcH / 2f),
                size = Size(mouthW, arcH),
                style = Stroke(width = mouthStroke, cap = StrokeCap.Round),
            )

            MouthShape.GRIN -> drawArc(
                color = mouthColor,
                startAngle = 4f,
                sweepAngle = 172f,
                useCenter = false,
                topLeft = Offset(cx - mouthW * 0.55f, mouthY - arcH * 0.66f),
                size = Size(mouthW * 1.10f, arcH * 1.32f),
                style = Stroke(width = mouthStroke, cap = StrokeCap.Round),
            )

            MouthShape.FLAT -> drawLine(
                color = mouthColor,
                start = Offset(cx - mouthW * 0.30f, mouthY),
                end = Offset(cx + mouthW * 0.30f, mouthY),
                strokeWidth = mouthStroke,
                cap = StrokeCap.Round,
            )

            MouthShape.FROWN -> drawArc(
                color = mouthColor,
                startAngle = 198f,
                sweepAngle = 144f,
                useCenter = false,
                topLeft = Offset(cx - mouthW * 0.42f, mouthY - arcH * 0.42f),
                size = Size(mouthW * 0.84f, arcH * 0.84f),
                style = Stroke(width = mouthStroke, cap = StrokeCap.Round),
            )

            MouthShape.OPEN -> drawOval(
                color = mouthColor,
                topLeft = Offset(cx - mouthW * 0.21f, mouthY - arcH * 0.30f),
                size = Size(mouthW * 0.42f, arcH * 0.62f),
            )

            MouthShape.SMIRK -> drawArc(
                color = mouthColor,
                startAngle = 20f,
                sweepAngle = 118f,
                useCenter = false,
                topLeft = Offset(cx - mouthW * 0.18f, mouthY - arcH * 0.36f),
                size = Size(mouthW * 0.72f, arcH * 0.72f),
                style = Stroke(width = mouthStroke, cap = StrokeCap.Round),
            )
        }

        // ---- flourishes ------------------------------------------------
        if (emotion.blush) {
            val blushW = faceR * 0.34f
            val blushH = faceR * 0.19f
            val blushY = mouthY - faceR * 0.06f
            val blushColor = Color(0xFFFF8FC7).copy(alpha = 0.30f)
            drawOval(
                color = blushColor,
                topLeft = Offset(cx - faceR * 0.86f - blushW / 2f, blushY - blushH / 2f),
                size = Size(blushW, blushH),
            )
            drawOval(
                color = blushColor,
                topLeft = Offset(cx + faceR * 0.86f - blushW / 2f, blushY - blushH / 2f),
                size = Size(blushW, blushH),
            )
        }

        if (emotion.tear) {
            val tearX = leftC.x - eyeW * 0.36f
            val tearY = eyeY + eyeBaseH * 0.72f
            drawOval(
                color = Color(0xFFCDEBFF),
                topLeft = Offset(tearX - faceR * 0.055f, tearY - faceR * 0.08f),
                size = Size(faceR * 0.11f, faceR * 0.16f),
            )
        }
    }
}

/**
 * One eye, shaped and squashed by the mood.
 *
 * [open] is the idle-blink signal (1 = wide, 0 = shut), applied on top of the
 * mood's own base openness — so a drawn expression still blinks like a real face.
 */
private fun DrawScope.drawEye(
    center: Offset,
    emotion: Emotion,
    eyeW: Float,
    eyeBaseH: Float,
    lidLineH: Float,
    open: Float,
    tint: Color,
) {
    when (emotion.eyeShape) {
        EyeShape.CAPSULE -> {
            val h = (eyeBaseH * emotion.eyeOpen * open).coerceAtLeast(lidLineH)
            val r = min(eyeW, h) / 2f
            drawRoundRect(
                color = tint,
                topLeft = Offset(center.x - eyeW / 2f, center.y - h / 2f),
                size = Size(eyeW, h),
                cornerRadius = CornerRadius(r, r),
            )
        }

        EyeShape.ROUND -> {
            val d = eyeW * emotion.eyeOpen
            val h = (d * open).coerceAtLeast(lidLineH)
            drawOval(
                color = tint,
                topLeft = Offset(center.x - d / 2f, center.y - h / 2f),
                size = Size(d, h),
            )
            // A dark pupil inside the disc — the "wide-eyed" look.
            if (emotion.pupil > 0f && h > lidLineH * 1.6f) {
                drawCircle(
                    color = PUPIL,
                    radius = d * 0.5f * emotion.pupil,
                    center = center,
                )
            }
        }

        EyeShape.ARC -> {
            // Closed-happy eye: the upper half of an ellipse ( ∩ ) that
            // flattens out into a line on a blink.
            val arcW = eyeW * 1.25f
            val arcH = (eyeBaseH * 0.95f * open).coerceAtLeast(lidLineH * 0.6f)
            drawArc(
                color = tint,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(center.x - arcW / 2f, center.y - arcH / 2f),
                size = Size(arcW, arcH),
                style = Stroke(width = eyeBaseH * 0.19f, cap = StrokeCap.Round),
            )
        }
    }
}

/** A shut eyelid — the winking eye. */
private fun DrawScope.drawShutLid(center: Offset, width: Float, color: Color) {
    drawLine(
        color = color,
        start = Offset(center.x - width / 2f, center.y),
        end = Offset(center.x + width / 2f, center.y),
        strokeWidth = width * 0.16f,
        cap = StrokeCap.Round,
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
