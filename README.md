# Weiter B2

An Android audio companion for **Einfach weiter! Deutsch B2**.

For the **2021 edition**, published by **telc gGmbH**. ISBN-13: **978-3946447337** · ISBN-10: **3946447333**.

- All 63 tracks, available offline.
- Find tracks by number, chapter, and any available page or exercise labels.
- Listen in the background, change speed, or repeat a track or passage.
- Resume listening, save bookmarks and notes, and mark tracks complete.
- English controls, German book labels, and light/dark themes.

## Build

Requires JDK 17, Android SDK 36 with build tools 36.0.0, and Python 3. Set `JAVA_HOME` and `ANDROID_HOME` to their installation folders.

In the project folder, run:

```sh
./gradlew :app:assembleDebug
python3 scripts/package_apk.py
```

The APK is saved to `output/Weiter-B2-1.0.0.apk`.

## Install

Requires Android 8.0 or newer. The APK is about 160 MB.

1. Copy the APK to your phone.
2. Open it in Files.
3. Allow installation from Files if prompted, then tap **Install**.

### Install with adb (optional)

Enable USB debugging, connect your phone, and accept the debugging prompt. Run from the project folder:

```sh
adb install --user 0 -r output/Weiter-B2-1.0.0.apk
```

## Disclosure

This app was fully vibecoded since Telc didn't bother to update their Play store app to work on new Android versions. Now for the rest:

This is an independently made personal companion, not an official one app. The original audio comes from [telc’s official downloads](https://www.telc.net/lehrmaterialien/downloadbereich/) and remains unchanged. Audio © telc gGmbH.

Book references were prepared using local speech recognition and public book samples. Some chapter assignments follow the announcement sequence; page and exercise details appear only where supported. Printed editions may differ.

The APK is debug-signed for personal installation. The app has no Internet permission, ads, backend, or runtime AI service.
