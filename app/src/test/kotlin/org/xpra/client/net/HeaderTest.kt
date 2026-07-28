package org.xpra.client.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HeaderTest {

    @Test
    fun `encodes the layout xpra expects`() {
        val h = Header.encode(0x11223344)
        // 'P', rencodeplus|flush, no compression, main packet, then a big-endian length.
        assertArrayEquals(
            byteArrayOf(0x50, 0x18, 0x00, 0x00, 0x11, 0x22, 0x33, 0x44),
            h,
        )
        assertEquals(Header.SIZE, h.size)
    }

    @Test
    fun `round trips through parse`() {
        val parsed = Header.parse(Header.encode(1234))
        assertEquals(1234, parsed.length)
        assertEquals(0, parsed.index)
        assertEquals(0, parsed.compression)
        assertEquals(Header.FLAG_RENCODEPLUS or Header.FLAG_FLUSH, parsed.flags)
    }

    @Test
    fun `reads compression and index fields`() {
        val h = byteArrayOf(0x50, 0x10, (0x10 or 1).toByte(), 7, 0, 0, 0x04, 0)
        val parsed = Header.parse(h)
        assertEquals(Header.COMPRESSION_LZ4, parsed.compression and 0xF0)
        assertEquals(1, parsed.compression and 0x0F)
        assertEquals(7, parsed.index)
        assertEquals(1024, parsed.length)
    }

    @Test
    fun `rejects malformed headers`() {
        // Bad magic.
        assertThrows { Header.parse(byteArrayOf(0x51, 0x10, 0, 0, 0, 0, 0, 1)) }
        // Zero-length payload: every packet is at least a one-element list.
        assertThrows { Header.parse(byteArrayOf(0x50, 0x10, 0, 0, 0, 0, 0, 0)) }
        // Packet index the server itself would reject.
        assertThrows { Header.parse(byteArrayOf(0x50, 0x10, 0, 16, 0, 0, 0, 1)) }
        // Oversized payload.
        assertThrows { Header.parse(byteArrayOf(0x50, 0x10, 0, 0, 0x7F, 0, 0, 1)) }
        // Short buffer.
        assertThrows { Header.parse(byteArrayOf(0x50, 0x10, 0)) }
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected a ProtocolException")
        } catch (e: Header.ProtocolException) {
            // expected
        }
    }
}
