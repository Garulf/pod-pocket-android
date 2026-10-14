#!/usr/bin/env python3
"""Force off the Pocket Casts features that need Automattic's cloud.

Each listed entry in Feature.kt gets defaultValue, hasFirebaseRemoteFlag and
hasDevToggle set to false, so neither Remote Config nor the developer menu can
turn it back on. Run it again after every upstream merge; --check fails when
Feature.kt drifted.
"""

import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
FEATURE_FILE = ROOT / "modules/services/utils/src/main/java/au/com/shiftyjelly/pocketcasts/utils/featureflag/Feature.kt"

DISABLED_FEATURES = [
    "AI_SUMMARIES",
    "BANNER_ADS_PLAYER",
    "BANNER_ADS_PODCASTS",
    "BLOGS",
    "ENCOURAGE_ACCOUNT_CREATION",
    "END_OF_YEAR_2025",
    "ENGAGE_SDK",
    "EPISODE_CHAT",
    "EPISODE_CHAT_FREE_BETA",
    "EPISODE_CHAT_PLAYABLE_QUOTES",
    "EXPLAT_EXPERIMENT",
    "GENERATED_CHAPTERS",
    "HLS_STREAMING",
    "IMPROVE_APP_RATINGS",
    "IMPROVED_LISTENING_STATS",
    "INTRO_PLUS_OFFER_ENABLED",
    "LIBRO_FM",
    "LIVE_ANALYTICS",
    "LOAD_ERROR_HANDLING_POLICY",
    "MEDIA3_SESSION",
    "NETWORK_DISCOVERY",
    "NEW_INSTALLMENT_PLAN",
    "NEXT_EPISODE_PREFETCH",
    "NOTIFICATIONS_REVAMP",
    "PLAYBACK_ERROR_INFO_BAR",
    "PROFILE_SHARING",
    "SLUMBER_STUDIOS_YEARLY_PROMO",
    "SMART_BOOKMARKS",
    "STATS_HEATMAP",
    "SYNC_EOY_DATA_ON_STARTUP",
    "SYNCED_TRANSCRIPTS",
    "UP_NEXT_SORT",
    "UP_NEXT_SYNC_PROTOBUF",
    "WHATS_NEW_FEED",
]

FIELDS = ("defaultValue", "hasFirebaseRemoteFlag", "hasDevToggle")
DEBUG_ONLY_MARKER = "isDebugOrPrototypeBuild"


def disable(source: str, name: str) -> str:
    entry = re.search(rf"^    {name}\(\n(?P<body>.*?)^    \),$", source, re.MULTILINE | re.DOTALL)
    if entry is None:
        raise SystemExit(f"{name} is not declared in {FEATURE_FILE.relative_to(ROOT)}")
    body = entry.group("body")
    for field in FIELDS:
        body, count = re.subn(rf"^(\s+{field} = ).*,$", r"\g<1>false,", body, flags=re.MULTILINE)
        if count != 1:
            raise SystemExit(f"{name} has {count} '{field}' lines, expected 1")
    return source[: entry.start("body")] + body + source[entry.end("body") :]


def debug_only_features(source: str) -> list[str]:
    offenders = []
    for entry in re.finditer(r"^    (?P<name>[A-Z0-9_]+)\(\n(?P<body>.*?)^    \),$", source, re.MULTILINE | re.DOTALL):
        if entry.group("name") in DISABLED_FEATURES:
            continue
        default = re.search(r"^\s+defaultValue = (?P<value>.*),$", entry.group("body"), re.MULTILINE)
        if default is not None and DEBUG_ONLY_MARKER in default.group("value"):
            offenders.append(entry.group("name"))
    return offenders


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="fail instead of editing when Feature.kt needs changes")
    args = parser.parse_args()

    original = FEATURE_FILE.read_text(encoding="utf-8")
    offenders = debug_only_features(original)
    if offenders:
        print(
            f"{', '.join(offenders)} default to {DEBUG_ONLY_MARKER} but are not in DISABLED_FEATURES; "
            "disable them or confirm they need no cloud",
            file=sys.stderr,
        )
        return 1
    updated = original
    for name in DISABLED_FEATURES:
        updated = disable(updated, name)

    if updated == original:
        return 0
    if args.check:
        print("Feature.kt enables features Pod Pocket removes; run scripts/podpocket/disable_features.py", file=sys.stderr)
        return 1
    FEATURE_FILE.write_text(updated, encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
