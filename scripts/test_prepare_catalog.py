"""Behavioral checks for original-content catalog preparation."""
import io
import json
import tempfile
import unittest
import zipfile
from unittest.mock import patch
from pathlib import Path

import prepare_catalog as catalog
from validate_catalog import validate_review


class CatalogPreparationTests(unittest.TestCase):
    def test_source_inventory_preserves_original_scope_and_language(self):
        feed = json.loads(catalog.DEFAULT_FEED.read_text())
        inventory = catalog.source_inventory(feed, {})
        self.assertEqual(len(inventory['books']), 53)
        self.assertEqual(sum(len(b['packages']) for b in inventory['books']), 209)
        documents = [d for b in inventory['books'] for d in b['documents']]
        self.assertEqual(len(documents), 44)
        self.assertEqual(sum(d['language'] == 'de' for d in documents), 27)
        self.assertEqual(sum(d['language'] == 'en' for d in documents), 17)
        self.assertEqual(len({b['id'] for b in inventory['books']}), 53)
        self.assertIn('einfach-weiter-b2', {b['id'] for b in inventory['books']})

    def test_registry_ids_survive_reordering(self):
        registry = {}
        first = catalog.registry_id(registry, 'book:/one', 'one')
        catalog.registry_id(registry, 'book:/two', 'two')
        self.assertEqual(catalog.registry_id(registry, 'book:/one', 'renamed'), first)

    def test_nested_and_flat_audio_sort_numerically(self):
        raw = io.BytesIO()
        with zipfile.ZipFile(raw, 'w') as archive:
            archive.writestr('nested/Example_Track_10.mp3', b'ten')
            archive.writestr('Example_Track_2.mp3', b'two')
            archive.writestr('__MACOSX/._Example.mp3', b'ignore')
        raw.seek(0)
        with zipfile.ZipFile(raw) as archive:
            members = catalog.audio_members(archive)
            self.assertEqual([x.filename for x in members], ['Example_Track_2.mp3', 'nested/Example_Track_10.mp3'])
        self.assertEqual(catalog.track_number('nested/001_book.mp3', 99), 1)
        self.assertEqual(catalog.track_number('Example_Track_10.mp3', 99), 10)

    def test_original_track_numbers_survive_suffixes_and_lesson_numbers(self):
        examples = {'EINFACH_GUT_A1-2_Track_16_LANGSAM.mp3': 16,
                    '076 Einfach gut Alpha!.mp3': 76,
                    'Track 2_Lektionstest_3.mp3': 2,
                    'Track_020_neu.mp3': 20}
        for filename, expected in examples.items():
            self.assertEqual(catalog.track_number(filename, 99), expected)

    def test_zip_paths_cannot_escape_content_cache(self):
        for path in ['../escape.mp3', '/escape.mp3', 'folder\\escape.mp3']:
            raw = io.BytesIO()
            with zipfile.ZipFile(raw, 'w') as archive:
                archive.writestr(path, b'bad')
            raw.seek(0)
            with zipfile.ZipFile(raw) as archive:
                with self.assertRaises(ValueError):
                    catalog.audio_members(archive)

    def test_pdf_classification_does_not_guess_unrecognized_languages(self):
        self.assertEqual(catalog.document_language({'name': 'Wortschatzliste Englisch', 'originalFileName': 'words_Englisch.pdf'}), 'en')
        self.assertEqual(catalog.document_language({'name': 'Hörtexte Lektion 2', 'originalFileName': '02_Hörtexte_Lektion_2.pdf'}), 'de')
        self.assertIsNone(catalog.document_language({'name': 'Wortschatzliste Arabisch', 'originalFileName': 'words_Arabisch.pdf'}))
        self.assertIsNone(catalog.document_language({'name': 'New list', 'originalFileName': 'unknown.pdf'}))

    def test_reviewed_overrides_require_known_ids_and_evidence(self):
        book = {'id': 'example', 'tracks': [{'id': 'track-1', 'title': 'Raw', 'references': []}], 'documents': []}
        reviewed = {'bookId': 'example', 'tracks': {'track-1': {'title': 'Lektion 2', 'references': [{'section': 'Kursbuch', 'chapter': 2, 'evidence': 'Opening announcement at 00:00: Lektion 2', 'status': 'verified'}]}}}
        catalog.apply_annotations(book, reviewed)
        self.assertEqual(book['tracks'][0]['title'], 'Lektion 2')
        with self.assertRaises(ValueError):
            catalog.apply_annotations(book, {'bookId': 'example', 'tracks': {'missing': {'references': []}}})
        with self.assertRaises(ValueError):
            catalog.apply_annotations(book, {'bookId': 'example', 'tracks': {'track-1': {'references': [{'section': 'Kursbuch', 'status': 'verified'}]}}})

    def test_release_review_requires_independent_author_and_explained_gaps(self):
        sha = 'a' * 64
        book = {'id': 'example', 'tracks': [{'id': 'track-1', 'sha256': sha, 'references': []}], 'documents': []}
        annotation = {'bookId': 'example', 'review': {'author': 'annotator', 'reviewer': 'reviewer',
            'reviewedAt': '2026-10-11T18:00:00Z', 'independentlyReviewedAt': '2026-10-11T19:00:00Z'},
            'tracks': {'track-1': {'references': [], 'review': {'status': 'reviewed', 'method': 'opening-announcement',
                'sourceSha256': sha, 'reason': 'Publisher introduction has no exercise announcement.'}}}, 'documents': {}, 'sources': []}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / 'scripts/catalog/annotations/example.json'
            path.parent.mkdir(parents=True)
            path.write_text(json.dumps(annotation))
            validate_review(root, book)
            del annotation['tracks']['track-1']['review']['reason']
            path.write_text(json.dumps(annotation))
            with self.assertRaises(AssertionError):
                validate_review(root, book)
            annotation['tracks']['track-1']['review']['reason'] = 'No exercise announcement.'
            annotation['review']['reviewer'] = 'annotator'
            path.write_text(json.dumps(annotation))
            with self.assertRaises(AssertionError):
                validate_review(root, book)

    def test_preparation_repairs_corrupted_extracted_resource_from_original_zip(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory) / 'content'
            analysis = Path(directory) / 'state'
            (cache / 'packages').mkdir(parents=True)
            package = {'id': 'example-pack-100', 'url': 'https://example.org/audio.zip', 'seriesId': 'example-series', 'bookId': 'example'}
            with zipfile.ZipFile(cache / 'packages/example-pack-100.zip', 'w') as archive:
                archive.writestr('Track_1.mp3', b'original-mp3')
            with patch.object(catalog, 'CACHE', cache), patch.object(catalog, 'ANALYSIS', analysis), patch.object(catalog, 'download'), patch.object(catalog, 'probe_audio', return_value=100):
                prepared = catalog.prepare_package({'id': 'example'}, package, {}, {})
                resource = cache / 'resources' / (prepared['tracks'][0]['id'] + '.mp3')
                resource.write_bytes(b'corrupted')
                catalog.prepare_package({'id': 'example'}, package, {}, {})
                self.assertEqual(resource.read_bytes(), b'original-mp3')


if __name__ == '__main__':
    unittest.main()
