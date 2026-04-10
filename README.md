# Ulap

Private media backup to Telegram.

Back up your photos and videos directly to your Telegram account — no servers, no subscriptions, no cost.

## Features

- **Folder-based backup**: Choose which folders to back up automatically
- **Photo & video support**: Images and videos up to 2GB
- **Streaming uploads**: Memory-efficient, no full-file buffering
- **Rate-limit aware**: Respects Telegram Bot API limits with exponential backoff
- **Restore**: Download backed-up media back to device
- **Private**: All data stays between your device and your Telegram account

## Setup

1. Create a Telegram bot via [@BotFather](https://t.me/BotFather)
2. Create a private channel or group and add the bot as admin
3. Get your chat ID (forward a message to [@userinfobot](https://t.me/userinfobot))
4. Open Ulap, enter your bot token and chat ID
5. Select folders to back up

## Building

```
./gradlew assembleDebug
```

Requires Android SDK 35 and JDK 17.

## Architecture

- **Kotlin** + **Jetpack Compose** + **Material 3**
- **Room** for local media database
- **Retrofit** + **OkHttp** for Telegram Bot API
- **WorkManager** for background sync
- **Hilt** for dependency injection
- **Coil 3** for image/video thumbnails
- **Media3 ExoPlayer** for video playback

## FAQ

**Is this free?**
Yes. Telegram Bot API is free. There are no servers or paid services.

**What's the file size limit?**
50MB for single upload via Bot API. Files over 2GB are excluded.

**Where are backups stored?**
In your Telegram chat (channel/group). Ulap doesn't store files on any server.
