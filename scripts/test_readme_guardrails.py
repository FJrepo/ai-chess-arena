"""README front-page guardrails from AGENTS.md: length and the Features list."""

import re
import unittest
from pathlib import Path

README = (Path(__file__).resolve().parent.parent / 'README.md').read_text(encoding='utf-8')
MAX_WORDS = 1100
MAX_FEATURES = 10
MAX_FEATURE_WORDS = 25


def features_section():
    return README.split('\n## Features\n', 1)[1].split('\n## ', 1)[0]


def visible_words(markdown):
    text = re.sub(r'\]\([^)]*\)', ']', markdown)
    return [word for word in re.sub(r'[*\[\]]', ' ', text).split() if word != '-']


class ReadmeGuardrailsTest(unittest.TestCase):
    def test_readme_stays_under_the_word_limit(self):
        self.assertLessEqual(len(README.split()), MAX_WORDS)

    def test_features_are_at_most_ten_short_one_line_bullets(self):
        section = features_section()
        bullets = [line for line in section.splitlines() if line.startswith('- ')]
        self.assertGreater(len(bullets), 0)
        self.assertLessEqual(len(bullets), MAX_FEATURES)
        for line in section.splitlines():
            with self.subTest(line=line):
                self.assertFalse(line.startswith((' ', '\t')) and line.strip(),
                                 'feature bullets stay on one line without nested text')
        paragraphs = [line for line in section.splitlines()
                      if line.strip() and not line.startswith('- ')]
        self.assertEqual(len(paragraphs), 1, 'only the "…and more" line may follow the bullets')
        self.assertTrue(paragraphs[0].startswith('…and more'))
        for bullet in bullets:
            with self.subTest(bullet=bullet):
                self.assertLessEqual(len(visible_words(bullet)), MAX_FEATURE_WORDS)


if __name__ == '__main__':
    unittest.main()
