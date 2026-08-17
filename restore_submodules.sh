#!/bin/bash
set -uo pipefail
cd "$(dirname "$0")"

# path|url|sha|host(github|googlesource)
ENTRIES="
app/jni/tgvoip/third_party/abseil-cpp|https://github.com/abseil/abseil-cpp|2f9e432cce407ce0ae50676696666f33a77d42ac|github
app/jni/tgvoip/third_party/crc32c|https://github.com/google/crc32c|21fc8ef30415a635e7351ffa0e5d5367943d4a94|github
app/jni/tgvoip/third_party/libsrtp|https://github.com/cisco/libsrtp|860492290f7d1f25e2bd45da6471bfd4cd4d7add|github
app/jni/tgvoip/third_party/openh264|https://github.com/cisco/openh264|c59550a2147c255cc8e09451f6deb96de2526b6d|github
app/jni/tgvoip/third_party/rnnoise|https://github.com/xiph/rnnoise|1cbdbcf1283499bbb2230a6b0f126eb9b236defd|github
app/jni/tgvoip/third_party/tgcalls|https://github.com/TGX-Android/tgcalls|332e581d6349c22460090ddf4b0aa19985b63330|github
app/jni/tgvoip/third_party/usrsctp|https://github.com/sctplab/usrsctp|01cc4e042e2235b29d9d489d89728a6f9ac063ed|github
app/jni/tgvoip/third_party/webrtc|https://github.com/TGX-Android/webrtc|6ecff4f2446ff7d4ce38ca1c764f023e44dbcb1b|github
app/jni/third_party/ffmpeg|https://github.com/FFmpeg/FFmpeg|8139862d3d034c100cb7fda576aed05c1cfc91db|github
app/jni/third_party/flac|https://github.com/xiph/flac|e94ff9f68b8e7dbd3e9f8b1ac18a8eca1914f181|github
app/jni/third_party/jni-utils|https://github.com/TGX-Android/jni-utils|0a517820b3584d3751c8e233f93952ce14ac3b9b|github
app/jni/third_party/libvpx|https://github.com/webmproject/libvpx|1024874c5919305883187e2953de8fcb4c3d7fa6|github
app/jni/third_party/libyuv|https://chromium.googlesource.com/libyuv/libyuv|b56492e2dfc064f65ef27fed9c45d9bbfc2e2ad2|googlesource
app/jni/third_party/lz4|https://github.com/lz4/lz4|8f61d8eb7c6979769a484cde8df61ff7c4c77765|github
app/jni/third_party/ogg|https://github.com/xiph/ogg|06a5e0262cdc28aa4ae6797627a783b5010440f0|github
app/jni/third_party/opus|https://github.com/xiph/opus|3da9f7a6db1c05c3996cb363a9d1931a978bf1be|github
app/jni/third_party/opusfile|https://github.com/xiph/opusfile|6dfd29e7adb87f2e193575fc3fa88cbf1a0b27df|github
app/jni/third_party/rlottie|https://github.com/TGX-Android/rlottie|a5fa60c5d866071b7a382e319634d57cbea22f78|github
app/jni/third_party/webp|https://github.com/webmproject/libwebp|991170bbab3e6afc74666d124f3f1dc7be942cd0|github
tdlib|https://github.com/TGX-Android/tdlib|d3aeebd9918c01a58fd7f1b356df07713fb0c937|github
thirdparty/androidx-media/latest|https://github.com/androidx/media|5fb306449733dd71595700c1227ad6087578c559|github
thirdparty/androidx-media/legacy|https://github.com/androidx/media|b930b40a16c06318e43c81771fa2b1024bdb3f29|github
thirdparty/androidx-media/lollipop|https://github.com/androidx/media|b7bbc6e2bc3e45ff3ed99884c114c50f03bba5c9|github
thirdparty/androidx-media/marshmallow|https://github.com/androidx/media|5fb306449733dd71595700c1227ad6087578c559|github
vkryl/android|https://github.com/TGX-Android/X-Android|2db99896521ae4834d22fdfada5dd5024a7b5257|github
vkryl/core|https://github.com/TGX-Android/X-Core|21f82a0cd2686c4fc5e206545327e6c9b3e5c894|github
vkryl/leveldb|https://github.com/TGX-Android/leveldb|60bd313f7d5834e7f9df511ace357357063f3707|github
vkryl/td|https://github.com/TGX-Android/tdlib-utils|8c6c636cbb8f0ddaa74596f4d7c6fdaca6d37852|github
"

while IFS='|' read -r path url sha host; do
  [ -z "$path" ] && continue
  echo "=== $path ==="
  rm -rf "$path"
  mkdir -p "$path"
  if [ "$host" = "github" ]; then
    owner_repo=$(echo "$url" | sed -E 's#https://github.com/##')
    archive_url="https://codeload.github.com/${owner_repo}/tar.gz/${sha}"
  else
    archive_url="${url}/+archive/${sha}.tar.gz"
  fi
  tmpf="/tmp/archive_$$.tar.gz"
  if curl -sfL "$archive_url" -o "$tmpf"; then
    if [ "$host" = "github" ]; then
      # github tarballs contain a single top-level dir; strip it
      tar xzf "$tmpf" -C "$path" --strip-components=1
    else
      tar xzf "$tmpf" -C "$path"
    fi
    rm -f "$tmpf"
    echo "  -> OK ($(du -sh "$path" 2>/dev/null | cut -f1))"
  else
    echo "  !! FAILED: $archive_url"
  fi
done <<< "$ENTRIES"
echo "DONE_ALL_SUBMODULES"
