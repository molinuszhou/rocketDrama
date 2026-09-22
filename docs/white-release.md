# JoyeDrama White Release

`whiteRelease` is a content-only partner build. It keeps the catalog, fictional demo covers, episode playback, search, discovery, favorites, watch history, and player controls, while disabling TopOn ads, reward/wallet UI, reward-service synchronization, automatic episode locks, and reward/check-in prompts at compile time.

## Build

From `android/`:

```bash
./gradlew :app:assembleWhiteRelease --no-daemon
```

The APK is generated at `android/app/build/outputs/apk/whiteRelease/app-whiteRelease.apk`.

The partner-testing `whiteRelease` variant is signed with the local Android
debug keystore, so it can be installed directly with `adb install -r`. This
signature is for testing only; use the partner's release keystore before
distribution.

## Demo content server

The repository includes three fictional English demo dramas and nine short, abstract placeholder episodes. They contain no real people, licensed characters, currency imagery, advertising, or payment prompts. Start the matching content service from `services/content/`:

```bash
docker compose -f docker-compose.white-release.yml up --build -d
```

The Android build uses the `LOCAL_CONTENT_API_BASE_URL` build setting. The white-release compose file exposes the service on host port `8001`; for a real device, set the reachable LAN address explicitly in `android/local.properties` before building:

```properties
LOCAL_CONTENT_API_BASE_URL=http://192.168.1.20:8001
```

If this property is omitted, the Android build falls back to the emulator address `http://10.0.2.2:8000`, which will not work on a physical phone. The white-release test variant permits local HTTP traffic only for this content-only test package; normal release builds retain the stricter network policy.

The generated cover and video files are intentionally simple placeholders. Replace the entire `services/content/media/whitepack/` directory and the catalog JSON only after the partner supplies cleared content and metadata.
