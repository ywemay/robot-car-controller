package com.ywemay.robotcar.usb

/** Which direction a debug-log line travelled. Drives its colour in the UI. */
enum class LogKind {
    /** Frame this app pushed out over the serial link. */
    TX,

    /** Line the Nano sent back (firmware status/ack). */
    RX,

    /** Local lifecycle/error chatter from [UsbSerialManager]. */
    SYS,
}

/**
 * One immutable row in the debug log panel.
 *
 * [id] is a monotonic counter so Compose can use it as a stable list key even
 * while older rows are being trimmed off the top of the ring buffer.
 */
data class LogEntry(
    val id: Long,
    val timestampMs: Long,
    val kind: LogKind,
    val text: String,
)
