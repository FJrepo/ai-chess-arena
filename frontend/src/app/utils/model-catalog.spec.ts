import { OpenRouterModelOption } from '../models/openrouter.model';
import {
  filterModels,
  formatIntelligenceIndex,
  formatPrice,
  formatReasoning,
  formatReasoningEfforts,
  formatReleaseDate,
  formatTokenCount,
  groupByProvider,
  isFreeModel,
  matchesQuery,
  modelMetaParts,
  providerOptions,
  sortModels,
} from './model-catalog';

function catalogModel(
  id: string,
  overrides: Partial<OpenRouterModelOption> = {},
): OpenRouterModelOption {
  return {
    id,
    name: id,
    provider: id.split('/')[0],
    contextLength: null,
    promptPricePerMillion: null,
    completionPricePerMillion: null,
    featured: false,
    releasedAt: null,
    cacheReadPricePerMillion: null,
    maxCompletionTokens: null,
    reasoningSupported: false,
    reasoningMandatory: null,
    reasoningEfforts: [],
    intelligenceIndex: null,
    description: null,
    ...overrides,
  };
}

const sonnet = catalogModel('anthropic/claude-sonnet-5.5', {
  name: 'Anthropic: Claude Sonnet 5.5',
  releasedAt: '2026-09-01',
  promptPricePerMillion: 3,
  completionPricePerMillion: 15,
  reasoningSupported: true,
  intelligenceIndex: 61.2,
});
const gpt = catalogModel('openai/gpt-6', {
  name: 'OpenAI: GPT-6',
  releasedAt: '2026-10-20',
  promptPricePerMillion: 2,
  completionPricePerMillion: 8,
  reasoningSupported: true,
  intelligenceIndex: 66,
});
const flash = catalogModel('google/gemini-4-flash', {
  name: 'Google: Gemini 4 Flash',
  releasedAt: '2026-11-02',
  promptPricePerMillion: 0.1,
  completionPricePerMillion: 0.4,
});
const free = catalogModel('inclusionai/ling-3.2-flash:free', {
  name: 'inclusionAI: Ling 3.2 Flash (free)',
  releasedAt: '2026-11-13',
  promptPricePerMillion: 0,
  completionPricePerMillion: 0,
});
const unknown = catalogModel('example/mystery');
const llama = catalogModel('meta/llama-5', { name: 'Meta: Llama 5', releasedAt: '2026-06-01' });
const all = [sonnet, gpt, flash, free, unknown, llama];

const noFilters = { query: '', provider: '', reasoningOnly: false, freeOnly: false };

describe('model catalog search', () => {
  it('requires every token, in any order, in name, id or provider', () => {
    expect(matchesQuery(sonnet, 'anthropic sonnet')).toBe(true);
    expect(matchesQuery(sonnet, 'sonnet anthropic')).toBe(true);
    expect(matchesQuery(sonnet, 'SONNET 5.5')).toBe(true);
    expect(matchesQuery(gpt, 'gpt 6')).toBe(true);
    expect(matchesQuery(sonnet, 'anthropic gpt')).toBe(false);
    expect(matchesQuery(flash, 'flash')).toBe(true);
    expect(matchesQuery(free, 'flash')).toBe(true);
    expect(matchesQuery(gpt, '')).toBe(true);
    expect(matchesQuery(gpt, '   ')).toBe(true);
  });

  it('treats "-", "." and spaces alike', () => {
    expect(matchesQuery(sonnet, 'claude-sonnet')).toBe(true);
    expect(matchesQuery(sonnet, 'sonnet-5.5')).toBe(true);
    expect(matchesQuery(sonnet, 'sonnet 5-5')).toBe(true);
    expect(matchesQuery(gpt, 'gpt-6')).toBe(true);
    expect(matchesQuery(flash, 'gemini.4')).toBe(true);
  });

  it('matches the provider display name', () => {
    // The id says "x-ai"; the display name is "xAI".
    const grok = catalogModel('x-ai/grok-5', { name: 'Grok 5' });
    expect(matchesQuery(grok, 'xai grok')).toBe(true);
    expect(matchesQuery(llama, 'meta llama')).toBe(true);
  });
});

describe('model catalog filters', () => {
  it('filters by query and provider', () => {
    expect(filterModels(all, { ...noFilters, query: 'flash' })).toEqual([flash, free]);
    expect(filterModels(all, { ...noFilters, provider: 'openai' })).toEqual([gpt]);
    expect(filterModels(all, noFilters)).toEqual(all);
  });

  it('keeps only reasoning models when asked', () => {
    expect(filterModels(all, { ...noFilters, reasoningOnly: true })).toEqual([sonnet, gpt]);
  });

  it('keeps only free models when asked', () => {
    expect(filterModels(all, { ...noFilters, freeOnly: true })).toEqual([free]);
  });

  it('counts a model as free only when both prices are known to be zero', () => {
    expect(isFreeModel(free)).toBe(true);
    expect(isFreeModel(unknown)).toBe(false);
    expect(isFreeModel(catalogModel('a/b', { promptPricePerMillion: 0 }))).toBe(false);
    expect(
      isFreeModel(catalogModel('a/b', { promptPricePerMillion: 0, completionPricePerMillion: 1 })),
    ).toBe(false);
  });

  it('merges provider aliases in the provider filter', () => {
    expect(filterModels(all, { ...noFilters, provider: 'meta-llama' })).toEqual([llama]);
  });
});

