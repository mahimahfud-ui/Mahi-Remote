package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.filetransfer.FileManager
import com.example.pairing.OperatorRequest
import com.example.pairing.PairingManager
import com.example.security.AllowlistValidator
import com.example.security.DeviceIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun testAppNameResource() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Mahi Remote", appName)
    }

    @Test
    fun testDeviceIdentityGeneration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val identity = DeviceIdentity.getOrCreateIdentity(context)
        assertNotNull(identity.deviceId)
        assertTrue(identity.deviceId.startsWith("MAHI-"))
        assertNotNull(identity.fingerprint)
        assertTrue(identity.fingerprint.contains(":"))
    }

    @Test
    fun testAllowlistValidatorBounds() {
        // Within bounds
        val validTap = AllowlistValidator.validateTap(500f, 1000f, 1080, 2400)
        assertTrue(validTap is AllowlistValidator.ValidationResult.Valid)

        // Out of bounds
        val invalidTap = AllowlistValidator.validateTap(-10f, 1000f, 1080, 2400)
        assertTrue(invalidTap is AllowlistValidator.ValidationResult.Rejected)

        // Prohibited shell command
        val shellTest = AllowlistValidator.validateRawCommand("rm -rf /")
        assertTrue(shellTest is AllowlistValidator.ValidationResult.Rejected)

        // Valid swipe
        val validSwipe = AllowlistValidator.validateSwipe(100f, 200f, 300f, 800f, 300L, 1080, 2400)
        assertTrue(validSwipe is AllowlistValidator.ValidationResult.Valid)

        // Invalid swipe duration
        val invalidDuration = AllowlistValidator.validateSwipe(100f, 200f, 300f, 800f, 10L, 1080, 2400)
        assertTrue(invalidDuration is AllowlistValidator.ValidationResult.Rejected)
    }

    @Test
    fun testPairingCodeAndSessionLifecycle() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = PairingManager(context)
        val codeData = manager.generateNewPairingCode()

        assertNotNull(codeData.code)
        assertEquals(7, codeData.code.length) // "XXX-XXX"
        assertTrue(codeData.expiresAtMillis > System.currentTimeMillis())

        val request = OperatorRequest(
            requestId = "req-1",
            operatorName = "Sylhet NOC Operator",
            operatorLocation = "Sylhet, Bangladesh",
            controllerFingerprint = "8E:4A:91:D2:11:F9:6C:55:A3:40:99:BC:14:8A:EF:29",
            requestedScopes = listOf("Screen Mirroring", "Remote Interaction"),
            timestamp = System.currentTimeMillis()
        )

        manager.approveRequest(request)
        val active = manager.activeSession.value
        assertNotNull(active)
        assertEquals("Sylhet NOC Operator", active?.operatorName)

        manager.emergencyDisconnect("Test disconnect")
        assertEquals(null, manager.activeSession.value)
    }

    @Test
    fun testFileManagerTwoWayTransferAndSha256() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fileManager = FileManager(context)

        val testDir = context.filesDir.absolutePath
        val testFile = File(testDir, "test_document.txt")
        testFile.delete()

        // 1. Write chunk
        val sampleData = "Hello from Mahi Remote Windows Controller!".toByteArray()
        val writeOk = fileManager.writeFileChunk(testFile.absolutePath, sampleData, append = false)
        assertTrue("Write chunk failed", writeOk)
        assertTrue(testFile.exists())

        // 2. Read chunk back
        val readBytes = fileManager.readFileChunk(testFile.absolutePath, 0, sampleData.size)
        assertNotNull(readBytes)
        assertEquals("Hello from Mahi Remote Windows Controller!", String(readBytes!!))

        // 3. Verify SHA-256 calculation
        val checksum = fileManager.calculateSha256(testFile.absolutePath)
        assertNotNull(checksum)
        assertEquals(64, checksum?.length) // 64 hex chars

        // 4. Create folder
        val folderOk = fileManager.createFolder(testDir, "TestLogs")
        assertTrue("Folder creation failed", folderOk)
        val createdFolder = File(testDir, "TestLogs")
        assertTrue(createdFolder.isDirectory)

        // 5. Delete file
        val deleteOk = fileManager.deleteFile(testFile.absolutePath, confirmed = true)
        assertTrue("File deletion failed", deleteOk)
        assertFalse(testFile.exists())
    }

    @Test
    fun testAndroidToAndroidRoleAndControllerSession() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = PairingManager(context)

        // Simulate incoming pairing request from an Android Phone controller
        manager.recordIncomingOperatorRequest(
            controllerId = "MAHI-PHONE-A-CONTROLLER",
            operatorName = "Phone A (Pixel 8 Pro)",
            location = "Sylhet NOC",
            fingerprint = "AA:BB:CC:DD:EE:FF:11:22:33:44:55:66:77:88:99:00",
            scopes = listOf("Screen Mirroring", "Remote Interaction", "File Transfer")
        )

        val pending = manager.pendingRequest.value
        assertNotNull(pending)
        assertEquals("MAHI-PHONE-A-CONTROLLER", pending?.controllerId)
        assertEquals("Phone A (Pixel 8 Pro)", pending?.operatorName)

        // Approve
        manager.approveRequest(pending!!)
        assertNotNull(manager.activeSession.value)
        assertEquals("Phone A (Pixel 8 Pro)", manager.activeSession.value?.operatorName)

        // Emergency disconnect terminates cleanly
        manager.emergencyDisconnect("Emergency Stop triggered")
        assertEquals(null, manager.activeSession.value)
    }
}
