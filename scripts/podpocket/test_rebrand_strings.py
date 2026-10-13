import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from rebrand_strings import rebrand


class RebrandTest(unittest.TestCase):
    def test_plain_space(self):
        self.assertEqual("Pod Pocket Plus", rebrand("Pocket Casts Plus"))

    def test_no_break_space_stays_no_break(self):
        self.assertEqual("Pod\u00a0Pocket", rebrand("Pocket\u00a0Casts"))

    def test_numeric_entity_stays_numeric_entity(self):
        self.assertEqual("Pod&#160;Pocket", rebrand("Pocket&#160;Casts"))

    def test_android_escape_stays_android_escape(self):
        self.assertEqual("Pod\\u00A0Pocket", rebrand("Pocket\\u00A0Casts"))
        self.assertEqual("Pod\\u00a0Pocket", rebrand("Pocket\\u00a0Casts"))

    def test_android_escape_possessive(self):
        self.assertEqual("Pod\\u00A0Pocket\\'s forums", rebrand("Pocket\\u00A0Casts\\' forums"))

    def test_named_entity_stays_named_entity(self):
        self.assertEqual("Pod&nbsp;Pocket", rebrand("Pocket&nbsp;Casts"))

    def test_misspelt_singular(self):
        self.assertEqual("Pod Pocket e Sonos", rebrand("Pocket Cast e Sonos"))

    def test_korean_particle_directly_after_brand(self):
        self.assertEqual("Pod Pocket를", rebrand("Pocket Casts를"))

    def test_resource_names_and_urls_stay(self):
        text = '<string name="pocket_casts_plus">https://www.pocketcasts.com/Pocket Cats</string>'
        self.assertEqual(text, rebrand(text))

    def test_escaped_possessive(self):
        self.assertEqual("Pod Pocket\\'s forums", rebrand("Pocket Casts\\' forums"))

    def test_curly_possessive(self):
        self.assertEqual("Pod Pocket’s menu", rebrand("Pocket Casts’ menu"))

    def test_possessive_with_no_break_space(self):
        self.assertEqual("Pod&#160;Pocket\\'s forums", rebrand("Pocket&#160;Casts\\' forums"))

    def test_existing_possessive_s_is_kept(self):
        self.assertEqual("Pod Pocket's", rebrand("Pocket Casts's"))

    def test_quoted_brand_is_not_a_possessive(self):
        self.assertEqual("\\'Pod Pocket\\' toestaan", rebrand("\\'Pocket Casts\\' toestaan"))

    def test_curly_quoted_brand_is_not_a_possessive(self):
        self.assertEqual("‘Pod Pocket’ toestaan", rebrand("‘Pocket Casts’ toestaan"))

    def test_typo_in_chinese(self):
        self.assertEqual("Pod Pocket Plus 订阅", rebrand("Pocket casting Plus 订阅"))

    def test_greek_typo_with_plural_s(self):
        self.assertEqual("στο Pod Pocket από", rebrand("στο Pockets Casts από"))

    def test_norwegian_possessive_has_no_apostrophe(self):
        self.assertEqual("Pod Pockets forumer", rebrand("Pocket Casts\\' forumer"))

    def test_share_message_links_to_the_repository(self):
        text = '<string name="settings_about_share_with_friends_message">Hi! Pocket Casts \\nhttps://www.pocketcasts.com</string>'
        expected = '<string name="settings_about_share_with_friends_message">Hi! Pod Pocket \\nhttps://github.com/Garulf/pod-pocket-android</string>'
        self.assertEqual(expected, rebrand(text))

    def test_other_pocketcasts_links_stay(self):
        text = '<string name="profile_sub_web_goto">https://www.pocketcasts.com</string>'
        self.assertEqual(text, rebrand(text))

    def test_idempotent(self):
        samples = [
            "Pocket Casts Plus",
            "Pocket\u00a0Casts",
            "Pocket&#160;Casts\\' forums",
            "\\'Pocket Casts\\' toestaan",
            "Pocket Casts\\' forumer",
            "Pocket casting Plus",
            "Pocket\\u00A0Casts\\' forums",
            "Pockets Casts",
        ]
        for sample in samples:
            once = rebrand(sample)
            self.assertEqual(once, rebrand(once), sample)


if __name__ == "__main__":
    unittest.main()
