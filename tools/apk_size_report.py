#!/usr/bin/env python3
"""
Solus APK Size Report Tool

Analyzes an APK (ZIP structure), categorizing entries into:
- Native libraries (lib/arm64-v8a/...)
- DEX files (*.dex)
- Bundled emoji font (assets/NotoColorEmojiCompat.ttf)
- PDFBox / FontBox assets
- Bouncy Castle resources
- Remaining entries

Optionally compares against an earlier baseline JSON report,
enforces size budget (--max-apk-bytes), and verifies optimization criteria (--verify-optimized).
"""

import argparse
import hashlib
import json
from pathlib import Path
import sys
import zipfile

REQUIRED_NATIVE_LIBRARIES = [
    "libandroidx.graphics.path.so",
    # LiteRT-LM 0.18.0's Android AAR ships its runtime in this JNI library;
    # it does not include the older separate LiteRT/CL-GL shared libraries.
    "liblitertlm_jni.so",
    "libllm_inference_engine_jni.so",
    "libsolus_llama.so",
]

ALLOWED_ABI = "arm64-v8a"


def format_bytes(num_bytes: int) -> str:
    mb = num_bytes / 1_000_000.0
    mib = num_bytes / (1024.0 * 1024.0)
    return f"{num_bytes:,} bytes ({mb:.2f} MB, {mib:.2f} MiB)"


def get_compression_name(method_id: int) -> str:
    if method_id == zipfile.ZIP_STORED:
        return "stored"
    elif method_id == zipfile.ZIP_DEFLATED:
        return "deflated"
    elif method_id == zipfile.ZIP_BZIP2:
        return "bzip2"
    elif method_id == zipfile.ZIP_LZMA:
        return "lzma"
    return f"unknown({method_id})"


def categorize_entry(filename: str) -> str:
    fn_lower = filename.lower()
    if filename.startswith("lib/"):
        return "native_libraries"
    elif filename.endswith(".dex"):
        return "dex"
    elif "notocoloremojicompat" in fn_lower or ("emoji" in fn_lower and fn_lower.endswith(".ttf")):
        return "emoji"
    elif "com/tom_roush" in filename or "pdfbox" in fn_lower or "fontbox" in fn_lower:
        return "pdfbox_assets"
    elif "org/bouncycastle" in filename or "bouncycastle" in fn_lower:
        return "bouncy_castle_resources"
    return "remaining"


def compute_sha256(filepath: Path) -> str:
    hasher = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            hasher.update(chunk)
    return hasher.hexdigest()


def analyze_apk(apk_path: Path) -> dict:
    total_bytes = apk_path.stat().st_size
    sha256_hash = compute_sha256(apk_path)

    category_data = {
        "native_libraries": {"original_bytes": 0, "compressed_bytes": 0, "count": 0},
        "dex": {"original_bytes": 0, "compressed_bytes": 0, "count": 0},
        "emoji": {"original_bytes": 0, "compressed_bytes": 0, "count": 0},
        "pdfbox_assets": {"original_bytes": 0, "compressed_bytes": 0, "count": 0},
        "bouncy_castle_resources": {"original_bytes": 0, "compressed_bytes": 0, "count": 0},
        "remaining": {"original_bytes": 0, "compressed_bytes": 0, "count": 0},
    }

    entries = []
    packaged_abis = set()
    packaged_native_libs = set()
    uncompressed_dex = []
    emoji_entries = []

    with zipfile.ZipFile(apk_path, "r") as z:
        for info in z.infolist():
            cat = categorize_entry(info.filename)
            category_data[cat]["original_bytes"] += info.file_size
            category_data[cat]["compressed_bytes"] += info.compress_size
            category_data[cat]["count"] += 1

            comp_name = get_compression_name(info.compress_type)
            entry_dict = {
                "path": info.filename,
                "original_bytes": info.file_size,
                "compressed_bytes": info.compress_size,
                "compression_method": comp_name,
                "category": cat,
            }
            entries.append(entry_dict)

            # Verification tracking
            if info.filename.startswith("lib/"):
                parts = info.filename.split("/")
                if len(parts) >= 2 and parts[1]:
                    abi = parts[1]
                    packaged_abis.add(abi)
                    lib_name = parts[-1]
                    packaged_native_libs.add(lib_name)

            if info.filename.endswith(".dex") and info.compress_type == zipfile.ZIP_STORED:
                uncompressed_dex.append(info.filename)

            if cat == "emoji":
                emoji_entries.append(info.filename)

    # Sort entries by compressed size descending
    entries_by_size = sorted(entries, key=lambda x: x["compressed_bytes"], reverse=True)
    top_30 = entries_by_size[:30]

    report = {
        "apk_filename": apk_path.name,
        "apk_sha256": sha256_hash,
        "apk_total_bytes": total_bytes,
        "apk_total_mb": round(total_bytes / 1_000_000.0, 4),
        "apk_total_mib": round(total_bytes / (1024.0 * 1024.0), 4),
        "categories": {},
        "top_30_largest_entries": top_30,
        "entries": entries,
        "metadata": {
            "packaged_abis": sorted(list(packaged_abis)),
            "packaged_native_libs": sorted(list(packaged_native_libs)),
            "uncompressed_dex": uncompressed_dex,
            "emoji_entries": emoji_entries,
        },
    }

    for cat_name, stats in category_data.items():
        c_bytes = stats["compressed_bytes"]
        o_bytes = stats["original_bytes"]
        report["categories"][cat_name] = {
            "count": stats["count"],
            "original_bytes": o_bytes,
            "compressed_bytes": c_bytes,
            "compressed_mb": round(c_bytes / 1_000_000.0, 4),
            "compressed_mib": round(c_bytes / (1024.0 * 1024.0), 4),
        }

    return report


