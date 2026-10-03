package com.ywemay.robotcar.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.SerialTimeoutException
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide owner of the single USB serial link to the Arduino Nano.
 *
 * Design notes
 * ------------
 * * Singleton ([object]) rather than a per-Activity object: there is exactly one
 *   cable and exactly one chip on it, and the connection must survive Activity
 *   recreation (rotation, permission-dialog round trips, launcher returns).
 * * All blocking USB I/O runs on [Dispatchers.IO]; [send] is fire-and-forget so a
 *   UI tap never blocks a frame. Writes are funnelled through [writeMutex] so
 *   concurrent slider + button input can never interleave two frames.
 * * Nothing here knows about Compose or Android UI: it publishes [state] and
 *   [logs] as StateFlows and lets the ViewModel re-expose them.
 *
 * Lifecycle: MainActivity calls [start] in onStart and [stop] in onStop. [stop]
 * only detaches the hot-plug receiver — the open port is intentionally kept, so
 * a permission dialog (which pauses the Activity) does not tear down a live link.
 */
object UsbSerialManager {

    private const val TAG = "UsbSerialManager"

    /** Private broadcast action used to deliver the USB permission result. */
    private const val ACTION_USB_PERMISSION = "com.ywemay.robotcar.action.USB_PERMISSION"

    /** The one and only line rate this firmware speaks. */
    const val BAUD_RATE = 115200

    /** Serial framing, as required by the robot firmware: 8 data bits, 1 stop, no parity. */
    const val DATA_BITS = 8
    const val STOP_BITS = UsbSerialPort.STOPBITS_1
    const val PARITY = UsbSerialPort.PARITY_NONE

    /** Short read window: keeps the link-loss detection responsive without spinning. */
    private const val READ_TIMEOUT_MS = 200

    /** Generous write window: a stalled Arduino should fail visibly, not hang forever. */
    private const val WRITE_TIMEOUT_MS = 2_000

    private const val READ_BUFFER_BYTES = 512
    private const val MAX_RX_LINE_CHARS = 512
    private const val MAX_LOG_LINES = 500

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val connectMutex = Mutex()
    private val logIds = AtomicLong(0L)

    private var appContext: Context? = null
    private var receiverRegistered = false

    /** The open port — the single source of truth for "do we have a link". */
    @Volatile
    private var port: UsbSerialPort? = null

    private var readJob: Job? = null

    private val _state = MutableStateFlow<UsbConnectionState>(UsbConnectionState.Scanning)
    val state: StateFlow<UsbConnectionState> = _state

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs

    private val usbManager: UsbManager?
        get() = appContext?.getSystemService(Context.USB_SERVICE) as? UsbManager

    // ------------------------------------------------------------------
    // Hot-plug listener
    // ------------------------------------------------------------------

