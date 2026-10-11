# Weiter

An independently made Android companion for the German-learning books in **telc Deutsch-Box**.

The library contains 53 book volumes, 3,070 original recordings across 209 audio packages, and 44 original German/English PDFs. Editions, teacher-manual series, and normal/slow recordings remain separate.

The default APK includes **metadata only**. Select a book in the app to download its audio pack, or download individual vocabulary/listening PDFs. Verified downloads work offline. The player supports background listening, speed adjustment, track/passage repeat, bookmarks, notes, and completion tracking. Original PDFs have zoom, page navigation, and remembered reading position.

Existing Weiter B2 progress and bookmarks are retained when upgrading with the same application ID and signing key. The default upgrade needs an initial B2 audio download; a B2 bundled build has that audio immediately available.

## Build

Requires JDK 17, Android SDK 36 with build tools 36.0.0, and Python 3. Set `JAVA_HOME` and `ANDROID_HOME` to their installation folders. Android 8.0 or newer is supported.

### Metadata-only APK

```sh
python3 scripts/build_app.py
```

Alternatively, use the standard Gradle build and verify/copy its artifact:

```sh
./gradlew :app:assembleDebug
python3 scripts/package_apk.py
```

Output: `output/Weiter-2.0.0.apk`, with a SHA-256 file alongside it. No audio or PDFs are downloaded or bundled by a default build.

### Bundle selected books

List the concrete book/edition/volume IDs and their offline sizes:

```sh
python3 scripts/build_app.py --list-books
```

Build with the complete original audio and German/English documents for selected books:

```sh
python3 scripts/build_app.py --bundle-book einfach-weiter-b2
python3 scripts/build_app.py --bundle-book einfach-weiter-b2 --bundle-book einfach-gut-a1-1
```

Repeat `--bundle-book` for additional books. The wrapper fetches missing originals from the publisher's source URLs, verifies their sizes/checksums, and bundles only the selected books. Downloads are reused from `.work/content`; `--cache <folder>` selects another cache. Each build uses an isolated asset directory, so a later default build cannot accidentally include earlier bundles. Ordinary builds do not require speech recognition or PDF extraction tools.

Bundled content is limited to 3,968 MiB to leave room within the APK format's 4 GiB ZIP limit. Download additional books inside the app.

## Install

Copy the APK to your phone, open it in Files, and allow installation from Files if prompted. With USB debugging enabled:

```sh
adb install --user 0 -r output/Weiter-2.0.0.apk
```

In the app, choose a book and volume, then **Audio → Download audio** or **Documents → Download**. Downloaded files can be removed without deleting bookmarks, notes, completion, or reading position. Bundled files are part of the APK and cannot be removed separately.

## Maintain the catalog

The checked-in snapshot comes from the [catalog used by the official app](https://telc.container.adrodev.de/produktiv/). Its original files are unchanged; source URLs, ZIP member paths, sizes, and SHA-256 hashes are retained in the catalog. Official website [downloads](https://www.telc.net/lehrmaterialien/downloadbereich/) supply additional annotation evidence.

Content preparation is separate from ordinary builds. It requires FFmpeg/ffprobe and a Python environment with the dependencies in `scripts/catalog-requirements.txt`:

```sh
python3 -m venv .work/catalog-venv
.work/catalog-venv/bin/pip install -r scripts/catalog-requirements.txt
.work/catalog-venv/bin/python scripts/prepare_catalog.py
python3 scripts/validate_catalog.py --cache
```

Preparation fetches originals into ignored `.work/content` and retains technical inventories under `.work/analysis/catalog-state`. To regenerate the catalog after reviewing annotations without repeating downloads:

```sh
.work/catalog-venv/bin/python scripts/prepare_catalog.py --generate-only
```

Reviewed annotations are stored per book under `scripts/catalog/annotations`. A worker owns a whole edition and writes its own annotation file; another worker reviews the cited evidence before integration. Announcements use local whisper.cpp models and a checksum-based cache. First-pass clips are 12 seconds; unclear openings can receive 40-second and alternative-model checks. Models and raw preparation evidence are excluded from APKs. See `python3 scripts/transcribe_announcements.py --help` for selection/review options.

References distinguish direct verification from chapter context. Page/exercise/timestamp details are included only when supported; editions may differ. Vocabulary PDFs remain original documents rather than extracted flashcards. Videos and translations into languages other than English are excluded.

## Verification

```sh
python3 -m unittest discover -s tests -v
python3 scripts/test_prepare_catalog.py
python3 scripts/validate_catalog.py --cache --review
./gradlew :app:testDebugUnitTest
```

Device suites check migration, safe preparation, cancellation, navigation, PDF viewing, and playback. Run playback tests with B2 bundled. Real-download tests are opt-in with instrumentation argument `runtime_network=true`; use a separate `-PapplicationId=com.example.weiterb2.validation` build for device validation.

## Disclosure

This app was built independently to provide a companion that works on newer Android versions. It is not an official telc app. Original audio and documents © telc gGmbH. The personal APK is debug-signed. There are no ads, hosted backend, runtime transcription, or cloud AI services. Internet access is used to download publisher resources chosen in the app.
