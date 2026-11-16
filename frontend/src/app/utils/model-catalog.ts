import { OpenRouterModelOption } from '../models/openrouter.model';
import { normalizeProviderKey, providerDisplayName } from './provider-brand';

/** Sort orders of the model picker. */
export type ModelSort = 'newest' | 'cheapest' | 'intelligence';

export interface ModelFilters {
  /** Free text; every whitespace-separated token must occur in name, id or provider. */
  query: string;
  /** Provider key, or '' for all providers. */
  provider: string;
  reasoningOnly: boolean;
  freeOnly: boolean;
}

export interface ProviderGroup {
  provider: string;
  label: string;
  models: OpenRouterModelOption[];
}

export interface ProviderOption {
  provider: string;
  label: string;
  count: number;
}

/** Lower case, with every run of punctuation or whitespace turned into one space ("gpt-5.5" -> "gpt 5 5"). */
export function normalizeSearchText(text: string): string {
  return text
    .toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, ' ')
    .trim();
}

/** Query tokens, each normalized like the searched text; punctuation inside a token stays a phrase. */
export function queryTokens(query: string): string[] {
  return query
    .split(/\s+/)
    .map(normalizeSearchText)
    .filter((token) => token.length > 0);
}

/** Provider key of a model, with aliases merged ("meta" and "meta-llama" are one provider). */
export function providerKey(model: OpenRouterModelOption): string {
  return normalizeProviderKey(model.provider, model.id);
}

const searchTextCache = new WeakMap<OpenRouterModelOption, string>();

function searchableText(model: OpenRouterModelOption): string {
  let text = searchTextCache.get(model);
  if (text === undefined) {
    text = normalizeSearchText(
      [model.name, model.id, model.provider, providerDisplayName(model.provider, model.id)].join(
        ' ',
      ),
    );
    searchTextCache.set(model, text);
  }
  return text;
}

/**
 * True when every token of the query occurs (case-insensitive, in any order) in the model's name, id or
 * provider; "-", "." and spaces are interchangeable, so "sonnet 5.5", "anthropic sonnet" and "gpt-6" match.
 */
export function matchesQuery(model: OpenRouterModelOption, query: string): boolean {
  const tokens = queryTokens(query);
  if (tokens.length === 0) {
    return true;
  }
  const text = searchableText(model);
  return tokens.every((token) => text.includes(token));
}

export const LATEST_ALIAS_LABEL = 'Latest alias';
export const LATEST_ALIAS_NOTE =
  "Moving alias: OpenRouter routes it to the provider's newest model, so results can't be tied to a " +
  'fixed model version.';

/** OpenRouter's moving "latest" aliases have ids starting with "~", e.g. "~anthropic/claude-sonnet-latest". */
export function isLatestAlias(model: OpenRouterModelOption): boolean {
  return model.id.startsWith('~');
}

/** A model whose prompt and completion prices are both known to be zero. */
export function isFreeModel(model: OpenRouterModelOption): boolean {
  return model.promptPricePerMillion === 0 && model.completionPricePerMillion === 0;
}

export function filterModels(
  models: readonly OpenRouterModelOption[],
  filters: ModelFilters,
): OpenRouterModelOption[] {
  return models.filter(
    (model) =>
      (!filters.provider || providerKey(model) === filters.provider) &&
      (!filters.reasoningOnly || model.reasoningSupported === true) &&
      (!filters.freeOnly || isFreeModel(model)) &&
      matchesQuery(model, filters.query),
  );
}

function totalPrice(model: OpenRouterModelOption): number | null {
  const prompt = finiteOrNull(model.promptPricePerMillion);
  const completion = finiteOrNull(model.completionPricePerMillion);
  return prompt === null || completion === null ? null : prompt + completion;
}

function finiteOrNull(value: number | null | undefined): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

/** Compares two possibly missing values; missing ones always go last. */
function compareNullsLast<T>(a: T | null, b: T | null, compare: (x: T, y: T) => number): number {
  if (a === null || b === null) {
    return a === null ? (b === null ? 0 : 1) : -1;
  }
  return compare(a, b);
}

const byNewest = (a: OpenRouterModelOption, b: OpenRouterModelOption) =>
  compareNullsLast(a.releasedAt || null, b.releasedAt || null, (x, y) => y.localeCompare(x));

/**
 * Concrete models before "latest" aliases; then newest first by release date, cheapest by input + output
 * price, or intelligence by the Intelligence Index, highest first. Models without the value go last; ties
 * fall back to newest, then name.
 */
export function sortModels(
  models: readonly OpenRouterModelOption[],
  sort: ModelSort,
): OpenRouterModelOption[] {
  const primary = (a: OpenRouterModelOption, b: OpenRouterModelOption): number => {
    switch (sort) {
      case 'cheapest':
        return compareNullsLast(totalPrice(a), totalPrice(b), (x, y) => x - y);
      case 'intelligence':
        return compareNullsLast(
          finiteOrNull(a.intelligenceIndex),
          finiteOrNull(b.intelligenceIndex),
          (x, y) => y - x,
        );
      default:
        return 0;
    }
  };
  const aliasLast = (a: OpenRouterModelOption, b: OpenRouterModelOption) =>
    Number(isLatestAlias(a)) - Number(isLatestAlias(b));
  return [...models].sort(
    (a, b) => aliasLast(a, b) || primary(a, b) || byNewest(a, b) || a.name.localeCompare(b.name),
  );
}

/**
 * The providers in the catalog (aliases merged): the featured providers in their configured order first,
 * then the rest by display name.
 */
