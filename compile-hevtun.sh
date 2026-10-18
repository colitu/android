#!/usr/bin/env bash
# Builds libhev-socks5-tunnel.so (the JNI tun2socks library TProxyService loads)
# for every ABI from the hev-socks5-tunnel commit and with the NDK pinned in
# scripts/hev.env, and compares the result with android/app/libs/SHA256SUMS.
#
# Usage: NDK_HOME=<ndk> ./compile-hevtun.sh [--update] [hev-repo]
#   (default)  build and compare; exit 1 on any difference
#   --update   build, copy into android/app/libs and rewrite their SHA256SUMS lines
#   hev-repo   where to clone hev-socks5-tunnel from (a local clone works); default HEV_REPO
#
# The output depends only on the hev sources, the NDK release and the flags
# below: the sources come from a fresh clone (with submodules, no line-ending
# conversion) and are compiled under the relative path jni/hev-socks5-tunnel,
# the linker writes no build id, the compiler writes no .ident (the NDK's clang
# names itself differently on Windows and Linux), and NDK_HOME must be the
# pinned release.
set -o errexit
set -o pipefail
set -o nounset

__dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/hev.env
. "$__dir/scripts/hev.env"
libs="$__dir/android/app/libs"
ABIS="armeabi-v7a arm64-v8a x86 x86_64"

update=0
if [ "${1:-}" = "--update" ]; then update=1; shift; fi
source="${1:-$HEV_REPO}"

if [[ ! -d "${NDK_HOME:-}" ]]; then
  echo "Android NDK: NDK_HOME not found. Set \$NDK_HOME to NDK $HEV_NDK."
  exit 1
fi
ndk_revision=$(sed -n 's/^Pkg.Revision *= *//p' "$NDK_HOME/source.properties" | tr -d '\r')
if [ "$ndk_revision" != "$HEV_NDK" ]; then
  echo "NDK_HOME is NDK $ndk_revision; scripts/hev.env pins $HEV_NDK (other releases build different bytes)." >&2
  exit 1
fi
ndk_build="$NDK_HOME/ndk-build"
[ -f "$ndk_build" ] || ndk_build="$NDK_HOME/ndk-build.cmd"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
src="$work/src"
git -c core.autocrlf=false -c core.eol=lf clone --quiet --no-checkout "$source" "$src"
git -C "$src" config core.autocrlf false
git -C "$src" -c advice.detachedHead=false checkout --quiet "$HEV_COMMIT"
git -C "$src" -c core.autocrlf=false submodule --quiet update --init --recursive
[ "$(git -C "$src" rev-parse HEAD)" = "$HEV_COMMIT" ] || { echo "hev checkout is not at $HEV_COMMIT" >&2; exit 1; }

# hev keeps some headers as git symlinks. A checkout without symlink support
# (Git for Windows) writes them as small files holding the target path: replace
# those with a copy of the target. The copy below dereferences real links, so
# every system compiles the same plain files.
resolve_links() {
  git -C "$1" ls-files -s | while read -r mode _ _ path; do
    [ "$mode" = 120000 ] || continue
    link="$1/$path"
    [ -L "$link" ] && continue
    target="$(dirname "$link")/$(cat "$link")"
    rm -f "$link"
    cp -R "$target" "$link"
  done
}
resolve_links "$src"
git -C "$src" submodule --quiet foreach --recursive pwd | while read -r dir; do resolve_links "$dir"; done

mkdir -p "$work/build/jni"
echo 'include $(call all-subdir-makefiles)' > "$work/build/jni/Android.mk"
# A copy, not a link: the same relative source paths on every system.
cp -RL "$src" "$work/build/jni/hev-socks5-tunnel"
find "$work/build/jni/hev-socks5-tunnel" -name .git -prune -exec rm -rf {} +
# hev's build.mk files (hev and its third-part libraries) collect the sources
# with $(wildcard), which GNU make returns in directory order: sorted on NTFS,
# arbitrary on ext4/APFS. That order is the link order, so it changes the bytes;
# sort it (byte order) in the build copy.
find "$work/build/jni/hev-socks5-tunnel" -name build.mk -exec \
  sed -i 's/\$(wildcard \$1\*)/$(sort $(wildcard $1*))/' {} +
sorted=$(grep -l 'sort $(wildcard $1\*)' $(find "$work/build/jni/hev-socks5-tunnel" -name build.mk) | wc -l)
[ "$sorted" -eq 4 ] || { echo "expected 4 build.mk files to sort their sources, found $sorted" >&2; exit 1; }
# build.mk asks git for the commit id unless REV_ID is given; pass it so nothing
# depends on whether a parent folder happens to be a git repository.
rev_id=$(git -C "$src" rev-parse --short=7 HEAD)

(
  cd "$work/build"
  "$ndk_build" \
      NDK_PROJECT_PATH=. \
      APP_BUILD_SCRIPT=jni/Android.mk \
      "APP_ABI=$ABIS" \
      APP_PLATFORM=android-24 \
      NDK_LIBS_OUT=libs \
      NDK_OUT=obj \
      "REV_ID=$rev_id" \
      "APP_CFLAGS=-O3 -DPKGNAME=com/v2ray/ang/service -fno-ident" \
      "APP_LDFLAGS=-Wl,--build-id=none -Wl,--hash-style=gnu" >/dev/null
)

status=0
for abi in $ABIS; do
  built="$work/build/libs/$abi/libhev-socks5-tunnel.so"
  name="$abi/libhev-socks5-tunnel.so"
  sum=$(sha256sum "$built" | cut -d' ' -f1)
  if [ "$update" = 1 ]; then
    cp "$built" "$libs/$name"
    sed -i "s|^[0-9a-f]\{64\}  $name\$|$sum  $name|" "$libs/SHA256SUMS"
    echo "$sum  $name"
  elif grep -qx "$sum  $name" "$libs/SHA256SUMS"; then
    echo "ok        $name"
  else
    echo "DIFFERENT $name (rebuilt $sum)"; status=1
    # Which parts differ: the hash of each section of the rebuilt file.
    readelf=$(ls "$NDK_HOME"/toolchains/llvm/prebuilt/*/bin/llvm-readelf* | head -n 1)
    for section in .text .rodata .data .eh_frame .dynsym .comment; do
      echo "  $abi $section $("$readelf" -x "$section" "$built" 2>/dev/null | sha256sum | cut -c1-16)"
    done
  fi
done
if [ "$update" = 1 ]; then
  (cd "$libs" && sha256sum -c --quiet SHA256SUMS)
fi
exit $status
