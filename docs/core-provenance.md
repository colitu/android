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

`libhev-socks5-tunnel.so` (the JNI tun2socks library `TProxyService` loads) is
built from source: tag `2.15.0` (commit `00c7eb9ad7ca381b0f1fee880abc1077fe9b93be`)
of [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) with
NDK `27.2.12479018` (r27c), pinned in `scripts/hev.env`, by `compile-hevtun.sh`.
The hashes are in `android/app/libs/SHA256SUMS`; CI rebuilds and compares them
on every push and before every signed release.

hev's makefiles list the sources with `$(wildcard)`, which returns them in
directory order (sorted on NTFS, arbitrary on ext4/APFS) and so changes the
link order; `compile-hevtun.sh` sorts that list in its build copy, compiles
under the relative path `jni/hev-socks5-tunnel`, and links without a build id.

The library shipped up to 2.9.2 came from the same source and NDK (its
`.note.android.ident` names r27c 12479018; same exported symbols, same section
sizes) but was linked in an unknown directory order, so it could not be
rebuilt byte for byte. The rebuilt files differ from it only in link order.

## Colitu sidecars

`libcolituengine.so` (Adaptive Connect decision engine) and `libcslclient.so`
(CSL client) are built from source: commit `6da38f5c302730f799969a58b2bbdc49d72b6b69`
of the public [colitu/csl](https://github.com/colitu/csl) repository, by its own
`engine/build.sh` and `cmd/csl-client/build.sh` (pure Go, `CGO_ENABLED=0`,
`-trimpath`, `-ldflags "-s -w -buildid="`) with Go `go1.27.1`. The pins live in
`scripts/sidecars.env`, the hashes in `android/app/libs/SHA256SUMS`.

`scripts/build-sidecars.sh` clones that commit, builds with exactly that Go
(`GOTOOLCHAIN`) and compares every file byte for byte; CI runs it on every push
and before every signed release. Each binary also names its commit itself:
`go version -m libcslclient.so` shows `vcs.revision` and `vcs.modified=false`.
To move to a newer commit or Go release, change `scripts/sidecars.env` and run
`scripts/build-sidecars.sh --update`.
