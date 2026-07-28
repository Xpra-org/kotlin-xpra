package org.xpra.client.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fixtures produced by xpra's own compressor, so this tests against the exact bytes the server
 * puts on the wire rather than against another LZ4 implementation:
 *
 * ```
 * python3 -c "from xpra.net.lz4.lz4 import compress; print(list(compress(b'...')))"
 * ```
 */
class Lz4Test {

    private fun fixture(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `size prefix is little-endian`() {
        // 176,4,0,0 == 0x4B0 == 1200, which is the length of the "repeat" sample below. Read
        // as big-endian this would be 2953248768, so a wrong endianness fails loudly here.
        val compressed = fixture(
            176, 4, 0, 0, 63, 65, 66, 67, 3, 0, 255, 255, 255, 255, 153, 80, 66, 67, 65, 66, 67,
        )
        val expected = "ABCABCABCABCABCABCABCABCABCABC".repeat(40).toByteArray()
        assertEquals(1200, expected.size)
        assertArrayEquals(expected, Lz4.decompressSizePrefixed(compressed))
    }

    @Test
    fun `text with back-references`() {
        val compressed = fixture(
            70, 5, 0, 0, 240, 16, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111,
            119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114,
            32, 31, 0, 145, 108, 97, 122, 121, 32, 100, 111, 103, 46, 14, 0, 15, 45, 0, 255,
            255, 255, 255, 255, 2, 80, 100, 111, 103, 46, 32,
        )
        val expected = "the quick brown fox jumps over the lazy dog. ".repeat(30).toByteArray()
        assertArrayEquals(expected, Lz4.decompressSizePrefixed(compressed))
    }

    /**
     * Long runs exercise the overlapping-match path: an offset of 1 means "repeat the previous
     * byte", which a plain arraycopy would get wrong.
     */
    @Test
    fun `long runs use overlapping matches`() {
        val compressed = fixture(
            180, 20, 0, 0, 31, 0, 1, 0, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
            255, 255, 255, 255, 255, 255, 255, 255, 135, 31, 255, 1, 0, 255, 20, 80, 255, 255,
            255, 255, 255,
        )
        val expected = ByteArray(5000) { 0 } + ByteArray(300) { 0xFF.toByte() }
        assertArrayEquals(expected, Lz4.decompressSizePrefixed(compressed))
    }

    @Test
    fun `literal-only block`() {
        val compressed = fixture(2, 0, 0, 0, 32, 104, 105)
        assertArrayEquals("hi".toByteArray(), Lz4.decompressSizePrefixed(compressed))
    }

    @Test
    fun `rejects a corrupt size prefix`() {
        // 0xFFFFFFFF reads as -1, which must not become a huge allocation.
        assertThrows { Lz4.decompressSizePrefixed(fixture(255, 255, 255, 255, 0)) }
        assertThrows { Lz4.decompressSizePrefixed(fixture(1, 2)) }
    }

    @Test
    fun `rejects a match offset outside the output window`() {
        // token 0x01: no literals, a minimal match, with an offset that points before the
        // start of the output.
        assertThrows { Lz4.decompressBlock(fixture(0x01, 16, 0), 0, 3, 16) }
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected an Lz4Exception")
        } catch (e: Lz4.Lz4Exception) {
            // expected
        }
    }
}
