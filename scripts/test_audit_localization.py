"""Unit tests for scripts/audit_localization.py validator.

Tests anti-regression requirements for 8 Auto UI Languages (Gói E04):
1. Fixture missing key -> checker catches it
2. Fixture base duplicate (string, plural, quantity) -> checker catches it
3. Fixture plural missing 'other' -> checker catches it
4. Fixture wrong format specifier type (%1$d changed to %1$s) -> checker catches it
5. Fixture config/alias mismatch -> checker catches it
6. Valid fixture containing brand names identical to base and format literal % -> passes cleanly
7. Missing required localized folder -> checker catches it
8. Unexpected / extra localized folder -> checker catches it
9. Extra string key in localized file (not in base) -> checker catches it
10. Extra plural in localized file (not in base) -> checker catches it
11. Duplicate plural quantity item -> checker catches it
12. AndroidManifest declaring android:localeConfig -> checker catches it
13. Complete valid 8-language setup -> passes cleanly
"""
import pathlib
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from scripts.audit_localization import (
    EXPECTED_CANONICAL_TAGS,
    EXPECTED_LOCALIZED_FOLDERS,
    audit_localization,
)


class TestAuditLocalization(unittest.TestCase):

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.test_root = pathlib.Path(self.temp_dir.name)
        self.res_dir = self.test_root / "res"
        self.res_dir.mkdir(parents=True)
        self.app_src_dir = self.test_root / "app_src"
        self.app_src_dir.mkdir(parents=True)

        # Base files setup
        self.values_dir = self.res_dir / "values"
        self.values_dir.mkdir(parents=True)
        self.xml_dir = self.res_dir / "xml"
        self.xml_dir.mkdir(parents=True)
        self.mgr_file = self.app_src_dir / "AppLanguageManager.kt"
        self.config_file = self.xml_dir / "locales_config.xml"
        self.manifest_file = self.app_src_dir / "AndroidManifest.xml"

        # Write 8-language AppLanguageManager.kt
        self.mgr_file.write_text(
            'UiLanguage("en", "English", "Tiếng Anh", "English", "🇺🇸")\n'
            'UiLanguage("vi", "Tiếng Việt", "Tiếng Việt", "Vietnamese", "🇻🇳")\n'
            'UiLanguage("es", "Español", "Tiếng Tây Ban Nha", "Spanish", "🇪🇸")\n'
            'UiLanguage("pt", "Português", "Tiếng Bồ Đào Nha", "Portuguese", "🇧🇷")\n'
            'UiLanguage("fr", "Français", "Tiếng Pháp", "French", "🇫🇷")\n'
            'UiLanguage("id", "Bahasa Indonesia", "Tiếng Indonesia", "Indonesian", "🇮🇩", aliases = listOf("in"))\n'
            'UiLanguage("de", "Deutsch", "Tiếng Đức", "German", "🇩🇪")\n'
            'UiLanguage("ja", "日本語", "Tiếng Nhật", "Japanese", "🇯🇵")\n',
            encoding="utf-8",
        )

        # Write 8-locale locales_config.xml
        self.config_file.write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<locale-config xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <locale android:name="en"/>\n'
            '    <locale android:name="vi"/>\n'
            '    <locale android:name="es"/>\n'
            '    <locale android:name="pt"/>\n'
            '    <locale android:name="fr"/>\n'
            '    <locale android:name="id"/>\n'
            '    <locale android:name="de"/>\n'
            '    <locale android:name="ja"/>\n'
            '</locale-config>\n',
            encoding="utf-8",
        )

        # Write valid AndroidManifest.xml (without android:localeConfig)
        self.manifest_file.write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <application android:name=".TScannerApplication" />\n'
            '</manifest>\n',
            encoding="utf-8",
        )

    def tearDown(self):
        self.temp_dir.cleanup()

    def create_base_strings(self, content):
        (self.values_dir / "strings.xml").write_text(content, encoding="utf-8")

    def create_locale_strings(self, folder_name, content):
        loc_dir = self.res_dir / folder_name
        loc_dir.mkdir(parents=True, exist_ok=True)
        (loc_dir / "strings.xml").write_text(content, encoding="utf-8")

    def setup_all_7_locales(self, base_content, loc_content):
        self.create_base_strings(base_content)
        for folder in EXPECTED_LOCALIZED_FOLDERS:
            self.create_locale_strings(folder, loc_content)

    def run_audit(self, **kwargs):
        defaults = {
            "res_dir": self.res_dir,
            "app_src_dir": self.app_src_dir,
            "locales_config_file": self.config_file,
            "language_manager_file": self.mgr_file,
            "manifest_file": self.manifest_file,
        }
        defaults.update(kwargs)
        return audit_localization(**defaults)

    def test_fixture_missing_key(self):
        """Checker must catch a locale that is missing keys defined in base."""
        self.create_base_strings(
            '<resources>\n'
            '    <string name="app_name">T-Scanner</string>\n'
            '    <string name="btn_save">Save</string>\n'
            '</resources>\n'
        )
        self.create_locale_strings(
            "values-fr",
            '<resources>\n'
            '    <string name="app_name">T-Scanner</string>\n'
            '</resources>\n'
        )
        report = self.run_audit(
            expected_canonical_tags={"en", "fr"},
            expected_localized_folders={"values-fr"},
        )
        self.assertTrue(len(report["errors"]) > 0)
        self.assertTrue(any("Missing 1 string keys" in err for err in report["errors"]))

    def test_fixture_base_duplicate(self):
        """Checker must catch duplicate keys in base strings.xml."""
        self.create_base_strings(
            '<resources>\n'
            '    <string name="btn_save">Save</string>\n'
            '    <string name="btn_save">Save duplicate</string>\n'
            '</resources>\n'
        )
        self.create_locale_strings(
            "values-fr",
            '<resources>\n'
            '    <string name="btn_save">Enregistrer</string>\n'
            '</resources>\n'
        )
        report = self.run_audit(
            expected_canonical_tags={"en", "fr"},
            expected_localized_folders={"values-fr"},
        )
        self.assertTrue(len(report["errors"]) > 0)
        self.assertTrue(any("Duplicate string keys found" in err for err in report["errors"]))

    def test_fixture_plural_missing_other(self):
        """Checker must catch plurals that lack the mandatory 'other' quantity."""
        self.create_base_strings(
            '<resources>\n'
            '    <plurals name="items_count">\n'
            '        <item quantity="one">%1$d item</item>\n'
            '        <item quantity="other">%1$d items</item>\n'
            '    </plurals>\n'
            '</resources>\n'
        )
        self.create_locale_strings(
            "values-fr",
            '<resources>\n'
            '    <plurals name="items_count">\n'
            '        <item quantity="one">%1$d élément</item>\n'
            '    </plurals>\n'
            '</resources>\n'
        )
        report = self.run_audit(
            expected_canonical_tags={"en", "fr"},
            expected_localized_folders={"values-fr"},
        )
        self.assertTrue(len(report["errors"]) > 0)
        self.assertTrue(any("missing mandatory 'other' quantity" in err for err in report["errors"]))

    def test_fixture_wrong_format_specifier_type(self):
        """Checker must catch format specifier type changes, e.g. %1$d changed to %1$s."""
        self.create_base_strings(
            '<resources>\n'
            '    <string name="doc_pages_format">Page %1$d of %2$d</string>\n'
            '</resources>\n'
        )
        self.create_locale_strings(
            "values-fr",
            '<resources>\n'
            '    <string name="doc_pages_format">Page %1$s sur %2$d</string>\n'
            '</resources>\n'
        )
        report = self.run_audit(
            expected_canonical_tags={"en", "fr"},
            expected_localized_folders={"values-fr"},
        )
        self.assertTrue(len(report["errors"]) > 0)
        self.assertTrue(any("format mismatch" in err for err in report["errors"]))

    def test_fixture_config_alias_mismatch(self):
        """Checker must catch when locales_config.xml contains an unsupported tag."""
        self.create_base_strings(
            '<resources>\n'
            '    <string name="app_name">T-Scanner</string>\n'
            '</resources>\n'
        )
        # locales_config contains 'xx_unknown'
        self.config_file.write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<locale-config xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <locale android:name="en"/>\n'
            '    <locale android:name="vi"/>\n'
            '    <locale android:name="es"/>\n'
            '    <locale android:name="pt"/>\n'
            '    <locale android:name="fr"/>\n'
            '    <locale android:name="id"/>\n'
            '    <locale android:name="de"/>\n'
            '    <locale android:name="ja"/>\n'
            '    <locale android:name="xx_unknown"/>\n'
            '</locale-config>\n',
            encoding="utf-8",
        )
        report = self.run_audit()
        self.assertTrue(len(report["errors"]) > 0)
        self.assertTrue(any("xx_unknown" in err for err in report["errors"]))

    def test_valid_8_languages_full_setup(self):
        """Complete 8-language setup with matching keys and plurals must pass cleanly."""
        base_xml = (
            '<resources>\n'
            '    <string name="app_name">T-Scanner</string>\n'
            '    <string name="format_pdf">PDF</string>\n'
            '    <string name="discount_percent" formatted="false">100%</string>\n'
            '    <string name="user_greeting">Hello %1$s!</string>\n'
            '    <plurals name="pages_count">\n'
            '        <item quantity="one">%1$d page</item>\n'
            '        <item quantity="other">%1$d pages</item>\n'
            '    </plurals>\n'
            '</resources>\n'
        )
        loc_xml = (
            '<resources>\n'
            '    <string name="app_name">T-Scanner</string>\n'
            '    <string name="format_pdf">PDF</string>\n'
            '    <string name="discount_percent" formatted="false">100%</string>\n'
            '    <string name="user_greeting">Bonjour %1$s !</string>\n'
            '    <plurals name="pages_count">\n'
            '        <item quantity="one">%1$d page</item>\n'
            '        <item quantity="other">%1$d pages</item>\n'
            '    </plurals>\n'
            '</resources>\n'
        )
        self.setup_all_7_locales(base_xml, loc_xml)
        report = self.run_audit()
        self.assertEqual(len(report["errors"]), 0, f"Expected 0 errors, got: {report['errors']}")

    def test_missing_required_localized_folder(self):
        """Checker must catch when a required 8-language folder (e.g. values-de) is missing."""
        base_xml = '<resources><string name="app_name">T-Scanner</string></resources>\n'
        loc_xml = '<resources><string name="app_name">T-Scanner</string></resources>\n'
        self.setup_all_7_locales(base_xml, loc_xml)

        # Remove values-de folder
        de_strings = self.res_dir / "values-de/strings.xml"
        if de_strings.exists():
            de_strings.unlink()
        (self.res_dir / "values-de").rmdir()

        report = self.run_audit()
        self.assertTrue(len(report["errors"]) > 0)
        self.assertTrue(any("Missing required localized folder(s)" in err and "values-de" in err for err in report["errors"]))

    def test_unexpected_localized_folder(self):
        """Checker must catch when an extra non-8 language folder (e.g. values-af) exists in res/."""
        base_xml = '<resources><string name="app_name">T-Scanner</string></resources>\n'
        loc_xml = '<resources><string name="app_name">T-Scanner</string></resources>\n'
        self.setup_all_7_locales(base_xml, loc_xml)

        # Add extra values-af
        self.create_locale_strings("values-af", loc_xml)

        report = self.run_audit()
        self.assertTrue(len(report["errors"]) > 0)
        self.assertTrue(any("Found unexpected localized folder(s) in res" in err and "values-af" in err for err in report["errors"]))

    def test_extra_key_in_localized_folder(self):
        """Checker must catch extra string keys in localized files that do not exist in base."""
        base_xml = '<resources><string name="app_name">T-Scanner</string></resources>\n'
        loc_xml = (
            '<resources>\n'
            '    <string name="app_name">T-Scanner</string>\n'
            '    <string name="ghost_key">Orphaned text</string>\n'
            '</resources>\n'
        )
        self.create_base_strings(base_xml)
        self.create_locale_strings("values-fr", loc_xml)

        report = self.run_audit(
            expected_canonical_tags={"en", "fr"},
            expected_localized_folders={"values-fr"},
        )
        self.assertTrue(len(report["errors"]) > 0)
        self.assertTrue(any("Extra 1 string keys not in base" in err and "ghost_key" in err for err in report["errors"]))

    def test_extra_plural_in_localized_folder(self):
        """Checker must catch extra plurals in localized files that do not exist in base."""
        base_xml = '<resources><string name="app_name">T-Scanner</string></resources>\n'
        loc_xml = (
            '<resources>\n'
            '    <string name="app_name">T-Scanner</string>\n'
            '    <plurals name="ghost_plural"><item quantity="other">%1$d</item></plurals>\n'
            '</resources>\n'
        )
        self.create_base_strings(base_xml)
        self.create_locale_strings("values-fr", loc_xml)

        report = self.run_audit(
            expected_canonical_tags={"en", "fr"},
            expected_localized_folders={"values-fr"},
        )
        self.assertTrue(len(report["errors"]) > 0)
        self.assertTrue(any("Extra 1 plurals not in base" in err and "ghost_plural" in err for err in report["errors"]))

    def test_duplicate_quantity_in_plural(self):
        """Checker must catch duplicate quantities inside the same plural element."""
        base_xml = '<resources><string name="app_name">T-Scanner</string></resources>\n'
        loc_xml = (
            '<resources>\n'
            '    <string name="app_name">T-Scanner</string>\n'
            '    <plurals name="items_count">\n'
            '        <item quantity="other">%1$d item</item>\n'
            '        <item quantity="other">%1$d items</item>\n'
            '    </plurals>\n'
            '</resources>\n'
        )
        self.create_base_strings(base_xml)
        self.create_locale_strings("values-fr", loc_xml)

        report = self.run_audit(
            expected_canonical_tags={"en", "fr"},
            expected_localized_folders={"values-fr"},
        )
        self.assertTrue(len(report["errors"]) > 0)
        self.assertTrue(any("Duplicate plural quantities found" in err for err in report["errors"]))

    def test_manifest_contains_locale_config(self):
        """Checker must report an error if AndroidManifest.xml declares android:localeConfig."""
        base_xml = '<resources><string name="app_name">T-Scanner</string></resources>\n'
        loc_xml = '<resources><string name="app_name">T-Scanner</string></resources>\n'
        self.setup_all_7_locales(base_xml, loc_xml)

        # Corrupt manifest with android:localeConfig
        self.manifest_file.write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <application android:name=".TScannerApplication" android:localeConfig="@xml/locales_config" />\n'
            '</manifest>\n',
            encoding="utf-8",
        )
        report = self.run_audit()
        self.assertTrue(len(report["errors"]) > 0)
        self.assertTrue(any("android:localeConfig must NOT be declared" in err for err in report["errors"]))


if __name__ == "__main__":
    unittest.main()
