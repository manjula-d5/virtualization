package com.rfid.virtulizations

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Maintenance test run only through an authorized ADB session. Contains no credentials. */
@RunWith(AndroidJUnit4::class)
class ProvisionSftpCredentialsTest {
    @Test fun provisionEncryptedCredentials() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.filesDir, ".sftp-provision")
        org.junit.Assume.assumeTrue("Requires explicit private ADB provisioning input", input.isFile)
        try {
            val password = input.readText().trim()
            check(password.isNotEmpty()) { "Provisioning input is empty" }
            val store = SftpCredentials(context)
            store.save(password)
            check(store.read() == password) { "Encrypted credential verification failed" }
        } finally {
            check(input.delete()) { "Private provisioning input cleanup failed" }
        }
    }
}
