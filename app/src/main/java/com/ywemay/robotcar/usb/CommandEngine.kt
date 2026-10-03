package com.ywemay.robotcar.usb

/**
 * The wire protocol. Pure Kotlin, zero Android dependencies, no I/O — every
 * method just builds the exact ASCII frame that the Arduino Nano expects, so it
 * can be unit tested (and reasoned about) without a cable attached.
 *
 * Frame grammar (every frame is terminated by a single LF, '\n' = 0x0A):
 *
 *     D,<DIR>,<SPEED>\n      DIR   := F | B | L | R | S
 *                            SPEED := 0..255
 *
 *     C,<PAN>,<TILT>\n       PAN   := 0..180   (degrees, servo 1)
 *                            TILT  := 0..180   (degrees, servo 2)
 *
 * Note the ASCII digits — "D,F,180\n" — not raw bytes. The Arduino side reads
 * with Serial.readStringUntil('\n') and parses with strtok/sscanf.
 */
object CommandEngine {

    /** Every transmission ends with exactly this: a single newline. Never CRLF. */
    const val TERMINATOR = "\n"

    const val SPEED_MIN = 0
    const val SPEED_MAX = 255

    const val ANGLE_MIN = 0
    const val ANGLE_MAX = 180
    const val ANGLE_DEFAULT = 90

    /** Speed used by every dashboard movement button. */
    const val DEFAULT_SPEED = 180

    // ---- direction characters -------------------------------------------
    const val FORWARD = 'F'
    const val BACKWARD = 'B'
    const val LEFT = 'L'
    const val RIGHT = 'R'
    const val STOP = 'S'

    /**
     * Builds a drive frame: `"D,DIR,SPEED\n"`.
     *
     * [speed] is clamped to 0..255 so a stray value from a UI control can never
     * emit a malformed frame that the sketch would reject.
     */
    fun drive(direction: Char, speed: Int): String =
        build('D', direction.uppercaseChar(), speed.coerceIn(SPEED_MIN, SPEED_MAX))

    /**
     * Builds a camera gimbal frame: `"C,PAN,TILT\n"`.
     *
     * Both angles are clamped to 0..180 — the physical limit of a standard
     * hobby servo — so dragging a slider can never command the horn past its
     * mechanical stop.
     */
    fun camera(pan: Int, tilt: Int): String =
        build('C', pan.coerceIn(ANGLE_MIN, ANGLE_MAX), tilt.coerceIn(ANGLE_MIN, ANGLE_MAX))

    private fun build(prefix: Char, vararg fields: Any): String = buildString {
        append(prefix)
        for (field in fields) {
            append(',')
            append(field)
        }
        append(TERMINATOR)
    }

    /** Escapes the frame terminator so the debug log shows exactly what went on the wire. */
    fun escape(frame: String): String = frame.replace(TERMINATOR, "\\n")
}
