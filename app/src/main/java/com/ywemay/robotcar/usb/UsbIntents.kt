package com.ywemay.robotcar.usb

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.os.Build

/**
 * Type-safe [UsbManager.EXTRA_DEVICE] read.
 *
 * The deprecated `getParcelableExtra(String)` overload was removed for API 33+
 * apps, while the new `getParcelableExtra(String, Class)` overload does not
 * exist below API 33 — so the branch is unavoidable. Wrapping it here keeps the
 * version check out of the receiver's `when` block.
 */
internal fun Intent.usbDevice(): UsbDevice? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE)
    }
