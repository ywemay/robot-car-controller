package com.ywemay.robotcar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.ywemay.robotcar.control.CarControl
import com.ywemay.robotcar.usb.LogEntry
import com.ywemay.robotcar.usb.UsbConnectionState
import com.ywemay.robotcar.usb.UsbSerialManager
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin MVVM bridge between the Compose dashboard and the process-wide car
 * services.
 *
 * It owns no real state: the serial link lives in [UsbSerialManager] and the
 * shared command hub (gimbal pose, last frame) lives in [CarControl], both of
 * which the remote-control web server also talks to. That split is what lets
 * the UI, the debug log and the Wi-Fi link all survive an Activity recreation —
 * and keeps the on-screen and browser controllers perfectly in sync.
 */
class RobotCarViewModel(application: Application) : AndroidViewModel(application) {

    /** USB link state → drives the colour + text of the status banner. */
    val connection: StateFlow<UsbConnectionState> = UsbSerialManager.state

    /** Ring buffer of every frame sent and every line received. */
    val logs: StateFlow<List<LogEntry>> = UsbSerialManager.logs

    /** Gimbal pose, shared with the web UI — read-only here. */
    val pan: StateFlow<Int> = CarControl.pan
    val tilt: StateFlow<Int> = CarControl.tilt

    /** One of the five movement buttons; Stop is emitted as `D,S,0`. */
    fun onDrive(direction: Char) = CarControl.drive(direction)

    /** Called on every drag frame; sends `C,<pan>,<tilt>` immediately. */
    fun onPanChanged(degrees: Int) = CarControl.setPan(degrees)

    fun onTiltChanged(degrees: Int) = CarControl.setTilt(degrees)

    fun clearLog() = UsbSerialManager.clearLog()

    /** Manual re-probe, wired to a tap on the USB status banner. */
    fun retryConnection() = UsbSerialManager.requestConnect()
}
