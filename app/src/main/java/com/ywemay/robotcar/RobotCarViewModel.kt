package com.ywemay.robotcar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.ywemay.robotcar.usb.CommandEngine
import com.ywemay.robotcar.usb.LogEntry
import com.ywemay.robotcar.usb.UsbConnectionState
import com.ywemay.robotcar.usb.UsbSerialManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thin MVVM bridge between the Compose dashboard and [UsbSerialManager].
 *
 * The link itself lives in the process-wide manager, so this class holds only
 * the *pure UI* state (gimbal slider positions) and translates taps/drags into
 * protocol frames. That split is what lets the debug log and the USB link
 * survive an Activity recreation untouched.
 */
class RobotCarViewModel(application: Application) : AndroidViewModel(application) {

    /** USB link state → drives the colour + text of the status banner. */
    val connection: StateFlow<UsbConnectionState> = UsbSerialManager.state

    /** Ring buffer of every frame sent and every line received. */
    val logs: StateFlow<List<LogEntry>> = UsbSerialManager.logs

    private val _pan = MutableStateFlow(CommandEngine.ANGLE_DEFAULT)
    val pan: StateFlow<Int> = _pan.asStateFlow()

    private val _tilt = MutableStateFlow(CommandEngine.ANGLE_DEFAULT)
    val tilt: StateFlow<Int> = _tilt.asStateFlow()

    // ------------------------------------------------------------------
    // Drive engine
    // ------------------------------------------------------------------

    /**
     * One of the five movement buttons. Every direction runs at
     * [CommandEngine.DEFAULT_SPEED] (180) — except Stop, which is emitted as
     * `D,S,0` so the firmware has an unambiguous "kill the motors" frame.
     */
    fun onDrive(direction: Char) {
        val speed = if (direction == CommandEngine.STOP) {
            CommandEngine.SPEED_MIN
        } else {
            CommandEngine.DEFAULT_SPEED
        }
        UsbSerialManager.send(CommandEngine.drive(direction, speed))
    }

    // ------------------------------------------------------------------
    // Camera gimbal
    // ------------------------------------------------------------------

    /** Called on every drag frame; sends `C,<pan>,<tilt>` immediately. */
    fun onPanChanged(degrees: Int) {
        _pan.value = degrees
        transmitCamera()
    }

    fun onTiltChanged(degrees: Int) {
        _tilt.value = degrees
        transmitCamera()
    }

    /** The two servos move as a pair, so both current values go out each time. */
    private fun transmitCamera() {
        UsbSerialManager.send(CommandEngine.camera(_pan.value, _tilt.value))
    }

    // ------------------------------------------------------------------

    fun clearLog() = UsbSerialManager.clearLog()

    /** Manual re-probe, wired to a tap on the USB status banner. */
    fun retryConnection() = UsbSerialManager.requestConnect()
}
