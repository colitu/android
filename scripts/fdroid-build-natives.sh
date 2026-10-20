#!/usr/bin/env bash
# F-Droid build (metadata/com.colitulu.yml in fdroiddata): builds every native
# library in android/app/libs from source in place of the committed files.
#
# Usage: NDK_HOME=<ndk> scripts/fdroid-build-natives.sh <go> <AndroidLibXrayLite> <hev-socks5-tunnel> <csl>
#   go                  a checkout of golang/go at CSL_GO (scripts/sidecars.env); built here
#   AndroidLibXrayLite  a checkout at the tag docs/core-provenance.md names (libv2ray.aar)
#   hev-socks5-tunnel   a clone holding HEV_COMMIT (scripts/hev.env)
#   csl                 a clone of colitu/csl holding CSL_COMMIT (scripts/sidecars.env)
# NDK_HOME (or ANDROID_NDK_HOME) must be NDK HEV_NDK.
#
# hev and the sidecars come from compile-hevtun.sh and build-sidecars.sh with
# --update, so they are the same commits, flags and toolchains as the committed
# files. libv2ray.aar is built the way upstream's release workflow builds it.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
# shellcheck source=sidecars.env
. "$root/scripts/sidecars.env"
libs="$root/android/app/libs"
sums="$libs/SHA256SUMS"
[ $# -eq 4 ] || { echo "usage: $0 <go> <AndroidLibXrayLite> <hev-socks5-tunnel> <csl>" >&2; exit 2; }
go_src=$(cd "$1" && pwd)
xray=$(cd "$2" && pwd)
hev=$(cd "$3" && pwd)
csl=$(cd "$4" && pwd)
export NDK_HOME="${NDK_HOME:-${ANDROID_NDK_HOME:?NDK_HOME or ANDROID_NDK_HOME must be set}}"
export ANDROID_NDK_HOME="$NDK_HOME"

# Go from source. build-sidecars.sh asks for exactly CSL_GO (GOTOOLCHAIN), so
# the Go built here must be that release or Go would download it.
(cd "$go_src/src" && ./make.bash)
export GOROOT="$go_src" GOPATH="${GOPATH:-$HOME/go}"
export PATH="$GOROOT/bin:$GOPATH/bin:$PATH"
have=$(GOTOOLCHAIN=local go env GOVERSION)
[ "$have" = "$CSL_GO" ] || { echo "Go checkout is $have; scripts/sidecars.env pins $CSL_GO" >&2; exit 1; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cp "$sums" "$work/all"

# Each script rewrites its own lines and then checks every line of SHA256SUMS,
# so each runs against a list of only its own files.
grep -E '  [^ ]+/libhev-socks5-tunnel\.so$' "$work/all" > "$sums"
bash "$root/compile-hevtun.sh" --update "$hev"
cp "$sums" "$work/hev"

grep -E '  [^ ]+/lib(colituengine|cslclient)\.so$' "$work/all" > "$sums"
bash "$root/scripts/build-sidecars.sh" --update "$csl"
cp "$sums" "$work/sidecars"

# libv2ray.aar, as upstream's .github/workflows/main.yml builds it. gomobile
# and gobind come at the golang.org/x/mobile version in its go.mod.
(
  cd "$xray"
  export GOTOOLCHAIN=local
  mkdir -p assets data
  bash gen_assets.sh download
  cp -v data/*.dat assets/
  go install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind
  gomobile init
  go mod tidy
  gomobile bind -v -androidapi 24 -trimpath -ldflags='-s -w -buildid= -checklinkname=0' ./
)
cp "$xray/libv2ray.aar" "$libs/libv2ray.aar"

{
  (cd "$libs" && sha256sum libv2ray.aar)
  cat "$work/hev" "$work/sidecars"
} > "$sums"
(cd "$libs" && sha256sum -c --quiet SHA256SUMS)
echo "native libraries built from source"
