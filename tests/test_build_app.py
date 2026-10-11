import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from scripts.build_app import prepare_bundle, verify_apk, verify_reviewed_snapshot

class BuildBundleTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.cache = self.root / 'cache'
        (self.cache / 'resources').mkdir(parents=True)
        self.data = b'original content'
        self.track = dict(id='audio-1', packageId='pack-1', sourceMember='book/Track_1.mp3', bytes=len(self.data), sha256=hashlib.sha256(self.data).hexdigest())
        (self.cache / 'resources/audio-1.mp3').write_bytes(self.data)
        self.catalog = dict(schemaVersion=2, books=[dict(id='one', title='One', tracks=[self.track], documents=[], packages=[])])

    def test_default_bundle_contains_no_content_even_after_selected_build(self):
        out = self.root / 'assets'
        prepare_bundle(self.catalog, ['one'], out, self.cache)
        self.assertEqual(self.data, (out / 'content/audio-1.mp3').read_bytes())
        prepare_bundle(self.catalog, [], out, self.cache)
        self.assertFalse((out / 'content').exists())
        self.assertEqual({}, json.loads((out / 'bundled.json').read_text())['resources'])

    def test_unknown_selection_rejected_before_downloading(self):
        with self.assertRaisesRegex(ValueError, 'Unknown book'):
            prepare_bundle(self.catalog, ['missing'], self.root / 'assets', self.cache)

    def test_oversized_selection_rejected_before_staging_or_downloading(self):
        self.track['bytes'] = 4 * 1024**3
        out = self.root / 'assets'
        out.mkdir()
        (out / 'existing').write_text('preserve')
        with self.assertRaisesRegex(ValueError, 'APK size limit'):
            prepare_bundle(self.catalog, ['one'], out, self.cache)
        self.assertEqual('preserve', (out / 'existing').read_text())

    def test_corrupt_cached_content_is_not_packaged(self):
        (self.cache / 'resources/audio-1.mp3').write_bytes(b'corrupt')
        with self.assertRaisesRegex(ValueError, 'checksum|content|source'):
            prepare_bundle(self.catalog, ['one'], self.root / 'assets', self.cache)

    def test_apk_verification_rejects_unselected_payload(self):
        import zipfile
        apk = self.root / 'app.apk'
        with zipfile.ZipFile(apk, 'w') as out:
            out.writestr('assets/catalog.json', json.dumps(self.catalog))
            out.writestr('assets/content/audio-1.mp3', self.data)
        with self.assertRaisesRegex(ValueError, 'payload|content'):
            verify_apk(apk, self.catalog, {})

    def test_default_apk_rejects_pdf_outside_content_directory(self):
        import zipfile
        apk = self.root / 'app.apk'
        with zipfile.ZipFile(apk, 'w') as out:
            out.writestr('assets/catalog.json', json.dumps(self.catalog))
            out.writestr('assets/old-vocabulary.pdf', self.data)
        with self.assertRaisesRegex(ValueError, 'payload|content'):
            verify_apk(apk, self.catalog, {})

    def test_apk_verification_rejects_unused_archive_payload(self):
        import zipfile
        apk = self.root / 'app.apk'
        apk.write_bytes(b'discarded' * 1024**2)
        with zipfile.ZipFile(apk, 'a') as out:
            out.writestr('assets/catalog.json', json.dumps(self.catalog))
        with self.assertRaisesRegex(ValueError, 'unused|stale'):
            verify_apk(apk, self.catalog, {})

    def test_build_rejects_catalog_changed_after_independent_review(self):
        catalog = self.root / 'catalog.json'
        catalog.write_text(json.dumps(self.catalog))
        review = self.root / 'review-manifest.json'
        review.write_text(json.dumps(dict(schemaVersion=1, catalogSha256='0' * 64, annotations={})))
        with self.assertRaisesRegex(ValueError, 'review|snapshot'):
            verify_reviewed_snapshot(catalog, review)
