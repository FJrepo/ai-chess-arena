"""Checks that docs/benchmarks.md documents exactly the setup recipes the UI pre-fills.

The recipes are defined once in frontend/src/app/utils/benchmark-recipes.json; the Benchmarks page
and tournament setup read that file, and this test renders each recipe's settings line from it.
"""

import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RECIPES = ROOT / 'frontend' / 'src' / 'app' / 'utils' / 'benchmark-recipes.json'
GUIDE = ROOT / 'docs' / 'benchmarks.md'

EFFORTS = {
    'default': 'Model default',
    'none': 'None (reasoning off)',
    'minimal': 'Minimal',
    'low': 'Low',
    'medium': 'Medium',
    'high': 'High',
    'xhigh': 'Extra high',
    'max': 'Max',
}
CHAT = {'OFF': 'Off', 'OPTIONAL': 'Optional', 'MANDATORY': 'Mandatory'}
BANTER = {None: 'Original', 'POLITE': 'Polite', 'PLAYFUL': 'Playful', 'SPICY': 'Spicy',
          'MIND_GAMES': 'Mind games'}


def on_off(value):
    return 'on' if value else 'off'


def move_time(seconds):
    return 'off' if seconds is None else f'{seconds} seconds'


def benchmark_line(settings):
    low, high = settings['targetStrengths']
    return ' · '.join([
        f"Legal move hints **{on_off(settings['legalMoveHints'])}**",
        f"Reasoning effort **{EFFORTS[settings['reasoningEffort']]}**",
        f"Chat mode **{CHAT[settings['chatMode']]}**",
        f'Stockfish levels **{low}** and **{high}**',
    ])


def tournament_line(settings):
    return ' · '.join([
        f"Legal move hints **{on_off(settings['legalMoveHints'])}**",
        f"Reasoning effort **{EFFORTS[settings['reasoningEffort']]}**",
        f"Chat **{CHAT[settings['chatMode']]}**",
        f"Banter **{BANTER[settings['banterLevel']]}**",
        f"Allow resignation **{on_off(settings['resignationAllowed'])}**",
        f"Adjudicate decisive wins **{on_off(settings['adjudicationEnabled'])}**",
        f'Limit time per move **{move_time(settings["moveTimeoutSeconds"])}**',
        f"Max attempts per move **{settings['maxAttempts']}**",
    ])


def anchor(heading):
    return re.sub(r'[^a-z0-9 -]', '', heading.lower()).replace(' ', '-')


def sections(text):
    parts = re.split(r'^## (.+)$', text, flags=re.MULTILINE)
    return {heading.strip(): body for heading, body in zip(parts[1::2], parts[2::2])}


class BenchmarkRecipesDocTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.definitions = json.loads(RECIPES.read_text(encoding='utf-8'))
        cls.guide = GUIDE.read_text(encoding='utf-8')
        cls.sections = sections(cls.guide)

    def recipes(self):
        for page, render, place in (
            ('benchmark', benchmark_line, 'Benchmarks → Recipe →'),
            ('tournament', tournament_line, 'Test recipe →'),
        ):
            for recipe in self.definitions[page]:
                yield recipe, render, place

    def test_each_recipe_has_a_section_with_exactly_its_settings(self):
        for recipe, render, place in self.recipes():
            with self.subTest(recipe=recipe['id']):
                self.assertIn(recipe['label'], self.sections)
                self.assertEqual(recipe['anchor'], anchor(recipe['label']))
                body = self.sections[recipe['label']]
                lines = [line for line in body.splitlines()
                         if line.startswith('**Recipe settings:**')]
                self.assertEqual(lines, [f"**Recipe settings:** {render(recipe['settings'])}"])
                self.assertIn(f"{place} *{recipe['label']}*", body)

    def test_overview_links_every_recipe(self):
        for recipe, _render, _place in self.recipes():
            with self.subTest(recipe=recipe['id']):
                self.assertIn(f"[{recipe['label']}](#{recipe['anchor']})", self.guide)

    def test_guide_link_points_to_this_guide(self):
        self.assertTrue(self.definitions['guideUrl'].endswith('/blob/main/docs/benchmarks.md'))

    def test_no_section_documents_settings_of_an_unknown_recipe(self):
        labels = {recipe['label'] for recipe, _render, _place in self.recipes()}
        for heading, body in self.sections.items():
            if heading not in labels:
                with self.subTest(section=heading):
                    self.assertNotRegex(body, r'\*\*Recipe settings:\*\* .*\*\*')


if __name__ == '__main__':
    unittest.main()
