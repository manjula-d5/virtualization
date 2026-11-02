Current UI update: the login dialog and Server login button are removed. Existing encrypted on-device credentials remain usable. First-time unattended provisioning is pending an upload-only credential; no root password is embedded. The instructions below describe the previous manual provisioning flow, retained for history.

# SFTP upload

The active Android implementation follows the supplied SFTP reference. No HTTP receiver, custom web service or additional server port is required.

- Host: 103.189.89.76
- Port: 22
- Account: root
- Destination: /opt/rfid_log_manjula/

The app saves the completed two-zone JSON-line TXT locally in DCIM, then queues the app-storage copy. The upload uses the exact same filename and bytes. After transfer, the app reads the temporary remote file back and verifies its SHA-256 and byte count before renaming it to the final filename. Retrying a file already present with identical bytes succeeds without creating another copy; differing content is reported as a conflict. It creates the destination directory if absent and permitted.

## First launch

In Server login, enter the supplied password once. It is encrypted in private preferences using an Android Keystore AES-GCM key, not embedded in the APK, source, or a private asset. The dialog masks the password. Use Server login to correct it later and retry pending files. Connection details above are preconfigured.

Successful uploads show Uploaded and the server path. Network failures retain local files and retry roughly every 60 seconds while the app process is alive, or when reopened. Authentication failure pauses retries until Server login is updated, to avoid repeated rejected logins. Uninstall removes the private queue and encrypted credentials; the DCIM copy remains.

## SSH host verification

app/src/main/assets/sftp_known_hosts pins the public host key captured from the supplied server. Fingerprint: SHA256:P9I2j7cjAECaJYGJvtmCxDlROYADwfF5Dfb5RkFvQ38. StrictHostKeyChecking is yes. Independently confirm this fingerprint with the server administrator; changing the server key requires verifying and updating the pin. The sample's StrictHostKeyChecking=no is not copied.

## Current verification limits

SSH is reachable, and host-key retrieval succeeded. Password authentication from this workstation was rejected twice, including the user-confirmed exact password. Therefore no successful live transfer or destination listing is claimed. Confirm root password login/credentials with the server administrator if the app reports the same rejection. The earlier prepared HTTPS alternative has been removed in favor of this direct SFTP workflow.