def compare_reports(current: dict, baseline: dict) -> dict:
    total_curr = current["apk_total_bytes"]
    total_base = baseline["apk_total_bytes"]
    total_diff = total_curr - total_base
    pct_diff = (total_diff / total_base * 100.0) if total_base else 0.0

    curr_entries = {e["path"]: e for e in current["entries"]}
    base_entries = {e["path"]: e for e in baseline.get("entries", [])}

    added = []
    removed = []
    modified = []

    for path, curr_e in curr_entries.items():
        if path not in base_entries:
            added.append(curr_e)
        else:
            base_e = base_entries[path]
            diff_compressed = curr_e["compressed_bytes"] - base_e["compressed_bytes"]
            diff_original = curr_e["original_bytes"] - base_e["original_bytes"]
            if diff_compressed != 0 or diff_original != 0:
                modified.append({
                    "path": path,
                    "baseline_compressed": base_e["compressed_bytes"],
                    "current_compressed": curr_e["compressed_bytes"],
                    "compressed_diff": diff_compressed,
                    "baseline_original": base_e["original_bytes"],
                    "current_original": curr_e["original_bytes"],
                    "original_diff": diff_original,
                })

    for path, base_e in base_entries.items():
        if path not in curr_entries:
            removed.append(base_e)

    cat_diffs = {}
    for cat in current["categories"]:
        curr_c = current["categories"][cat]["compressed_bytes"]
        base_c = baseline.get("categories", {}).get(cat, {}).get("compressed_bytes", 0)
        diff_c = curr_c - base_c
        pct = (diff_c / base_c * 100.0) if base_c else 0.0
        cat_diffs[cat] = {
            "baseline_compressed_bytes": base_c,
            "current_compressed_bytes": curr_c,
            "diff_bytes": diff_c,
            "diff_percent": round(pct, 2),
        }

    return {
        "baseline_apk": baseline.get("apk_filename", "unknown"),
        "baseline_total_bytes": total_base,
        "current_total_bytes": total_curr,
        "total_diff_bytes": total_diff,
        "total_diff_percent": round(pct_diff, 2),
        "category_diffs": cat_diffs,
        "added_entries_count": len(added),
        "removed_entries_count": len(removed),
        "modified_entries_count": len(modified),
        "added_entries": added,
        "removed_entries": removed,
        "modified_entries": sorted(modified, key=lambda x: abs(x["compressed_diff"]), reverse=True),
    }


def verify_optimized(report: dict, max_apk_bytes: int | None = None) -> list[str]:
    errors = []
    meta = report["metadata"]

    # 1. ABI check
    abis = meta.get("packaged_abis", [])
    for abi in abis:
        if abi != ALLOWED_ABI:
            errors.append(f"Disallowed native ABI packaged: '{abi}' (only '{ALLOWED_ABI}' permitted).")

    # 2. Bundled emoji font check
    emoji_entries = meta.get("emoji_entries", [])
    if emoji_entries:
        errors.append(f"Bundled emoji font present: {emoji_entries}")

    # 3. Uncompressed DEX check
    uncompressed_dex = meta.get("uncompressed_dex", [])
    if uncompressed_dex:
        errors.append(f"DEX stored without compression: {uncompressed_dex}")

    # 4. Required native libraries check
    packaged_libs = set(meta.get("packaged_native_libs", []))
    for req_lib in REQUIRED_NATIVE_LIBRARIES:
        if req_lib not in packaged_libs:
            errors.append(f"Missing required native library: '{req_lib}'")

    # 5. Max APK bytes check
    total_bytes = report["apk_total_bytes"]
    if max_apk_bytes is not None and total_bytes > max_apk_bytes:
        errors.append(
            f"APK size {total_bytes:,} bytes ({total_bytes/1_000_000.0:.2f} MB, {total_bytes/(1024*1024):.2f} MiB) "
            f"exceeds maximum allowed {max_apk_bytes:,} bytes "
            f"({max_apk_bytes/1_000_000.0:.2f} MB, {max_apk_bytes/(1024*1024):.2f} MiB)."
        )

    return errors


