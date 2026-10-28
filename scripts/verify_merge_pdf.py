"""Independently verify the controlled 50-file merge fixture pulled from instrumentation.

This reads only a supplied synthetic evidence folder (merged.pdf, inputs/, expected-pages.csv).
It does not search personal documents or connect to a phone.
"""
import argparse
import csv
import hashlib
import json
import pathlib
import sys

import fitz
from pypdf import PdfReader


def geometry(page):
    media, crop = page.mediabox, page.cropbox
    return [float(media.left), float(media.bottom), float(media.width), float(media.height),
            float(crop.left), float(crop.bottom), float(crop.width), float(crop.height),
            int(page.get('/Rotate', 0)) % 360]


def content_hash(page):
    contents = page.get_contents()
    return hashlib.sha256(contents.get_data() if contents is not None else b'').hexdigest()


def verify(folder):
    merged_file = folder / 'merged.pdf'
    with (folder / 'expected-pages.csv').open(encoding='utf-8', newline='') as source:
        expected = list(csv.DictReader(source))
    assert expected, 'No expected pages were supplied'
    output = PdfReader(merged_file, strict=True)
    assert not output.is_encrypted and len(output.pages) == len(expected)
    sources = sorted((folder / 'inputs').glob('source-*.pdf'))
    assert sources, 'Controlled source fixtures are missing'
    readers = [PdfReader(path, strict=True) for path in sources]
    original_pages = [(path, index, page) for path, reader in zip(sources, readers)
                      for index, page in enumerate(reader.pages)]
    assert len(original_pages) == len(expected), 'Input/output page counts differ'
    labels, hashes = [], []
    for index, (row, page, (_, _, original)) in enumerate(zip(expected, output.pages, original_pages), start=1):
        assert int(row['page']) == index, f'Expected CSV order failed at {index}'
        label = row['label']
        assert label and label not in labels, f'Duplicate expected label on page {index}'
        labels.append(label)
        assert label in (original.extract_text() or ''), f'Input text differs on page {index}'
        assert label in (page.extract_text() or ''), f'Selectable text/order differs on page {index}'
        expected_geometry = [float(row[name]) for name in (
            'media_llx', 'media_lly', 'media_width', 'media_height',
            'crop_llx', 'crop_lly', 'crop_width', 'crop_height', 'rotation')]
        for actual in (geometry(original), geometry(page)):
            assert all(abs(a - b) <= 0.02 for a, b in zip(actual, expected_geometry)), f'Page boxes/rotation differ on page {index}'
        original_hash = content_hash(original)
        assert content_hash(page) == original_hash, f'Decoded page operators differ on page {index}'
        hashes.append(original_hash)
    previews = folder / 'independent-previews'
    previews.mkdir(exist_ok=True)
    renders = []
    with fitz.open(merged_file) as document:
        assert not document.is_repaired, 'MuPDF had to repair the merged document'
        for page_index in sorted({0, len(expected) // 2, len(expected) - 1}):
            source_path, source_index, _ = original_pages[page_index]
            with fitz.open(source_path) as original:
                before = original[source_index].get_pixmap(matrix=fitz.Matrix(1, 1), alpha=False)
                after = document[page_index].get_pixmap(matrix=fitz.Matrix(1, 1), alpha=False)
                assert (before.width, before.height, before.n) == (after.width, after.height, after.n)
                assert before.samples == after.samples, f'Independent raster comparison differs on page {page_index + 1}'
                drawings = len(document[page_index].get_drawings())
                assert drawings > 0, f'Vector sample missing on page {page_index + 1}'
                path = previews / f'merged-page-{page_index + 1:04d}.png'
                after.save(path)
                renders.append({'page': page_index + 1, 'pixelsMatchOriginal': True,
                                'vectorDrawings': drawings, 'preview': str(path)})
    return {'kind': 'independent-synthetic-pdf-merge-validation', 'passed': True,
            'files': len(sources), 'pages': len(expected), 'strictPypdf': True,
            'allPageTextOrder': True, 'allMediaCropBoxesAndRotation': True,
            'allDecodedPageContentStreamsMatch': True, 'mupdfRepaired': False,
            'renderSamples': renders, 'bytes': merged_file.stat().st_size,
            'sha256': hashlib.file_digest(merged_file.open('rb'), 'sha256').hexdigest(),
            'scope': 'Controlled synthetic fixtures only; full text/geometry/operator checks and three raster comparisons. '
                     'Not certification of every PDF feature, arbitrary large inputs, background execution or 4-GB devices.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('evidence', type=pathlib.Path)
    parser.add_argument('--report', type=pathlib.Path)
    args = parser.parse_args()
    destination = args.report or args.evidence / 'independent-validation.json'
    try:
        result = verify(args.evidence)
    except Exception as failure:
        result = {'kind': 'independent-synthetic-pdf-merge-validation', 'passed': False,
                  'error': f'{type(failure).__name__}: {failure}'}
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    sys.exit(main())
