"""README front-page guardrails from AGENTS.md: length and the Features list."""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
README = (ROOT / 'README.md').read_text(encoding='utf-8')
HOW_IT_WORKS = (ROOT / 'docs' / 'how-it-works.md').read_text(encoding='utf-8')
MAX_WORDS = 1100
MAX_FEATURES = 10
MAX_FEATURE_WORDS = 25


def features_section():
    return README.split('\n## Features\n', 1)[1].split('\n## ', 1)[0]


def heading_anchors(markdown):
    """GitHub-style anchors of all headings outside code blocks."""
    anchors, in_code = set(), False
    for line in markdown.splitlines():
        if line.startswith('```'):
            in_code = not in_code
        elif not in_code and re.match(r'#{1,6} ', line):
            heading = line.lstrip('#').strip().lower()
            anchors.add(re.sub(r'[^\w\- ]', '', heading).replace(' ', '-'))
    return anchors


def feature_links(bullet):
    """How-it-works anchors linked from a bullet; links inside inline code spans are not links."""
    text = re.sub(r'(`+).*?\1', '', bullet)
    return re.findall(r'\]\(docs/how-it-works\.md#([^)]+)\)', text)


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


    def test_feature_bullets_link_to_existing_how_it_works_sections(self):
        anchors = heading_anchors(HOW_IT_WORKS)
        bullets = [line for line in features_section().splitlines() if line.startswith('- ')]
        for bullet in bullets:
            with self.subTest(bullet=bullet):
                links = feature_links(bullet)
                self.assertTrue(links, 'each feature links to its how-it-works section')
                for anchor in links:
                    self.assertIn(anchor, anchors)

    def test_a_link_inside_an_inline_code_span_does_not_count(self):
        self.assertEqual(feature_links(
            '- ` [Tournaments](docs/how-it-works.md#tournament-formats-and-draw-policies) `'), [])
        self.assertEqual(feature_links(
            '- `code` [Tournaments](docs/how-it-works.md#tournament-formats-and-draw-policies)'),
            ['tournament-formats-and-draw-policies'])


if __name__ == '__main__':
    unittest.main()
