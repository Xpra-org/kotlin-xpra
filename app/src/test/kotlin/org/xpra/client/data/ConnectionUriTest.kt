package org.xpra.client.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionUriTest {

    private fun parse(s: String) = ConnectionUri.parse(s).getOrThrow()

    @Test
    fun `bare host gets the default port`() {
        val uri = parse("192.168.0.10")
        assertEquals("192.168.0.10", uri.host)
        assertEquals(ConnectionUri.DEFAULT_PORT, uri.port)
    }

    @Test
    fun `host and port without a scheme is treated as tcp`() {
        val uri = parse("192.168.0.10:10000")
        assertEquals("192.168.0.10", uri.host)
        assertEquals(10000, uri.port)
        assertEquals("tcp://192.168.0.10:10000/", uri.canonical())
    }

    @Test
    fun `full form with credentials and display`() {
        val uri = parse("tcp://antoine:secret@fedora:10000/:7")
        assertEquals("fedora", uri.host)
        assertEquals(10000, uri.port)
        assertEquals("antoine", uri.username)
        assertEquals("secret", uri.password)
        assertEquals(":7", uri.display)
        // The password is deliberately left out of the canonical form, which is what gets
        // written to the saved-connections list.
        assertEquals("tcp://antoine@fedora:10000/:7", uri.canonical())
    }

    @Test
    fun `username without a password`() {
        val uri = parse("tcp://antoine@fedora:10000/")
        assertEquals("antoine", uri.username)
        assertEquals("", uri.password)
    }

    @Test
    fun `an at sign in the password does not confuse the host`() {
        val uri = parse("tcp://user:p@ss@host:10000/")
        assertEquals("host", uri.host)
        assertEquals("user", uri.username)
        assertEquals("p@ss", uri.password)
    }

    @Test
    fun `unsupported schemes say so by name`() {
        val error = ConnectionUri.parse("ssl://fedora:10000/").exceptionOrNull()
        assertTrue(error!!.message!!.contains("ssl://"))
        assertTrue(error.message!!.contains("only tcp://"))
    }

    @Test
    fun `rejects malformed input`() {
        assertTrue(ConnectionUri.parse("").isFailure)
        assertTrue(ConnectionUri.parse("   ").isFailure)
        assertTrue(ConnectionUri.parse("tcp://").isFailure)
        assertTrue(ConnectionUri.parse("host:notaport").isFailure)
        assertTrue(ConnectionUri.parse("host:0").isFailure)
        assertTrue(ConnectionUri.parse("host:99999").isFailure)
    }
}
