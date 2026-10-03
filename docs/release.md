# Android release

The existing Play identity remains `com.colitulu`; changing it creates a different store application. Pull requests build and test without production signing material.

Protected release builds inject the following secrets through the CI secret store:

- `COLITU_ANDROID_KEYSTORE_PATH`
- `COLITU_ANDROID_STORE_PASSWORD`
- `COLITU_ANDROID_KEY_ALIAS`
- `COLITU_ANDROID_KEY_PASSWORD`
- `COLITU_REQUIRE_RELEASE_SIGNING=true`

Run `./gradlew testPlaystoreReleaseUnitTest lintPlaystoreRelease connectedPlaystoreDebugAndroidTest bundlePlaystoreRelease -PCOLITU_REQUIRE_RELEASE_SIGNING=true`. Upload the generated signed AAB only after `jarsigner -verify` succeeds. Never commit the keystore or `signing.properties`.

## Build flavors

| Flavor | Where it goes | Self-updater |
|---|---|---|
| `playstore` | Google Play (signed AAB) | no — Play forbids it |
| `direct` | colitu.com/downloads/android (APK) | yes (`REQUEST_INSTALL_PACKAGES` only in `src/direct`) |
| `fdroid` | F-Droid | no |

All three share the application id `com.colitulu` and the release key, so a
colitu.com install can move to Play and back without losing data. `direct`
and `playstore` use the same version codes (`4_000_000 + versionCode`).

The website APKs come from `./gradlew assembleDirectRelease` (release.yml
builds and attaches them). Never publish a `playstore` APK on the website:
it has no updater.

## Signed update manifest

Since 2.3.0 the app ignores `latest.json` unless it carries a valid
`signature` (ECDSA P-256 over the fields listed in
`scripts/sign-update-manifest.ps1`, matching `ColituUpdateSignature` in the
app). After writing `latest.json` for a release:

```
pwsh scripts/sign-update-manifest.ps1 -Manifest latest.json
```

The private key is `_gizli_anahtarlar/colitu-android--update-signing-private.pem`
(outside the repository); the public key is embedded in
`ColituUpdateSignature.kt`. Losing the private key means no more automatic
updates for colitu.com installs; users would have to reinstall manually.

## Certificate pins

`res/xml/network_security_config.xml` pins colitu.com / api.colitu.com to the
ISRG (Let's Encrypt) roots with Google Trust Services as backup. The pin set
expires on the date in the file, after which the app falls back to normal
system trust. Check the chain and move the date forward with every release;
if the API ever moves to another CA, add its root before switching.

## Prebuilt core

`android/app/libs/SHA256SUMS` lists the committed Xray AAR and
hev-socks5-tunnel libraries (see `core-provenance.md`). CI fails when a
binary changes without that file being updated in the same commit.
