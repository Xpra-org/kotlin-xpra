package org.xpra.client.data

/**
 * `tcp://[user[:password]@]host[:port]/[display]`.
 *
 * A bare `host` or `host:port` is treated as tcp. 14500 is xpra's default TCP port, though a
 * server started with `--bind-tcp=:10000` (as the mDNS records on this network show) will
 * advertise its own.
 */
data class ConnectionUri(
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = "",
    val display: String = "",
) {
    fun canonical(): String = buildString {
        append("tcp://")
        if (username.isNotEmpty()) {
            append(username)
            append('@')
        }
        append(host)
        append(':')
        append(port)
        append('/')
        append(display)
    }

    companion object {
        const val DEFAULT_PORT = 14500

        fun parse(input: String): Result<ConnectionUri> {
            val text = input.trim()
            if (text.isEmpty()) return Result.failure(IllegalArgumentException("enter a server address"))

            val withoutScheme = when {
                text.startsWith("tcp://") -> text.removePrefix("tcp://")
                // Named so the message is useful rather than just "bad URI".
                text.contains("://") -> {
                    val scheme = text.substringBefore("://")
                    return Result.failure(
                        IllegalArgumentException("'$scheme://' is not supported yet, only tcp://")
                    )
                }
                else -> text
            }

            val slash = withoutScheme.indexOf('/')
            val authority = if (slash >= 0) withoutScheme.substring(0, slash) else withoutScheme
            val display = if (slash >= 0) withoutScheme.substring(slash + 1) else ""

            val at = authority.lastIndexOf('@')
            val credentials = if (at >= 0) authority.substring(0, at) else ""
            val hostPort = if (at >= 0) authority.substring(at + 1) else authority

            val username = credentials.substringBefore(':', credentials)
            val password = if (credentials.contains(':')) credentials.substringAfter(':') else ""

            if (hostPort.isEmpty()) return Result.failure(IllegalArgumentException("no host in '$input'"))
            val host = hostPort.substringBefore(':')
            if (host.isEmpty()) return Result.failure(IllegalArgumentException("no host in '$input'"))

            val port = if (hostPort.contains(':')) {
                hostPort.substringAfter(':').toIntOrNull()
                    ?: return Result.failure(IllegalArgumentException("bad port in '$input'"))
            } else {
                DEFAULT_PORT
            }
            if (port !in 1..65535) return Result.failure(IllegalArgumentException("port $port is out of range"))

            return Result.success(ConnectionUri(host, port, username, password, display))
        }
    }
}