    /**
     * The automatic connection listener. Registered for the whole time the
     * dashboard is visible, so inserting the OTG cable connects instantly and
     * yanking it out updates the banner without any user action.
     *
     * Note we ask the system for the attach/detach broadcasts *and* for our own
     * private permission-result action on one IntentFilter.
     */
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    addLog(LogKind.SYS, "USB device attached — probing for serial bridge")
                    scope.launch { connect() }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val name = intent.usbDevice()?.let { deviceName(it) } ?: "device"
                    addLog(LogKind.SYS, "USB device detached ($name) — link down")
                    disconnect("cable unplugged")
                }

                ACTION_USB_PERMISSION -> {
                    val device = intent.usbDevice()
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    // hasPermission() is authoritative; the extra can be missing
                    // when the PendingIntent is delivered IMMUTABLE on some ROMs.
                    val reallyGranted = granted || (device != null && usbManager?.hasPermission(device) == true)
                    if (reallyGranted) {
                        addLog(LogKind.SYS, "USB access granted by user")
                        scope.launch { connect() }
                    } else {
                        addLog(LogKind.SYS, "USB access DENIED by user")
                        _state.value = UsbConnectionState.Disconnected("permission denied")
                    }
                }
            }
        }
    }

    /**
     * Attach the hot-plug listener and immediately probe for a device that may
     * already be plugged in. Idempotent — safe to call from every onStart.
     */
    fun start(context: Context) {
        appContext = context.applicationContext
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(ACTION_USB_PERMISSION)
            }
            // System broadcasts are delivered to NOT_EXPORTED receivers; explicit
            // broadcasts from our own PendingIntent are too.
            ContextCompat.registerReceiver(
                appContext!!, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
        scope.launch { connect() }
    }

    /** Detach the listener (Activity leaving the foreground). Keeps the port open. */
    fun stop() {
        if (receiverRegistered) {
            runCatching { appContext?.unregisterReceiver(usbReceiver) }
            receiverRegistered = false
        }
    }

    /** Fire-and-forget re-probe, for a manual "retry" tap on the status banner. */
    fun requestConnect() {
        scope.launch { connect() }
    }

    // ------------------------------------------------------------------
    // Connection state machine
    // ------------------------------------------------------------------

    /**
     * Probe every attached device for a supported USB-serial bridge, then either
     * open it or ask the user for permission. Serialised by [connectMutex]
     * because the attach broadcast and the system's auto-launch intent can fire
     * simultaneously and a double openDevice() would fail.
     */
    suspend fun connect() {
        connectMutex.withLock {
            val manager = usbManager ?: return
            if (port?.isOpen == true) return          // already live — nothing to do

            val drivers = runCatching {
                UsbSerialProber.getDefaultProber().findAllDrivers(manager)
            }.getOrElse { error ->
                val detail = error.message ?: error.javaClass.simpleName
                addLog(LogKind.SYS, "USB probe failed: $detail")
                _state.value = UsbConnectionState.Failed(detail)
                return
            }

            if (drivers.isEmpty()) {
                addLog(LogKind.SYS, "No USB serial device found on the bus")
                _state.value = UsbConnectionState.Disconnected("no serial device")
                return
            }

            // An Arduino car has exactly one bridge; take the first one found.
            val driver = drivers.first()
            val name = describe(driver)

            if (!manager.hasPermission(driver.device)) {
                addLog(LogKind.SYS, "Requesting USB permission for $name")
                _state.value = UsbConnectionState.AwaitingPermission(name)
                runCatching { manager.requestPermission(driver.device, permissionIntent()) }
                    .onFailure {
                        addLog(LogKind.SYS, "requestPermission failed: ${it.message}")
                        _state.value = UsbConnectionState.Failed("request failed")
                    }
                return
            }

            openPort(driver)
        }
    }

    /** Opens the port and hard-locks the framing the firmware expects. */
    private fun openPort(driver: UsbSerialDriver) {
        val manager = usbManager ?: return
        val name = describe(driver)

        val connection = runCatching { manager.openDevice(driver.device) }.getOrNull()
        if (connection == null) {
            addLog(LogKind.SYS, "openDevice() returned null for $name")
            _state.value = UsbConnectionState.Failed("openDevice failed")
            return
        }

        val serialPort = driver.ports.firstOrNull()
        if (serialPort == null) {
            addLog(LogKind.SYS, "$name exposes no serial port")
            runCatching { connection.close() }
            _state.value = UsbConnectionState.Failed("no serial port")
            return
        }

        try {
            serialPort.open(connection)
            // THE contract from the spec: 115200 baud, 8 data bits, 1 stop bit, no parity.
            serialPort.setParameters(BAUD_RATE, DATA_BITS, STOP_BITS, PARITY)
        } catch (t: Throwable) {
            runCatching { serialPort.close() }
            val detail = t.message ?: t.javaClass.simpleName
            addLog(LogKind.SYS, "Failed to open $name: $detail")
            _state.value = UsbConnectionState.Failed(detail)
            return
        }

        // Best-effort control lines. Many low-cost CH340 clones simply do not
        // implement them and throw — that is not a reason to fail the link.
        runCatching {
            serialPort.dtr = true
            serialPort.rts = true
        }

        port = serialPort
        _state.value = UsbConnectionState.Connected(name, BAUD_RATE)
        addLog(LogKind.SYS, "OPEN $name @ $BAUD_RATE 8N1 — link ready")
        startReadLoop(serialPort)
    }

    /** Closes the port and reports [reason] on the banner. */
    fun disconnect(reason: String) {
        stopReadLoop()
        val previous = port
        port = null
        if (previous != null) {
            runCatching { if (previous.isOpen) previous.close() }
            addLog(LogKind.SYS, "CLOSE serial port ($reason)")
        }
        if (_state.value !is UsbConnectionState.Failed) {
            _state.value = UsbConnectionState.Disconnected(reason)
        }
    }

    // ------------------------------------------------------------------
    // Transmission
    // ------------------------------------------------------------------

    /**
     * Writes one already-terminated frame (see [CommandEngine]) to the Nano.
     *
     * Returns immediately: the actual `write()` runs on the IO dispatcher behind
     * [writeMutex], so it is impossible for two frames to interleave on the wire
     * even if a slider drag and a button tap land in the same millisecond.
     *
     * @return false when there is no link — the attempt is still echoed into the
     *         debug log so the UI always shows what the engine produced.
     */
    fun send(frame: String): Boolean {
        val target = port
        if (target == null || !target.isOpen) {
            addLog(LogKind.SYS, "TX blocked (no link): ${CommandEngine.escape(frame)}")
            return false
        }
        scope.launch {
            writeMutex.withLock {
                try {
                    target.write(frame.toByteArray(Charsets.US_ASCII), WRITE_TIMEOUT_MS)
                    addLog(LogKind.TX, CommandEngine.escape(frame))
                } catch (e: SerialTimeoutException) {
                    addLog(LogKind.SYS, "TX timeout after ${e.bytesTransferred} B")
                } catch (e: IOException) {
                    addLog(LogKind.SYS, "TX failed: ${e.message}")
                    handleLinkLoss(e.message ?: "write failed")
                } catch (t: Throwable) {
                    addLog(LogKind.SYS, "TX failed: ${t.message}")
                    handleLinkLoss(t.message ?: "write failed")
                }
            }
        }
        return true
    }

    // ------------------------------------------------------------------
    // Receive loop
    // ------------------------------------------------------------------

    private fun startReadLoop(serialPort: UsbSerialPort) {
        stopReadLoop()
        readJob = scope.launch {
            val buffer = ByteArray(READ_BUFFER_BYTES)
            val line = StringBuilder()
            while (isActive) {
                val read = try {
                    // Blocks up to READ_TIMEOUT_MS; returns 0 on timeout
                    // (SerialTimeoutException is only ever thrown by write()).
                    serialPort.read(buffer, READ_TIMEOUT_MS)
                } catch (e: IOException) {
                    handleLinkLoss(e.message ?: "read failed")
                    return@launch
                } catch (t: Throwable) {
                    handleLinkLoss(t.message ?: "read failed")
                    return@launch
                }

                if (read <= 0) continue

                for (i in 0 until read) {
                    val c = buffer[i].toInt().toChar()
                    when (c) {
                        '\n' -> {
                            val text = line.toString().trimEnd('\r')
                            if (text.isNotEmpty()) addLog(LogKind.RX, text)
                            line.setLength(0)
                        }
                        // Drop other control bytes so the panel stays readable.
                        '\r' -> Unit
                        else -> {
                            line.append(c)
                            if (line.length > MAX_RX_LINE_CHARS) line.setLength(0)
                        }
                    }
                }
            }
        }
    }

    private fun stopReadLoop() {
        readJob?.cancel()
        readJob = null
    }

    private fun handleLinkLoss(reason: String) {
        if (port == null) return      // a concurrent detach already cleaned up
        addLog(LogKind.SYS, "LINK LOST — $reason")
        disconnect("io error")
    }

    // ------------------------------------------------------------------
    // Log plumbing
    // ------------------------------------------------------------------

    private fun addLog(kind: LogKind, text: String) {
        val entry = LogEntry(
            id = logIds.incrementAndGet(),
            timestampMs = System.currentTimeMillis(),
            kind = kind,
            text = text,
        )
        // Ring buffer: newest MAX_LOG_LINES rows only.
        _logs.value = (_logs.value + entry).takeLast(MAX_LOG_LINES)
        if (kind != LogKind.TX) Log.d(TAG, "[$kind] $text")
    }

    fun clearLog() {
        _logs.value = emptyList()
        addLog(LogKind.SYS, "Debug log cleared")
    }

    /**
     * Publish an informational line (SYS) into the debug log from outside.
     *
     * Used by subsystems that are not the serial link itself — e.g. the web
     * server announcing its URL — so the on-device panel shows one unified
     * timeline of what the car brain is doing.
     */
    fun note(text: String) = addLog(LogKind.SYS, text)

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun permissionIntent(): PendingIntent {
        val context = appContext!!
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        @Suppress("UnspecifiedImmutableFlag")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The platform attaches EXTRA_PERMISSION_GRANTED / EXTRA_DEVICE to the
            // result intent, which an IMMUTABLE PendingIntent would forbid.
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    /** "CH340 [1a86:7523]" — enough to tell two dongles apart in the log. */
    private fun describe(driver: UsbSerialDriver): String = describe(driver.device, driver)

    private fun describe(device: UsbDevice, driver: UsbSerialDriver? = null): String {
        val name = runCatching { device.productName?.takeIf { it.isNotBlank() } }.getOrNull()
            ?: driver?.javaClass?.simpleName?.removeSuffix("SerialDriver")
            ?: "USB serial"
        return "$name [${hex(device.vendorId)}:${hex(device.productId)}]"
    }

    private fun deviceName(device: UsbDevice): String = describe(device)

    private fun hex(value: Int): String = String.format(Locale.US, "%04x", value)

    /** Only exposed for diagnostics assertions in tests/tools. */
    internal fun isOpen(): Boolean = port?.isOpen == true
}
