#!/usr/bin/env bash
# Builds gecko-client (the ColituGecko protocol) for Android as
# android/app/libs/<abi>/libgeckoclient.so from the commit of Colitu's PRIVATE
# repository pinned in scripts/gecko.env. The files are packaged like the other
# sidecars (an executable in the APK's native library directory) but are never
# committed (see gecko.env); an APK built without them cannot connect over ColituGecko.
#
# Usage: scripts/build-gecko.sh [gecko-repo]
#   gecko-repo  where to clone colitu-gecko from (a local clone works); default GECKO_REPO
#
# Same toolchain rules as build-sidecars.sh: a fresh clone without line-ending
# conversion, GOTOOLCHAIN pinned, CGO off, -trimpath and an empty build id;
# Android runs the linux binary (as csl-client). One addition:
# gecko-overlay/colitu_android_dns.go (go build -overlay) gives the binary the
# resolver the app hands it (COLITU_DNS), since Android has no /etc/resolv.conf.
# Go comes from PATH, or from GO_BIN (a directory) when set.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
# shellcheck source=gecko.env
. "$root/scripts/gecko.env"
libs="$root/android/app/libs"
overlay_src="$root/scripts/gecko-overlay/colitu_android_dns.go"
if [ -n "${GO_BIN:-}" ]; then PATH="$GO_BIN:$PATH"; fi
source="${1:-$GECKO_REPO}"

winpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
gecko="$work/gecko"
git -c core.autocrlf=false -c core.eol=lf clone --quiet --no-checkout "$source" "$gecko" \
  || { echo "git clone $source failed (the Gecko repository is private: it needs access)" >&2; exit 1; }
git -C "$gecko" config core.autocrlf false
git -C "$gecko" -c advice.detachedHead=false checkout --quiet "$GECKO_COMMIT"
head=$(git -C "$gecko" rev-parse HEAD)
[ "$head" = "$GECKO_COMMIT" ] || { echo "gecko checkout is at $head, gecko.env pins $GECKO_COMMIT" >&2; exit 1; }

printf '{"Replace":{"%s":"%s"}}' \
  "$(winpath "$gecko/cmd/gecko-client/colitu_android_dns.go")" "$(winpath "$overlay_src")" > "$work/overlay.json"

build() { # GOARCH GOARM abi
  local out="$work/dist/$3/libgeckoclient.so"
  mkdir -p "$(dirname "$out")"
  (
    cd "$gecko"
    unset GOFLAGS GOEXPERIMENT GOAMD64 GOARM64 GO386 GOARM CGO_ENABLED
    export GOTOOLCHAIN="$GECKO_GO" GOAMD64=v1 GOARM64=v8.0 GO386=sse2
    CGO_ENABLED=0 GOOS=linux GOARCH=$1 GOARM=$2 go build -trimpath -ldflags "-s -w -buildid=" \
      -overlay "$(winpath "$work/overlay.json")" -o "$(winpath "$out")" ./cmd/gecko-client
  )
  mkdir -p "$libs/$3"
  cp "$out" "$libs/$3/libgeckoclient.so"
  echo "$(sha256sum "$out" | cut -d' ' -f1)  $3/libgeckoclient.so"
}

(cd "$gecko" && GOTOOLCHAIN="$GECKO_GO" go version)
echo "colitu-gecko ${head:0:12}"
build arm64 7 arm64-v8a
build arm 7 armeabi-v7a
build amd64 7 x86_64
build 386 7 x86
