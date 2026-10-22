#!/usr/bin/env python3
"""Turn failing device tests into GitHub annotations.

The device-test log is not readable without repository admin rights over the API, but
annotations are public on a public repository. Printing one ``::error`` line per failure puts the
test name and its assertion message directly on the pull request / commit, where it is actually
readable.

Usage: python3 scripts/annotate-device-failures.py [results-dir]
"""

from __future__ import annotations

import glob
import pathlib
import sys
import xml.etree.ElementTree as ET


def escape(value: str) -> str:
    return (
        value.replace("%", "%25")
        .replace("\r", "%0D")
        .replace("\n", "%0A")
    )


def main() -> int:
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "app/build/outputs/androidTest-results")
    files = sorted(glob.glob(str(root / "**" / "*.xml"), recursive=True))
    if not files:
        print(f"::warning::no device test results under {root}")
        return 0

    failures = 0
    for path in files:
        try:
            tree = ET.parse(path)
        except ET.ParseError as error:
            print(f"::warning file={path}::could not parse results: {error}")
            continue
        for case in tree.getroot().iter("testcase"):
            problem = case.find("failure")
            if problem is None:
                problem = case.find("error")
            if problem is None:
                continue
            failures += 1
            name = f"{case.get('classname', '?')}.{case.get('name', '?')}"
            detail = (problem.get("message") or problem.text or "").strip()
            detail = " | ".join(line.strip() for line in detail.splitlines()[:8])
            print(f"::error title={escape(name)}::{escape(name)} failed: {escape(detail)[:900]}")

    print(f"{failures} failing device test(s) in {len(files)} result file(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
