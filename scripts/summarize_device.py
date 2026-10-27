"""Summarize app-private benchmark evidence without treating samples as true peaks.

Input is the directory produced by collect_device.py. This script never connects
to a device, runs a benchmark, or fills missing measurements with invented data.
"""
from __future__ import annotations

import argparse
import csv
import io
import json
import re
from pathlib import Path


METRICS = ("java_used_bytes", "native_alloc_bytes", "pss_kb", "rss_kb")


def read_csv(path: Path) -> tuple[dict[str, str], list[dict[str, str]]]:
    metadata: dict[str, str] = {}
    data: list[str] = []
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        if line.startswith("#"):
            for pair in line.lstrip("# ").split(";"):
                key, separator, value = pair.partition("=")
                if separator:
                    metadata[key.strip()] = value.strip()
        elif line.strip():
            data.append(line)
    return metadata, list(csv.DictReader(io.StringIO("\n".join(data))))


def optional_int(value: str | None) -> int | None:
    try:
        number = int(value) if value is not None else None
        return number if number is not None and number >= 0 else None
    except ValueError:
        return None


def summarize(path: Path, report_folder: Path) -> dict:
    metadata, summaries = read_csv(path)
    result = {"summary_file": str(path.resolve()), "metadata": metadata, "runs": []}
    for row in summaries:
        task_id = row["task_id"]
        if Path(task_id).name != task_id or any(char in task_id for char in ("/", "\\")):
            raise ValueError("Unexpected task identifier")
        expected = int(row["count"])
        measurement = report_folder / f"{task_id}-measurement.csv"
        run = {"task_id": task_id, "count": expected, "repeat": int(row["repeat"]),
               "converter_invocation": optional_int(row.get("converter_invocation")),
               "elapsed_ms": int(row["elapsed_ms"]), "file_bytes": int(row["file_bytes"]),
               "pages": int(row["pages"]), "under_5_minutes": int(row["elapsed_ms"]) <= 300000,
               "measurement_file": str(measurement.resolve()), "measurement_present": measurement.is_file()}
        if measurement.is_file():
            metric_metadata, values = read_csv(measurement)
            run["measurement_metadata"] = metric_metadata
            pages = [int(value["page"]) for value in values]
            run["sample_count"] = len(values)
            run["page_samples_complete_in_order"] = pages == list(range(1, expected + 1))
            maxima = {}
            for field in METRICS:
                measured = [optional_int(value.get(field)) for value in values]
                measured = [value for value in measured if value is not None]
                maxima[field] = max(measured) if measured else None
            run["post_page_sample_maximum"] = maxima
            heap_limit = optional_int(metric_metadata.get("heap_limit_bytes") or metadata.get("heap_limit_bytes"))
            run["heap_limit_bytes"] = heap_limit
            java_max = maxima["java_used_bytes"]
            run["sampled_java_heap_fraction"] = (java_max / heap_limit
                                                   if java_max is not None and heap_limit else None)
            run["sampled_java_below_70_percent"] = (java_max < heap_limit * 0.7
                                                     if java_max is not None and heap_limit else None)
            run["checkpoints"] = [{key: optional_int(value.get(key))
                                    for key in ("page", "elapsed_ms", *METRICS, "pdf_bytes")}
                                   for value in values if int(value["page"]) in (100, 300, 500)]
        result["runs"].append(run)
    return result


