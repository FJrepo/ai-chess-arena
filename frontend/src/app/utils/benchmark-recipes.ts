import type { CreateBenchmarkRequest } from '../models/benchmark.model';
import type { BanterLevel, ChatMode } from '../models/tournament.model';
import type { ReasoningEffort } from './reasoning-effort';
import definitions from './benchmark-recipes.json';

/**
 * Setup recipes from docs/benchmarks.md. The JSON file is the single source of truth: the
 * Benchmarks page and tournament setup pre-fill from it, and scripts/test_benchmark_recipes.py
 * checks that the guide documents exactly these settings.
 */
export interface Recipe<S> {
  id: string;
  label: string;
  /** Heading anchor in docs/benchmarks.md. */
  anchor: string;
  /** One-line explanation shown under the selector. */
  summary: string;
  settings: Readonly<S>;
}

export type BenchmarkRecipeSettings = Pick<
  CreateBenchmarkRequest,
  'legalMoveHints' | 'reasoningEffort' | 'chatMode' | 'targetStrengths'
>;

export interface TournamentRecipeSettings {
  legalMoveHints: boolean;
  reasoningEffort: ReasoningEffort | 'default';
  chatMode: ChatMode;
  banterLevel: BanterLevel | null;
  resignationAllowed: boolean;
  adjudicationEnabled: boolean;
  limitMoveTime: boolean;
  maxAttempts: number;
}

export const RECIPE_GUIDE_URL: string = definitions.guideUrl;

export const BENCHMARK_RECIPES =
  definitions.benchmark as unknown as readonly Recipe<BenchmarkRecipeSettings>[];

export const TOURNAMENT_RECIPES =
  definitions.tournament as unknown as readonly Recipe<TournamentRecipeSettings>[];

export function recipeGuideLink(recipe: Recipe<unknown> | undefined): string {
  return recipe ? `${RECIPE_GUIDE_URL}#${recipe.anchor}` : RECIPE_GUIDE_URL;
}

export function findRecipe<S>(
  recipes: readonly Recipe<S>[],
  id: string | null | undefined,
): Recipe<S> | undefined {
  return id ? recipes.find((recipe) => recipe.id === id) : undefined;
}

/** Whether every setting of the recipe still has the recipe's value in the form. */
export function recipeStillApplies<S extends object>(recipe: Recipe<S>, form: S): boolean {
  return (Object.keys(recipe.settings) as (keyof S)[]).every(
    (key) => JSON.stringify(form[key]) === JSON.stringify(recipe.settings[key]),
  );
}
