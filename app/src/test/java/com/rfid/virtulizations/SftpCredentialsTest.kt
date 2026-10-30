package com.rfid.virtulizations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SftpCredentialsTest {
    @Test
    fun fingerprintIsStableForSamePasswordAndChangesWhenPasswordChanges() {
        val original = SftpCredentials.fingerprint("server-pass")
        val same = SftpCredentials.fingerprint("server-pass")
        val changed = SftpCredentials.fingerprint("server-pass-2")

        assertEquals(original, same)
        assertNotEquals(original, changed)
        assertNull(SftpCredentials.fingerprint(null))
        assertNull(SftpCredentials.fingerprint(""))
    }

    @Test
    fun identifiesAuthenticationFailuresCorrectly() {
        val disconnectExc = com.jcraft.jsch.JSchException("SSH_MSG_DISCONNECT: 2 Too many authentication failures")
        val authFailExc = com.jcraft.jsch.JSchException("Auth fail")
        val authCancelExc = com.jcraft.jsch.JSchException("Auth cancel")
        val accessDeniedExc = com.jcraft.jsch.JSchException("Access denied")
        val wrappedExc = RuntimeException("Upload failed", com.jcraft.jsch.JSchException("Too many authentication failures"))

        val timeoutExc = com.jcraft.jsch.JSchException("timeout")
        val networkExc = java.io.IOException("Connection refused")

        org.junit.Assert.assertTrue(RfidLogUploader.isAuthFailure(disconnectExc))
        org.junit.Assert.assertTrue(RfidLogUploader.isAuthFailure(authFailExc))
        org.junit.Assert.assertTrue(RfidLogUploader.isAuthFailure(authCancelExc))
        org.junit.Assert.assertTrue(RfidLogUploader.isAuthFailure(accessDeniedExc))
        org.junit.Assert.assertTrue(RfidLogUploader.isAuthFailure(wrappedExc))

        org.junit.Assert.assertFalse(RfidLogUploader.isAuthFailure(timeoutExc))
        org.junit.Assert.assertFalse(RfidLogUploader.isAuthFailure(networkExc))
    }
}
