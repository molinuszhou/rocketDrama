# JoyeDrama White Release

`whiteRelease` is a content-only partner build. It keeps the catalog, fictional demo covers, episode playback, search, discovery, favorites, watch history, and player controls, while disabling TopOn ads, reward/wallet UI, reward-service synchronization, automatic episode locks, and reward/check-in prompts at compile time.

## Build

From `android/`:

```bash
./gradlew :app:assembleWhiteRelease --no-daemon
```

The APK is generated at `android/app/build/outputs/apk/whiteRelease/app-whiteRelease.apk`.

## Demo content server

The repository includes three fictional English demo dramas and nine short, abstract placeholder episodes. They contain no real people, licensed characters, currency imagery, advertising, or payment prompts. Start the matching content service from `services/content/`:

```bash
docker compose -f docker-compose.white-release.yml up --build -d
```

The Android build still uses the existing `LOCAL_CONTENT_API_BASE_URL` build setting. For a real device, set it to the reachable LAN address of this content service, for example `http://192.168.1.20:8000`, in `android/local.properties` before building.

The generated cover and video files are intentionally simple placeholders. Replace the entire `services/content/media/whitepack/` directory and the catalog JSON only after the partner supplies cleared content and metadata.
