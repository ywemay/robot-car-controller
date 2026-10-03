package com.ywemay.robotcar

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ywemay.robotcar.ui.RobotCarScreen
import com.ywemay.robotcar.ui.RobotCarTheme
import com.ywemay.robotcar.usb.UsbSerialManager

/**
 * Host Activity for the onboard dashboard.
 *
 * Its only jobs are (a) bind the USB hot-plug listener to the visible lifetime
 * and (b) hand the screen to Compose.
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

        setContent {
            // lifecycle-runtime-compose 2.8 moved LocalLifecycleOwner to
            // androidx.lifecycle.compose, but Compose UI 1.6 (BOM 2024.05) still
            // provides only the old compose.ui.platform one — so nothing populates
            // the new local and collectAsStateWithLifecycle would crash with
            // "CompositionLocal LocalLifecycleOwner not present". Provide it
            // explicitly (exactly what Compose UI 1.7 does internally).
            CompositionLocalProvider(LocalLifecycleOwner provides this) {
                RobotCarTheme {
                    RobotCarScreen()
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
}
