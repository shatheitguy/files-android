# Files for Android

An iPhone-style file manager for Android, built natively with Kotlin and Jetpack Compose. It works hand in hand with [Photos for Android](https://github.com/shatheitguy/photos-android).

**Website:** https://shatheitguy.github.io/files-android/
**Download:** [Latest APK](https://github.com/shatheitguy/files-android/releases/latest)

---

## Features

### Browse
- **Recents · Browse · Search** with a floating tab bar. Press it and slide your finger to switch tabs.
- Locations: **On My Phone**, SD card and **USB drives** (they only appear while plugged in), and **Recently Deleted** (30 days).
- **Favorites** and colored **Tags**. Every section can be collapsed or expanded.
- ••• menu: **Edit**, **Connect to Server**, **Scan Documents** and **Settings**.

### Folders
- Icons or List view; sort by Name, Kind, Date or Size; show hidden files.
- Aspect-ratio thumbnails for photos, videos, PDFs and APKs.
- Long-press menu: Get Info, Rename, Compress / Uncompress (ZIP), Duplicate, Tags, Copy, Move, Share, Delete.
- **Select** mode with Select All, and swipe across files to select many at once.
- Copy and Move use an iOS-style sheet and a progress pill.

### Connect to Server
- SMB 2/3 shares on a Windows PC, Mac or NAS: browse, open, download to the phone, upload, new folder, delete.

### Scan Documents
- Scan paper with the camera and save it as a PDF in `Documents/Scans` (needs Google Play services).

### Works with Photos
- Photos and videos open in the Photos viewer, so you can swipe through the folder.
- **Save to Files** shows up in every share sheet.
- Moves and deletes show up in Photos straight away.

### Updates
- Files checks GitHub for a new version when you open it and installs it from inside the app.

## Install

1. Download `Files.apk` from the [latest release](https://github.com/shatheitguy/files-android/releases/latest).
2. Open it on your phone and allow installing from this source if Android asks.
3. Open **Files**, tap **Continue** and turn on **Allow access to manage all files**.

Requires **Android 10 or newer**.

## Build from source

Requirements: JDK 17 and the Android SDK (platform 35).

```bash
git clone https://github.com/shatheitguy/files-android.git
cd files-android
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`.

## Notes

- Saved server passwords are kept in the app's private storage on your phone.
- The app uses the internet only to reach your servers and to check GitHub for updates.
- This is an independent project and isn't affiliated with Apple or Google.
