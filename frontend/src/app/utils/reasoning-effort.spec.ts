import {
  nearestEffort,
  reasoningEffortLabel,
  suggestedLowerEffort,
  supportedEfforts,
} from './reasoning-effort';

describe('reasoning effort', () => {
  it('lists a model’s levels in order and nothing for a model without effort control', () => {
    expect(
      supportedEfforts({
        id: 'moonshotai/kimi-k3.1-pro',
        name: 'Kimi',
        provider: 'moonshotai',
        contextLength: null,
        promptPricePerMillion: null,
        completionPricePerMillion: null,
        featured: false,
        reasoningEfforts: ['max', 'high', 'low', 'none', 'ultra'],
      }),
    ).toEqual(['none', 'low', 'high', 'max']);
    expect(supportedEfforts(null)).toEqual([]);
  });

  it('maps to the nearest supported level like the backend', () => {
    expect(nearestEffort('low', ['minimal', 'low', 'max'])).toBe('low');
    expect(nearestEffort('medium', ['none', 'low', 'high', 'max'])).toBe('low');
    expect(nearestEffort('xhigh', ['none', 'low', 'high', 'max'])).toBe('high');
    expect(nearestEffort('minimal', ['none', 'low'])).toBe('low');
    expect(nearestEffort('none', ['minimal', 'low'])).toBe('minimal');
    expect(nearestEffort('low', ['none'])).toBeNull();
  });

  it('suggests low for a cut-off answer unless the side already asks for less', () => {
    const ember = ['minimal', 'low', 'medium', 'high', 'xhigh', 'max'] as const;
    expect(suggestedLowerEffort(null, ember)).toBe('low');
    expect(suggestedLowerEffort('max', ember)).toBe('low');
    expect(suggestedLowerEffort('minimal', ember)).toBe('minimal');
    expect(suggestedLowerEffort(null, ['high', 'medium'])).toBe('medium');
    expect(suggestedLowerEffort(null, [])).toBeNull();
  });

  it('labels levels readably', () => {
    expect(reasoningEffortLabel('xhigh')).toBe('Extra high');
    expect(reasoningEffortLabel('none')).toBe('None (reasoning off)');
    expect(reasoningEffortLabel(null)).toBe('');
  });
});
