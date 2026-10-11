# Original content catalog

The snapshot comes from the public feed used by the official [telc Deutsch-Box APK](https://www.telc.net/fileadmin/user_upload/telc_Deutsch-Box/telc_deutsch-box.apk): <https://telc.container.adrodev.de/produktiv/>. It was retrieved on 2026-10-11. The [official download area](https://www.telc.net/lehrmaterialien/downloadbereich/) supplies additional evidence PDFs for annotation; it contains more resources than the original app feed.

The selected app scope is 53 concrete book volumes, 209 original audio ZIPs, 3,070 MP3 members (2,736 unique audio hashes), and 44 original PDFs: 27 German and 17 English. Videos and other vocabulary translations are excluded. The PDFs remain intact.

`registry.json` binds IDs to original hierarchy paths, package URLs, and exact ZIP member paths. IDs survive display-name changes and corrected track-number parsing. The 63 original full-volume Weiter B2 IDs and annotations are preserved only when original audio SHA-256 matches; split editions receive their own records and reviewed exercise numbers.

`source-inventory.json` describes the selected source hierarchy. The app's complete schema-v2 catalog is `app/src/main/assets/catalog.json`. `annotations/<book-id>.json` contains reviewed resource references and evidence provenance. A successful release review creates `review-manifest.json`, binding the catalog and every annotation file to hashes and independent reviewers.

## Preparation

Inventory-only work uses the Python standard library:

```sh
python3 scripts/prepare_catalog.py --inventory-only
```

Full source preparation also needs `ffmpeg`, `ffprobe`, and the pinned PDF dependency:

```sh
python3 -m venv .work/catalog-venv
.work/catalog-venv/bin/python -m pip install -r scripts/catalog-requirements.txt
.work/catalog-venv/bin/python scripts/prepare_catalog.py
```

Preparation downloads original files into ignored `.work/content/packages` and `.work/content/documents`, validates ZIP member paths and CRCs, decodes and probes every MP3, hashes exact original bytes, and extracts filename-safe resource copies into `.work/content/resources`. Cached resources are hash-checked and repaired from their original ZIP when needed. `--book <id>` is repeatable for partial preparation. `--generate-only` merges existing verified receipts and reviewed annotations without downloading or analysing content again.

Ordinary APK builds use the checked-in catalog and release manifest. They do not run transcription or PDF analysis. Optional bundling downloads only selected original books through the build wrapper.

## Opening evidence and annotation

The local opening pipeline expects `.work/tools/whisper-build/bin/whisper-cli` and `.work/tools/ggml-small.bin` / `ggml-base.bin`. It runs at most two Whisper jobs with four threads each. The first pass uses 12 seconds; a 40-second clip or a different model is reserved for uncertain openings. It reuses exact audio hashes across books and the prior Weiter B2 evidence.

```sh
python3 scripts/transcribe_announcements.py --model small --primary
python3 scripts/transcribe_announcements.py --model base --primary
python3 scripts/transcribe_announcements.py --sha <audio-sha256> --seconds 40 --model small
```

Canonical first-pass evidence lives in `.work/analysis/announcements-by-sha/<sha>.json`, `.txt`, and `.evidence.json`. The metadata records the actual model, clip length, original member, and source audio hash. Existing canonical evidence is preserved when the primary model changes. Alternative evidence uses `<sha>-40s-small` or `<sha>-12s-base`; exact matching settings reuse existing results. All three files must be complete before a cache hit is accepted.

Per-book jobs live in `.work/analysis/annotation-jobs`. Reviewers may add JSON arrays of audio hashes in `.work/analysis/*-announcement-priority.json`, `*-announcement-fallback.json` (40-second small model), or `*-announcement-base-fallback.json` (12-second base model). The shared worker pool reloads these queues between batches.

Annotations require actual opening announcements or explicit official PDF track headings. Original filenames may establish an explicitly named section or lesson, but do not establish an unstated exercise. PDF source pages identify evidence pages, not printed textbook pages. Ambiguous and introductory resources retain empty references with a reviewed reason.

Each annotation file records `review.author` and `review.reviewedAt`. A different worker supplies `review.reviewer` and `review.independentlyReviewedAt`. Every track and document has a `review` containing `status: reviewed`, `method`, and its exact `sourceSha256`; empty references also require `reason`. Audio evidence has `sourceLocator`. PDF references have `sourceUrl` / `sourcePage`, supported by source URL, SHA-256, and page count records.

## Release checks

```sh
python3 -m unittest discover -s scripts -p test_prepare_catalog.py
python3 scripts/prepare_catalog.py --generate-only
python3 scripts/validate_catalog.py --cache --review
```

The release gate requires every original ZIP member and resource, stable registry IDs, numeric queues, all 53 independent book reviews, and complete first-pass local evidence for every unique audio hash. It emits the reviewed catalog fingerprint and resource/evidence coverage manifest only after those checks pass.