def summarize_external(path: Path) -> dict:
    """Parse optional dumpsys snapshots; preserve missing fields as unavailable."""
    fields = {"total_pss_kb": [], "total_rss_kb": [],
              "dalvik_heap_alloc_kb": [], "native_heap_alloc_kb": []}
    snapshots = 0
    invalid_records = 0
    valid_meminfo_snapshots = 0
    elapsed = []
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        if not line.strip():
            continue
        snapshots += 1
        try:
            record = json.loads(line)
        except json.JSONDecodeError:
            invalid_records += 1
            continue
        if isinstance(record.get("elapsed_seconds"), (int, float)):
            elapsed.append(record["elapsed_seconds"])
        if record.get("return_code") != 0:
            continue
        meminfo = record.get("meminfo", "")
        if not isinstance(meminfo, str):
            continue
        match = re.search(r"TOTAL PSS:\s*(\d+)", meminfo)
        if match is None:
            match = re.search(r"^\s*TOTAL\s+(\d+)\s", meminfo, re.MULTILINE)
        if match is not None:
            fields["total_pss_kb"].append(int(match.group(1)))
            valid_meminfo_snapshots += 1
        match = re.search(r"TOTAL RSS:\s*(\d+)", meminfo)
        if match is not None:
            fields["total_rss_kb"].append(int(match.group(1)))
        for heap, field in (("Native", "native_heap_alloc_kb"), ("Dalvik", "dalvik_heap_alloc_kb")):
            match = re.search(rf"^\s*{heap} Heap\s+([\d \t]+)$", meminfo, re.MULTILINE)
            if match is not None:
                columns = [int(value) for value in match.group(1).split()]
                # Native/Dalvik rows end in HeapSize, HeapAlloc, HeapFree on dumpsys versions
                # that expose heap statistics. Do not use PSS as a heap-allocation proxy.
                if len(columns) >= 7:
                    fields[field].append(columns[-2])
    return {
        "file": str(path.resolve()),
        "scope": "Entire benchmark suite, including startup/source generation; snapshots are not aligned to individual runs.",
        "sampling": "Approximately one-second external dumpsys samples; maxima are sampled, not continuous or exact peaks.",
        "snapshot_count": snapshots,
        "valid_meminfo_snapshot_count": valid_meminfo_snapshots,
        "invalid_json_record_count": invalid_records,
        "elapsed_seconds_first": min(elapsed) if elapsed else None,
        "elapsed_seconds_last": max(elapsed) if elapsed else None,
        "sample_maximum": {field: max(values) if values else None for field, values in fields.items()},
        "field_sample_counts": {field: len(values) for field, values in fields.items()},
    }


def summarize_unfinished(evidence: Path, completed_ids: set[str]) -> list[dict]:
    """Keep partial task evidence visible; a valid-looking file alone never means success."""
    unfinished = []
    for folder in sorted((evidence / "files/results").glob("benchmark-*")):
        match = re.fullmatch(r"benchmark-(\d+)-(\d+)-(\d+)", folder.name)
        if not folder.is_dir() or match is None or folder.name in completed_ids:
            continue
        measurement = folder / "measurement.csv"
        record = {"task_id": folder.name, "requested_pages": int(match.group(1)),
                  "repeat": int(match.group(2)), "status": "No completed summary row; no success claimed",
                  "measurement_present": measurement.is_file(),
                  "output_candidates": [{"name": file.name, "bytes": file.stat().st_size}
                                        for file in sorted(folder.iterdir())
                                        if file.is_file() and file.suffix in (".partial", ".pdf")]}
        if measurement.is_file():
            metadata, values = read_csv(measurement)
            record["metadata"] = metadata
            record["post_page_sample_count"] = len(values)
            record["last_written_page_sample"] = ({key: optional_int(values[-1].get(key))
                                                    for key in ("page", "elapsed_ms", *METRICS, "pdf_bytes")}
                                                   if values else None)
        unfinished.append(record)
    return unfinished


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("evidence", type=Path, help="Root returned by collect_device.py")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    report_folder = args.evidence / "files/benchmark-reports"
    candidates = sorted(report_folder.glob("summary-*.csv"))
    if not candidates:
        parser.error(f"No summary CSV found in {report_folder}")
    result = {
        "kind": "android-synthetic-12mp-benchmark-summary",
        "limitations": [
            "Synthetic numbered 4000x3000 JPEGs, not a real-camera-photo acceptance run.",
            "Direct ImageConverter calls do not exercise pickers, SAF, Compose, foreground services or screen-off behavior.",
            "Memory is sampled after each page and bitmap recycle; maxima are not full-lifecycle or continuous peaks.",
            "First/middle/last rendered marker assertions are sampled checks, not full-page-order certification.",
            "Source creation time is excluded; source IO cache is warm or unknown, not proven cold.",
            "A single high-memory phone cannot certify the required 4 GB device threshold.",
        ],
        "reports": [summarize(path, report_folder) for path in candidates],
    }
    external = args.evidence / "external-memory.jsonl"
    if external.is_file():
        result["external_memory"] = summarize_external(external)
    completed_ids = {run["task_id"] for report in result["reports"] for run in report["runs"]}
    unfinished = summarize_unfinished(args.evidence, completed_ids)
    if unfinished:
        result["tasks_without_completed_summary"] = unfinished
    output = args.output or args.evidence / "device-summary.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for report in result["reports"]:
        for run in report["runs"]:
            maxima = run.get("post_page_sample_maximum", {})
            print(f"{run['count']} pages run {run['repeat']}: {run['elapsed_ms']} ms, "
                  f"{run['file_bytes']} bytes, post-page samples: {maxima}")
    print(f"Saved {output.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
