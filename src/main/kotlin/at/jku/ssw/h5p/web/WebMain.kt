package at.jku.ssw.h5p.web

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

/**
 * The port the server listens on ($PORT env variable or 8080 if not set)
 */
private fun port(): Int {
    val declared = System.getenv("PORT")?.trim()?.takeIf { it.isNotEmpty() } ?: return DEFAULT_PORT
    return declared.toIntOrNull() ?: error("PORT is set to \"$declared\", which is not a port number.")
}

private const val DEFAULT_PORT = 8080

fun runWeb() {
    val port = port()
    println("Web interface on port $port")
    embeddedServer(CIO, port = port, host = "0.0.0.0") {
        configureRouting()
    }.start(wait = true)
}
