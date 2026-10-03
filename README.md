# Colitu VPN for Android

**English** · [Русский](README.ru.md)

The open-source Android client of [Colitu VPN](https://colitu.com). It pairs a
Jetpack Compose interface with an Xray / `VpnService` tunnel runtime, and gets
every account, plan and server detail from the Colitu API.

| | |
|---|---|
| Package | `com.colitulu` |
| Min / target SDK | 24 (Android 7.0) / 36 |
| Languages | Russian, English, Turkish (switch instantly in the app) |
| Website | <https://colitu.com> |
| License | [GPL-3.0](LICENSE) |

> A Colitu account is required to connect. The app has no hard-coded servers:
> the server list and connection profiles are issued per device by the Colitu API.

## Features

- **Automatic protocol selection.** Hysteria2, VLESS Reality, Trojan and
  Shadowsocks endpoints are probed in parallel, the fastest one is started, the
  tunnel is verified with a real request, and the app falls back to the next
  candidate if it fails.
- **Full account flow in the app.** Onboarding, sign-in and registration,
  e-mail verification, plan status, devices, usage, and a support inbox with
  ticket replies. Nothing is sold inside the app: plans are bought and renewed
  in the customer account on app.colitu.com.
- **Server locations** with live latency and a remembered preferred location.
- **Android TV** layout (leanback launcher, D-pad navigation).
- **Verified in-app updates** only in the APK from colitu.com (`direct`
  flavor): the release manifest must carry our ECDSA signature, the APK must
  match its SHA-256, and Android itself refuses an update signed with a
  different key. The Play and F-Droid builds are updated by their store.
- **Secure token storage.** Session tokens and VPN profiles are encrypted with
  AES-GCM using a key held in the Android Keystore (an app-private key on
  devices whose Keystore is broken).
- **Closed to other apps.** Xray's local SOCKS inbound gets a random port and
  account for every connection; the app's internal broadcasts are not
  exported, and there is no URL scheme or Tasker plug-in that could add
  servers or start and stop the tunnel. While connected, API calls go through
  the tunnel, and api.colitu.com is certificate-pinned.

## How it connects

1. `POST /auth/login` (or `/auth/register`) returns an access/refresh token pair.
2. `POST /devices/register` binds the install to a device slot.
3. `GET /client/bootstrap`, `/me`, `/me/entitlement` and `/servers` fill the UI.
4. Picking a location calls `PUT /me/preferences`, then `GET /config` returns a
   device-bound configuration envelope with a primary profile and alternatives.
5. `XrayMobileAdapter` turns the envelope into an Xray config and the runtime
   starts it through `VpnService` + [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel).

The app never computes prices, eligibility or device limits itself; the server
is always the source of truth.

## Project layout

```
android/                      Android Studio project (Gradle, Kotlin)
  app/src/main/java/com/v2ray/ang/
    colitu/
      api/                    HTTP client, token manager, secure storage
      app/ColituController.kt connection orchestration and fallback
      data/, repository/      API models and repositories
      design/                 theme, Colitu Sans typography, icons, particles
      screens/                Compose screens (home, locations, plan, account, support…)
      l10n/                   ru / en / tr strings
      update/                 verified self-updater
    …                         tunnel runtime (core, service, fmt, handler)
  app/libs/                   prebuilt libv2ray.aar and libhev-socks5-tunnel.so
AndroidLibXrayLite/           submodule: source of libv2ray.aar
hev-socks5-tunnel/            submodule: source of libhev-socks5-tunnel.so
docs/                         release and store-listing notes
fastlane/                     store metadata
```

## Building

Requirements: JDK 21 and the Android SDK (platform 36). The native libraries
are already prebuilt in `android/app/libs`, so the submodules are only needed
if you want to rebuild them (`compile-hevtun.sh`, AndroidLibXrayLite).

```sh
git clone https://github.com/cyberlexs/colitu-android.git
cd colitu-android/android
./gradlew assemblePlaystoreDebug          # debug APK
./gradlew testPlaystoreDebugUnitTest      # unit tests
```

There are two product flavors: `playstore` and `fdroid`.

### Pointing at another API

```sh
./gradlew assemblePlaystoreDebug -PCOLITU_API_BASE_URL=https://staging.example.com/api/v1
```

### Release signing

Release builds are signed only when a keystore is provided through environment
variables (`COLITU_ANDROID_KEYSTORE_PATH`, `COLITU_ANDROID_STORE_PASSWORD`,
`COLITU_ANDROID_KEY_ALIAS`, `COLITU_ANDROID_KEY_PASSWORD`) or a local,
git-ignored `signing.properties`. Keystores are never committed. See
[`docs/release.md`](docs/release.md).

## Security

If you find a vulnerability, please do not open a public issue. Write to
**support@colitu.com** with the details and we will get back to you.

## License

Colitu VPN for Android is distributed under the
[GNU General Public License v3.0](LICENSE). It includes open-source components
(Xray-core, hev-socks5-tunnel and others) that keep their own licenses; see
[NOTICE](NOTICE). The app shows the same notices in
`app/src/main/assets/open_source_licenses.html`.

The "Colitu" name and logo are trademarks of Colitu and are not covered by the
GPL. If you redistribute a modified version, please use your own name and
branding.
