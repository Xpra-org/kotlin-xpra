package org.xpra.client.net

import java.io.ByteArrayOutputStream

/**
 * The `rencodeplus` packet encoding, which is the only encoder a modern xpra server will
 * negotiate (`xpra/net/packet_encoding.py` lists it first and `rencode`/`bencode` are gone).
 *
 * The format is bencode with the typecodes relocated onto the base-256 character set, so most
 * small values encode as a single byte. The table below is transcribed from
 * `xpra/net/rencodeplus/rencodeplus.pyx:48`.
 */
object Rencodeplus {

    // Typecodes that introduce a value.
    private const val CHR_FLOAT64 = 44
    private const val CHR_LIST = 59
    private const val CHR_DICT = 60
    private const val CHR_INT = 61
    private const val CHR_INT1 = 62
    private const val CHR_INT2 = 63
    private const val CHR_INT4 = 64
    private const val CHR_INT8 = 65
    private const val CHR_FLOAT32 = 66
    private const val CHR_TRUE = 67
    private const val CHR_FALSE = 68
    private const val CHR_NONE = 69
    private const val CHR_TERM = 127

    // Typecodes with the value or length embedded in the code itself.
    private const val INT_POS_FIXED_START = 0
    private const val INT_POS_FIXED_COUNT = 44
    private const val INT_NEG_FIXED_START = 70
    private const val INT_NEG_FIXED_COUNT = 32
    private const val DICT_FIXED_START = 102
    private const val DICT_FIXED_COUNT = 25
    private const val STR_FIXED_START = 128
    private const val STR_FIXED_COUNT = 64
    private const val LIST_FIXED_START = 192
    private const val LIST_FIXED_COUNT = 64

    /**
     * Length-prefixed strings and byte strings share the `<decimal length><sep><payload>` form
     * and are told apart *only* by the separator. This is the "plus" in rencodeplus, and it is
     * the one detail that must not be got wrong: the failure is silent, because the peer would
     * see `b"title"` where it expects `"title"` and every dictionary lookup would miss.
     */
    private const val SEP_TEXT = ':'.code // 58
    private const val SEP_BINARY = '/'.code // 47

    /** Guards against a hostile peer nesting containers deeply enough to blow the stack. */
    private const val MAX_DEPTH = 64

    class DecodeException(message: String) : RuntimeException(message)

    // ------------------------------------------------------------------ encoding

    fun encode(value: Any?): ByteArray {
        val out = ByteArrayOutputStream(256)
        encodeValue(out, value, 0)
        return out.toByteArray()
    }

    private fun encodeValue(out: ByteArrayOutputStream, value: Any?, depth: Int) {
        if (depth > MAX_DEPTH) throw IllegalArgumentException("value nested more than $MAX_DEPTH deep")
        when (value) {
            null -> out.write(CHR_NONE)
            // Booleans are checked before the integer arms: in Kotlin they are not numbers, but
            // keeping the order explicit matches the reference implementations.
            is Boolean -> out.write(if (value) CHR_TRUE else CHR_FALSE)
            is Byte -> encodeInt(out, value.toLong())
            is Short -> encodeInt(out, value.toLong())
            is Int -> encodeInt(out, value.toLong())
            is Long -> encodeInt(out, value)
            is Float -> encodeDouble(out, value.toDouble())
            is Double -> encodeDouble(out, value)
            is String -> encodeString(out, value)
            is ByteArray -> encodeBytes(out, value)
            is Map<*, *> -> encodeDict(out, value, depth)
            is List<*> -> encodeList(out, value, depth)
            is Array<*> -> encodeList(out, value.asList(), depth)
            is IntArray -> encodeList(out, value.toList(), depth)
            else -> throw IllegalArgumentException("cannot rencode ${value.javaClass.name}")
        }
    }

