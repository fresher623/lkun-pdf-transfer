#!/usr/bin/env python3
"""Independently inspect engine output with pypdf and MuPDF; no network or private samples.

Example:
  python scripts/verify_pdf.py build/engine-probe/host-100.pdf \
      build/engine-probe/host-300.pdf build/engine-probe/host-500.pdf \
      --expect-a4 --expect-numbered --render-dir build/engine-probe/previews \
      --report build/engine-probe/independent-validation.json

Install host-only tools with: python -m pip install pypdf pymupdf
They are not bundled in the Android app. MuPDF/PyMuPDF has AGPL/commercial licensing;
this optional validation script uses a separately installed host tool and imports no
MuPDF code into the app, APK or PDF engine.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path


def validate(path: Path, args: argparse.Namespace) -> dict:
    import fitz
    from pypdf import PdfReader

    expected = args.expected_pages
    if expected is None:
        match = re.fullmatch(r"host-(\d+)\.pdf", path.name)
        expected = int(match.group(1)) if match else None

    reader = PdfReader(str(path), strict=True)
    count = len(reader.pages)
    if expected is not None and count != expected:
        raise ValueError(f"{path.name}: expected {expected} pages, got {count}")
    if not 1 <= count <= 500:
        raise ValueError(f"{path.name}: invalid V1 page count {count}")
    dimensions = []
    image_hashes = []
    for index, page in enumerate(reader.pages):
        width, height = float(page.mediabox.width), float(page.mediabox.height)
        if width <= 0 or height <= 0:
            raise ValueError(f"{path.name} page {index + 1}: nonpositive MediaBox")
        if args.expect_a4 and (abs(width - 595.27559) > 0.02 or abs(height - 841.88976) > 0.02):
            raise ValueError(f"{path.name} page {index + 1}: not portrait A4 ({width}, {height})")
        if args.expect_numbered and page.extract_text().strip() != str(index + 1):
            raise ValueError(f"{path.name} page {index + 1}: footer does not match page order")
        xobjects = page["/Resources"]["/XObject"]
        images = [value.get_object() for value in xobjects.values()
                  if value.get_object().get("/Subtype") == "/Image"]
        if len(images) != 1:
            raise ValueError(f"{path.name} page {index + 1}: expected exactly one raster image")
        image = images[0]
        if image["/Width"] <= 0 or image["/Height"] <= 0:
            raise ValueError(f"{path.name} page {index + 1}: invalid image size")
        image_hashes.append(hashlib.sha256(image.get_data()).hexdigest())
        dimensions.append([width, height])

    selected = sorted({0, count // 2, count - 1})
    previews = []
    with fitz.open(str(path)) as document:
        if document.is_repaired:
            raise ValueError(f"{path.name}: MuPDF had to repair the document")
        if document.page_count != count:
            raise ValueError(f"{path.name}: independent readers disagree on page count")
        for index in selected:
            page = document.load_page(index)
            pixmap = page.get_pixmap(matrix=fitz.Matrix(0.8, 0.8), alpha=False)
            if pixmap.width <= 0 or pixmap.height <= 0:
                raise ValueError(f"{path.name} page {index + 1}: rendering failed")
            if args.render_dir:
                directory = Path(args.render_dir)
                directory.mkdir(parents=True, exist_ok=True)
                target = directory / f"{path.stem}-page-{index + 1:03}.png"
                pixmap.save(str(target))
                previews.append(str(target.resolve()))
    unique_images = len(set(image_hashes))
    if args.expect_numbered and unique_images != count:
        raise ValueError(f"{path.name}: numbered probe unexpectedly repeated JPEG payloads")
    return {
        "file": str(path.resolve()), "pages": count, "bytes": path.stat().st_size,
        "firstPagePoints": dimensions[0], "lastPagePoints": dimensions[-1],
        "uniqueImagePayloads": unique_images,
        "renderedPages": [index + 1 for index in selected], "previews": previews,
        "pypdfStrict": True, "mupdfRepaired": False,
        "pageNumberSequenceChecked": args.expect_numbered,
        "androidDevicePerformance": "待验证",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("files", nargs="+", type=Path)
    parser.add_argument("--expected-pages", type=int)
    parser.add_argument("--expect-a4", action="store_true")
    parser.add_argument("--expect-numbered", action="store_true")
    parser.add_argument("--render-dir")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    results = []
    failures = []
    for path in args.files:
        try:
            result = validate(path, args)
            results.append(result)
            print(f"PASS {path.name}: {result['pages']} pages; strict parse and first/middle/last render succeeded")
        except Exception as failure:
            failures.append({"file": str(path), "error": str(failure)})
            print(f"FAIL {path.name}: {failure}", file=sys.stderr)
    report = {"kind": "independent-host-PDF-validation", "passed": results, "failed": failures,
              "limitation": "Host structure/render checks do not validate Android decoding, EXIF, background execution, memory or 12MP performance."}
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
