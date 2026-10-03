package com.ywemay.robotcar.control

import com.ywemay.robotcar.usb.CommandEngine
import com.ywemay.robotcar.usb.UsbSerialManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single funnel through which *everything* that wants to move the car goes.
 *
 * Why this exists
 * ---------------
 * The car now has two independent drivers: the on-device Compose dashboard and
 * the remote-control web page served over Wi-Fi. If each kept its own idea of
 * where the gimbal is pointing they would drift apart the moment one of them
 * moved a slider. So the pan/tilt pose (and the "last command" readout the web
 * status endpoint shows) live here — process-wide, exactly like the serial
 * link — and both front-ends call into it.
 *
 * It is deliberately tiny: clamp, build the frame with [CommandEngine], hand it
 * to [UsbSerialManager]. No Android UI, no I/O of its own.
 */
object CarControl {

    private val _pan = MutableStateFlow(CommandEngine.ANGLE_DEFAULT)
    val pan: StateFlow<Int> = _pan.asStateFlow()

    private val _tilt = MutableStateFlow(CommandEngine.ANGLE_DEFAULT)
    val tilt: StateFlow<Int> = _tilt.asStateFlow()

    /** The most recent frame handed to the link, terminator escaped — for the web status page. */
    private val _lastCommand = MutableStateFlow<String?>(null)
    val lastCommand: StateFlow<String?> = _lastCommand.asStateFlow()

    /**
     * The car's current mood.
     *
     * Unlike everything else in this object an emotion is *not* a serial frame —
     * nothing goes on the wire. It still belongs here for the same reason the
     * gimbal pose does: the on-device face and the remote web page are two
     * independent drivers of one piece of state, and the moment they each kept
     * their own copy they would disagree.
     */
    private val _emotion = MutableStateFlow(Emotion.DEFAULT)
    val emotion: StateFlow<Emotion> = _emotion.asStateFlow()

    // ------------------------------------------------------------------
    // Mood
    // ------------------------------------------------------------------

    /**
     * Put the car in a different mood.
     *
     * Presentation only — no frame is emitted. The change is announced in the
     * shared debug log so the on-device panel shows one timeline of everything
     * the car brain is doing, whoever asked for it.
     *
     * No-op when the mood is unchanged, so a polling web page cannot spam the log.
     */
    fun setEmotion(emotion: Emotion) {
        if (_emotion.value == emotion) return
        _emotion.value = emotion
        UsbSerialManager.note("Emotion: ${emotion.label}")
    }

    // ------------------------------------------------------------------
    // Drive
    // ------------------------------------------------------------------

    /**
     * Emits a drive frame for [direction] (F/B/L/R/S).
     *
     * Stop is special-cased to speed 0 so the firmware always sees an
     * unambiguous `D,S,0` kill frame regardless of the requested [speed].
     *
     * @return false when there is no live USB link (the frame is still logged).
     */
    fun drive(direction: Char, speed: Int = CommandEngine.DEFAULT_SPEED): Boolean {
        val dir = direction.uppercaseChar()
        val effectiveSpeed = if (dir == CommandEngine.STOP) {
            CommandEngine.SPEED_MIN
        } else {
            speed.coerceIn(CommandEngine.SPEED_MIN, CommandEngine.SPEED_MAX)
        }
        return transmit(CommandEngine.drive(dir, effectiveSpeed))
    }

    /** Explicit kill frame — what every remote-control surface binds to "release". */
    fun stop(): Boolean = drive(CommandEngine.STOP, CommandEngine.SPEED_MIN)

    // ------------------------------------------------------------------
    // Camera gimbal
    // ------------------------------------------------------------------

    /** Move pan only; tilt keeps its current value (the two servos always travel together). */
    fun setPan(degrees: Int): Boolean {
        _pan.value = degrees.coerceIn(CommandEngine.ANGLE_MIN, CommandEngine.ANGLE_MAX)
        return transmitCamera()
    }

    /** Move tilt only; pan keeps its current value. */
    fun setTilt(degrees: Int): Boolean {
        _tilt.value = degrees.coerceIn(CommandEngine.ANGLE_MIN, CommandEngine.ANGLE_MAX)
        return transmitCamera()
    }

    /** Aim both axes at once. Either argument may be null to keep the current pose. */
    fun setCamera(pan: Int? = null, tilt: Int? = null): Boolean {
        pan?.let { _pan.value = it.coerceIn(CommandEngine.ANGLE_MIN, CommandEngine.ANGLE_MAX) }
        tilt?.let { _tilt.value = it.coerceIn(CommandEngine.ANGLE_MIN, CommandEngine.ANGLE_MAX) }
        return transmitCamera()
    }

    private fun transmitCamera(): Boolean =
        transmit(CommandEngine.camera(_pan.value, _tilt.value))

    private fun transmit(frame: String): Boolean {
        _lastCommand.value = CommandEngine.escape(frame)
        return UsbSerialManager.send(frame)
    }
}
