package anomaly.hex.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextTest {
    @Test
    fun codesReadTheWayTheComputerDoes() {
        // Matches the computer's own normalization (src/local_api/phone.rs).
        assertEquals("7KQ2M9XD4FTA", normalizeCode(" 7kq2-m9xd-4fta "))
        assertEquals("0110", normalizeCode("oil0"))
        assertEquals("7KQ2-M9XD-4FTA", displayCode("7kq2m9xd4fta"))
    }

    @Test
    fun tailscaleAddressesAreRecognized() {
        assertEquals(RouteKind.TAILSCALE, routeKind("100.101.102.103"))
        assertEquals(RouteKind.TAILSCALE, routeKind("mac.tail1234.ts.net"))
        assertEquals(RouteKind.LAN, routeKind("100.128.0.1"))
        assertEquals(RouteKind.LAN, routeKind("192.168.1.20"))
    }

    @Test
    fun automaticTriesTailscaleFirst() {
        val lan = Route(RouteKind.LAN, "192.168.1.20")
        val tailscale = Route(RouteKind.TAILSCALE, "100.101.102.103")
        assertEquals(listOf(tailscale, lan), Router.ordered(listOf(lan, tailscale), RoutePreference.AUTOMATIC))
        assertEquals(listOf(lan), Router.ordered(listOf(lan, tailscale), RoutePreference.LAN))
    }

    @Test
    fun addressesTakeAnOptionalPort() {
        assertEquals("10.0.0.2" to 47213, parseAddress("10.0.0.2", 47213))
        assertEquals("mac.local" to 5000, parseAddress("http://mac.local:5000/", 47213))
        assertNull(parseAddress("10.0.0.2:99999", 47213))
        assertNull(parseAddress("", 47213))
    }

    @Test
    fun dictationsDoNotRunTogether() {
        assertEquals(" world", joinAtCursor("hello", "world"))
        assertEquals("world", joinAtCursor("hello ", "world"))
        assertEquals(", then", joinAtCursor("hello", ", then"))
        assertEquals("first", joinAtCursor("", "first"))
    }

    @Test
    fun wavHeaderDescribes16kMonoPcm() {
        val out = wav(byteArrayOf(1, 2, 3, 4), 16_000)
        assertEquals(48, out.size)
        assertArrayEquals("RIFF".toByteArray(), out.copyOfRange(0, 4))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x3E, 0, 0), out.copyOfRange(24, 28))
        assertArrayEquals(byteArrayOf(4, 0, 0, 0), out.copyOfRange(40, 44))
    }
}
