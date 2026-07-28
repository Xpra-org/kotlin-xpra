package org.xpra.client.client

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Paint
import org.xpra.client.net.Lz4
import org.xpra.client.net.Packet
import java.nio.ByteBuffer

/**
 * Decodes a `draw` packet and paints it into a window's backing store.
 *
 * `("draw", wid, x, y, w, h, coding, data, sequence, rowstride, client_options)`
 */
object DrawPainter {

    /** Sentinels for the `decode_time` field of the ack (`xpra/constants.py:127`). */
    const val DECODE_ERROR = -1
    const val WINDOW_NOT_FOUND = -2

    class PaintException(message: String) : RuntimeException(message)

    /** Replaces the destination region rather than blending over it. */
    private val replacePaint = Paint().apply {
        isFilterBitmap = false
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
    }

    /**
     * Paints [packet] into [window]. Throws on anything we cannot decode; the caller turns that
     * into a [DECODE_ERROR] ack, which must still be sent.
     */
    fun paint(window: XWindow, packet: Packet) {
        val x = packet.int(2)
        val y = packet.int(3)
        val w = packet.int(4)
        val h = packet.int(5)
        val coding = packet.str(6)
        val options = packet.dict(10)

        // "void" means the server had nothing to send after all. Nothing to paint, but the ack
        // still has to go out.
        if (coding == "void") return

        val data = packet.bytes(7) ?: throw PaintException("draw packet has no pixel data")
        val tile = when {
            coding == "rgb24" || coding == "rgb32" ->
                decodeRaw(data, w, h, packet.int(9), coding, options)
            // BitmapFactory handles png, png/P, png/L, jpeg and webp natively.
            else -> BitmapFactory.decodeByteArray(data, 0, data.size)
                ?: throw PaintException("could not decode a '$coding' image of ${data.size} bytes")
        }

        try {
            synchronized(window.lock) {
                if (window.bitmap.isRecycled) return
                Canvas(window.bitmap).drawBitmap(tile, x.toFloat(), y.toFloat(), replacePaint)
            }
        } finally {
            tile.recycle()
        }
    }

    /**
     * Raw pixels. We advertise `rgb_formats: [RGBA, RGBX, RGB]`, so the server reformats on its
     * side and the bytes arrive in exactly the order `Bitmap.Config.ARGB_8888` holds them in
     * memory (R,G,B,A) — no per-pixel work here.
     */
    private fun decodeRaw(
        raw: ByteArray,
        w: Int,
        h: Int,
        rowstride: Int,
        coding: String,
        options: Map<String, Any?>,
    ): Bitmap {
        // Application-level pixel compression: the network layer deliberately leaves the header
        // level at 0 for these and flags it here instead (xpra/codecs/argb/encoder.py:105).
        var data = raw
        if ((options["lz4"] as? Number)?.toInt() ?: 0 > 0) {
            data = Lz4.decompressSizePrefixed(data)
        }
        if ((options["zstd"] as? Number)?.toInt() ?: 0 > 0) {
            throw PaintException("server used zstd for pixels, which we never advertised")
        }

        val format = (options["rgb_format"] as? String) ?: if (coding == "rgb24") "RGB" else "RGBA"
        val srcBytesPerPixel = if (format.length >= 4) 4 else 3
        val srcStride = if (rowstride > 0) rowstride else w * srcBytesPerPixel

        val out = ByteArray(w * h * 4)
        when (srcBytesPerPixel) {
            4 -> for (row in 0 until h) {
                val from = row * srcStride
                if (from + w * 4 > data.size) throw PaintException("rgb payload is short at row $row")
                System.arraycopy(data, from, out, row * w * 4, w * 4)
            }
            // 24-bit needs the alpha byte inserted.
            else -> for (row in 0 until h) {
                var from = row * srcStride
                var to = row * w * 4
                if (from + w * 3 > data.size) throw PaintException("rgb payload is short at row $row")
                repeat(w) {
                    out[to] = data[from]
                    out[to + 1] = data[from + 1]
                    out[to + 2] = data[from + 2]
                    out[to + 3] = 0xFF.toByte()
                    from += 3
                    to += 4
                }
            }
        }
        // "X" in the format name means the fourth byte is padding, not alpha, so force it
        // opaque rather than painting a fully transparent tile.
        if (format.endsWith("X")) {
            var i = 3
            while (i < out.size) {
                out[i] = 0xFF.toByte()
                i += 4
            }
        }

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(out))
        return bitmap
    }

    /**
     * `flush` counts the draw packets still to come for this screen update, so zero means the
     * frame is complete and it is worth telling the UI to redraw.
     */
    fun isLastOfFrame(packet: Packet): Boolean =
        ((packet.dict(10)["flush"] as? Number)?.toInt() ?: 0) == 0
}
