package org.xpra.client.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A framed connection to an xpra server over plain TCP.
 *
 * Two coroutines: a reader that turns the byte stream into [Packet]s on [incoming], and a
 * writer that drains [outgoing]. All state lives in [org.xpra.client.client.XpraClient], which
 * is the only consumer of [incoming] and the only producer for [outgoing].
 */
class XpraConnection(
    private val scope: CoroutineScope,
    private val onError: (Throwable) -> Unit,
) {

    /** Decoded packets from the server, plus synthetic ones the client posts to itself. */
    val incoming = Channel<Packet>(capacity = Channel.UNLIMITED)

    private val outgoing = Channel<List<Any?>>(capacity = Channel.UNLIMITED)

    private var socket: Socket? = null

    @Volatile
    private var closing = false

    suspend fun connect(host: String, port: Int, timeoutMs: Int = 10_000) {
        val s = withContext(Dispatchers.IO) {
            Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(host, port), timeoutMs)
            }
        }
        socket = s
        startReader(s)
        startWriter(s)
    }

    fun send(vararg parts: Any?) {
        outgoing.trySend(parts.toList())
    }

    fun close() {
        // Set before closing the socket: the reader will wake up with a SocketException, and
        // that is an orderly shutdown rather than something to report.
        closing = true
        outgoing.close()
        incoming.close()
        runCatching { socket?.close() }
        socket = null
    }

    /** Posts a synthetic packet into [incoming] so the client actor sees one event stream. */
    fun post(vararg parts: Any?) {
        incoming.trySend(Packet(parts.toList()))
    }

    private fun startWriter(s: Socket) = scope.launch(Dispatchers.IO) {
        try {
            val out: OutputStream = s.getOutputStream()
            for (packet in outgoing) {
                val payload = Rencodeplus.encode(packet)
                // Header and payload go out in one write. Writing them separately would put an
                // 8-byte segment on the wire ahead of every packet.
                val frame = ByteArray(Header.SIZE + payload.size)
                System.arraycopy(Header.encode(payload.size), 0, frame, 0, Header.SIZE)
                System.arraycopy(payload, 0, frame, Header.SIZE, payload.size)
                out.write(frame)
                out.flush()
            }
        } catch (e: Throwable) {
            if (isActive && !closing) fail(e)
        }
    }

    private fun startReader(s: Socket) = scope.launch(Dispatchers.IO) {
        try {
            // DataInputStream.readFully handles a header or payload spanning several reads.
            val input = DataInputStream(s.getInputStream().buffered(65536))
            val headerBuf = ByteArray(Header.SIZE)
            while (isActive) {
                input.readFully(headerBuf)
                val header = Header.parse(headerBuf)

                var payload = ByteArray(header.length)
                input.readFully(payload)

                if (header.compression != 0) {
                    payload = when {
                        header.compression and Header.COMPRESSION_LZ4 != 0 ->
                            Lz4.decompressSizePrefixed(payload)
                        header.compression and Header.COMPRESSION_BROTLI != 0 ->
                            throw Header.ProtocolException("server used brotli, which we never advertised")
                        header.compression and Header.COMPRESSION_ZSTD != 0 ->
                            throw Header.ProtocolException("server used zstd, which we never advertised")
                        // No algorithm bit means uncompressed: zlib is gone server-side.
                        else -> payload
                    }
                }

                if (header.index > 0) {
                    // We negotiate `chunks: false`, so the server inlines binary payloads and
                    // there is no reassembly path here. Anything else means the capability was
                    // ignored, and silently dropping it would lose pixels.
                    throw Header.ProtocolException(
                        "server sent an out-of-band chunk (index ${header.index}) despite chunks=false"
                    )
                }

                val decoded = Rencodeplus.decode(payload)
                if (decoded !is List<*>) {
                    throw Header.ProtocolException("packet is a ${decoded?.javaClass?.simpleName}, not a list")
                }
                incoming.send(Packet(decoded))
            }
        } catch (e: Throwable) {
            if (isActive && !closing) fail(e)
        }
    }

    private fun fail(e: Throwable) {
        onError(e)
        post("connection-lost", e.message ?: e.javaClass.simpleName)
    }
}
