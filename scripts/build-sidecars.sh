#!/usr/bin/env bash
# Rebuilds the Go sidecars (libcolituengine.so, libcslclient.so) from the
# colitu/csl commit pinned in scripts/sidecars.env and compares them, byte for
# byte, with the committed files listed in android/app/libs/SHA256SUMS.
#
# Usage: scripts/build-sidecars.sh [--update] [csl-repo]
#   (default)  build and compare; exit 1 on any difference
#   --update   build, copy into android/app/libs and rewrite their SHA256SUMS
#              lines (after moving CSL_COMMIT / CSL_GO in sidecars.env)
#   csl-repo   where to clone colitu/csl from (a local clone works); default CSL_REPO
#
# Go builds are reproducible across host systems with -trimpath and an empty
# build id (both set by the csl build scripts), provided the toolchain, the
# commit and the environment match: GOTOOLCHAIN pins the exact Go release, the
# variables that change code generation are reset, and the sources come from a
# fresh clone without line-ending conversion (a Windows checkout with
# core.autocrlf would put CRLF into string literals; Go also records
# vcs.modified for a dirty tree).
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
# shellcheck source=sidecars.env
. "$root/scripts/sidecars.env"
libs="$root/android/app/libs"
ABIS=(arm64-v8a armeabi-v7a x86 x86_64)

update=0
if [ "${1:-}" = "--update" ]; then update=1; shift; fi
source="${1:-$CSL_REPO}"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
csl="$work/csl"
git -c core.autocrlf=false -c core.eol=lf clone --quiet --no-checkout "$source" "$csl"
git -C "$csl" config core.autocrlf false
git -C "$csl" -c advice.detachedHead=false checkout --quiet "$CSL_COMMIT"
head=$(git -C "$csl" rev-parse HEAD)
[ "$head" = "$CSL_COMMIT" ] || { echo "csl checkout is at $head, sidecars.env pins $CSL_COMMIT" >&2; exit 1; }
(
  cd "$csl"
  unset GOFLAGS GOEXPERIMENT GOAMD64 GOARM64 GO386 GOARM CGO_ENABLED
  export GOTOOLCHAIN="$CSL_GO" GOAMD64=v1 GOARM64=v8.0 GO386=sse2
  go version
  sh engine/build.sh >/dev/null
  sh cmd/csl-client/build.sh >/dev/null
)

status=0
for abi in "${ABIS[@]}"; do
  for pair in "engine/dist/android/$abi/libcolituengine.so:libcolituengine.so" \
              "cmd/csl-client/dist/android/$abi/libcslclient.so:libcslclient.so"; do
    built="$csl/${pair%%:*}"
    name="$abi/${pair##*:}"
    sum=$(sha256sum "$built" | cut -d' ' -f1)
    if [ "$update" = 1 ]; then
      cp "$built" "$libs/$name"
      sed -i "s|^[0-9a-f]\{64\}  $name\$|$sum  $name|" "$libs/SHA256SUMS"
      echo "$sum  $name"
    elif grep -qx "$sum  $name" "$libs/SHA256SUMS"; then
      echo "ok        $name"
    else
      echo "DIFFERENT $name (rebuilt $sum)"; status=1
    fi
  done
done
if [ "$update" = 1 ]; then
  (cd "$libs" && sha256sum -c --quiet SHA256SUMS)
fi
exit $status
