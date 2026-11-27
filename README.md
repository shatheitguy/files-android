<div align="center">

<img src="docs/icon.svg" width="112" height="112" alt="Files icon">

# Files for Android

**Files. Now on Android.**<br>
Browse every folder, USB drive, network share and cloud drive on your phone — with the calm, familiar feel of the iPhone Files app.

[![Latest release](https://img.shields.io/github/v/release/shatheitguy/files-android?style=for-the-badge&label=Download&color=ff5e7e)](https://github.com/shatheitguy/files-android/releases/latest)
[![Website](https://img.shields.io/badge/Website-visit-8a5cff?style=for-the-badge)](https://shatheitguy.github.io/files-android/)
[![Android 10+](https://img.shields.io/badge/Android-10%2B-34A853?style=for-the-badge&logo=android&logoColor=white)](#-install)

![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-UI-4285F4?logo=jetpackcompose&logoColor=white)
![SMB](https://img.shields.io/badge/SMB-2%20%2F%203-0a84ff)
![No ads](https://img.shields.io/badge/ads-none-lightgrey)

[Features](#-features) · [Install](#-install) · [Build](#%EF%B8%8F-build-from-source) · [Author](#-author)

</div>

---

## ✨ Features

**Every file, beautifully organized.** Designed to feel instantly familiar, built natively for Android.

### 🗂️ Browse, Recents and Search
_The floating tab bar you can slide your finger across._

- On My Phone, SD card and USB drives (shown when plugged in)
- Your cloud storage apps, shown only when installed
- Move Locations up or down with Edit
- Collapsible Locations, Favorites and Tags
- Icons or List, sort by Name, Kind, Date or Size
- Aspect-ratio thumbnails for photos, videos, PDFs and apps

### 📁 Organize anything
_Long-press for the menu you expect._

- Swipe across files to select many at once
- Rename, Duplicate, Copy, Move — with live progress
- Compress and uncompress ZIP files
- Recently Deleted keeps files for 30 days
- Remembers the folder you were in, even after closing

### 🌐 Connect to Server & Scan
_From the ••• menu on Browse, just like iPhone._

- SMB shares on your PC, Mac or NAS
- Open, download, upload, new folder, delete
- Scan paper documents straight to PDF

### 📱 Made for tablets too
_An iPad-style sidebar beside your files on big screens._

- Recents, Locations, Shared, Favorites and Tags
- “On My Tablet”, and Move / Copy as a centered sheet

### ⬇️ Updates built in
_Files tells you when a new version is out._

- Download with a live progress ring
- Install straight from the app
- Glass menus and buttons, light or dark

## 🤝 Two apps that feel like one.

Open a picture in Files and it opens in Photos, so you can swipe through the whole folder. “Save to Files” appears in every share sheet, and moves or deletes show up in Photos instantly.

👉 [Get Photos for Android](https://github.com/shatheitguy/photos-android)

## 🔒 Privacy

No accounts, no ads, no analytics. Files only uses the internet to reach the servers you add and to check GitHub for new versions. Cloud storage opens in its own app, and saved server passwords stay in the app's private storage.

## 📥 Install

**Up and running in a minute.**

1. **Download**: Get `Files.apk` from the [latest release](https://github.com/shatheitguy/files-android/releases/latest).
2. **Install**: Open the file on your phone. If Android asks, allow installing from this source.
3. **Allow access**: Open Files, tap **Continue** and turn on **Allow access to manage all files**.

> Requires **Android 10 or newer**.

## 🛠️ Build from source
You need JDK 17 and the Android SDK (platform 35).

```bash
git clone https://github.com/shatheitguy/files-android.git
cd files-android
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`.

<details>
<summary><b>Project layout</b></summary>

```
app/src/main/java/com/photos/files/
├── MainActivity.kt          Entry point, watches for USB drives
├── SaveActivity.kt          "Save to Files" from the share sheet
├── data/
│   ├── FileStore.kt         Listing, sorting, recents, trash, zip, volumes
│   ├── Smb.kt               SMB servers and shares
│   ├── Cloud.kt             Cloud drive apps on the phone
│   └── Thumbs.kt            Thumbnails for photos, videos, PDFs and APKs
└── ui/
    ├── Root.kt              Navigation, sheets, dialogs, progress pill
    ├── Screens.kt           Browse, Recents, Folder, Tags, Trash, Search
    ├── Collection.kt        File grid / list, selection, menus
    ├── SmbScreen.kt         Server browser
    ├── Locations.kt         Locations order and cloud drives
    ├── FilesTablet.kt       iPad-style sidebar and pane
    ├── FilesSettings.kt     Settings, ••• menu, document scanner
    ├── Updates.kt           In-app updates
    └── Theme.kt, IosIcons.kt, IosPopup.kt, SlidingTabs.kt, Tablet.kt
```
</details>

## 👤 Author

**Hi, I'm Sharqan.** I build Android apps that feel as calm and polished as the ones on iPhone, with no ads, no tracking, and everything staying on your phone.

<table>
  <tr>
    <td><img src="https://github.com/shatheitguy.png" width="72" height="72" alt="Sharqan Ahamed"></td>
    <td>
      <b>Sharqan Ahamed</b><br>
      Designer and developer of Files and Photos for Android<br>
      <a href="https://github.com/shatheitguy">github.com/shatheitguy</a>
    </td>
  </tr>
</table>

If you enjoy the app, ⭐ the repo. It really helps!

---

<sub>Files for Android is an independent project and isn't affiliated with Apple or Google. The app icon is an original design.</sub>
