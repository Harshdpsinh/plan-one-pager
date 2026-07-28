package com.gohil.bookkeeper.web

import java.awt.Desktop
import java.security.SecureRandom
import java.util.Base64

/**
 * Entry point for the local server.
 *
 * Defaults are chosen so the safe thing needs no thought: it listens on loopback only, where
 * nothing outside the machine can reach it. Serving other devices is a deliberate flag, and
 * turning it on brings an access token with it — this handles bank statements and their
 * passwords, and an unauthenticated upload form on a shared network (an office, a hotel, a
 * café) is a genuinely bad idea rather than a theoretical one.
 */
fun main(args: Array<String>) {
    val options = Options.parse(args)
    if (options.help) {
        println(Options.USAGE)
        return
    }

    val token = if (options.exposeToNetwork) options.token ?: generateToken() else null
    val server = BookkeeperServer(token = token)
    server.start(host = options.host, port = options.port)

    val shownHost = if (options.exposeToNetwork) localAddress() else "localhost"
    val url = buildString {
        append("http://").append(shownHost).append(':').append(options.port)
        if (token != null) append("/?token=").append(token)
    }

    // Console output is deliberately plain ASCII. The Windows console defaults to a
    // legacy code page, where box-drawing characters and em-dashes come out as garbage —
    // which looks like a broken program to anyone reading the window.
    println()
    println("  Gohil Bookkeeper - local server")
    println("  -------------------------------")
    println("  Open: $url")
    if (options.exposeToNetwork) {
        println()
        println("  Reachable by other devices on this network.")
        println("  The link above contains an access token - anyone with it can use the app,")
        println("  so share it only with your own devices. Restart to invalidate it.")
    } else {
        println("  This machine only. Use --network to reach it from your phone or laptop.")
    }
    println()
    println("  Nothing is uploaded anywhere: the server makes no outbound connections, and")
    println("  your workbook is modified only in the copy you download.")
    println()
    println("  Press Ctrl+C to stop.")
    println()

    if (options.openBrowser) openBrowser(url)

    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
}

/**
 * Opens the default browser at the app.
 *
 * Best effort by design: on a headless machine, over SSH, or on a minimal Linux install
 * there may be no browser to open, and that is not a failure — the URL is already printed.
 * Never let this stop the server from running.
 */
private fun openBrowser(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported()) {
            val desktop = Desktop.getDesktop()
            if (desktop.isSupported(Desktop.Action.BROWSE)) {
                desktop.browse(java.net.URI(url))
                return
            }
        }
        // Desktop.browse is unavailable on many Linux setups even with a working browser.
        val opener = when {
            System.getProperty("os.name").startsWith("Windows", ignoreCase = true) ->
                listOf("rundll32", "url.dll,FileProtocolHandler", url)
            System.getProperty("os.name").contains("Mac", ignoreCase = true) ->
                listOf("open", url)
            else -> listOf("xdg-open", url)
        }
        ProcessBuilder(opener).start()
    }
}

private fun generateToken(): String {
    val bytes = ByteArray(24)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/** Best-effort LAN address, purely so the printed URL is one you can type on a phone. */
private fun localAddress(): String = runCatching {
    java.net.NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is java.net.Inet4Address }
        ?.hostAddress
}.getOrNull() ?: "localhost"

data class Options(
    val port: Int = 8080,
    val exposeToNetwork: Boolean = false,
    val token: String? = null,
    val openBrowser: Boolean = true,
    val help: Boolean = false,
) {
    val host: String get() = if (exposeToNetwork) "0.0.0.0" else "127.0.0.1"

    companion object {
        val USAGE = """
            Gohil Bookkeeper - local server

              --port <n>       Port to listen on (default 8080)
              --network        Also serve other devices on your network.
                               Prints a URL containing an access token.
              --token <value>  Use a fixed token instead of a generated one.
              --no-open        Do not open a browser automatically.
              --help           Show this message

            With no flags the server is reachable only from this computer.
        """.trimIndent()

        fun parse(args: Array<String>): Options {
            var options = Options()
            var i = 0
            while (i < args.size) {
                when (args[i]) {
                    "--port" -> {
                        options = options.copy(port = args.getOrNull(i + 1)?.toIntOrNull() ?: options.port)
                        i++
                    }
                    "--network" -> options = options.copy(exposeToNetwork = true)
                    "--no-open" -> options = options.copy(openBrowser = false)
                    "--token" -> {
                        options = options.copy(token = args.getOrNull(i + 1), exposeToNetwork = true)
                        i++
                    }
                    "--help", "-h" -> options = options.copy(help = true)
                }
                i++
            }
            return options
        }
    }
}
