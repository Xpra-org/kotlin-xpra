package org.xpra.client.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The byte-exact vectors come from the self-test in the reference implementation,
 * `xpra-html5/html5/js/lib/rencode.js:532`. If these pass, our encoder agrees with the one the
 * server is talking to.
 */
class RencodeplusTest {

    private fun enc(v: Any?) = Rencodeplus.encode(v).map { it.toInt() and 0xFF }

    private fun bytes(vararg v: Int) = v.toList()

    @Test
    fun `reference vectors`() {
        assertEquals(bytes(67), enc(true))
        assertEquals(bytes(68), enc(false))
        assertEquals(bytes(69), enc(null))
        assertEquals(bytes(1), enc(1))
        assertEquals(bytes(40), enc(40))
        assertEquals(bytes(79), enc(-10))
        assertEquals(bytes(98), enc(-29))
        assertEquals(bytes(62, 100), enc(100))
        assertEquals(bytes(62, 156), enc(-100))
        assertEquals(bytes(63, 105, 243), enc(27123))
        assertEquals(bytes(64, 0, 114, 49, 0), enc(7483648))
        assertEquals(bytes(137, 102, 111, 111, 98, 97, 114, 98, 97, 122), enc("foobarbaz"))
        assertEquals(bytes(129, 0), enc("\u0000"))
        assertEquals(bytes(136, 102, 195, 182, 195, 182, 98, 97, 114), enc("fööbar"))
    }

    @Test
    fun `fixed-width integer boundaries`() {
        // 0..43 embed in the typecode; 44 is the first that needs CHR_INT1.
        assertEquals(bytes(43), enc(43))
        assertEquals(bytes(62, 44), enc(44))
        // -1..-32 embed in the typecode; -33 is the first that needs CHR_INT1.
        assertEquals(bytes(70), enc(-1))
        assertEquals(bytes(101), enc(-32))
        assertEquals(bytes(62, 223), enc(-33))
        // Int8/16/32/64 promotion.
        assertEquals(bytes(62, 127), enc(127))
        assertEquals(bytes(63, 0, 128), enc(128))
        assertEquals(bytes(63, 127, 255), enc(32767))
        assertEquals(bytes(64, 0, 0, 128, 0), enc(32768))
        assertEquals(bytes(65, 0, 0, 0, 0, 128, 0, 0, 0), enc(2147483648L))
    }

    /**
     * The separator is the whole "plus" of rencodeplus, and getting it backwards fails
     * silently, so it gets a test of its own in both directions.
     */
    @Test
    fun `text uses colon and binary uses slash`() {
        val longText = "x".repeat(70)
        val encodedText = Rencodeplus.encode(longText)
        assertEquals('7'.code.toByte(), encodedText[0])
        assertEquals('0'.code.toByte(), encodedText[1])
        assertEquals(':'.code.toByte(), encodedText[2])

        val binary = ByteArray(70) { 'x'.code.toByte() }
        val encodedBinary = Rencodeplus.encode(binary)
        assertEquals('7'.code.toByte(), encodedBinary[0])
        assertEquals('0'.code.toByte(), encodedBinary[1])
        assertEquals('/'.code.toByte(), encodedBinary[2])

        // Short byte strings have no compact form, unlike short text.
        assertEquals(bytes('3'.code, '/'.code, 1, 2, 3), enc(byteArrayOf(1, 2, 3)))
        assertEquals(bytes(131, 97, 98, 99), enc("abc"))

        assertTrue(Rencodeplus.decode(encodedText) is String)
        assertTrue(Rencodeplus.decode(encodedBinary) is ByteArray)
    }

    @Test
    fun `round trip of a realistic hello`() {
        val hello = mapOf(
            "version" to "8.0",
            "protocol-version" to listOf(7, 0),
            "client_type" to "kotlin",
            "encoders" to listOf("rencodeplus"),
            "rencodeplus" to true,
            "chunks" to false,
            "compressors" to listOf("lz4"),
            "compression_level" to 1,
            "windows" to true,
            "encoding" to mapOf(
                "core" to listOf("rgb24", "rgb32", "jpeg", "png", "webp"),
                "rgb_formats" to listOf("RGBA", "RGBX"),
                "transparency" to true,
            ),
            "display" to mapOf(
                "desktop_size" to listOf(540, 1200),
                "refresh-rate" to 60,
            ),
            "uuid" to "0123456789abcdef0123456789abcdef",
        )
        val packet = listOf("hello", hello)

        @Suppress("UNCHECKED_CAST")
        val decoded = Rencodeplus.decode(Rencodeplus.encode(packet)) as List<Any?>
        assertEquals("hello", decoded[0])

        @Suppress("UNCHECKED_CAST")
        val caps = decoded[1] as Map<String, Any?>
        assertEquals("8.0", caps["version"])
        assertEquals(listOf(7L, 0L), caps["protocol-version"])
        assertEquals(true, caps["rencodeplus"])
        assertEquals(false, caps["chunks"])
        assertEquals(1L, caps["compression_level"])
        assertEquals(listOf("lz4"), caps["compressors"])

        @Suppress("UNCHECKED_CAST")
        val encoding = caps["encoding"] as Map<String, Any?>
        assertEquals(listOf("rgb24", "rgb32", "jpeg", "png", "webp"), encoding["core"])

        @Suppress("UNCHECKED_CAST")
        val display = caps["display"] as Map<String, Any?>
        assertEquals(listOf(540L, 1200L), display["desktop_size"])
    }

    @Test
    fun `containers cross the fixed-size boundary`() {
        // Lists of 64+ items and dicts of 25+ pairs switch to the terminated long form.
        val bigList = (0 until 100).map { it }
        assertEquals(bigList.map { it.toLong() }, Rencodeplus.decode(Rencodeplus.encode(bigList)))

        val bigDict = (0 until 40).associate { "key$it" to it }

        @Suppress("UNCHECKED_CAST")
        val back = Rencodeplus.decode(Rencodeplus.encode(bigDict)) as Map<String, Any?>
        assertEquals(40, back.size)
        assertEquals(39L, back["key39"])

        // And the compact forms still work either side of it.
        assertEquals(listOf(1L, 2L), Rencodeplus.decode(Rencodeplus.encode(listOf(1, 2))))
    }

    @Test
    fun `binary payloads survive a round trip unchanged`() {
        val pixels = ByteArray(4096) { (it * 31 % 251).toByte() }
        val packet = listOf("draw", 1, 0, 0, 32, 32, "rgb32", pixels, 7, 128, emptyMap<String, Any>())

        @Suppress("UNCHECKED_CAST")
        val decoded = Rencodeplus.decode(Rencodeplus.encode(packet)) as List<Any?>
        assertArrayEquals(pixels, decoded[7] as ByteArray)
    }

    @Test
    fun `decoder rejects malformed input`() {
        // A dangling terminator.
        assertThrowsDecode { Rencodeplus.decode(byteArrayOf(127)) }
        // A length prefix that overruns the buffer.
        assertThrowsDecode { Rencodeplus.decode("99:abc".toByteArray()) }
        // Trailing bytes after a complete value.
        assertThrowsDecode { Rencodeplus.decode(byteArrayOf(67, 67)) }
        // Truncated fixed-width integer.
        assertThrowsDecode { Rencodeplus.decode(byteArrayOf(64, 0, 0)) }
    }

    private fun assertThrowsDecode(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected a DecodeException")
        } catch (e: Rencodeplus.DecodeException) {
            // expected
        }
    }
}
