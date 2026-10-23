package app.maskan.chat.util

import app.maskan.chat.util.EndpointPlace.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where a provider's base URL sends the user's words - the Settings badge reads this. */
class EndpointPlaceTest {

    @Test
    fun theReportedCase_aCloudBehindCustomIsNotLocal() {
        assertEquals(Place.REMOTE, EndpointPlace.placeOf("https://api.cerebras.ai/v1"))
        assertEquals(Place.REMOTE, EndpointPlace.placeOf("https://api.x.ai/v1/"))
    }

    @Test
    fun loopbackIsThePhone() {
        for (url in listOf("http://localhost:11434/", "http://127.0.0.1:1234", "http://[::1]:8080/", "http://0.0.0.0:8000")) {
            assertEquals(url, Place.PHONE, EndpointPlace.placeOf(url))
        }
    }

    @Test
    fun privateRangesLanNamesAndTailscaleAreTheNetwork() {
        for (url in listOf(
            "http://192.168.1.20:11434", "http://10.0.0.5:8080", "http://172.20.1.1", "http://169.254.3.4",
            "http://100.101.102.103:8080", "https://pc.tail1234.ts.net", "http://ai-pc.local:8189",
            "http://myserver:8080", "http://box.lan", "http://nas.home.arpa", "http://[fd7a:115c::1]:8080"
        )) {
            assertEquals(url, Place.NETWORK, EndpointPlace.placeOf(url))
        }
    }

    @Test
    fun lookalikesAreNotPrivate() {
        // A domain that merely starts with a private-looking number leaves the network.
        assertEquals(Place.REMOTE, EndpointPlace.placeOf("http://10.example.com"))
        assertEquals(Place.REMOTE, EndpointPlace.placeOf("http://172.15.0.1"))
        assertEquals(Place.REMOTE, EndpointPlace.placeOf("http://100.128.0.1"))
        assertEquals(Place.REMOTE, EndpointPlace.placeOf("https://local.example.com"))
    }

    @Test
    fun hostIsWhatTheBadgeNames() {
        assertEquals("api.cerebras.ai", EndpointPlace.host("https://user:pw@API.Cerebras.ai:443/v1?x=1"))
        assertEquals("fd7a:115c::1", EndpointPlace.host("http://[fd7a:115c::1]:8080/"))
        assertNull(EndpointPlace.placeOf("   "))
    }

    @Test
    fun theHttpWarningIsForTheOutsideOnly() {
        assertTrue(EndpointPlace.isInsecureRemote("http://api.example.com/v1"))
        assertFalse(EndpointPlace.isInsecureRemote("https://api.example.com/v1"))
        assertFalse(EndpointPlace.isInsecureRemote("http://192.168.1.20:11434"))
        assertFalse(EndpointPlace.isInsecureRemote("http://pc.tail1234.ts.net"))
    }
}
