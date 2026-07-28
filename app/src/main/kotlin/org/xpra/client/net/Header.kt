package org.xpra.client.net

/**
 * xpra's 8-byte packet header.
 *
 * The layout, from `xpra/net/protocol/header.py`, is `struct '!BBBBL'`:
 *
 * ```
 *  0  magic 'P'
 *  1  protocol flags (which packet encoder, plus the cipher and flush bits)
 *  2  compression: the low nibble is the level, the high bits select the algorithm
 *  3  packet index (0 for the main packet, >0 for an out-of-band chunk)
 *  4  payload length, big-endian uint32
 * ```
 */
object Header {

    const val SIZE = 8

    private const val MAGIC = 'P'.code // 0x50

    // Protocol flags (header byte 1). Only one encoder bit may be set at a time.
    const val FLAG_RENCODE = 0x01
    const val FLAG_CIPHER = 0x02 // AES; we never negotiate encryption
    const val FLAG_YAML = 0x04
    const val FLAG_FLUSH = 0x08 // "no more packets follow immediately"
    const val FLAG_RENCODEPLUS = 0x10

    // Compression algorithm bits (the high bits of header byte 2). A zero byte means the
    // payload is not compressed; otherwise the low nibble is the level. Note that zlib is no
    // longer implemented server-side, so "a level with no algorithm bit" means uncompressed.
    const val COMPRESSION_LZ4 = 0x10
    const val COMPRESSION_BROTLI = 0x40
    const val COMPRESSION_ZSTD = 0x80

    /**
     * Bounds what we will allocate for a single packet. xpra's own limits are 16MB
     * (`max_packet_size`) and 256MB absolute; 64MB is generous for a client that never enables
     * mmap or video encodings, and it keeps a corrupt length field from exhausting memory.
     */
    const val MAX_PAYLOAD = 64 shl 20

    class ProtocolException(message: String) : RuntimeException(message)

    data class Parsed(val flags: Int, val compression: Int, val index: Int, val length: Int)

    /**
     * Writes the header for a payload of [payloadLen]. We only ever send uncompressed,
     * unchunked, rencodeplus-encoded packets: our own packets are small input events, and we
     * negotiate `chunks: false` so there is never more than the main packet.
     */
    fun encode(payloadLen: Int): ByteArray = byteArrayOf(
        MAGIC.toByte(),
        (FLAG_RENCODEPLUS or FLAG_FLUSH).toByte(),
        0, // compression level
        0, // packet index
        (payloadLen ushr 24).toByte(),
        (payloadLen ushr 16).toByte(),
        (payloadLen ushr 8).toByte(),
        payloadLen.toByte(),
    )

    fun parse(b: ByteArray, offset: Int = 0): Parsed {
        if (b.size - offset < SIZE) throw ProtocolException("short header: ${b.size - offset} bytes")
        val magic = b[offset].toInt() and 0xFF
        if (magic != MAGIC) {
            throw ProtocolException("bad magic byte 0x%02x, want 0x%02x".format(magic, MAGIC))
        }
        val length = ((b[offset + 4].toInt() and 0xFF) shl 24) or
            ((b[offset + 5].toInt() and 0xFF) shl 16) or
            ((b[offset + 6].toInt() and 0xFF) shl 8) or
            (b[offset + 7].toInt() and 0xFF)
        val index = b[offset + 3].toInt() and 0xFF
        // The server rejects an index of 16 or more itself, so anything that high is a
        // desynchronised stream rather than a chunk we could ever reassemble.
        if (index >= 16) throw ProtocolException("invalid packet index: $index")
        // xpra treats a zero-length payload as a protocol error, since every packet is at
        // least a one-element list.
        if (length == 0) throw ProtocolException("zero-length payload")
        if (length < 0 || length > MAX_PAYLOAD) {
            throw ProtocolException("payload of $length bytes exceeds the $MAX_PAYLOAD byte limit")
        }
        return Parsed(
            flags = b[offset + 1].toInt() and 0xFF,
            compression = b[offset + 2].toInt() and 0xFF,
            index = index,
            length = length,
        )
    }
}
