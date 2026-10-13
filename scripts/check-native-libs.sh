#!/usr/bin/env bash
# Fails when an APK or AAB lacks a native library the app loads at start, for
# any ABI it ships. A dependency that silently drops an ABI (MMKV 2.x has no
# armeabi-v7a/x86) would otherwise build fine and crash on those devices.
#
# Usage: scripts/check-native-libs.sh <apk-or-aab> [...]
#   Colitu_<version>_<abi>.apk  -> that ABI
#   *_universal.apk, *.aab      -> every ABI
set -euo pipefail

ABIS=(arm64-v8a armeabi-v7a x86 x86_64)
LIBS=(libmmkv.so libgojni.so libhev-socks5-tunnel.so)

[ "$#" -gt 0 ] || { echo "usage: $0 <apk-or-aab> [...]" >&2; exit 2; }

failed=0
for file in "$@"; do
  name=$(basename "$file")
  listing=$(unzip -Z1 "$file")
  prefix="lib"
  case "$name" in
    *.aab) prefix="base/lib"; abis=("${ABIS[@]}") ;;
    *_universal.apk) abis=("${ABIS[@]}") ;;
    *.apk)
      abis=()
      for abi in "${ABIS[@]}"; do
        case "$name" in *"_${abi}.apk") abis=("$abi") ;; esac
      done
      [ "${#abis[@]}" -eq 1 ] || { echo "::error::$name: ABI not recognised from the file name"; failed=1; continue; }
      ;;
    *) echo "::error::$name: not an APK or AAB"; failed=1; continue ;;
  esac
  missing=0
  for abi in "${abis[@]}"; do
    for lib in "${LIBS[@]}"; do
      if ! grep -qx "$prefix/$abi/$lib" <<<"$listing"; then
        echo "::error::$name: missing $prefix/$abi/$lib"
        missing=1
      fi
    done
  done
  if [ "$missing" -eq 0 ]; then echo "$name: ${abis[*]} complete"; else failed=1; fi
done
exit "$failed"
