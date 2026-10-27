#!/usr/bin/env python3
"""Independently validate every numbered page from sample_version=2 device batches.

Requires host-only pypdf, Pillow and PyMuPDF. No Android application dependency is added.
Each source is a synthetic 4000x3000 JPEG with nine MSB-first black/white cells,
encoding page 1..500. This checks PDF structure, image order, page size, and selected
independent renders. It does not certify real camera samples or runtime performance.
"""
from __future__ import annotations

import argparse
import io
import json
import statistics
import sys
from pathlib import Path


def decode_barcode(image) -> int:
    """Read sample-v2 cells from an image scaled uniformly from the 4000x3000 source."""
    rgb = image.convert("RGB")
    value = 0
    for bit in range(9):
        x = round((1200 + bit * 200) / 4000 * rgb.width)
        y = round(2350 / 3000 * rgb.height)
        radius = max(1, min(rgb.width, rgb.height) // 400)
        crop = rgb.crop((max(0, x - radius), max(0, y - radius),
                         min(rgb.width, x + radius + 1), min(rgb.height, y + radius + 1)))
        luminance = statistics.median(sum(pixel) / 3 for pixel in crop.getdata())
        if 64 < luminance < 192:
            raise ValueError(f"Ambiguous barcode cell {bit + 1}: luminance={luminance:.1f}")
        value = (value << 1) | int(luminance <= 64)
    return value


def marker_color(number: int) -> tuple[int, int, int]:
    return (40 + number * 37 % 160, 40 + number * 61 % 160, 40 + number * 83 % 160)


def validate(path: Path, expected_pages: int, render_dir: Path | None) -> dict:
    import fitz
    from PIL import Image
    from pypdf import PdfReader

    reader = PdfReader(str(path), strict=True)
    count = len(reader.pages)
    if count != expected_pages or not 1 <= count <= 500:
        raise ValueError(f"Expected {expected_pages} pages, found {count}")
    decoded = []
    for index, page in enumerate(reader.pages):
        width, height = float(page.mediabox.width), float(page.mediabox.height)
        if abs(width - 841.88976) > 0.02 or abs(height - 595.27559) > 0.02:
            raise ValueError(f"Page {index + 1}: not landscape A4 ({width}, {height})")
        xobjects = page["/Resources"]["/XObject"]
        images = [value.get_object() for value in xobjects.values()
                  if value.get_object().get("/Subtype") == "/Image"]
        if len(images) != 1:
            raise ValueError(f"Page {index + 1}: expected one raster image, found {len(images)}")
        image_object = images[0]
        if image_object.get("/Filter") != "/DCTDecode":
            raise ValueError(f"Page {index + 1}: expected JPEG /DCTDecode")
        with Image.open(io.BytesIO(image_object.get_data())) as image:
            if image.width != image_object["/Width"] or image.height != image_object["/Height"]:
                raise ValueError(f"Page {index + 1}: JPEG and PDF dimensions differ")
            if abs(image.width / image.height - 4 / 3) > 0.01:
                raise ValueError(f"Page {index + 1}: unexpected source image aspect ratio")
            number = decode_barcode(image)
        if number != index + 1:
            raise ValueError(f"Page {index + 1}: barcode encodes {number}, expected {index + 1}")
        decoded.append(number)

    selected = sorted({0, count // 2, count - 1})
    renders = []
    with fitz.open(str(path)) as document:
        if document.is_repaired:
            raise ValueError("MuPDF repaired this PDF")
        if document.page_count != count:
            raise ValueError("Independent readers disagree on page count")
        for index in selected:
            page = document.load_page(index)
            pixmap = page.get_pixmap(matrix=fitz.Matrix(0.8, 0.8), alpha=False)
            actual = pixmap.pixel(pixmap.width // 2, pixmap.height // 2)[:3]
            expected = marker_color(index + 1)
            if any(abs(a - b) >= 15 for a, b in zip(actual, expected)):
                raise ValueError(f"Rendered page {index + 1}: center marker {actual}, expected {expected}")
            rendered = {"page": index + 1, "centerRgb": list(actual), "expectedRgb": list(expected)}
            if render_dir is not None:
                render_dir.mkdir(parents=True, exist_ok=True)
                target = render_dir / f"{path.stem}-page-{index + 1:03}.png"
                pixmap.save(str(target))
                rendered["preview"] = str(target.resolve())
            renders.append(rendered)
    return {"file": str(path.resolve()), "passed": True, "pages": count,
            "checkedPages": count, "barcodeVersion": 2, "allBarcodesInOrder": True,
            "firstBarcode": decoded[0], "lastBarcode": decoded[-1],
            "landscapeA4EveryPage": True, "pypdfStrict": True, "mupdfRepaired": False,
            "bytes": path.stat().st_size, "independentRenders": renders}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("files", nargs="+", type=Path)
    parser.add_argument("--expected-pages", type=int, default=500)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--render-dir", type=Path)
    args = parser.parse_args()
    passed, failed = [], []
    for path in args.files:
        try:
            result = validate(path, args.expected_pages, args.render_dir)
            passed.append(result)
            print(f"PASS {path.name}: {result['checkedPages']} page barcodes, A4 sizes and selected renders checked")
        except Exception as error:
            failed.append({"file": str(path), "passed": False, "error": str(error)})
            print(f"FAIL {path.name}: {error}", file=sys.stderr)
    report = {"kind": "independent-device-synthetic-pdf-validation", "passed": passed,
              "failed": failed, "limitation": "Synthetic sample-v2 JPEGs only. Does not certify real camera samples, runtime peaks, UI/SAF or background behavior."}
    if args.report is not None:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
