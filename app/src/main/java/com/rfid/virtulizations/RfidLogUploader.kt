
package com.rfid.virtulizations

import android.content.Context
import android.util.Log
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.SftpException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * File-backed SFTP retry queue.
 *
 * - Strict SSH host-key verification.
 * - AES/GCM encrypted credentials via Android Keystore.
 * - Pending files are retained until upload is verified.
 * - Uploads are verified using SHA-256.
 */
class RfidLogUploader(
    private val context: Context
) {
    companion object {
        private const val TAG = "RfidUpload"

        const val SFTP_HOST = "144.31.146.124"
        const val SFTP_PORT = 22
        const val SFTP_USER = "root"
        const val SFTP_REMOTE_DIR = "/opt/manjula_logs"

        private const val KNOWN_HOSTS_ASSET = "sftp_known_hosts"

        fun isAuthFailure(error: Throwable): Boolean {
            var cause: Throwable? = error

            while (cause != null) {
                val message = cause.message.orEmpty()

                if (
                    cause is JSchException &&
                    (
                            message.contains("Auth fail", true) ||
                                    message.contains("Auth cancel", true) ||
                                    message.contains("authentication failure", true) ||
                                    message.contains("Too many authentication failures", true) ||
                                    message.contains("Access denied", true) ||
                                    message.contains("USERAUTH", true)
                            )
                ) {
                    return true
                }

                cause = cause.cause
            }

            return false
        }
    }

    private val appContext = context.applicationContext

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO
    )

    private val lock = Mutex()

    private val queue = File(
        appContext.filesDir,
        "upload-queue"
    )

    @Volatile
    private var authenticationBlocked = false

    @Volatile
    private var lastCredentialFingerprint: String? = null

    val credentials = SftpCredentials(appContext)

    private val mutable = MutableStateFlow(
        if (credentials.read().isNotBlank()) {
            "SFTP upload ready"
        } else {
            "SFTP credentials required; local saving remains enabled"
        }
    )

    val status = mutable.asStateFlow()

    private fun report(
        message: String,
        failure: Throwable? = null
    ) {
        mutable.value = message

        if (failure == null) {
            Log.i(TAG, message)
        } else {
            Log.e(TAG, message, failure)
        }
    }

    private fun refreshAuthenticationState() {
        val current = SftpCredentials.fingerprint(credentials.read())

        if (
            authenticationBlocked &&
            current != null &&
            current != lastCredentialFingerprint
        ) {
            authenticationBlocked = false
            lastCredentialFingerprint = current

            report(
                "Stored SFTP credentials changed; retry resumed."
            )
            return
        }

        lastCredentialFingerprint = current
    }

    init {
        lastCredentialFingerprint =
            SftpCredentials.fingerprint(credentials.read())

        report(mutable.value)

        scope.launch {
            while (isActive) {
                try {
                    drain()
                } catch (error: Exception) {
                    report(
                        "Upload queue processing failed; files retained.",
                        error
                    )
                }

                delay(60_000)
            }
        }
    }

    /**
     * Add a file to the persistent upload queue.
     * The source file is not deleted by this method.
     */
    fun enqueue(file: File) {
        require(file.isFile) {
            "Upload source file does not exist"
        }

        check(queue.isDirectory || queue.mkdirs()) {
            "Cannot create upload queue"
        }

        val marker = File(
            queue,
            file.name + ".pending"
        )

        if (!marker.exists()) {
            val staging = File(
                queue,
                file.name + ".staging"
            )

            FileOutputStream(staging).use {
                it.write(
                    file.absolutePath.toByteArray(Charsets.UTF_8)
                )
                it.fd.sync()
            }

            check(staging.renameTo(marker)) {
                "Cannot persist upload queue entry"
            }
        }

        if (credentials.read().isBlank()) {
            report(
                "Saved locally: ${file.name}. Configure SFTP credentials to upload."
            )
        } else {
            report(
                "Saved locally. Upload queued: ${file.name}"
            )
        }

        scope.launch {
            drain()
        }
    }

    private suspend fun drain() = lock.withLock {
        refreshAuthenticationState()

        val pending = queue.listFiles()
            ?.filter { it.extension == "pending" }
            .orEmpty()

        if (pending.isEmpty()) {
            return@withLock
        }

        if (authenticationBlocked) {
            report(
                "Upload blocked by authentication failure. " +
                        "${pending.size} file(s) retained."
            )
            return@withLock
        }

        if (credentials.read().isBlank()) {
            report(
                "SFTP credentials are missing or unreadable. " +
                        "${pending.size} file(s) retained."
            )
            return@withLock
        }

        for (marker in pending) {
            try {
                val sourcePath = marker.readText(Charsets.UTF_8)
                val source = File(sourcePath)

                check(source.isFile && source.length() > 0) {
                    "Queued log is missing or empty: ${marker.name}"
                }

                report("Uploading ${source.name}...")

                upload(source)

                check(marker.delete()) {
                    "Upload succeeded, but queue acknowledgement failed"
                }

                report(
                    "Uploaded and checksum verified: " +
                            "$SFTP_REMOTE_DIR/${source.name}"
                )
            } catch (error: Exception) {
                if (isAuthFailure(error)) {
                    authenticationBlocked = true

                    report(
                        "SFTP authentication failed. " +
                                "${pending.size} queued file(s) retained.",
                        error
                    )
                } else {
                    report(
                        "Upload pending: ${error.message}. " +
                                "Local file retained; automatic retry enabled.",
                        error
                    )
                }

                break
            }
        }
    }

    /**
     * Manually retry pending uploads.
     */
    fun retryNow() {
        lastCredentialFingerprint =
            SftpCredentials.fingerprint(credentials.read())

        authenticationBlocked = false

        scope.launch {
            drain()
        }
    }

    /**
     * Upload a file over SFTP using a verified server host key.
     */
    private fun upload(file: File) {
        check(file.isFile && file.length() > 0) {
            "Queued log is missing or empty"
        }

        val password = credentials.read()

        check(password.isNotBlank()) {
            "SFTP credentials are missing or cannot be decrypted"
        }

        val jsch = JSch()

        // The asset must contain the actual verified server host key.
        appContext.assets.open(KNOWN_HOSTS_ASSET).use { input ->
            jsch.setKnownHosts(input)
        }

        val session = jsch.getSession(
            SFTP_USER,
            SFTP_HOST,
            SFTP_PORT
        )

        session.setPassword(password)

        session.userInfo = SimpleSftpUserInfo(password)

        // Never disable server host-key verification.
        session.setConfig(
            "StrictHostKeyChecking",
            "yes"
        )

        session.setConfig(
            "PreferredAuthentications",
            "password,keyboard-interactive"
        )

        session.timeout = 60_000

        var channel: ChannelSftp? = null

        try {
            session.connect(15_000)

            Log.i(TAG, "SSH connection and authentication successful")

            val sftp = session.openChannel("sftp") as ChannelSftp
            channel = sftp

            sftp.connect(15_000)

            val targetDir = SFTP_REMOTE_DIR.trimEnd('/')

            try {
                check(sftp.stat(targetDir).isDir) {
                    "SFTP destination is not a directory"
                }
            } catch (error: SftpException) {
                if (
                    error.id != ChannelSftp.SSH_FX_NO_SUCH_FILE
                ) {
                    throw error
                }

                sftp.mkdir(targetDir)
            }

            val remote = "$targetDir/${file.name}"
            val localHash = sha256(file.inputStream())

            val existing = try {
                sftp.stat(remote)
            } catch (error: SftpException) {
                if (
                    error.id != ChannelSftp.SSH_FX_NO_SUCH_FILE
                ) {
                    throw error
                }

                null
            }

            // A retry of an already completed upload is safe only
            // when the remote file matches the local file.
            if (existing != null) {
                check(
                    existing.size == file.length() &&
                            sha256(sftp.get(remote)) == localHash
                ) {
                    "Remote filename already contains different data"
                }

                return
            }

            val staging = "$remote.${java.util.UUID.randomUUID()}.uploading"

            try {
                file.inputStream().use { input ->
                    sftp.put(input, staging)
                }

                check(
                    sftp.stat(staging).size == file.length() &&
                            sha256(sftp.get(staging)) == localHash
                ) {
                    "SFTP checksum verification failed"
                }

                sftp.rename(staging, remote)

                Log.i(
                    TAG,
                    "File uploaded successfully: ${file.name}"
                )
            } catch (error: Exception) {
                runCatching {
                    sftp.rm(staging)
                }

                throw error
            }
        } finally {
            runCatching {
                channel?.disconnect()
            }

            runCatching {
                session.disconnect()
            }
        }
    }

    private fun sha256(input: java.io.InputStream): String {
        return input.use {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65_536)

            while (true) {
                val count = it.read(buffer)

                if (count < 0) break

                digest.update(buffer, 0, count)
            }

            digest.digest().joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
        }
    }

    /**
     * Stop background retry processing when the owner is finished.
     */
    fun close() {
        scope.cancel()
    }
}

/**
 * Non-interactive credential provider.
 *
 * Host-key trust is controlled by StrictHostKeyChecking,
 * not by accepting an interactive host-key prompt.
 */
private class SimpleSftpUserInfo(
    private val password: String
) : com.jcraft.jsch.UserInfo,
    com.jcraft.jsch.UIKeyboardInteractive {

    override fun getPassphrase(): String? = null

    override fun getPassword(): String = password

    override fun promptPassword(message: String?): Boolean = true

    override fun promptPassphrase(message: String?): Boolean = false

    override fun promptYesNo(message: String?): Boolean = false

    override fun showMessage(message: String?) {
        // Do not display sensitive authentication details.
    }

    override fun promptKeyboardInteractive(
        destination: String?,
        name: String?,
        instruction: String?,
        prompt: Array<out String>?,
        echo: BooleanArray?
    ): Array<String>? {
        if (prompt.isNullOrEmpty()) {
            return emptyArray()
        }

        return Array(prompt.size) { password }
    }
}
