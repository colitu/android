# Android Play Store release

The application ID remains `com.colitulu`. Build the signed Play Store AAB only from a protected `v*` tag with the `production` environment secrets. The workflow fails if signing material is incomplete, verifies the JAR signature, and retains the AAB, checksum, native-core checksum and dependency inventory.

Before upload, complete every item in [play-store-checklist.md](play-store-checklist.md) and the shared `docs/release/production-checklist.md` in the `colitu-panel` repository. A physical-device VPN smoke test and complete Android native wrapper provenance are release blockers.
