# Ulap

Android app for private media backup to Telegram.

Back up photos and videos from device folders (and optionally from Google Photos) to your own Telegram chat — no separate backend, no subscription tied to Ulap itself.

## Features

- **Folder-based backup**: Choose which storage buckets to include; incremental scans keep the local catalog in sync
- **Large files**: Streams uploads; files above ~20MB are split into ~19MB Telegram document parts and reassembled for viewing and restore (aligned with Bot API `getFile` limits)
- **Rate-limit aware**: Handles HTTP 429 with backoff; optional **multiple bot tokens** to spread upload load (round-robin, per-bot cooldowns)
- **Restore**: Download backed-up media back to the device
- **Google Photos import**: Sign in with Google, grant the Photos Picker scope, select items in Google Photos, and import into the same Telegram backup flow
- **QR setup**: Show or scan a QR code on the welcome flow to copy bot/chat setup to another device
- **Privacy options**: Optional stripping of GPS EXIF from JPEGs before upload (settings)
- **Private by design**: Payload goes between your device and Telegram; see Telegram’s own terms and limits for the chat you use

## Requirements

- **Android** 8.0+ (`minSdk` 26)
- **Build**: Android SDK **35**, **JDK 17**, **Gradle 8.9** (via wrapper), **Android Gradle Plugin** and **Kotlin** versions from `gradle/libs.versions.toml`

## Setup (Telegram)

1. Create a bot with [@BotFather](https://t.me/BotFather)
2. Create a private channel or group and add the bot as an administrator with permission to post
3. Obtain the chat ID (e.g. send a message to [@userinfobot](https://t.me/userinfobot) and select the group from the bot options. 
4. In Ulap, enter the bot token and chat ID (or use **QR show/scan** on a second device)
5. Pick folders to back up and run sync

Additional bot tokens (settings) are optional and used to rotate uploads when you hit rate limits.

## Building

From the repository root:

```bash
# Debug APK (typical local build)
./gradlew :app:assembleDebug
```

On Windows, use `gradlew.bat` instead of `./gradlew` if you prefer:

```bat
gradlew.bat :app:assembleDebug
```

Release builds use minification and require a **release signing** config. Set these in `local.properties` (file is gitignored; do not commit secrets):

- `ulap.storeFile` — path to your keystore
- `ulap.storePassword`, `ulap.keyAlias`, `ulap.keyPassword`

Then:

```bash
./gradlew :app:assembleRelease
```

**Build outputs:** this project sets the app module’s build directory to **`app/build-out/`** (instead of `app/build/`) so release packaging is less likely to fail on Windows when an APK path is locked by another process. Example artifacts:

- Debug APK: `app/build-out/outputs/apk/debug/`
- Release APK: `app/build-out/outputs/apk/release/`

### Optional: debug test credentials

For local debug builds only, you can inject test Bot API values via `local.properties`:

- `ulap.testBotToken`
- `ulap.testChatId`

These become `BuildConfig` fields in the **debug** build type (release leaves them empty).

## Testing

- **Unit tests** (JVM, including Robolectric where used):

  ```bash
  ./gradlew :app:testDebugUnitTest
  ```

- **Instrumented tests** (device or emulator):

  ```bash
  ./gradlew :app:connectedDebugAndroidTest
  ```

## Repository layout

| Path | Purpose |
|------|--------|
| `app/src/main/` | Kotlin, Compose UI, sync workers, Telegram and Google Photos clients |
| `app/src/test/` | Unit tests |
| `app/src/androidTest/` | Android instrumented tests |
| `app/schemas/` | Exported Room schemas |
| `gradle/libs.versions.toml` | Centralized dependency and plugin versions |
| `app/build-out/` | Gradle outputs for the `:app` module (see above) |

## Architecture (high level)

- **UI**: Jetpack Compose, Material 3, Navigation Compose
- **DI**: Hilt (incl. Hilt WorkManager)
- **Local data**: Room, DataStore Preferences, Encrypted storage for sensitive prefs where applicable
- **Background work**: WorkManager (`BackupWorker`, `GooglePhotosImportWorker`, maintenance workers)
- **Network**: Retrofit + Gson, OkHttp (Telegram Bot API)
- **Media**: Coil 3 (thumbnails), Media3 ExoPlayer (playback; progressive/chunked sources for large restored video)
- **Google Photos**: Google Sign-In / Play services flows and Photos Picker API–style scope for importing picks
- **QR**: ZXing, CameraX, ML Kit barcode scanning

## FAQ

**Is Telegram’s Bot API free to use?**  
Telegram provides the Bot API without a separate fee from Ulap; your use still follows [Telegram’s policies](https://telegram.org) and any limits on your account.

**What are Telegram’s file limits?**  
Limits depend on Telegram’s current Bot API and product rules. Ulap avoids loading entire huge files into memory and chunks large uploads into multiple Bot API document messages so restores can stream and reassemble.

**Where do backups live?**  
In the Telegram chat you configured (channel or group). Ulap does not operate its own file servers for your media.

**Google Photos import — what does Ulap access?**  
Only what you explicitly select in the Google Photos picker after signing in and granting the app the Photos Picker–related scope; imports are then uploaded to Telegram like local folder media.
