# Rhythmix Android

Your private, offline music player with persistent analytics — data stored directly in your music folder as `rhythmix_data.json`.

---

## Why This Architecture Works (The Core Idea)

The web app used `localStorage` — wiped on reinstall.  
Capacitor Filesystem only writes to app-private dirs — also wiped on reinstall.

This app uses **Android's Storage Access Framework (SAF)** with **persistable URI permissions**:

```
User picks /sdcard/Music/
   ↓
Android grants read+write permission to that URI
   ↓
We call takePersistableUriPermission()
   ↓
Permission is stored in Android's permission table — SURVIVES reinstall
   ↓
We write rhythmix_data.json INTO /sdcard/Music/
   ↓
Reinstall app → user picks same folder → rhythmix_data.json found → data restored ✓
```

This is the **only Play Store-compliant way** to write to arbitrary user folders on Android 10+.

---

## Project Structure

```
app/src/main/java/com/rhythmix/app/
├── model/
│   ├── Song.kt              — Audio track data
│   └── AnalyticsData.kt     — PlayEvent, AnalyticsData, SongStats
├── storage/
│   └── StorageManager.kt    — SAF folder picker, file R/W, song scanning
├── player/
│   ├── PlaybackService.kt   — Media3 foreground service
│   └── PlayerController.kt  — ExoPlayer wrapper, analytics recording
└── ui/
    ├── MainActivity.kt       — Hosts fragments, SAF launcher
    ├── MainViewModel.kt      — Central state: songs, analytics, navigation
    ├── OnboardingFragment.kt — Folder picker screen
    ├── ScanningFragment.kt   — Progress screen
    ├── HomeFragment.kt       — Dashboard
    ├── LibraryFragment.kt    — Full song list + search
    ├── AnalyticsFragment.kt  — Stats dashboard
    ├── PlayerFragment.kt     — Full-screen player
    ├── SettingsFragment.kt   — Folder & data management
    └── adapter/
        ├── SongAdapter.kt
        └── StatsAdapter.kt
```

---

## How to Build

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17 (bundled with Android Studio)
- Android SDK API 34

### Steps

1. **Open in Android Studio**
   ```
   File → Open → select the RhythmixAndroid folder
   ```

2. **Sync Gradle**
   Android Studio will prompt to sync. Click "Sync Now".

3. **Build**
   ```
   Build → Make Project   (Ctrl+F9)
   ```

4. **Run**
   - Connect an Android phone (USB debugging on) or use the emulator
   - Click the green ▶ Run button

### Minimum requirements
- Android 8.0 (API 26) or higher
- ~50 MB storage (app itself is ~10 MB; Media3 adds ~30 MB)

---

## First-Run Flow

1. App opens → **Onboarding screen**
2. Tap "Choose Music Folder"
3. Android's built-in folder picker opens
4. Navigate to your MP3 folder and tap "Use this folder"
5. App scans for audio files (shows progress ring)
6. Home screen loads with your library

**`rhythmix_data.json` is created automatically in your music folder.**

---

## Reinstall Recovery

1. Uninstall app
2. Install app again
3. Tap "Choose Music Folder"
4. Pick the **same folder** as before
5. App finds `rhythmix_data.json` and loads all your history instantly ✓

No sync. No cloud. No internet. Everything lives in your music folder.

---

## Analytics Data Format

`rhythmix_data.json` schema is **identical to the web app** — fully portable:

```json
{
  "schemaVersion": 1,
  "lastSyncAt": 1718000000000,
  "playEvents": [
    {
      "songId": "a1b2c3d4",
      "startedAt": 1718000000000,
      "listenedMs": 185000,
      "completed": true
    }
  ]
}
```

- `songId`: stable hash of `filename + filesize` — same algorithm as the web app
- Up to 5,000 events stored (older ones trimmed automatically)

---

## Key Dependencies

| Library | Purpose |
|---------|---------|
| `androidx.media3:media3-exoplayer` | Audio playback engine |
| `androidx.media3:media3-session` | Foreground playback service + media notification |
| `androidx.documentfile:documentfile` | SAF tree URI helper |
| `com.google.code.gson:gson` | JSON serialisation for analytics |
| `androidx.lifecycle:lifecycle-viewmodel-ktx` | ViewModel + LiveData |

---

## Extending the App

### Add ID3 tag reading
The current version parses metadata from filenames (`Artist - Title.mp3`).  
To read actual ID3 tags, add [JAudioTagger](https://www.jthink.net/jaudiotagger/) or use `MediaMetadataRetriever` with additional keys (`METADATA_KEY_ARTIST`, `METADATA_KEY_TITLE`, etc.) — already imported in `StorageManager.kt`.

### Add album art
Use `MediaMetadataRetriever.getEmbeddedPicture()` to extract cover art bytes from the audio file, then load with Glide:
```kotlin
val art = mmr.embeddedPicture
Glide.with(context).load(art).into(imageView)
```

### Add a playlist feature
Store playlists as additional fields in `AnalyticsData` — they'll be persisted to the same JSON file automatically.

---

## Privacy

- No internet permission in the manifest
- No analytics SDKs
- No crash reporting
- All data stays on device, in your music folder
- The app cannot access any files outside the folder you explicitly chose
