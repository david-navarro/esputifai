#!/bin/sh
# Build the Android app inside Docker and print the APK path.
# Usage: scripts/build-apk.sh [gradle task...]   (default: assembleRelease)
set -eu
cd "$(dirname "$0")/.."
IMAGE=music-android-build

docker build -q -t "$IMAGE" -f docker/android-build.Dockerfile docker >/dev/null

# Persistent signing key, so new builds install over old ones on the phone.
KS=android/keystore/release.jks
if [ ! -f "$KS" ]; then
  mkdir -p android/keystore
  PASS=$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9')
  docker run --rm -u "$(id -u):$(id -g)" -v "$PWD/android:/src" "$IMAGE" \
    keytool -genkeypair -keystore keystore/release.jks -alias music -keyalg RSA -keysize 4096 \
      -validity 36500 -storepass "$PASS" -keypass "$PASS" -dname "CN=Music" >/dev/null 2>&1
  umask 077
  printf 'storePassword=%s\nkeyPassword=%s\nkeyAlias=music\n' "$PASS" "$PASS" > android/keystore/keystore.properties
  echo "Created signing key in android/keystore/ (back it up: losing it means reinstalling the app)"
fi

# Gradle/dependency cache on the host (a named volume would be root-owned).
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/music-android-gradle"
mkdir -p "$CACHE"
docker run --rm -u "$(id -u):$(id -g)" \
  -v "$PWD/android:/src" -v "$CACHE:/gradle" \
  "$IMAGE" ./gradlew --no-daemon --console=plain "${@:-assembleRelease}"

ls -1 android/app/build/outputs/apk/*/*.apk 2>/dev/null || true
