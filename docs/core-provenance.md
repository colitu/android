# Android VPN core provenance

## Android

The checked-in `android/app/libs/libv2ray.aar` has SHA-256:

```text
322A37E4F8D07C939D0AF85799D3692612834441CF36BEE5086F6B577C8E028A
```

Go build metadata extracted from its `arm64-v8a/libgojni.so` reports:

- Go `1.26.3`
- `github.com/xtls/xray-core` `v1.260327.1-0.20260509173629-1bdb488c9ec0`
- `github.com/2dust/AndroidLibXrayLite` was replaced by a local development checkout and has no source commit embedded in the binary.

The Xray dependency is therefore attributable to commit prefix `1bdb488c9ec0`; the wrapper source is not fully reproducible from the AAR alone. A production release remains blocked until the wrapper repository commit/build recipe is pinned and a byte-for-byte or signed artifact comparison is recorded.

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