export function providerOptions(
  models: readonly OpenRouterModelOption[],
  featuredProviders: readonly string[],
): ProviderOption[] {
  const counts = new Map<string, number>();
  for (const model of models) {
    const key = providerKey(model);
    counts.set(key, (counts.get(key) ?? 0) + 1);
  }
  const featured = featuredProviders.map((provider) => normalizeProviderKey(provider));
  const rank = (provider: string) => {
    const index = featured.indexOf(provider);
    return index === -1 ? Number.MAX_SAFE_INTEGER : index;
  };
  return [...counts.entries()]
    .map(([provider, count]) => ({ provider, label: providerDisplayName(provider), count }))
    .sort(
      (a, b) =>
        rank(a.provider) - rank(b.provider) ||
        a.label.localeCompare(b.label) ||
        a.provider.localeCompare(b.provider),
    );
}

/**
 * Groups already sorted models by provider, keeping their order inside each group. Groups follow the
 * provider order of {@link providerOptions}: featured providers first, then by display name.
 */
export function groupByProvider(
  models: readonly OpenRouterModelOption[],
  featuredProviders: readonly string[],
): ProviderGroup[] {
  const byProvider = new Map<string, OpenRouterModelOption[]>();
  for (const model of models) {
    const key = providerKey(model);
    const group = byProvider.get(key);
    if (group) {
      group.push(model);
    } else {
      byProvider.set(key, [model]);
    }
  }
  return providerOptions(models, featuredProviders).map((option) => ({
    provider: option.provider,
    label: option.label,
    models: byProvider.get(option.provider) ?? [],
  }));
}

export const NOT_AVAILABLE = 'n/a';

/**
 * At least two decimals, more for small prices (two significant digits): "$3.00", "$0.10", "$0.50", "$0.01",
 * "$0.075", "$0.0002"; "Free" for 0; "n/a" for a missing, negative or non-numeric price.
 */
export function formatPrice(pricePerMillion: number | null | undefined): string {
  const value = finiteOrNull(pricePerMillion);
  if (value === null || value < 0) {
    return NOT_AVAILABLE;
  }
  if (value === 0) {
    return 'Free';
  }
  if (value >= 0.1) {
    return `$${value.toFixed(2)}`;
  }
  // Round to two significant digits first, so 0.0999999 (0.0000001 per token x 1M) becomes 0.10.
  const rounded = Number(value.toPrecision(2));
  const decimals = Math.min(10, Math.max(2, 1 - Math.floor(Math.log10(rounded))));
  const fixed = rounded.toFixed(decimals);
  return `$${fixed.replace(/(\.\d\d\d*?)0+$/, '$1')}`;
}

/** Token counts such as "128K", "1M", "1.8M"; "n/a" when missing or not positive. */
export function formatTokenCount(tokens: number | null | undefined): string {
  const value = finiteOrNull(tokens);
  if (value === null || value <= 0) {
    return NOT_AVAILABLE;
  }
  if (value >= 1_000_000) {
    return `${Number((value / 1_000_000).toFixed(1))}M`;
  }
  if (value >= 1_000) {
    return `${Math.round(value / 1_000)}K`;
  }
  return `${Math.round(value)}`;
}

export function formatReleaseDate(releasedAt: string | null | undefined): string {
  return releasedAt && /^\d{4}-\d{2}-\d{2}$/.test(releasedAt) ? releasedAt : NOT_AVAILABLE;
}

export function formatIntelligenceIndex(index: number | null | undefined): string {
  const value = finiteOrNull(index);
  if (value === null) {
    return NOT_AVAILABLE;
  }
  return Number.isInteger(value) ? `${value}` : value.toFixed(1);
}

/** "Not supported", "Always on", "Optional", or "Supported" when the catalog does not say. */
export function formatReasoning(model: OpenRouterModelOption): string {
  if (!model.reasoningSupported) {
    return 'Not supported';
  }
  if (model.reasoningMandatory === true) {
    return 'Always on';
  }
  return model.reasoningMandatory === false ? 'Optional' : 'Supported';
}

export function formatReasoningEfforts(model: OpenRouterModelOption): string {
  const efforts = model.reasoningEfforts ?? [];
  return efforts.length > 0 ? efforts.join(', ') : NOT_AVAILABLE;
}

/**
 * Compact facts for a suggestion: alias marker, release date, prices, context, reasoning and AA index;
 * missing ones left out.
 */
export function modelMetaParts(model: OpenRouterModelOption): string[] {
  const parts: string[] = [];
  if (isLatestAlias(model)) {
    parts.push(LATEST_ALIAS_LABEL);
  }
  if (formatReleaseDate(model.releasedAt) !== NOT_AVAILABLE) {
    parts.push(formatReleaseDate(model.releasedAt));
  }
  if (isFreeModel(model)) {
    parts.push('Free');
  } else {
    const input = formatPrice(model.promptPricePerMillion);
    const output = formatPrice(model.completionPricePerMillion);
    parts.push(
      input === NOT_AVAILABLE && output === NOT_AVAILABLE
        ? 'Price n/a'
        : `${input} in · ${output} out /1M`,
    );
  }
  if (formatTokenCount(model.contextLength) !== NOT_AVAILABLE) {
    parts.push(`${formatTokenCount(model.contextLength)} ctx`);
  }
  if (model.reasoningSupported) {
    parts.push('Reasoning');
  }
  if (formatIntelligenceIndex(model.intelligenceIndex) !== NOT_AVAILABLE) {
    parts.push(`AA ${formatIntelligenceIndex(model.intelligenceIndex)}`);
  }
  return parts;
}
