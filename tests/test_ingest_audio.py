import tempfile
import unittest
import zipfile
from pathlib import Path
from scripts.ingest_audio import inspect_archive

class ArchiveTests(unittest.TestCase):
    def archive(self, names):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        path = Path(temporary.name) / 'audio.zip'
        with zipfile.ZipFile(path, 'w') as archive:
            for name in names:
                archive.writestr(name, b'original mp3 bytes')
        return path

    def test_track_number_comes_from_filename(self):
        tracks = inspect_archive(self.archive(['001_book.mp3', '063_book.mp3']))
        self.assertEqual([1, 63], [track['number'] for track in tracks])
        self.assertEqual(b'original mp3 bytes', tracks[0]['data'])

    def test_rejects_duplicate_track_numbers(self):
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            inspect_archive(self.archive(['001_a.mp3', '001_b.mp3']))

    def test_rejects_out_of_range_track(self):
        with self.assertRaisesRegex(ValueError, 'range'):
            inspect_archive(self.archive(['064_book.mp3']))

    def test_rejects_unsafe_archive_path(self):
        with self.assertRaisesRegex(ValueError, 'Unsafe'):
            inspect_archive(self.archive(['../001_book.mp3']))

    def test_ignores_metadata_files(self):
        tracks = inspect_archive(self.archive(['__MACOSX/._001_book.mp3', '001_book.mp3', 'readme.txt']))
        self.assertEqual(1, len(tracks))

if __name__ == '__main__':
    unittest.main()
