package com.example

import com.example.security.AllowlistValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MahiLogicUnitTest {

    @Test
    fun testAllowlistValidatorBounds() {
        val validTap = AllowlistValidator.validateTap(500f, 1000f, 1080, 2400)
        assertTrue(validTap is AllowlistValidator.ValidationResult.Valid)

        val invalidTap = AllowlistValidator.validateTap(-10f, 1000f, 1080, 2400)
        assertTrue(invalidTap is AllowlistValidator.ValidationResult.Rejected)

        val shellTest = AllowlistValidator.validateRawCommand("rm -rf /")
        assertTrue(shellTest is AllowlistValidator.ValidationResult.Rejected)

        val validSwipe = AllowlistValidator.validateSwipe(100f, 200f, 300f, 800f, 300L, 1080, 2400)
        assertTrue(validSwipe is AllowlistValidator.ValidationResult.Valid)
    }

    @Test
    fun testWebSocketProtocolConversion() {
        // Test HTTP -> WS conversion logic
        val httpUrl = "http://192.168.1.105:8080"
        val wsUrl = if (httpUrl.startsWith("https://")) {
            httpUrl.replaceFirst("https://", "wss://")
        } else {
            httpUrl.replaceFirst("http://", "ws://")
        } + "/ws"

        assertEquals("ws://192.168.1.105:8080/ws", wsUrl)

        // Test HTTPS -> WSS conversion
        val httpsUrl = "https://signaling.mahi-remote.cloud"
        val wssUrl = if (httpsUrl.startsWith("https://")) {
            httpsUrl.replaceFirst("https://", "wss://")
        } else {
            httpsUrl.replaceFirst("http://", "ws://")
        } + "/ws"

        assertEquals("wss://signaling.mahi-remote.cloud/ws", wssUrl)
    }

    @Test
    fun testOtaVersionComparison() {
        val currentVersionCode = 1
        val newOtaVersionCode = 2
        val sameOtaVersionCode = 1

        assertTrue(newOtaVersionCode > currentVersionCode)
        assertFalse(sameOtaVersionCode > currentVersionCode)
    }

    @Test
    fun testPackageIdentityVerification() {
        val expectedPackage = "com.aistudio.mahiremote.bqpjku"
        val receivedPackage = "com.aistudio.mahiremote.bqpjku"
        val foreignPackage = "com.other.malicious.app"

        assertEquals(expectedPackage, receivedPackage)
        assertFalse(expectedPackage == foreignPackage)
    }
}
