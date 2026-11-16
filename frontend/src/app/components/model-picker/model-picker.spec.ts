import { ComponentFixture, TestBed } from '@angular/core/testing';
import { OpenRouterModelOption } from '../../models/openrouter.model';
import { ModelPicker } from './model-picker';

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
    ...overrides,
  };
}

const gpt6 = catalogModel('openai/gpt-6', { name: 'OpenAI: GPT-6', releasedAt: '2026-10-20' });
const gpt5 = catalogModel('openai/gpt-5', { name: 'OpenAI: GPT-5', releasedAt: '2025-08-07' });
const sonnet = catalogModel('anthropic/claude-sonnet-5.5', {
  name: 'Anthropic: Claude Sonnet 5.5',
  releasedAt: '2026-09-01',
  reasoningSupported: true,
});
const free = catalogModel('inclusionai/ling-3.2-flash:free', {
  name: 'inclusionAI: Ling 3.2 Flash (free)',
  promptPricePerMillion: 0,
  completionPricePerMillion: 0,
});
const catalog = [sonnet, gpt5, free, gpt6];

function render(models = catalog): ComponentFixture<ModelPicker> {
  const fixture = TestBed.createComponent(ModelPicker);
  fixture.componentRef.setInput('models', models);
  fixture.componentRef.setInput('featuredProviders', ['openai', 'anthropic']);
  fixture.detectChanges();
  return fixture;
}

function selections(fixture: ComponentFixture<ModelPicker>): string[] {
  const emitted: string[] = [];
  fixture.componentInstance.selectedId.subscribe((id) => emitted.push(id));
  return emitted;
}

/** Change detection plus the forms and autocomplete microtasks that write the field's value. */
async function settle(fixture: ComponentFixture<ModelPicker>): Promise<void> {
  for (let i = 0; i < 3; i++) {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
  }
}

function key(input: HTMLInputElement, name: string, keyCode: number): void {
  const event = new KeyboardEvent('keydown', { key: name, bubbles: true, cancelable: true });
  Object.defineProperty(event, 'keyCode', { get: () => keyCode });
  input.dispatchEvent(event);
}

describe('ModelPicker', () => {
  it('groups the whole catalog by provider, featured providers first, newest first', () => {
    const picker = render().componentInstance;

    expect(picker.groups().map((group) => group.provider)).toEqual([
      'openai',
      'anthropic',
      'inclusionai',
    ]);
    expect(picker.groups()[0].models).toEqual([gpt6, gpt5]);
    expect(picker.matches()).toHaveLength(4);
  });

  it('lists a "latest" alias under its provider, after the concrete models, with a note', () => {
    const alias = catalogModel('~openai/gpt-astra-latest', {
      name: 'OpenAI: GPT Astra Latest',
      provider: 'openai',
      releasedAt: '2026-11-15',
    });
    const picker = render([alias, ...catalog]).componentInstance;

    expect(picker.providers().map((option) => option.provider)).toEqual([
      'openai',
      'anthropic',
      'inclusionai',
    ]);
    expect(picker.groups()[0].models).toEqual([gpt6, gpt5, alias]);
    expect(picker.metaLine(alias)).toMatch(/^Latest alias · 2026-11-15/);
    expect(picker.aliasNote(alias)).toContain('Moving alias');
    expect(picker.aliasNote(gpt6)).toBeNull();
  });

  it('suggests matches for every typed word', () => {
    const picker = render().componentInstance;

    picker.onInput('gpt 6');
    expect(picker.matches()).toEqual([gpt6]);

    picker.onInput('anthropic sonnet');
    expect(picker.matches()).toEqual([sonnet]);
  });

  it('applies the provider, reasoning and free filters and the sort', () => {
    const picker = render().componentInstance;

    picker.providerFilter.set('openai');
    expect(picker.matches()).toEqual([gpt6, gpt5]);

    picker.providerFilter.set('');
    picker.reasoningOnly.set(true);
    expect(picker.matches()).toEqual([sonnet]);

    picker.reasoningOnly.set(false);
    picker.freeOnly.set(true);
    expect(picker.matches()).toEqual([free]);

    picker.freeOnly.set(false);
    picker.sort.set('cheapest');
    expect(picker.matches()[0]).toBe(free);
  });

  it('lists the providers of the catalog for the provider filter', () => {
    const picker = render().componentInstance;

    expect(picker.providers().map((option) => option.provider)).toEqual([
      'openai',
      'anthropic',
      'inclusionai',
    ]);
    expect(picker.providers()[0].count).toBe(2);
  });

  it('selects a suggestion and shows its name', () => {
    const fixture = render();
    const picker = fixture.componentInstance;
    const emitted = selections(fixture);

    picker.onInput('sonnet');
    picker.onSelected(sonnet);

    expect(picker.selectedId()).toBe(sonnet.id);
    expect(emitted).toEqual([sonnet.id]);
    expect(picker.displayModel(picker.inputValue())).toBe('Anthropic: Claude Sonnet 5.5');
    // Reopening the list shows every model again.
    expect(picker.matches()).toHaveLength(4);
  });

  it('clears the selection when the text is edited or cleared', () => {
    const fixture = render();
    const picker = fixture.componentInstance;
    picker.onSelected(gpt6);
    const emitted = selections(fixture);

    picker.onInput('gpt');
    expect(picker.selectedId()).toBe('');
    expect(picker.inputValue()).toBe('gpt');
    expect(emitted).toEqual(['']);

    picker.onSelected(gpt5);
    picker.clear();
    expect(picker.selectedId()).toBe('');
    expect(picker.inputValue()).toBe('');

    picker.onSelected(gpt5);
    picker.onInput('');
    expect(picker.selectedId()).toBe('');
  });

  it('ignores option objects written back by the autocomplete', () => {
    const picker = render().componentInstance;
    picker.onSelected(gpt6);

    picker.onInput(gpt6);

    expect(picker.selectedId()).toBe(gpt6.id);
  });

  it('empties the field when the parent resets the selection', () => {
    const fixture = render();
    fixture.componentRef.setInput('selectedId', sonnet.id);
    fixture.detectChanges();
    expect(fixture.componentInstance.inputValue()).toBe(sonnet);

    fixture.componentRef.setInput('selectedId', '');
    fixture.detectChanges();

    expect(fixture.componentInstance.inputValue()).toBe('');
  });

  it('selects with the keyboard: the first suggestion is active, arrow keys move, Enter selects', async () => {
    const fixture = render();
    const emitted = selections(fixture);
    const input: HTMLInputElement = fixture.nativeElement.querySelector('input');

    input.focus();
    input.value = 'gpt';
    input.dispatchEvent(new Event('input'));
    await settle(fixture);
    // gpt-6 (newest) is active first; one step down is gpt-5.
    key(input, 'ArrowDown', 40);
    await settle(fixture);
    key(input, 'Enter', 13);
    await settle(fixture);

    expect(emitted).toEqual([gpt5.id]);
    expect(fixture.componentInstance.selectedId()).toBe(gpt5.id);
    expect(input.value).toBe('OpenAI: GPT-5');
  });

  it('is disabled while there is no catalog', async () => {
    const fixture = render([]);
    fixture.componentRef.setInput('disabled', true);
    await settle(fixture);

    const input: HTMLInputElement = fixture.nativeElement.querySelector('input');
    expect(input.disabled).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('0 of 0 models');
  });
});
