#!/usr/bin/env python3
"""Replace the user-visible Pocket Casts brand with Pod Pocket.

Rewrites string values in place, keeping resource names, URLs and file
formatting untouched. Run it again after every upstream merge; --check fails
when any brand text is left.
"""

import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
POSSESSIVE = re.compile(r"(?<!['’])Pocket Casts(\\?['’])(?!s)")
BRAND = re.compile(r"Pocket Casts?(?![A-Za-z])")
NEW_BRAND = "Pod Pocket"

TARGET_GLOBS = [
    "modules/services/localization/src/main/res/values*/strings.xml",
    "app/src/main/res/values/titles.xml",
    "automotive/src/main/res/values/titles.xml",
    "wear/src/main/res/values/titles.xml",
    "app/src/main/AndroidManifest.xml",
]


def target_files() -> list[pathlib.Path]:
    files = sorted({path for pattern in TARGET_GLOBS for path in ROOT.glob(pattern)})
    if not files:
        raise SystemExit("no string resources found; run from a Pod Pocket checkout")
    return files


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="fail instead of editing when brand text is left")
    args = parser.parse_args()

    stale = []
    for path in target_files():
        original = path.read_text(encoding="utf-8")
        updated = BRAND.sub(NEW_BRAND, POSSESSIVE.sub(NEW_BRAND + r"\1s", original))
        if updated == original:
            continue
        stale.append(path.relative_to(ROOT))
        if not args.check:
            path.write_text(updated, encoding="utf-8")

    if args.check and stale:
        print("Pocket Casts brand text left in:", *stale, sep="\n  ", file=sys.stderr)
        print("Run scripts/podpocket/rebrand_strings.py", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
