#!/usr/bin/env python3
"""EN/AR string catalog parity gate (SC-006).

Compares app/src/main/res/values/strings.xml against values-ar/strings.xml:
  - every non-translatable=false key in EN must exist in AR with a non-empty value
  - format-arg placeholders (%1$s, %2$d, ...) must match in count/type per key

Exit 0 on success, exit 1 and print mismatches on failure.
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
EN_PATH = REPO_ROOT / "app/src/main/res/values/strings.xml"
AR_PATH = REPO_ROOT / "app/src/main/res/values-ar/strings.xml"

PLACEHOLDER_RE = re.compile(r"%(\d+\$)?[sdf]")


def load_catalog(path: Path) -> dict[str, tuple[str, bool]]:
    tree = ET.parse(path)
    catalog = {}
    for node in tree.getroot().findall("string"):
        name = node.get("name")
        translatable = node.get("translatable", "true") != "false"
        text = "".join(node.itertext())
        catalog[name] = (text, translatable)
    return catalog


def main() -> int:
    if not EN_PATH.exists() or not AR_PATH.exists():
        print(f"FAIL: missing catalog file(s): {EN_PATH} / {AR_PATH}")
        return 1

    en = load_catalog(EN_PATH)
    ar = load_catalog(AR_PATH)

    errors: list[str] = []

    translatable_en_keys = {k for k, (_, translatable) in en.items() if translatable}

    for key in sorted(translatable_en_keys):
        if key not in ar:
            errors.append(f"missing in AR: {key}")
            continue
        ar_text, _ = ar[key]
        if not ar_text.strip():
            errors.append(f"empty AR value: {key}")
            continue
        en_text, _ = en[key]
        en_ph = re.findall(PLACEHOLDER_RE, en_text)
        ar_ph = re.findall(PLACEHOLDER_RE, ar_text)
        if sorted(en_ph) != sorted(ar_ph):
            errors.append(
                f"placeholder mismatch: {key} (EN={en_ph} AR={ar_ph})"
            )

    extra_ar_keys = set(ar.keys()) - set(en.keys())
    for key in sorted(extra_ar_keys):
        errors.append(f"key present only in AR: {key}")

    if errors:
        print(f"FAIL: {len(errors)} parity issue(s):")
        for err in errors:
            print(f"  - {err}")
        return 1

    print(f"OK: {len(translatable_en_keys)} translatable keys, EN/AR parity confirmed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
