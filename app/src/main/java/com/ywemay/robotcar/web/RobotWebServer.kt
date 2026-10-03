package com.ywemay.robotcar.web

import com.ywemay.robotcar.control.CarControl
import com.ywemay.robotcar.usb.CommandEngine
import com.ywemay.robotcar.usb.UsbConnectionState
import com.ywemay.robotcar.usb.UsbSerialManager
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream

/**
 * The embedded remote-control server (NanoHTTPD).
 *
 * Routes
 * ------
 *   GET /                 -> the self-contained control page (see [ControlPage]).
 *   GET /status           -> JSON health/pose snapshot; also what the page polls.
 *   GET /cmd?d=F&s=180    -> drive frame. `d` is F/B/L/R/S, `s` optional 0..255.
 *   GET /drive?dir=R      -> alias of /cmd (friendly name).
 *   GET /camera?pan=&tilt=-> gimbal frame; either argument may be omitted.
 *   GET /stop             -> explicit kill frame (D,S,0).
 *   GET /favicon.ico      -> 204 so browsers stop asking.
 *
 * Every command route funnels into [CarControl], the *same* hub the on-device
 * dashboard uses — so the phone UI and the web UI can never disagree about
 * direction, speed, or gimbal pose. Frames are logged through [UsbSerialManager]
 * exactly as if a finger had pressed a button.
 *
 * Deliberately no auth: it assumes the robot's own trusted LAN. See README.
 *
 * (JSON is assembled with explicit escapes rather than Kotlin raw strings — a
 * raw string that ends in a `"` character is a parser trap.)
 */
class RobotWebServer(
    port: Int,
    /** Live "http://<phone-ip>:<port>" lookup — recomputed per request so the page tracks a DHCP change. */
    private val urlProvider: () -> String,
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri ?: "/"
        val params = session.parameters
        return when (path) {
            "/", "/index.html", "/control" -> htmlResponse()

            "/status" -> jsonResponse(Response.Status.OK, statusJson())

            "/cmd", "/drive" -> driveRoute(params)

            "/camera" -> cameraRoute(params)

            "/stop" -> commandResponse(CarControl.stop())

            "/favicon.ico" ->
                newFixedLengthResponse(Response.Status.NO_CONTENT, "image/x-icon", "")

            else -> jsonResponse(
                Response.Status.NOT_FOUND,
                "{\"ok\":false,\"error\":" + q("unknown route " + path) + "}",
            )
        }
    }

    // ------------------------------------------------------------------
    // Routes
    // ------------------------------------------------------------------

    private fun driveRoute(params: Map<String, List<String>>): Response {
        val raw = first(params, "d") ?: first(params, "dir")
            ?: return badRequest("missing 'd' (one of F B L R S)")

        val dir = raw.trim().first().uppercaseChar()
        if (dir !in DIRECTIONS) return badRequest("bad direction " + q(raw))

        val speed = first(params, "s")?.toIntOrNull()
        val sent = if (speed != null) CarControl.drive(dir, speed) else CarControl.drive(dir)
        return commandResponse(sent)
    }

    private fun cameraRoute(params: Map<String, List<String>>): Response {
        val pan = first(params, "pan")?.toIntOrNull()
        val tilt = first(params, "tilt")?.toIntOrNull()
        if (pan == null && tilt == null) {
            return badRequest("provide 'pan' and/or 'tilt' (0..180)")
        }

        val sent = CarControl.setCamera(pan, tilt)
        return jsonResponse(
            Response.Status.OK,
            "{\"ok\":true,\"sent\":" + sent +
                ",\"pan\":" + CarControl.pan.value +
                ",\"tilt\":" + CarControl.tilt.value +
                ",\"frame\":" + q(CarControl.lastCommand.value ?: "") + "}",
        )
    }

    private fun commandResponse(sent: Boolean): Response =
        jsonResponse(
            Response.Status.OK,
            "{\"ok\":true,\"sent\":" + sent +
                ",\"frame\":" + q(CarControl.lastCommand.value ?: "") + "}",
        )

    private fun statusJson(): String {
        val state = UsbSerialManager.state.value
        val connected = state.isConnected
        val device = (state as? UsbConnectionState.Connected)?.deviceName ?: state.label
        val url = runCatching(urlProvider).getOrDefault("")
        return "{" +
            "\"usb\":" + q(if (connected) "connected" else "disconnected") + "," +
            "\"device\":" + q(device) + "," +
            "\"label\":" + q(state.label) + "," +
            "\"pan\":" + CarControl.pan.value + "," +
            "\"tilt\":" + CarControl.tilt.value + "," +
            "\"last\":" + q(CarControl.lastCommand.value ?: "") + "," +
            "\"address\":" + q(url) +
            "}"
    }

    // ------------------------------------------------------------------
    // Responses
    // ------------------------------------------------------------------

    private fun htmlResponse(): Response {
        val body = ControlPage.HTML.toByteArray(Charsets.UTF_8)
        return newFixedLengthResponse(
            Response.Status.OK,
            "text/html; charset=utf-8",
            ByteArrayInputStream(body),
            body.size.toLong(),
        ).apply { addHeader("Cache-Control", "no-store") }
    }

    private fun jsonResponse(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("Access-Control-Allow-Origin", "*")
        }

    private fun badRequest(message: String): Response =
        jsonResponse(
            Response.Status.BAD_REQUEST,
            "{\"ok\":false,\"error\":" + q(message) + "}",
        )

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun first(params: Map<String, List<String>>, key: String): String? =
        params[key]?.firstOrNull()?.takeIf { it.isNotBlank() }

    /** A JSON string literal, escaped. */
    private fun q(s: String): String = "\"" + jsonEscape(s) + "\""

    private fun jsonEscape(s: String): String = buildString(s.length + 8) {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append(' ') else append(c)
        }
    }

    private companion object {
        val DIRECTIONS = charArrayOf(
            CommandEngine.FORWARD, CommandEngine.BACKWARD, CommandEngine.LEFT,
            CommandEngine.RIGHT, CommandEngine.STOP,
        )
    }
}
