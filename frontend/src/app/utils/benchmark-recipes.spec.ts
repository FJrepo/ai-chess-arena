import { REASONING_EFFORTS } from './reasoning-effort';
import {
  BENCHMARK_RECIPES,
  RECIPE_GUIDE_URL,
  TOURNAMENT_RECIPES,
  findRecipe,
  recipeGuideLink,
  recipeStillApplies,
} from './benchmark-recipes';
import definitions from './benchmark-recipes.json';

const efforts: readonly string[] = [...REASONING_EFFORTS, 'default'];
const chatModes = ['OFF', 'OPTIONAL', 'MANDATORY'];
const slug = (label: string) =>
  label
    .toLowerCase()
    .replace(/[^a-z0-9 -]/g, '')
    .replace(/ /g, '-');

describe('benchmark recipes', () => {
  it('exposes the JSON definitions unchanged', () => {
    expect(BENCHMARK_RECIPES).toEqual(definitions.benchmark);
    expect(TOURNAMENT_RECIPES).toEqual(definitions.tournament);
    expect(RECIPE_GUIDE_URL).toBe(
      'https://github.com/FJrepo/ai-chess-arena/blob/main/docs/benchmarks.md',
    );
  });

  it('uses unique IDs and anchors that match the guide headings', () => {
    const recipes = [...BENCHMARK_RECIPES, ...TOURNAMENT_RECIPES];
    expect(new Set(recipes.map((recipe) => recipe.id)).size).toBe(recipes.length);
    for (const recipe of recipes) {
      expect(recipe.anchor).toBe(slug(recipe.label));
      expect(recipe.summary.length).toBeLessThanOrEqual(80);
    }
  });

  it('only uses values the create forms accept', () => {
    for (const { settings } of BENCHMARK_RECIPES) {
      expect(efforts).toContain(settings.reasoningEffort);
      expect(chatModes).toContain(settings.chatMode);
      const [low, high] = settings.targetStrengths;
      expect(low).not.toBe(high);
      for (const level of [low, high]) {
        expect(Number.isInteger(level) && level >= 1320 && level <= 3190).toBe(true);
      }
    }
    for (const { settings } of TOURNAMENT_RECIPES) {
      expect(efforts).toContain(settings.reasoningEffort);
      expect(chatModes).toContain(settings.chatMode);
      expect([null, 'POLITE', 'PLAYFUL', 'SPICY', 'MIND_GAMES']).toContain(settings.banterLevel);
      expect(Number.isInteger(settings.maxAttempts)).toBe(true);
      const seconds = settings.moveTimeoutSeconds;
      expect(
        seconds === null || (Number.isInteger(seconds) && seconds >= 10 && seconds <= 3600),
      ).toBe(true);
    }
  });

  it('links to the recipe section of the guide and detects edits', () => {
    const recipe = findRecipe(BENCHMARK_RECIPES, 'board-understanding')!;
    expect(recipeGuideLink(recipe)).toBe(`${RECIPE_GUIDE_URL}#board-understanding`);
    expect(recipeGuideLink(undefined)).toBe(RECIPE_GUIDE_URL);
    expect(findRecipe(BENCHMARK_RECIPES, '')).toBeUndefined();
    expect(findRecipe(BENCHMARK_RECIPES, 'unknown')).toBeUndefined();
    expect(recipeStillApplies(recipe, { ...recipe.settings })).toBe(true);
    expect(recipeStillApplies(recipe, { ...recipe.settings, targetStrengths: [1500, 2100] })).toBe(
      false,
    );
  });
});
