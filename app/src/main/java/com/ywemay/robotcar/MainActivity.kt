package com.ywemay.robotcar

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ywemay.robotcar.ui.FaceScreen
import com.ywemay.robotcar.ui.RobotCarScreen
import com.ywemay.robotcar.ui.RobotCarTheme
import com.ywemay.robotcar.usb.UsbSerialManager
import com.ywemay.robotcar.web.WebControlServer
import com.ywemay.robotcar.web.WebServerState

/**
 * Host Activity.
 *
 * Two screens, both in one Activity (no navigation graph needed for two views):
 *   1. [FaceScreen]     — the car's "face", shown on launch; blinks when idle.
 *   2. [RobotCarScreen] — the control dashboard, opened by double-tapping the face.
 *
 * It also owns the process-wide services' visible-lifetime wiring: the USB
 * hot-plug listener (onStart/onStop) and the embedded web server (process life).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // This phone is the robot's onboard computer — the dashboard is meant to
        // stay readable while driving, so hold the display on while it is visible.
        // FLAG_KEEP_SCREEN_ON is window-scoped: it releases automatically when the
        // app leaves the foreground, no wake lock and no permission required.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Start the remote-control web server for the life of the process. It is
        // intentionally NOT stopped on onStop: driving the car from a laptop must
        // survive the phone's screen going dark. Idempotent, so a recreated
        // Activity cannot double-bind the port.
        WebControlServer.start()

        setContent {
            // lifecycle-runtime-compose 2.8 moved LocalLifecycleOwner to
            // androidx.lifecycle.compose, but Compose UI 1.6 (BOM 2024.05) still
            // provides only the old compose.ui.platform one — so nothing populates
            // the new local and collectAsStateWithLifecycle would crash with
            // "CompositionLocal LocalLifecycleOwner not present". Provide it
            // explicitly (exactly what Compose UI 1.7 does internally).
            CompositionLocalProvider(LocalLifecycleOwner provides this) {
                RobotCarTheme {
                    RobotCarApp()
                }
            }
        }
    }

    /**
     * Registering here (and not in onCreate) means the attach/detach broadcasts
     * are only subscribed while the dashboard is actually on screen — no leaked
     * receiver, and plugging the OTG cable in while running connects instantly.
     * [UsbSerialManager.stop] deliberately keeps the open port alive so returning
     * from the launcher or a permission dialog does not drop the link.
     */
    override fun onStart() {
        super.onStart()
        UsbSerialManager.start(this)
    }

    override fun onStop() {
        UsbSerialManager.stop()
        super.onStop()
    }

    override fun onDestroy() {
        // Only tear the server down on a real finish (user closed the app), not on
        // a configuration change.
        if (isFinishing) WebControlServer.stop()
        super.onDestroy()
    }
}

/**
 * The two-screen shell. Crossfades between the face and the dashboard so the
 * transition reads as the car "waking up"; system Back returns to the face
 * instead of leaving the app.
 */
@Composable
private fun RobotCarApp() {
    val viewModel: RobotCarViewModel = viewModel()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val emotion by viewModel.emotion.collectAsStateWithLifecycle()
    val webState by WebControlServer.state.collectAsStateWithLifecycle()
    val webUrl = (webState as? WebServerState.Running)?.url

    var showControls by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showControls) { showControls = false }

    Crossfade(
        targetState = showControls,
        animationSpec = tween(durationMillis = 240),
        label = "screen",
    ) { controls ->
        if (controls) {
            RobotCarScreen(
                onClose = { showControls = false },
                webUrl = webUrl,
                viewModel = viewModel,
            )
        } else {
            FaceScreen(
                emotion = emotion,
                connection = connection,
                webUrl = webUrl,
                onOpenControls = { showControls = true },
            )
        }
    }
}
