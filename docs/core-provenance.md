# Android VPN core provenance

## Android

The checked-in `android/app/libs/libv2ray.aar` is the official upstream
`2dust/AndroidLibXrayLite` release **v26.10.10** (asset `libv2ray.aar`), with
SHA-256:

```text
15DB760BFBC24C0492EE997784B48A7B5E1BC5032950D617CD252FC2195F7AC7
```

This matches the `sha256` digest the GitHub release publishes for that asset, so
the binary is attributable to a tagged upstream build rather than a local
checkout. Go build metadata extracted from its `arm64-v8a/libgojni.so` reports:

- Go `1.27.2`
- `github.com/xtls/xray-core` `v1.260327.1-0.20261010092107-701af60772cd`
- `golang.org/x/net` `v0.59.0`, `golang.org/x/crypto` `v0.57.0`, `golang.org/x/text` `v0.42.0`, `google.golang.org/grpc` `v1.84.0`

This release includes upstream xray-core's RFC 1928 UDP-ASSOCIATE handling
(`proxy/socks`: per-session `TempUDPConn` bound to the authenticated TCP
association, commit `e26f5e9548`, replacing the old IP-only `UDPFilter`), so the
loopback SOCKS inbound no longer relays UDP from an unauthenticated local app,
and it carries the dependency bumps that clear the 2026-10 govulncheck findings
against the previous `1bdb488c9ec0` build.

Protected Android builds also retain `colitu-android.cdx.json`, a CycloneDX 1.5
SBOM generated from the resolved Gradle runtime graph. Bundled AAR and shared
library files are represented by SHA-256 so the binary inventory is reviewable
even while the legacy wrapper provenance remains a release blocker.

CI records the AAR checksum and Play Store runtime dependency graph on every run.

The checked-in `libhev-socks5-tunnel.so` builds (built from the `hev-socks5-tunnel`
submodule with `compile-hevtun.sh`) have SHA-256:

```text
59025a8f62e7af4a842ac66196dcd1c487d08a16bb8c6220f8ef6ea2a7713270  arm64-v8a
0b84549ccdcf8a4f281e6c50ffcf1ce97255dcba357c6061ecc7fd0c8172a015  armeabi-v7a
83d53c1064e7cd22d5b76a5467a8b77a5cff3efb2ccc2f297cca82ecfb690719  x86
810d7097c245d0b7303a2f9820f244332e76ab4562b06e0873b846d1b7625334  x86_64
```