describe('model catalog sorting', () => {
  const ids = (models: OpenRouterModelOption[]) => models.map((model) => model.id);

  it('sorts newest first with unknown release dates last', () => {
    expect(ids(sortModels(all, 'newest'))).toEqual([
      free.id,
      flash.id,
      gpt.id,
      sonnet.id,
      llama.id,
      unknown.id,
    ]);
  });

  it('sorts cheapest by input plus output price with unknown prices last', () => {
    expect(ids(sortModels(all, 'cheapest'))).toEqual([
      free.id,
      flash.id,
      gpt.id,
      sonnet.id,
      // Both unpriced: newest first.
      llama.id,
      unknown.id,
    ]);
  });

  it('sorts by Intelligence Index, highest first, models without an index last', () => {
    expect(ids(sortModels(all, 'intelligence'))).toEqual([
      gpt.id,
      sonnet.id,
      // No index: newest first.
      free.id,
      flash.id,
      llama.id,
      unknown.id,
    ]);
  });

  it('does not change the input array', () => {
    const input = [...all];
    sortModels(input, 'cheapest');
    expect(input).toEqual(all);
  });
});

describe('model catalog grouping', () => {
  it('lists featured providers first in their configured order, then the rest by name', () => {
    const options = providerOptions(all, ['openai', 'anthropic', 'google', 'meta-llama']);
    expect(options.map((option) => option.provider)).toEqual([
      'openai',
      'anthropic',
      'google',
      'meta-llama',
      'example',
      'inclusionai',
    ]);
    expect(options[0]).toEqual({ provider: 'openai', label: 'OpenAI', count: 1 });
  });

  it('groups sorted models by provider and keeps their order', () => {
    const second = catalogModel('openai/gpt-5', { name: 'GPT-5', releasedAt: '2025-08-07' });
    const groups = groupByProvider(sortModels([sonnet, second, gpt], 'newest'), ['openai']);

    expect(groups.map((group) => group.provider)).toEqual(['openai', 'anthropic']);
    expect(groups[0].label).toBe('OpenAI');
    expect(groups[0].models).toEqual([gpt, second]);
    expect(groups[1].models).toEqual([sonnet]);
  });

  it('returns no groups for no models', () => {
    expect(groupByProvider([], ['openai'])).toEqual([]);
  });
});

describe('model catalog formatting', () => {
  it('shows n/a instead of 0 or NaN for missing values', () => {
    for (const missing of [null, undefined, Number.NaN]) {
      expect(formatPrice(missing)).toBe('n/a');
      expect(formatTokenCount(missing)).toBe('n/a');
      expect(formatIntelligenceIndex(missing)).toBe('n/a');
    }
    expect(formatPrice(-1)).toBe('n/a');
    expect(formatTokenCount(0)).toBe('n/a');
    expect(formatReleaseDate(null)).toBe('n/a');
    expect(formatReleaseDate('yesterday')).toBe('n/a');
    expect(formatReasoningEfforts(unknown)).toBe('n/a');
  });

  it('formats known values', () => {
    expect(formatPrice(0)).toBe('Free');
    expect(formatPrice(3)).toBe('$3.00');
    expect(formatPrice(0.1)).toBe('$0.10');
    expect(formatPrice(0.075)).toBe('$0.075');
    expect(formatPrice(0.01)).toBe('$0.01');
    expect(formatTokenCount(128_000)).toBe('128K');
    expect(formatTokenCount(1_048_576)).toBe('1M');
    expect(formatTokenCount(1_800_000)).toBe('1.8M');
    expect(formatTokenCount(512)).toBe('512');
    expect(formatIntelligenceIndex(60)).toBe('60');
    expect(formatIntelligenceIndex(36.5)).toBe('36.5');
    expect(formatReleaseDate('2026-10-07')).toBe('2026-10-07');
  });

  it('describes reasoning support', () => {
    expect(formatReasoning(unknown)).toBe('Not supported');
    expect(formatReasoning(sonnet)).toBe('Supported');
    expect(formatReasoning({ ...sonnet, reasoningMandatory: true })).toBe('Always on');
    expect(formatReasoning({ ...sonnet, reasoningMandatory: false })).toBe('Optional');
    expect(formatReasoningEfforts({ ...sonnet, reasoningEfforts: ['high', 'low'] })).toBe(
      'high, low',
    );
  });

  it('builds a compact meta line and leaves out missing facts', () => {
    expect(modelMetaParts({ ...sonnet, contextLength: 1_000_000 })).toEqual([
      '2026-09-01',
      '$3.00 in · $15.00 out /1M',
      '1M ctx',
      'Reasoning',
      'AA 61.2',
    ]);
    expect(modelMetaParts(free)).toEqual(['2026-11-13', 'Free']);
    expect(modelMetaParts(unknown)).toEqual(['Price n/a']);
  });

  it('tolerates models from an older backend without the detail fields', () => {
    const legacy: OpenRouterModelOption = {
      id: 'openai/gpt-4o',
      name: 'GPT-4o',
      provider: 'openai',
      contextLength: 128_000,
      promptPricePerMillion: 2.5,
      completionPricePerMillion: 10,
      featured: true,
    };
    expect(modelMetaParts(legacy)).toEqual(['$2.50 in · $10.00 out /1M', '128K ctx']);
    expect(formatReasoning(legacy)).toBe('Not supported');
    expect(sortModels([legacy, gpt], 'newest')).toEqual([gpt, legacy]);
  });
});
