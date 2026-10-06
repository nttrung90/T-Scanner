"""Localization CI validator and inventory. Run from any cwd.

Performs strict anti-regression validation for 8 Auto UI Languages (E04):
1. Base duplicates check (strings, plurals, quantities)
2. Exact match of 8 UI languages:
   - Base source: values (English)
   - 7 localized folders: values-vi, values-es, values-pt, values-fr, values-in, values-de, values-ja
   - Missing required folder -> FAIL
   - Extra / unexpected folder in res/ -> FAIL
3. Zero missing keys and zero extra keys across all localized folders against values/strings.xml
4. Plurals validation: mandatory 'other' quantity, placeholder signature & type matching, no duplicate quantities
5. Zero format specifier type/index mismatches (e.g. %1$d vs %1$s)
6. Zero unextracted alphabetic text in layouts/dialogs
7. Full synchronization between AppLanguageManager, locales_config.xml, and res folders
8. AndroidManifest.xml verification: android:localeConfig must NOT be declared (per-app chooser promotion disabled)

Note:
  Android Lint (:app:lintDebug) is the authoritative gate for locale-specific
  grammatical plural quantities (e.g. zero/two/few/many).

Usage:
  python scripts/audit_localization.py          # Human-readable CI check (exits 0 or 1)
  python scripts/audit_localization.py --json   # Output JSON report
"""
import collections
import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

ROOT = pathlib.Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
ANDROID = "{http://schemas.android.com/apk/res/android}"
TOOLS = "{http://schemas.android.com/tools}"
FORMAT = re.compile(r"%(?:(\d+)\$)?[-#+ 0,(<]*\d*(?:\.\d+)?([a-zA-Z%])")

ALLOWLISTED_BRANDS_AND_FORMATS = {
    "T-Scanner", "PDF", "Word", "Excel", "PPT", "TXT", "Google Drive",
    "Drive", "VIP", "OCR", "Tesseract", "PaddleOCR", "ML Kit", "VietQR",
    "VND", "CCCD", "CMND", "AAB", "APK", "ID Card", "100%", "GB", "MB", "KB",
    "EMVCo", "Demo Account", "Google", "Android"
}

EXPECTED_CANONICAL_TAGS = {"en", "vi", "es", "pt", "fr", "id", "de", "ja"}
EXPECTED_LOCALIZED_FOLDERS = {"values-vi", "values-es", "values-pt", "values-fr", "values-in", "values-de", "values-ja"}



def signature(value):
    """Extract list of (index, type_char) from format specifiers in order."""
    result = []
    implicit = 0
    for index, kind in FORMAT.findall(value):
        if kind in ("%", "n"):
            continue
        if not index:
            implicit += 1
            index = str(implicit)
        result.append((index, kind))
    return sorted(result)


def read_strings_and_plurals(path):
    root = ET.parse(path).getroot()
    entries = root.findall("string")
    plurals = root.findall("plurals")

    string_dupes = [
        key for key, count in collections.Counter(e.attrib.get("name") for e in entries).items()
        if count > 1
    ]
    plural_dupes = [
        key for key, count in collections.Counter(p.attrib.get("name") for p in plurals).items()
        if count > 1
    ]

    strings_map = {}
    for e in entries:
        name = e.attrib.get("name")
        if name and name not in strings_map:
            strings_map[name] = e

    plurals_map = {}
    quantity_dupes = []
    for p in plurals:
        name = p.attrib.get("name")
        if not name:
            continue
        quantities = [item.attrib.get("quantity") for item in p.findall("item") if item.attrib.get("quantity")]
        dupe_q = [q for q, count in collections.Counter(quantities).items() if count > 1]
        if dupe_q:
            quantity_dupes.append(f"{name}: duplicate quantity {dupe_q}")
        if name not in plurals_map:
            items = {}
            for item in p.findall("item"):
                q = item.attrib.get("quantity")
                if q and q not in items:
                    items[q] = "".join(item.itertext())
            plurals_map[name] = {"elem": p, "items": items}

    return root, strings_map, plurals_map, string_dupes, plural_dupes, quantity_dupes


def value(element):
    return "".join(element.itertext())


