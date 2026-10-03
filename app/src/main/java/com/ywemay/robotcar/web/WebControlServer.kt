package com.ywemay.robotcar.web

import com.ywemay.robotcar.usb.UsbSerialManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

/** Lifecycle state of the embedded remote-control server. */
sealed class WebServerState {
    /** Not started (app closing). */
    object Stopped : WebServerState()

    /** Listening. [url] is the address to type into a browser on the same Wi-Fi. */
    data class Running(val port: Int, val url: String) : WebServerState()

    /** Could not bind (port in use, no network permission, …). */
    data class Failed(val message: String) : WebServerState()
}

/**
 * Process-wide owner of the single [RobotWebServer] instance.
 *
 * Started once from MainActivity and left running for the life of the process
 * (it is deliberately NOT tied to onStart/onStop: the whole point of a remote
 * control link is that it keeps serving while the phone is on the car with the
 * screen showing the face). Stopped only when the Activity is finishing.
 *
 * Idempotent [start] so an Activity recreation cannot double-bind the port.
 */
object WebControlServer {

    /** Conventional HTTP alt port; matches the sibling ipcam-stream server. */
    const val PORT = 8080

    private val _state = MutableStateFlow<WebServerState>(WebServerState.Stopped)
    val state: StateFlow<WebServerState> = _state

    private var server: RobotWebServer? = null

    /** The URL the on-device UI advertises, or null while not running. */
    val url: String? get() = (_state.value as? WebServerState.Running)?.url

    @Synchronized
    fun start() {
        if (server != null) return

        val address = currentUrl()
        val candidate = RobotWebServer(PORT) { currentUrl() }
        try {
            // 0 = no socket read timeout for the accept loop; non-daemon fine
            // (the process owns it and we stop it explicitly).
            candidate.start(NanoHttpdReadTimeout, false)
        } catch (t: Throwable) {
            val detail = t.message ?: t.javaClass.simpleName
            _state.value = WebServerState.Failed("cannot bind port $PORT — $detail")
            UsbSerialManager.note("Web server failed: $detail")
            return
        }

        server = candidate
        _state.value = WebServerState.Running(PORT, address)
        UsbSerialManager.note("Web control ready at $address")
    }

    @Synchronized
    fun stop() {
        val running = server ?: return
        runCatching { running.stop() }
        server = null
        _state.value = WebServerState.Stopped
        UsbSerialManager.note("Web control server stopped")
    }

    /**
     * Recompute `http://<lan-ip>:<port>` on demand.
     *
     * Called fresh for every `/status` poll so the page follows a DHCP lease
     * change without an app restart.
     */
    fun currentUrl(): String = "http://${localIpv4() ?: "phone-ip"}:$PORT"

    // ------------------------------------------------------------------
    // LAN address discovery
    // ------------------------------------------------------------------

    /**
     * Best-effort IPv4 of the Wi-Fi/Ethernet interface.
     *
     * Uses `java.net.NetworkInterface` (the `android.net` one does not exist) and
     * prefers the usual Android interface-name prefixes so a stray VPN/rmnet
     * address does not win over the real WLAN address.
     */
    private fun localIpv4(): String? {
        val found = ArrayList<Pair<String, String>>()
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addresses = nif.inetAddresses ?: continue
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        address.hostAddress
                            ?.takeIf { it.isNotBlank() && !it.startsWith("127.") }
                            ?.let { found += nif.name.lowercase() to it }
                    }
                }
            }
        }
        val preference = listOf("wlan", "ap", "eth", "en", "usb", "rndis", "rmnet")
        return found.minByOrNull { (name, _) ->
            val index = preference.indexOfFirst { name.contains(it) }
            if (index < 0) preference.size else index
        }?.second
    }

    /** Kept local so this file has no NanoHTTPD import surface beyond the server. */
    private const val NanoHttpdReadTimeout = 5000
}