    private fun encodeInt(out: ByteArrayOutputStream, v: Long) {
        when {
            v >= 0 && v < INT_POS_FIXED_COUNT -> out.write(INT_POS_FIXED_START + v.toInt())
            v >= -INT_NEG_FIXED_COUNT && v < 0 -> out.write(INT_NEG_FIXED_START - 1 - v.toInt())
            v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE -> {
                out.write(CHR_INT1)
                out.write(v.toInt() and 0xFF)
            }
            v >= Short.MIN_VALUE && v <= Short.MAX_VALUE -> {
                out.write(CHR_INT2)
                writeBigEndian(out, v, 2)
            }
            v >= Int.MIN_VALUE && v <= Int.MAX_VALUE -> {
                out.write(CHR_INT4)
                writeBigEndian(out, v, 4)
            }
            else -> {
                out.write(CHR_INT8)
                writeBigEndian(out, v, 8)
            }
        }
    }

    private fun encodeDouble(out: ByteArrayOutputStream, v: Double) {
        out.write(CHR_FLOAT64)
        writeBigEndian(out, java.lang.Double.doubleToRawLongBits(v), 8)
    }

    private fun writeBigEndian(out: ByteArrayOutputStream, v: Long, bytes: Int) {
        for (i in bytes - 1 downTo 0) out.write(((v shr (8 * i)) and 0xFF).toInt())
    }

    private fun encodeString(out: ByteArrayOutputStream, s: String) {
        val utf8 = s.toByteArray(Charsets.UTF_8)
        if (utf8.size < STR_FIXED_COUNT) {
            out.write(STR_FIXED_START + utf8.size)
        } else {
            out.write(utf8.size.toString().toByteArray(Charsets.US_ASCII))
            out.write(SEP_TEXT)
        }
        out.write(utf8)
    }

    /** Binary payloads have no short form: always `<decimal length>/<bytes>`. */
    private fun encodeBytes(out: ByteArrayOutputStream, b: ByteArray) {
        out.write(b.size.toString().toByteArray(Charsets.US_ASCII))
        out.write(SEP_BINARY)
        out.write(b)
    }

    private fun encodeList(out: ByteArrayOutputStream, list: List<*>, depth: Int) {
        if (list.size < LIST_FIXED_COUNT) {
            out.write(LIST_FIXED_START + list.size)
            for (item in list) encodeValue(out, item, depth + 1)
        } else {
            out.write(CHR_LIST)
            for (item in list) encodeValue(out, item, depth + 1)
            out.write(CHR_TERM)
        }
    }

    private fun encodeDict(out: ByteArrayOutputStream, map: Map<*, *>, depth: Int) {
        if (map.size < DICT_FIXED_COUNT) {
            out.write(DICT_FIXED_START + map.size)
            for ((k, v) in map) {
                encodeValue(out, k, depth + 1)
                encodeValue(out, v, depth + 1)
            }
        } else {
            out.write(CHR_DICT)
            for ((k, v) in map) {
                encodeValue(out, k, depth + 1)
                encodeValue(out, v, depth + 1)
            }
            out.write(CHR_TERM)
        }
    }

    // ------------------------------------------------------------------ decoding

    /**
     * Decodes one complete value. Integers always come back as [Long] and dictionary keys are
     * normalised to [String], so callers never have to care whether the peer sent a key as text
     * or as bytes. Use the accessors on [Packet] rather than casting directly.
     */
    fun decode(data: ByteArray): Any? {
        val cursor = Cursor(data)
        val value = decodeValue(cursor, 0)
        if (cursor.pos != data.size) {
            throw DecodeException("${data.size - cursor.pos} trailing bytes after the decoded value")
        }
        return value
    }

    private class Cursor(val data: ByteArray) {
        var pos = 0

        fun byte(): Int {
            if (pos >= data.size) throw DecodeException("truncated packet at offset $pos")
            return data[pos++].toInt() and 0xFF
        }

        fun peek(): Int {
            if (pos >= data.size) throw DecodeException("truncated packet at offset $pos")
            return data[pos].toInt() and 0xFF
        }

        fun take(n: Int): ByteArray {
            if (n < 0 || pos + n > data.size) {
                throw DecodeException("payload of $n bytes overruns the packet at offset $pos")
            }
            // Copied rather than aliased: the caller may hold on to pixel data long after the
            // read buffer has been reused.
            val out = data.copyOfRange(pos, pos + n)
            pos += n
            return out
        }
    }