def parse_app_language_manager(mgr_path):
    """Parse canonical tags and alias mappings from AppLanguageManager.kt."""
    if not mgr_path or not mgr_path.exists():
        return None
    content = mgr_path.read_text(encoding="utf-8")
    pattern = re.compile(
        r'UiLanguage\(\s*"([^"]+)"(?:,\s*"[^"]*")*(?:,\s*aliases\s*=\s*listOf\(([^)]*)\))?',
        re.DOTALL,
    )
    result = {}
    for tag, aliases_raw in pattern.findall(content):
        aliases = [a.strip().strip('"') for a in aliases_raw.split(",") if a.strip()] if aliases_raw else []
        result[tag] = aliases
    return result


def audit_localization(
    res_dir=RES,
    app_src_dir=ROOT / "app/src/main",
    locales_config_file=None,
    language_manager_file=None,
    manifest_file=None,
    expected_canonical_tags=EXPECTED_CANONICAL_TAGS,
    expected_localized_folders=EXPECTED_LOCALIZED_FOLDERS,
):
    if locales_config_file is None:
        locales_config_file = res_dir / "xml/locales_config.xml"
    if language_manager_file is None:
        language_manager_file = app_src_dir / "java/com/tscanner/app/utils/AppLanguageManager.kt"
    if manifest_file is None:
        manifest_file = app_src_dir / "AndroidManifest.xml"

    report = {
        "scope": "Localization anti-regression audit",
        "base_keys": 0,
        "base_plurals": 0,
        "locales": {},
        "xml_hardcoded_text": [],
        "errors": [],
        "warnings": [],
        "identical_to_base_review_required": {},
    }

    # 1. AndroidManifest.xml verification: android:localeConfig must NOT be declared
    if manifest_file.exists():
        manifest_text = manifest_file.read_text(encoding="utf-8")
        if "android:localeConfig" in manifest_text:
            report["errors"].append(
                "[AndroidManifest.xml] android:localeConfig must NOT be declared (per-app language chooser promotion disabled)"
            )

    base_file = res_dir / "values/strings.xml"
    if not base_file.exists():
        report["errors"].append(f"Base strings file not found: {base_file}")
        return report

    _, base_strings, base_plurals, base_str_dupes, base_pl_dupes, base_qty_dupes = read_strings_and_plurals(base_file)
    report["base_keys"] = len(base_strings)
    report["base_plurals"] = len(base_plurals)

    if base_str_dupes:
        report["errors"].append(f"[base values/strings.xml] Duplicate string keys found: {base_str_dupes}")
    if base_pl_dupes:
        report["errors"].append(f"[base values/strings.xml] Duplicate plural names found: {base_pl_dupes}")
    if base_qty_dupes:
        report["errors"].append(f"[base values/strings.xml] Duplicate plural quantities found: {base_qty_dupes}")

    # Check that base plurals have mandatory 'other'
    for pl_name, pl_data in base_plurals.items():
        if "other" not in pl_data["items"]:
            report["errors"].append(f"[base values/strings.xml] Plural '{pl_name}' is missing mandatory 'other' quantity")

    # 2. Cross-check AppLanguageManager catalog and locales_config.xml
    catalog = parse_app_language_manager(language_manager_file)
    configured_locales = []
    if locales_config_file.exists():
        try:
            lc_root = ET.parse(locales_config_file).getroot()
            configured_locales = [
                elem.attrib.get(ANDROID + "name", "") for elem in lc_root.findall("locale")
            ]
            report["configured_locales_count"] = len(configured_locales)
        except Exception as e:
            report["errors"].append(f"Malformed locales_config.xml: {e}")

    if catalog is not None and expected_canonical_tags is not None:
        missing_catalog = sorted(set(expected_canonical_tags) - set(catalog.keys()))
        extra_catalog = sorted(set(catalog.keys()) - set(expected_canonical_tags))
        if missing_catalog:
            report["errors"].append(
                f"AppLanguageManager missing expected canonical tags: {missing_catalog}"
            )
        if extra_catalog:
            report["errors"].append(
                f"AppLanguageManager has unexpected canonical tags: {extra_catalog}"
            )

    if locales_config_file.exists() and expected_canonical_tags is not None:
        missing_cfg = sorted(set(expected_canonical_tags) - set(configured_locales))
        extra_cfg = sorted(set(configured_locales) - set(expected_canonical_tags))
        if missing_cfg:
            report["errors"].append(
                f"locales_config.xml missing expected tags: {missing_cfg}"
            )
        if extra_cfg:
            report["errors"].append(
                f"locales_config.xml has unexpected tags: {extra_cfg}"
            )

    if catalog and configured_locales:
        for tag in configured_locales:
            if tag not in catalog:
                report["errors"].append(
                    f"locales_config.xml contains locale '{tag}' not found in AppLanguageManager canonical tags"
                )
        for tag in catalog.keys():
            if tag not in configured_locales:
                report["errors"].append(
                    f"Canonical tag '{tag}' in AppLanguageManager missing from locales_config.xml"
                )

    # 3. Check localized folders in res_dir
    found_locale_files = sorted(res_dir.glob("values-*/strings.xml"))
    found_folders = {p.parent.name for p in found_locale_files}

    if expected_localized_folders is not None:
        missing_folders = sorted(set(expected_localized_folders) - found_folders)
        extra_folders = sorted(found_folders - set(expected_localized_folders))
        if missing_folders:
            report["errors"].append(f"Missing required localized folder(s): {missing_folders}")
        if extra_folders:
            report["errors"].append(f"Found unexpected localized folder(s) in res: {extra_folders}")

    for path in found_locale_files:
        folder_name = path.parent.name
        _, entries, plurals, str_dupes, pl_dupes, qty_dupes = read_strings_and_plurals(path)

        if str_dupes:
            report["errors"].append(f"[{folder_name}] Duplicate string keys found: {str_dupes}")
        if pl_dupes:
            report["errors"].append(f"[{folder_name}] Duplicate plural names found: {pl_dupes}")
        if qty_dupes:
            report["errors"].append(f"[{folder_name}] Duplicate plural quantities found: {qty_dupes}")

        missing_keys = sorted(set(base_strings) - set(entries))
        if missing_keys:
            report["errors"].append(f"[{folder_name}] Missing {len(missing_keys)} string keys: {missing_keys[:5]}...")

        extra_keys = sorted(set(entries) - set(base_strings))
        if extra_keys:
            report["errors"].append(f"[{folder_name}] Extra {len(extra_keys)} string keys not in base: {extra_keys[:5]}...")

        missing_plurals = sorted(set(base_plurals) - set(plurals))
        if missing_plurals:
            report["errors"].append(f"[{folder_name}] Missing {len(missing_plurals)} plurals: {missing_plurals}")

        extra_plurals = sorted(set(plurals) - set(base_plurals))
        if extra_plurals:
            report["errors"].append(f"[{folder_name}] Extra {len(extra_plurals)} plurals not in base: {extra_plurals}")

        # Plural items check: mandatory 'other' & placeholder signature match
        for pl_name, pl_data in plurals.items():
            if pl_name not in base_plurals:
                continue
            base_pl_items = base_plurals[pl_name]["items"]
            # Check mandatory 'other'
            if "other" not in pl_data["items"]:
                report["errors"].append(f"[{folder_name}] Plural '{pl_name}' missing mandatory 'other' quantity")

            # Check format specifiers in each item
            base_other_sig = signature(base_pl_items.get("other", ""))
            for q, item_val in pl_data["items"].items():
                item_sig = signature(item_val)
                item_sig_dict = dict(item_sig)
                base_sig_dict = dict(base_other_sig)
                for idx, kind in item_sig_dict.items():
                    if idx in base_sig_dict and base_sig_dict[idx] != kind:
                        report["errors"].append(
                            f"[{folder_name}] Plural '{pl_name}' item '{q}' type mismatch on %{idx}${kind} vs base %{idx}${base_sig_dict[idx]}"
                        )

        # String format signature check
        sig_mismatches = []
        identical_to_base = []
        for key in entries.keys() & base_strings.keys():
            base_e = base_strings[key]
            loc_e = entries[key]
            base_val = value(base_e)
            loc_val = value(loc_e)

            # Check format signatures unless formatted="false"
            if base_e.get("formatted") != "false" and loc_e.get("formatted") != "false":
                base_s = signature(base_val)
                loc_s = signature(loc_val)
                if base_s != loc_s:
                    sig_mismatches.append((key, base_s, loc_s))

            # Review identical strings (exclude allowlisted brands/formats)
            if folder_name not in ("values-en", "values-vi") and base_val == loc_val:
                cleaned = base_val.strip()
                if cleaned not in ALLOWLISTED_BRANDS_AND_FORMATS and "%" not in cleaned:
                    identical_to_base.append(key)

        if sig_mismatches:
            for key, bs, ls in sig_mismatches:
                report["errors"].append(
                    f"[{folder_name}] String '{key}' format mismatch: base={bs}, translated={ls}"
                )

        if identical_to_base:
            report["identical_to_base_review_required"][folder_name] = identical_to_base

        report["locales"][folder_name] = {
            "keys": len(entries),
            "plurals": len(plurals),
            "missing_keys_count": len(missing_keys),
            "extra_keys_count": len(extra_keys),
            "missing_plurals_count": len(missing_plurals),
            "extra_plurals_count": len(extra_plurals),
            "duplicates": str_dupes + pl_dupes + qty_dupes,
            "signature_mismatches_count": len(sig_mismatches),
            "identical_to_base_count": len(identical_to_base),
        }

    # 4. Check layouts for unextracted alphabetic text
    for path in sorted(res_dir.rglob("*.xml")):
        if path.parent.name.startswith("values"):
            continue
        try:
            ET.parse(path)
        except Exception as e:
            report["errors"].append(f"Malformed XML in {path.name}: {e}")
            continue

        source = path.read_text(encoding="utf-8")
        for match in re.finditer(r'android:(text|hint|contentDescription)="([^"@?][^"]*)"', source):
            val = match[2]
            # Ignore pure emoji / punctuation symbols (e.g. 📱, •, ✓, etc.)
            if re.search(r"[a-zA-Z\u00C0-\u024F\u1EA0-\u1EF9]", val):
                line_no = source.count("\n", 0, match.start()) + 1
                rel_path = path.as_posix()
                report["xml_hardcoded_text"].append({
                    "file": rel_path,
                    "line": line_no,
                    "attribute": match[1],
                    "value": val,
                })
                report["errors"].append(f"Unextracted hardcoded text in {path.name}:{line_no} [{match[1]}=\"{val}\"]")

    return report


