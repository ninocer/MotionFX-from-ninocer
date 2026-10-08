#!/usr/bin/env python3
"""Pack data-only JSON into a reproducible .mfxplugin. The host remains the authority for validation."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import zipfile


def package(source: Path) -> bytes:
    manifest = json.loads((source / "manifest.json").read_text(encoding="utf-8"))
    content = json.loads((source / "content.json").read_text(encoding="utf-8"))
    content_bytes = json.dumps(content, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()
    manifest["contentSha256"] = hashlib.sha256(content_bytes).hexdigest()
    manifest_bytes = json.dumps(manifest, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()
    assert len(manifest_bytes) <= 32768 and len(content_bytes) <= 131072, "Package member too large"
    result = io.BytesIO()
    with zipfile.ZipFile(result, "w", compression=zipfile.ZIP_STORED) as archive:
        for name, data in (("manifest.json", manifest_bytes), ("content.json", content_bytes)):
            archive.writestr(zipfile.ZipInfo(name, date_time=(2024, 1, 1, 0, 0, 0)), data)
    assert len(result.getvalue()) <= 262144, "Package too large"
    return result.getvalue()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--check", action="store_true", help="Fail if the committed package differs from source")
    args = parser.parse_args()
    data = package(args.source)
    if args.check:
        if not args.output.exists() or args.output.read_bytes() != data:
            raise SystemExit(f"Package out of date: {args.output}")
        print(f"Verified {args.output}")
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_bytes(data)
        print(f"Wrote {len(data)} bytes to {args.output}")
