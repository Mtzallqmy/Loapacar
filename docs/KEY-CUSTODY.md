# Signing-key custody

## The current app

- Package: `com.masalik.yemen`.
- Current certificate SHA-256: `570dd95b026bd36fe371328c1294534656c111eece621c46491a44dc31b8507c`.
- Versions 2.0, 2.1, 2.2 and this 3.0 update use that same certificate.
- This is the original debug-origin key. The release build is not a migration to a production key.
- The private key is deliberately excluded from this source archive. No durable backup has been completed as part of this handoff. The current compatible working copy must not be treated as a permanent backup.

Losing the original key generally prevents ordinary in-place APK updates for existing installations. Generating a key with the same alias or password does not recreate it. Changing the certificate fingerprint in a text file does not change APK signing identity.

## Recommended custody options

1. **User-controlled encrypted backup of the ORIGINAL key.** Best for continued direct APK updates. Agree on a secure file-transfer route outside chat, keep the key in encrypted storage you control, keep its password in a separate password-manager record, and make a second encrypted backup. Verify a restored copy can sign a test APK with the same certificate before deleting any working copy. This has not yet been done.
2. **A controlled release process.** Once you hold the original key, keep it in an encrypted secrets store and inject it only during authorized builds. Keep the source repository key-free. Restrict build access and do not log signing secrets or publish a release without approval. This reduces manual handling but still requires the initial secure key handoff.
3. **A new production identity for a planned new distribution.** Use the supplied generator only when you intentionally accept a new signing identity. Existing direct-installed copies will not accept it as an ordinary update. Export local progress first and plan the migration and restore test. Store signing or key-upgrade services may offer options only under their specific eligibility rules; do not assume they can convert these existing direct-installed APKs automatically.

Do not email the raw key, send it in chat, commit it, or place it in the source ZIP. Encryption and a password-manager backup need an agreed owner-controlled destination before execution. No such transfer or custody setup is claimed here.

## Certificate check

With Android SDK Build Tools installed:

```bash
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
```

Compare the reported SHA-256 digest to the expected value above for an update. Check package and version too. Before installing, export your game progress; do not uninstall an existing copy as a troubleshooting shortcut.
