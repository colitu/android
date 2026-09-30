#!/usr/bin/env python3
"""Create a deterministic CycloneDX SBOM from the resolved Gradle graph."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from datetime import datetime, timezone
from pathlib import Path

COORDINATE = re.compile(r"([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):([A-Za-z0-9+_.-]+)")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dependencies", required=True, type=Path)
    parser.add_argument("--artifact", required=True, type=Path)
    parser.add_argument("--native-dir", required=True, type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    dependencies: set[tuple[str, str, str]] = set()
    for line in args.dependencies.read_text(encoding="utf-8", errors="replace").splitlines():
        match = COORDINATE.search(line)
        if match:
            dependencies.add(match.groups())

    components: list[dict[str, object]] = []
    for group, name, version in sorted(dependencies):
        purl = f"pkg:maven/{group}/{name}@{version}"
        components.append({
            "type": "library",
            "bom-ref": purl,
            "group": group,
            "name": name,
            "version": version,
            "purl": purl,
        })

    for path in sorted(p for p in args.native_dir.rglob("*") if p.is_file() and p.suffix.lower() in {".aar", ".so"}):
        relative = path.relative_to(args.native_dir).as_posix()
        components.append({
            "type": "file",
            "bom-ref": f"file:android-native/{relative}",
            "name": relative,
            "hashes": [{"alg": "SHA-256", "content": sha256(path)}],
        })

    timestamp = datetime.fromtimestamp(args.artifact.stat().st_mtime, timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    document = {
        "bomFormat": "CycloneDX",
        "specVersion": "1.5",
        "serialNumber": f"urn:uuid:{hashlib.sha256((sha256(args.artifact) + args.version).encode()).hexdigest()[:8]}-0000-4000-8000-{hashlib.sha256(args.version.encode()).hexdigest()[:12]}",
        "version": 1,
        "metadata": {
            "timestamp": timestamp,
            "tools": [{"vendor": "Vostwave", "name": "generate-android-sbom", "version": "1"}],
            "component": {
                "type": "application",
                "name": "Colitu Android",
                "version": args.version,
                "hashes": [{"alg": "SHA-256", "content": sha256(args.artifact)}],
            },
        },
        "components": components,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(document, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
