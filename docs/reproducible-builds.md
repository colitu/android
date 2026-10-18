# Reproducible builds

From the first release after 2.9.2, anyone can rebuild the colitu.com APKs from
this repository and get the same bytes as the published files, apart from the
APK signature. Releases up to 2.9.2 were built before this and cannot be
reproduced.

## What makes the build reproducible

| Input | How it is fixed |
|---|---|
| Gradle | wrapper with `distributionSha256Sum` |
| Plugins (AGP, R8, Kotlin) and libraries | versions in `gradle/libs.versions.toml`, resolved graph locked in `buildscript-gradle.lockfile` and `app/gradle.lockfile` |
| JDK | Temurin `21.0.12` (CI pins it; use the same locally) |
| Xray core (`libv2ray.aar`) | official upstream release asset, SHA-256 in `android/app/libs/SHA256SUMS` (see `core-provenance.md`) |
| Colitu sidecars (`libcolituengine.so`, `libcslclient.so`) | rebuilt from a pinned colitu/csl commit with a pinned Go release, see `core-provenance.md` and `scripts/build-sidecars.sh` |
| hev-socks5-tunnel | rebuilt from a pinned hev tag with a pinned NDK release (`scripts/hev.env`, `compile-hevtun.sh`), see `core-provenance.md` |
| Line endings | `.gitattributes` gives every checkout LF (text assets go into the APK as they are); a protected release build refuses CRLF sources |
| APK metadata | no Google dependency block (`dependenciesInfo.includeInApk = false`, its encrypted content differs on every build), no `version-control-info.textproto` |

## The one file that is not in the source

The API mirror origins are kept out of the public repository. The build writes
them to a single file at the root of the APK, `colitu-build.json`, which the
app reads at start (`ColituBuildValues`); nothing else in the APK depends on
them. Anyone holding the published APK can read that file, so a rebuild uses
the same value and gets an identical APK.

## Checking a release

```bash
git clone https://github.com/colitu/android && cd android && git checkout v<version>
# The value the published APK carries:
unzip -p Colitu_<version>_universal.apk colitu-build.json
# Build without a signing key (JDK 21.0.12):
cd android
COLITU_MIRRORS='<the "mirrors" value from colitu-build.json>' ./gradlew assembleDirectRelease
# Compare, ignoring only the signature (https://github.com/obfusk/apksigcopier):
apksigcopier compare Colitu_<version>_arm64-v8a.apk --unsigned app/build/outputs/apk/direct/release/Colitu_<version>_arm64-v8a.apk
```

The Google Play build is not reproducible by design: Google re-signs and
re-packages the bundle for each device.

## Keeping it reproducible

- After changing a dependency: `./gradlew :app:dependencies --write-locks` and commit the lock files.
- After changing the sidecar commit or Go release in `scripts/sidecars.env`: `scripts/build-sidecars.sh --update`.
- After changing the hev tag or NDK release in `scripts/hev.env`: `NDK_HOME=<that NDK> ./compile-hevtun.sh --update` (and test on a device: it is the tunnel).
- Never compile build-time secrets into code (`buildConfigField`); add them to `colitu-build.json` instead.
