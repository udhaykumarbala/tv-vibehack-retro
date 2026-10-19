#!/usr/bin/env bash
# Fetch the fceumm libretro core (GPL-2.0-or-later) from the libretro buildbot into src/main/jniLibs.
# The core is renamed to lib*.so so Android packages and extracts it like any other native library.
# Cores are not committed; cores/SHA256SUMS records the exact binaries the last build used.
#   cores/fetch-cores.sh          fetch only the missing ABIs
#   cores/fetch-cores.sh --force  refetch everything
set -euo pipefail
cd "$(dirname "$0")/.."

BASE="${CORE_BASE:-https://buildbot.libretro.com/nightly/android/latest}"
CORE=fceumm
FORCE="${1:-}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

for abi in armeabi-v7a arm64-v8a; do
  out="src/main/jniLibs/$abi/lib${CORE}_libretro_android.so"
  if [ -f "$out" ] && [ "$FORCE" != "--force" ]; then
    echo "have $out"
    continue
  fi
  echo "fetching $CORE for $abi"
  mkdir -p "$TMP/$abi" "$(dirname "$out")"
  curl -fsSL -o "$TMP/$abi/core.zip" "$BASE/$abi/${CORE}_libretro_android.so.zip"
  unzip -oq "$TMP/$abi/core.zip" -d "$TMP/$abi"
  mv "$TMP/$abi/${CORE}_libretro_android.so" "$out"
done

( cd src/main/jniLibs && shasum -a 256 */lib${CORE}_libretro_android.so ) > cores/SHA256SUMS
cat cores/SHA256SUMS
