package de.trailscape.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MultipartTest {

    @Test
    fun `Text- und Dateiteile mit CRLF und abschliessender Boundary`() {
        val body = buildMultipartFormData(
            listOf(
                MultipartPart("data_type", "gpx"),
                MultipartPart("file", "<gpx/>", fileName = "tour.gpx", contentType = "application/gpx+xml"),
            ),
            boundary = "B",
        )

        assertEquals("multipart/form-data; boundary=B", body.contentType)
        assertEquals(
            "--B\r\n" +
                "Content-Disposition: form-data; name=\"data_type\"\r\n" +
                "\r\n" +
                "gpx\r\n" +
                "--B\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"tour.gpx\"\r\n" +
                "Content-Type: application/gpx+xml\r\n" +
                "\r\n" +
                "<gpx/>\r\n" +
                "--B--\r\n",
            body.body,
        )
    }

    @Test
    fun `Anfuehrungszeichen und Zeilenumbrueche im Dateinamen werden maskiert`() {
        val body = buildMultipartFormData(
            listOf(MultipartPart("file", "x", fileName = "Runde \"Nord\"\r\n.gpx")),
            boundary = "B",
        )
        assertTrue(body.body.contains("filename=\"Runde %22Nord%22%0D%0A.gpx\""), body.body)
    }

    @Test
    fun `Boundary im Inhalt wirft`() {
        assertFailsWith<IllegalArgumentException> {
            buildMultipartFormData(listOf(MultipartPart("name", "a --XYZ b")), boundary = "XYZ")
        }
    }

    @Test
    fun `zufaellige Boundaries unterscheiden sich`() {
        val a = randomMultipartBoundary()
        val b = randomMultipartBoundary()
        assertTrue(a != b)
        assertTrue(a.length <= 70)
    }

    @Test
    fun `formUrlEncode kodiert Sonderzeichen, Leerzeichen und Umlaute`() {
        assertEquals(
            "scope=read%2Cactivity%3Awrite&name=Gr%C3%BCne+Runde&a%26b=c%3Dd",
            formUrlEncode(
                listOf(
                    "scope" to "read,activity:write",
                    "name" to "Grüne Runde",
                    "a&b" to "c=d",
                ),
            ),
        )
    }
}
