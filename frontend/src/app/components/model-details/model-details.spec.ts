import { TestBed } from '@angular/core/testing';
import { OpenRouterModelOption } from '../../models/openrouter.model';
import { ModelDetails } from './model-details';

function render(model: OpenRouterModelOption): HTMLElement {
  const fixture = TestBed.createComponent(ModelDetails);
  fixture.componentRef.setInput('model', model);
  fixture.detectChanges();
  return fixture.nativeElement;
}

function facts(element: HTMLElement): Record<string, string> {
  const result: Record<string, string> = {};
  element.querySelectorAll('.fact').forEach((fact) => {
    result[fact.querySelector('dt')!.textContent!.trim()] = fact
      .querySelector('dd')!
      .textContent!.trim();
  });
  return result;
}

describe('ModelDetails', () => {
  it('shows every detail of a fully described model', () => {
    const element = render({
      id: 'anthropic/claude-haiku-5.5',
      name: 'Anthropic: Claude Haiku 5.5',
      provider: 'anthropic',
      contextLength: 1_000_000,
      promptPricePerMillion: 0.1,
      completionPricePerMillion: 0.5,
      featured: true,
      releasedAt: '2026-10-07',
      cacheReadPricePerMillion: 0.01,
      maxCompletionTokens: 128_000,
      reasoningSupported: true,
      reasoningMandatory: false,
      reasoningEfforts: ['high', 'medium', 'low'],
      intelligenceIndex: 36.5,
      description: 'Small, fast model.',
    });

    expect(element.textContent).toContain('Anthropic: Claude Haiku 5.5');
    expect(element.textContent).toContain('anthropic/claude-haiku-5.5');
    expect(facts(element)).toEqual({
      Released: '2026-10-07',
      'Input / 1M tokens': '$0.10',
      'Output / 1M tokens': '$0.50',
      'Cache read / 1M tokens': '$0.01',
      'Context window': '1M',
      'Max output tokens': '128K',
      Reasoning: 'Optional',
      'Effort levels': 'high, medium, low',
      'Intelligence Index': '36.5',
    });
    expect(element.querySelector('.details-description')?.textContent?.trim()).toBe(
      'Small, fast model.',
    );
  });

  it('shows n/a for missing values, never 0 or NaN', () => {
    const element = render({
      id: 'example/mystery',
      name: 'Mystery',
      provider: 'example',
      contextLength: null,
      promptPricePerMillion: null,
      completionPricePerMillion: null,
      featured: false,
    });

    const values = Object.values(facts(element));
    expect(values.filter((value) => value === 'n/a')).toHaveLength(8);
    expect(facts(element)['Reasoning']).toBe('Not supported');
    expect(element.textContent).not.toMatch(/NaN|\$0\b/);
    expect(element.querySelector('.details-description')?.textContent?.trim()).toBe(
      'Description: n/a',
    );
  });

  it('credits Artificial Analysis for the Intelligence Index', () => {
    const element = render({
      id: 'openai/gpt-6',
      name: 'GPT-6',
      provider: 'openai',
      contextLength: null,
      promptPricePerMillion: 0,
      completionPricePerMillion: 0,
      featured: true,
    });

    const attribution = element.querySelector('.details-attribution');
    expect(attribution?.textContent?.replace(/\s+/g, ' ').trim()).toBe(
      'Intelligence Index by Artificial Analysis, via OpenRouter',
    );
    expect(attribution?.querySelector('a')?.getAttribute('href')).toBe(
      'https://artificialanalysis.ai',
    );
    expect(facts(element)['Input / 1M tokens']).toBe('Free');
  });
});