def print_report(report: dict, comparison: dict | None = None):
    print("=" * 80)
    print(f"APK Size Report: {report['apk_filename']}")
    print(f"SHA-256: {report['apk_sha256']}")
    print(f"Total Size: {format_bytes(report['apk_total_bytes'])}")
    print("-" * 80)
    print(f"{'Category':<26} {'Count':<6} {'Compressed Size':<32} {'Original Size':<20}")
    print("-" * 80)
    for cat, stats in report["categories"].items():
        c_str = f"{stats['compressed_bytes']:,} B ({stats['compressed_mb']:.2f} MB / {stats['compressed_mib']:.2f} MiB)"
        o_str = f"{stats['original_bytes']:,} B"
        print(f"{cat:<26} {stats['count']:<6} {c_str:<32} {o_str:<20}")
    print("-" * 80)

    if comparison:
        print("\n" + "=" * 80)
        print("Comparison with Baseline:")
        print(f"Baseline APK: {comparison['baseline_apk']}")
        print(f"Baseline Size: {format_bytes(comparison['baseline_total_bytes'])}")
        print(f"Current Size:  {format_bytes(comparison['current_total_bytes'])}")
        diff_b = comparison['total_diff_bytes']
        diff_pct = comparison['total_diff_percent']
        sign = "+" if diff_b > 0 else ""
        print(f"Change:        {sign}{diff_b:,} bytes ({sign}{diff_b/1_000_000.0:.2f} MB / {sign}{diff_b/(1024*1024):.2f} MiB) [{sign}{diff_pct:.2f}%]")
        print("-" * 80)
        print(f"{'Category':<26} {'Baseline B':<14} {'Current B':<14} {'Diff B':<14} {'Diff %':<10}")
        print("-" * 80)
        for cat, c_stats in comparison["category_diffs"].items():
            b_b = c_stats["baseline_compressed_bytes"]
            cur_b = c_stats["current_compressed_bytes"]
            df_b = c_stats["diff_bytes"]
            df_pct = c_stats["diff_percent"]
            s = "+" if df_b > 0 else ""
            print(f"{cat:<26} {b_b:<14,} {cur_b:<14,} {s}{df_b:<13,} {s}{df_pct:.2f}%")
        print("-" * 80)
        print(f"Entries Added: {comparison['added_entries_count']}, Removed: {comparison['removed_entries_count']}, Modified: {comparison['modified_entries_count']}")

    print("\n" + "=" * 80)
    print("Top 30 Largest Entries (by compressed size):")
    print("-" * 80)
    for i, entry in enumerate(report["top_30_largest_entries"], 1):
        c_str = f"{entry['compressed_bytes']:,} B ({entry['compressed_bytes']/1_000_000.0:.2f} MB)"
        print(f"{i:2d}. {entry['path']:<55} {c_str:<20} [{entry['compression_method']}]")
    print("=" * 80)


def main():
    parser = argparse.ArgumentParser(description="Analyze and verify Solus APK size.")
    parser.add_argument("--apk", type=Path, required=True, help="Path to APK to analyze.")
    parser.add_argument("--output", type=Path, help="Path to write JSON report.")
    parser.add_argument("--baseline", type=Path, help="Path to baseline JSON report.")
    parser.add_argument("--max-apk-bytes", type=int, help="Maximum allowed APK size in bytes.")
    parser.add_argument("--verify-optimized", action="store_true", help="Enforce optimization verification rules.")

    args = parser.parse_args()

    apk_target = args.apk
    if apk_target.is_dir():
        metadata_file = apk_target / "output-metadata.json"
        if metadata_file.exists():
            with open(metadata_file, "r", encoding="utf-8") as f:
                data = json.load(f)
            apk_target = apk_target / data["elements"][0]["outputFile"]
    elif apk_target.name == "output-metadata.json":
        with open(apk_target, "r", encoding="utf-8") as f:
            data = json.load(f)
        apk_target = apk_target.parent / data["elements"][0]["outputFile"]

    if not apk_target.exists():
        print(f"Error: APK file not found at {apk_target}", file=sys.stderr)
        sys.exit(1)

    report = analyze_apk(apk_target)
    comparison = None

    if args.baseline:
        if not args.baseline.exists():
            print(f"Error: Baseline report not found at {args.baseline}", file=sys.stderr)
            sys.exit(1)
        with open(args.baseline, "r", encoding="utf-8") as f:
            baseline_data = json.load(f)
        comparison = compare_reports(report, baseline_data)
        report["comparison"] = comparison

    print_report(report, comparison)

    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with open(args.output, "w", encoding="utf-8") as f:
            json.dump(report, f, indent=2)
        print(f"\nJSON report written to {args.output}")

    if args.verify_optimized:
        errors = verify_optimized(report, args.max_apk_bytes)
        if errors:
            print("\n" + "!" * 80, file=sys.stderr)
            print("OPTIMIZATION VERIFICATION FAILED:", file=sys.stderr)
            for err in errors:
                print(f" - {err}", file=sys.stderr)
            print("!" * 80, file=sys.stderr)
            sys.exit(1)
        else:
            print("\n[OK] Optimization verification PASSED: all criteria met.")


if __name__ == "__main__":
    main()
