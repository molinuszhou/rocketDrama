#!/usr/bin/env bash
# Build a production Release APK, then sign a copy with a temporary test key.
# Run from Windows Git Bash or WSL. This script never changes the Gradle signing
# configuration and never signs the unsigned APK in place.
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ANDROID_DIR="$REPO_ROOT/android"
OUTPUT_DIR="${OUTPUT_DIR:-$ANDROID_DIR/app/build/outputs/release-test}"
KEYSTORE_PATH="${KEYSTORE_PATH:-$OUTPUT_DIR/joyedrama-test.keystore}"
KEY_ALIAS="${KEY_ALIAS:-joyedrama-test}"
KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-android}"
KEY_PASSWORD="${KEY_PASSWORD:-$KEYSTORE_PASSWORD}"

log() {
  printf '\n[JoyedDrama] %s\n' "$*"
}

fail() {
  printf '\n[JoyedDrama] ERROR: %s\n' "$*" >&2
  exit 1
}

command -v java >/dev/null 2>&1 || fail "Java was not found. Install JDK 17 and set JAVA_HOME."
command -v keytool >/dev/null 2>&1 || fail "keytool was not found. Install JDK 17 and set JAVA_HOME."

if [[ ! -d "$ANDROID_DIR" ]]; then
  fail "Android project directory was not found: $ANDROID_DIR"
fi

if [[ -n "${ANDROID_HOME:-}" ]]; then
  SDK_ROOT="$ANDROID_HOME"
elif [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
  SDK_ROOT="$ANDROID_SDK_ROOT"
elif [[ -n "${ANDROID_HOME_WIN:-}" ]]; then
  SDK_ROOT="$ANDROID_HOME_WIN"
else
  fail "Set ANDROID_HOME or ANDROID_SDK_ROOT to your Android SDK directory."
fi

[[ -d "$SDK_ROOT/build-tools" ]] || fail "Android build-tools directory was not found under: $SDK_ROOT"

# Select the newest installed Build Tools version. Git Bash and WSL both support
# sort -V; BUILD_TOOLS_VERSION can override it when a specific version is needed.
if [[ -n "${BUILD_TOOLS_VERSION:-}" ]]; then
  BUILD_TOOLS_DIR="$SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION"
else
  BUILD_TOOLS_DIR="$(find "$SDK_ROOT/build-tools" -mindepth 1 -maxdepth 1 -type d -print | sort -V | tail -n 1)"
fi
[[ -d "$BUILD_TOOLS_DIR" ]] || fail "No Android Build Tools installation was found."

if [[ -x "$BUILD_TOOLS_DIR/apksigner" ]]; then
  APKSIGNER="$BUILD_TOOLS_DIR/apksigner"
elif [[ -x "$BUILD_TOOLS_DIR/apksigner.bat" ]]; then
  APKSIGNER="$BUILD_TOOLS_DIR/apksigner.bat"
elif [[ -f "$BUILD_TOOLS_DIR/apksigner" ]]; then
  APKSIGNER="$BUILD_TOOLS_DIR/apksigner"
elif [[ -f "$BUILD_TOOLS_DIR/apksigner.bat" ]]; then
  APKSIGNER="$BUILD_TOOLS_DIR/apksigner.bat"
else
  fail "apksigner was not found in: $BUILD_TOOLS_DIR"
fi

GRADLE_CMD=("$ANDROID_DIR/gradlew")
if [[ -f "$ANDROID_DIR/gradlew.bat" && ! -x "$ANDROID_DIR/gradlew" ]]; then
  # Git Bash can execute the Windows wrapper through bash; WSL normally uses
  # the Unix wrapper. Prefer gradlew when it is executable.
  GRADLE_CMD=("$ANDROID_DIR/gradlew.bat")
fi
[[ -f "${GRADLE_CMD[0]}" ]] || fail "Gradle wrapper was not found under: $ANDROID_DIR"

mkdir -p "$OUTPUT_DIR"
UNSIGNED_APK="$ANDROID_DIR/app/build/outputs/apk/release/app-release-unsigned.apk"
SIGNED_APK="$OUTPUT_DIR/app-release-test-signed.apk"

log "Building unsigned Release APK"
(
  cd "$ANDROID_DIR"
  "${GRADLE_CMD[@]}" :app:assembleRelease
)
[[ -f "$UNSIGNED_APK" ]] || fail "Gradle completed but unsigned APK was not found: $UNSIGNED_APK"

if [[ ! -f "$KEYSTORE_PATH" ]]; then
  log "Generating temporary test keystore: $KEYSTORE_PATH"
  keytool -genkeypair -v \
    -keystore "$KEYSTORE_PATH" \
    -storepass "$KEYSTORE_PASSWORD" \
    -keypass "$KEY_PASSWORD" \
    -alias "$KEY_ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=JoyeDrama Test, OU=QA, O=JoyeDrama, L=Test, ST=Test, C=US"
else
  log "Reusing temporary test keystore: $KEYSTORE_PATH"
fi

cp -f "$UNSIGNED_APK" "$SIGNED_APK"

log "Signing test copy with temporary key"
"$APKSIGNER" sign \
  --ks "$KEYSTORE_PATH" \
  --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$KEYSTORE_PASSWORD" \
  --key-pass "pass:$KEY_PASSWORD" \
  --out "$SIGNED_APK" \
  "$SIGNED_APK"

log "Verifying APK signature"
"$APKSIGNER" verify --verbose "$SIGNED_APK"

log "Build complete"
printf 'Unsigned APK: %s\n' "$UNSIGNED_APK"
printf 'Test-signed APK: %s\n' "$SIGNED_APK"
printf 'Keystore: %s\n' "$KEYSTORE_PATH"
printf 'Alias: %s\n' "$KEY_ALIAS"
printf '\nInstall for device testing with:\n  adb install -r "%s"\n' "$SIGNED_APK"
printf '\nImportant: deliver the unsigned APK to the partner for final signing, not the test-signed APK.\n'
