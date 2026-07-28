package org.xpra.client.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.xpra.client.proto.Challenge
import org.xpra.client.proto.Hello

/**
 * Talks to a real xpra server. Opt-in, because it needs one running:
 *
 * ```
 * ./gradlew :app:testDebugUnitTest --tests '*ServerSmokeTest*' -DxpraServer=192.168.0.10:10000 -i
 * ```
 *
 * This is the fastest way to iterate on the capability dictionary: it exercises the framing,
 * rencodeplus and the hello against the real thing without an emulator in the loop.
 */
class ServerSmokeTest {

    @Test
    fun `handshake and first packets`() {
        val target = System.getProperty("xpraServer").orEmpty()
        assumeTrue("set -DxpraServer=host:port to run this", target.isNotBlank())
        val host = target.substringBefore(':')
        val port = target.substringAfter(':', "14500").toInt()

        val job = SupervisorJob()
        val scope = CoroutineScope(job)
        val seen = LinkedHashMap<String, Int>()
        val codings = LinkedHashMap<String, Int>()
        var drawBytes = 0
        var serverVersion: String? = null

        runBlocking {
            val conn = XpraConnection(scope) { e -> println("!! connection error: $e") }
            conn.connect(host, port)
            println(">> connected to $host:$port")

            val display = Hello.Display(width = 540, height = 1200, dpi = 160)
            val caps = Hello.build(
                display = display,
                username = System.getProperty("user.name") ?: "",
                uuid = Challenge.randomHex(16),
                sessionId = Challenge.randomHex(16),
                hostname = "kotlin-xpra-test",
                doubleClickTimeMs = 400,
                doubleClickDistancePx = 24,
            )
            conn.send("hello", caps)
            println(">> sent hello (${caps.size} capabilities)")

            // Drain for a few seconds and record what the server sends us.
            withTimeoutOrNull(6_000) {
                for (packet in conn.incoming) {
                    seen[packet.type] = (seen[packet.type] ?: 0) + 1
                    when (packet.type) {
                        "hello" -> {
                            val h = packet.dict(1)
                            serverVersion = h["version"]?.toString()
                            println("<< hello: version=$serverVersion display=${h["display"]} " +
                                "session=${h["session_name"]} rencodeplus=${h["rencodeplus"]}")
                            println("   wheel.precise=${h["wheel.precise"]} clipboard=${h["clipboard"]}")
                            val types = (h["packet-types"] as? List<*>)?.map { it.toString() }.orEmpty()
                            println("   packet-types: ${types.size} entries, " +
                                "configure-display=${"configure-display" in types}")
                        }
                        "challenge" -> println("<< challenge: authentication required (not configured here)")
                        "disconnect" -> println("<< DISCONNECT: ${packet.str(1)} ${packet.str(2)}")
                        "window-create" -> {
                            println("<< $packet")
                            // The server sends no pixels until the window is mapped, so this
                            // is what turns the handshake into an actual session.
                            val wid = packet.int(1)
                            conn.send(
                                "map-window", wid, packet.int(2), packet.int(3),
                                packet.int(4), packet.int(5), emptyMap<String, Any>(),
                                emptyMap<String, Any>(),
                            )
                            conn.send("focus", wid, emptyList<String>())
                        }
                        "window-draw" -> {
                            val coding = packet.str(6)
                            val payload = packet.bytes(7)?.size ?: 0
                            codings[coding] = (codings[coding] ?: 0) + 1
                            drawBytes += payload
                            // Ack every draw, including ones we could not paint: the server's
                            // damage accounting stalls the window otherwise.
                            conn.send(
                                "damage-sequence", packet.long(8), packet.int(1),
                                packet.int(4), packet.int(5), 1, "",
                            )
                        }
                        "ping" -> conn.send("ping_echo", packet.long(1), 0, 0, 0, -1, "")
                        else -> if (seen[packet.type] == 1) println("<< $packet")
                    }
                }
            }
            conn.close()
        }
        scope.cancel()

        println("\n=== packet types received ===")
        seen.forEach { (k, v) -> println("  $k x$v") }
        println("=== draw encodings chosen by the server ===")
        codings.forEach { (k, v) -> println("  $k x$v") }
        println("  $drawBytes pixel bytes total")

        check(seen.containsKey("hello")) { "server never sent a hello; got: ${seen.keys}" }
        check(!seen.containsKey("disconnect")) { "server disconnected us" }
        check(seen.containsKey("window-create")) { "no windows; start one with: xpra control :7 start xterm" }
        check(seen.containsKey("window-draw")) { "mapped a window but got no pixels" }
        // Every encoding the server picked has to be one we advertised, or we would be unable
        // to paint it.
        codings.keys.forEach { c ->
            check(c in Hello.CORE_ENCODINGS) { "server sent '$c', which we never advertised" }
        }
        println("=== server version: $serverVersion ===")
    }
}
