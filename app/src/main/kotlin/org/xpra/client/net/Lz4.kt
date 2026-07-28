package org.xpra.client.net

/**
 * LZ4 block decompression.
 *
 * Only the decompressor is needed: we never compress outbound packets, since they are small
 * input events. Writing the ~60 lines by hand avoids pulling in `lz4-java` for one function.
 *
 * xpra does not use the LZ4 *frame* format. `xpra/net/lz4/lz4.pyx:55` packs the uncompressed
 * size with `struct "@I"` — **native** byte order, i.e. little-endian on every realistic
 * server — and follows it with a raw LZ4 block. Note that this is the opposite endianness to
 * every other length field in the protocol, which are all big-endian.
 */
object Lz4 {

    class Lz4Exception(message: String) : RuntimeException(message)

    /** Decompresses a size-prefixed payload as it arrives on the wire. */
    fun decompressSizePrefixed(src: ByteArray, maxSize: Int = Header.MAX_PAYLOAD): ByteArray {
        if (src.size < 4) throw Lz4Exception("payload too short to hold a size prefix")
        val size = (src[0].toInt() and 0xFF) or
            ((src[1].toInt() and 0xFF) shl 8) or
            ((src[2].toInt() and 0xFF) shl 16) or
            ((src[3].toInt() and 0xFF) shl 24)
        if (size < 0 || size > maxSize) throw Lz4Exception("implausible decompressed size: $size")
        return decompressBlock(src, 4, src.size - 4, size)
    }

    /** Decompresses a raw LZ4 block of known output length. */
    fun decompressBlock(src: ByteArray, srcOff: Int, srcLen: Int, destLen: Int): ByteArray {
        val dest = ByteArray(destLen)
        var sp = srcOff
        val srcEnd = srcOff + srcLen
        var dp = 0

        while (sp < srcEnd) {
            val token = src[sp++].toInt() and 0xFF

            // Literals: the high nibble is the length, 15 means "read more length bytes".
            var literalLen = token ushr 4
            if (literalLen == 15) {
                while (true) {
                    if (sp >= srcEnd) throw Lz4Exception("truncated literal length")
                    val b = src[sp++].toInt() and 0xFF
                    literalLen += b
                    if (b != 255) break
                }
            }
            if (literalLen > 0) {
                if (sp + literalLen > srcEnd) throw Lz4Exception("literal run overruns the input")
                if (dp + literalLen > destLen) throw Lz4Exception("literal run overruns the output")
                System.arraycopy(src, sp, dest, dp, literalLen)
                sp += literalLen
                dp += literalLen
            }

            // The last sequence of a block is literals only, with no match to follow.
            if (sp >= srcEnd) break

            // Match offset: 2 bytes, little-endian.
            if (sp + 2 > srcEnd) throw Lz4Exception("truncated match offset")
            val offset = (src[sp].toInt() and 0xFF) or ((src[sp + 1].toInt() and 0xFF) shl 8)
            sp += 2
            if (offset == 0 || offset > dp) throw Lz4Exception("match offset $offset outside the output window")

            // Match length: the low nibble of the token, plus the mandatory minimum of 4.
            var matchLen = token and 0x0F
            if (matchLen == 15) {
                var extra = 0
                while (true) {
                    if (sp >= srcEnd) throw Lz4Exception("truncated match length")
                    val b = src[sp++].toInt() and 0xFF
                    extra += b
                    if (b != 255) break
                }
                matchLen += extra
            }
            matchLen += 4
            if (dp + matchLen > destLen) throw Lz4Exception("match overruns the output")

            // Byte-by-byte on purpose: LZ4 matches routinely overlap the region being written
            // (offset 1 means "repeat the previous byte"), which arraycopy would get wrong.
            var mp = dp - offset
            repeat(matchLen) { dest[dp++] = dest[mp++] }
        }

        if (dp != destLen) throw Lz4Exception("decompressed $dp bytes, expected $destLen")
        return dest
    }
}
