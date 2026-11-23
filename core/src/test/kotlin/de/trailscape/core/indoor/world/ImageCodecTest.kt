package de.trailscape.core.indoor.world

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.awt.image.BufferedImage
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.log10
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageCodecTest {
    /** Rauschbild mit Verlauf: fBm plus etwas Pixelrauschen ueber einem Farbverlauf. */
    private fun testImage(w: Int, h: Int, alpha: Boolean = false): RgbaImage {
        val img = RgbaImage(w, h)
        val rnd = Random(42)
        for (y in 0 until h) for (x in 0 until w) {
            val n = Noise.fbm(x / 23.0, y / 23.0, 5, 5)
            val grain = rnd.nextInt(-6, 7)
            val r = (x * 255 / w + n * 40 + grain).toInt()
            val g = (y * 255 / h + n * 60 + grain).toInt()
            val b = (128 + n * 90 + grain).toInt()
            val a = if (alpha) (x * 7 + y * 3) and 0xFF else 255
            img[x, y] = Argb.of(a, r, g, b)
        }
        return img
    }

    private fun decode(bytes: ByteArray): BufferedImage = assertNotNull(ImageIO.read(ByteArrayInputStream(bytes)))

    private fun psnr(a: RgbaImage, b: BufferedImage): Double {
        var se = 0.0
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val p = a[x, y]
            val q = b.getRGB(x, y)
            for (s in intArrayOf(16, 8, 0)) {
                val d = ((p shr s) and 0xFF) - ((q shr s) and 0xFF)
                se += d * d
            }
        }
        val mse = se / (a.width * a.height * 3)
        return 10 * log10(255.0 * 255.0 / mse)
    }

    @Test
    fun pngRoundTripRgbAndRgba() {
        for (alpha in listOf(false, true)) {
            val img = testImage(67, 45, alpha)
            val bytes = encodePng(img, withAlpha = alpha)
            val info = assertNotNull(imageInfo(bytes))
            assertEquals(ImageFormat.PNG, info.format)
            assertEquals(67, info.width)
            assertEquals(45, info.height)
            assertEquals(alpha, info.hasAlpha)
            assertEquals(67 to 45, pngSize(bytes))
            val back = decode(bytes)
            for (y in 0 until 45) for (x in 0 until 67) {
                val expected = if (alpha) img[x, y] else img[x, y] or (0xFF shl 24)
                assertEquals(expected, back.getRGB(x, y), "Pixel $x,$y")
            }
        }
    }

    @Test
    fun pngAdaptiveFilterKeepsSmoothTexturesSmall() {
        val img = RgbaImage(256, 256)
        for (y in 0 until 256) for (x in 0 until 256) img[x, y] = Argb.rgb(x, y, (x + y) / 2)
        val bytes = encodePng(img, withAlpha = false)
        assertTrue(bytes.size < 256 * 256 * 3 / 20, "Verlauf sollte stark schrumpfen: ${bytes.size} Bytes")
    }

    @Test
    fun jpegIsBaselineAndDecodesWithGoodPsnr() {
        for (sub in listOf(true, false)) {
            val img = testImage(203, 141)
            val bytes = encodeJpegBaseline(img, quality = 85, subsample420 = sub)
            assertTrue(hasMarker(bytes, 0xC0), "SOF0 fehlt")
            assertFalse(hasMarker(bytes, 0xC2), "progressiver Rahmen")
            val info = assertNotNull(imageInfo(bytes))
            assertEquals(ImageFormat.JPEG, info.format)
            assertTrue(info.baseline)
            assertFalse(info.progressive)
            assertEquals(203 to 141, jpegSize(bytes))
            val back = decode(bytes)
            assertEquals(203, back.width)
            assertEquals(141, back.height)
            val p = psnr(img, back)
            assertTrue(p > 30.0, "PSNR $p dB (4:2:0 = $sub)")
        }
        val img = testImage(64, 64)
        val hi = psnr(img, decode(encodeJpegBaseline(img, 100, subsample420 = false)))
        val lo = psnr(img, decode(encodeJpegBaseline(img, 20)))
        assertTrue(hi > 40.0, "Qualitaet 100: $hi dB")
        assertTrue(lo in 20.0..hi, "Qualitaet 20: $lo dB")
    }

    @Test
    fun jpegEncodesLargeImagesQuickly() {
        val img = testImage(1024, 1024)
        encodeJpegBaseline(img) // Aufwaermen (JIT)
        val t0 = System.nanoTime()
        val bytes = encodeJpegBaseline(img)
        val ms = (System.nanoTime() - t0) / 1e6
        println("JPEG 1024² in ${"%.0f".format(ms)} ms, ${bytes.size / 1024} KiB")
        assertTrue(ms < 1500, "JPEG 1024² dauerte $ms ms")
        assertEquals(1024, decode(bytes).width)
    }

    @Test
    fun imageInfoDetectsProgressiveJpegAndNeverThrows() {
        val bi = BufferedImage(32, 16, BufferedImage.TYPE_INT_RGB)
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam
        param.progressiveMode = ImageWriteParam.MODE_DEFAULT
        val bos = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(bos).use { ios ->
            writer.output = ios
            writer.write(null, IIOImage(bi, null, null), param)
        }
        writer.dispose()
        val prog = assertNotNull(imageInfo(bos.toByteArray()))
        assertTrue(prog.progressive)
        assertFalse(prog.baseline)
        assertEquals(32, prog.width)
        assertEquals(16, prog.height)

        val good = encodeJpegBaseline(testImage(16, 16))
        val rnd = Random(1)
        assertNull(imageInfo(ByteArray(0)))
        assertNull(imageInfo(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
        assertNull(imageInfo("GIF89a....".toByteArray()))
        repeat(2000) {
            val junk = when (it % 4) {
                0 -> ByteArray(rnd.nextInt(0, 64)) { rnd.nextInt().toByte() }
                1 -> good.copyOf(rnd.nextInt(0, good.size))
                2 -> good.copyOf().also { b -> repeat(5) { b[rnd.nextInt(2, b.size)] = rnd.nextInt().toByte() } }
                else -> encodePng(testImage(3, 3), false).let { b -> b.copyOf(rnd.nextInt(0, b.size)) }
            }
            imageInfo(junk) // darf nie werfen
        }
    }

    private fun hasMarker(b: ByteArray, m: Int): Boolean {
        // Nur bis zum Bildanfang (SOS) suchen, Entropiedaten sind gestopft.
        var i = 2
        while (i + 3 < b.size) {
            val marker = b[i + 1].toInt() and 0xFF
            if (marker == m) return true
            if (marker == 0xDA) return false
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            i += 2 + len
        }
        return false
    }
}
