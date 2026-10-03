package com.ywemay.robotcar.usb

/**
 * Single source of truth for the USB banner at the top of the dashboard.
 *
 * [label] is rendered verbatim, so the state machine reads the same in the log
 * and on screen. [isConnected] is the one flag the whole UI keys off.
 */
sealed class UsbConnectionState(val label: String) {

    /** A probe is in flight — app just started, or the cable was just plugged in. */
    object Scanning : UsbConnectionState("Scanning for USB device…")

    /** A supported bridge was found; the system permission dialog is on screen. */
    class AwaitingPermission(deviceName: String) :
        UsbConnectionState("Waiting for permission — $deviceName")

    /** GREEN banner. A port is open and set to 115200 8N1. */
    class Connected(val deviceName: String, val baudRate: Int) :
        UsbConnectionState("Connected — $deviceName @ $baudRate")

    /** RED banner. Nothing on the wire (unplugged, denied, or link dropped). */
    class Disconnected(reason: String) : UsbConnectionState("No USB link — $reason")

    /** RED banner, but with the failure detail rather than the generic hint. */
    class Failed(detail: String) : UsbConnectionState("USB error — $detail")

    val isConnected: Boolean get() = this is Connected
}