    private fun decodeValue(c: Cursor, depth: Int): Any? {
        if (depth > MAX_DEPTH) throw DecodeException("value nested more than $MAX_DEPTH deep")
        val tag = c.byte()
        return when {
            tag < INT_POS_FIXED_COUNT -> tag.toLong()
            tag == CHR_FLOAT64 -> java.lang.Double.longBitsToDouble(readBigEndian(c, 8))
            // ASCII digits introduce a length-prefixed string or byte string.
            tag in '0'.code..'9'.code -> {
                c.pos--
                decodeLengthPrefixed(c)
            }
            tag == CHR_LIST -> {
                val list = ArrayList<Any?>()
                while (c.peek() != CHR_TERM) list.add(decodeValue(c, depth + 1))
                c.byte()
                list
            }
            tag == CHR_DICT -> {
                val map = LinkedHashMap<String, Any?>()
                while (c.peek() != CHR_TERM) readPair(c, map, depth)
                c.byte()
                map
            }
            tag == CHR_INT -> {
                val sb = StringBuilder()
                while (c.peek() != CHR_TERM) {
                    sb.append(c.byte().toChar())
                    if (sb.length > 64) throw DecodeException("oversized ascii integer")
                }
                c.byte()
                sb.toString().toLongOrNull() ?: throw DecodeException("bad ascii integer '$sb'")
            }
            tag == CHR_INT1 -> c.byte().toByte().toLong()
            tag == CHR_INT2 -> readBigEndian(c, 2).toShort().toLong()
            tag == CHR_INT4 -> readBigEndian(c, 4).toInt().toLong()
            tag == CHR_INT8 -> readBigEndian(c, 8)
            // The server never emits float32, but the reference decoders accept it.
            tag == CHR_FLOAT32 -> java.lang.Float.intBitsToFloat(readBigEndian(c, 4).toInt()).toDouble()
            tag == CHR_TRUE -> true
            tag == CHR_FALSE -> false
            tag == CHR_NONE -> null
            tag < INT_NEG_FIXED_START + INT_NEG_FIXED_COUNT -> (INT_NEG_FIXED_START - 1 - tag).toLong()
            tag < DICT_FIXED_START + DICT_FIXED_COUNT -> {
                val map = LinkedHashMap<String, Any?>()
                repeat(tag - DICT_FIXED_START) { readPair(c, map, depth) }
                map
            }
            tag == CHR_TERM -> throw DecodeException("unexpected terminator at offset ${c.pos - 1}")
            tag < STR_FIXED_START + STR_FIXED_COUNT -> String(c.take(tag - STR_FIXED_START), Charsets.UTF_8)
            else -> {
                val list = ArrayList<Any?>(tag - LIST_FIXED_START)
                repeat(tag - LIST_FIXED_START) { list.add(decodeValue(c, depth + 1)) }
                list
            }
        }
    }

    private fun readPair(c: Cursor, map: LinkedHashMap<String, Any?>, depth: Int) {
        val key = decodeValue(c, depth + 1)
        val name = when (key) {
            is String -> key
            is ByteArray -> String(key, Charsets.UTF_8)
            else -> throw DecodeException("dictionary key is a ${key?.javaClass?.simpleName ?: "null"}")
        }
        map[name] = decodeValue(c, depth + 1)
    }

    /** Reads `<decimal length><sep><payload>`; the separator decides text versus binary. */
    private fun decodeLengthPrefixed(c: Cursor): Any {
        var length = 0
        var digits = 0
        while (true) {
            val b = c.byte()
            if (b == SEP_TEXT || b == SEP_BINARY) {
                if (digits == 0) throw DecodeException("length prefix with no digits")
                val payload = c.take(length)
                return if (b == SEP_BINARY) payload else String(payload, Charsets.UTF_8)
            }
            if (b < '0'.code || b > '9'.code) {
                throw DecodeException("bad byte ${b} in a length prefix at offset ${c.pos - 1}")
            }
            length = length * 10 + (b - '0'.code)
            if (++digits > 10) throw DecodeException("oversized length prefix")
        }
    }

    private fun readBigEndian(c: Cursor, bytes: Int): Long {
        var v = 0L
        repeat(bytes) { v = (v shl 8) or c.byte().toLong() }
        return v
    }
}