def main():
    json_mode = "--json" in sys.argv
    report = audit_localization()

    if json_mode:
        print(json.dumps(report, ensure_ascii=False, indent=2))
        sys.exit(1 if report["errors"] else 0)

    print("=" * 70)
    print("           T-SCANNER LOCALIZATION CI VALIDATION REPORT")
    print("=" * 70)
    print(f"Base strings: {report['base_keys']} strings, {report['base_plurals']} plurals")
    print(f"Locale folders audited: {len(report['locales'])}")
    if "configured_locales_count" in report:
        print(f"Locales declared in locales_config.xml: {report['configured_locales_count']}")
    print("-" * 70)
    print("Plural completeness note:")
    print("  Android Lint (:app:lintDebug) is the authoritative gate for locale-specific")
    print("  grammatical quantities (e.g. zero/two/few/many).")
    print("  This script validates basic resource integrity (presence of 'other',")
    print("  non-duplicate keys, placeholder type matching).")
    print("-" * 70)

    has_errors = len(report["errors"]) > 0
    if has_errors:
        print(f"FAILURE: Found {len(report['errors'])} localization error(s):")
        for err in report["errors"][:30]:
            print(f"  ❌ {err}")
        if len(report["errors"]) > 30:
            print(f"  ... and {len(report['errors']) - 30} more errors.")
        print("=" * 70)
        sys.exit(1)
    else:
        print("Resource integrity checks passed:")
        print(f"  ✓ Exactly {len(report['locales'])} localized folders in res/ (expected 7 + base values)")
        print(f"  ✓ Zero missing or extra keys across all {len(report['locales'])} locale folders")
        print(f"  ✓ Zero missing or extra plurals across all {len(report['locales'])} locale folders")
        print("  ✓ Mandatory 'other' quantity present in all plurals")
        print("  ✓ Zero duplicate quantities, string keys, or plural keys")
        print("  ✓ Zero format specifier type/index mismatches")
        print("  ✓ Zero unextracted hardcoded text in layouts/dialogs")
        print("  ✓ AndroidManifest.xml verified: android:localeConfig is NOT declared")
        print("=" * 70)
        print("SUCCESS: Localization resource integrity verified for 8 UI languages.")
        print("=" * 70)
        sys.exit(0)


if __name__ == "__main__":
    main()
