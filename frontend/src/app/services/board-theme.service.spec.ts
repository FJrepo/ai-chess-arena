import { TestBed } from '@angular/core/testing';
import { App } from '../app';
import { BoardThemeService } from './board-theme.service';

describe('BoardThemeService', () => {
  let previousBoard: string | null;
  let previousSaved: string | null;

  beforeEach(() => {
    previousBoard = document.documentElement.getAttribute('data-board');
    previousSaved = localStorage.getItem('boardTheme');
    localStorage.removeItem('boardTheme');
    document.documentElement.removeAttribute('data-board');
  });

  afterEach(() => {
    vi.restoreAllMocks();
    if (previousBoard === null) document.documentElement.removeAttribute('data-board');
    else document.documentElement.setAttribute('data-board', previousBoard);
    if (previousSaved === null) localStorage.removeItem('boardTheme');
    else localStorage.setItem('boardTheme', previousSaved);
  });

  it('defaults to green and applies it', () => {
    const service = TestBed.inject(BoardThemeService);
    service.apply();
    expect(service.theme()).toBe('green');
    expect(document.documentElement.getAttribute('data-board')).toBe('green');
  });

  it('persists and restores the chosen board independently of the app theme', () => {
    const service = TestBed.inject(BoardThemeService);
    const theme = document.documentElement.getAttribute('data-theme');
    service.select('wood');
    expect(service.theme()).toBe('wood');
    expect(localStorage.getItem('boardTheme')).toBe('wood');
    expect(document.documentElement.getAttribute('data-board')).toBe('wood');
    expect(document.documentElement.getAttribute('data-theme')).toBe(theme);
    expect(new BoardThemeService(document).theme()).toBe('wood');
  });

  it.each(['green', 'blue-grey', 'wood'] as const)('restores saved %s', (theme) => {
    localStorage.setItem('boardTheme', theme);
    const service = TestBed.inject(BoardThemeService);
    service.apply();
    expect(service.theme()).toBe(theme);
    expect(document.documentElement.getAttribute('data-board')).toBe(theme);
  });

  it.each(['unknown', '', 'BLUE-GREY'])(
    'falls back to green for invalid saved value %s',
    (value) => {
      localStorage.setItem('boardTheme', value);
      const service = TestBed.inject(BoardThemeService);
      service.apply();
      expect(service.theme()).toBe('green');
      expect(document.documentElement.getAttribute('data-board')).toBe('green');
    },
  );

  it('tolerates storage read errors', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('Storage unavailable');
    });
    const service = TestBed.inject(BoardThemeService);
    expect(() => service.apply()).not.toThrow();
    expect(service.theme()).toBe('green');
    expect(document.documentElement.getAttribute('data-board')).toBe('green');
  });

  it('keeps the current choice when a storage write fails', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('Storage unavailable');
    });
    const service = TestBed.inject(BoardThemeService);
    expect(() => service.select('blue-grey')).not.toThrow();
    expect(service.theme()).toBe('blue-grey');
    expect(document.documentElement.getAttribute('data-board')).toBe('blue-grey');
  });

  it('applies the saved board at app startup', () => {
    localStorage.setItem('boardTheme', 'blue-grey');
    const service = TestBed.inject(BoardThemeService);
    const app = new App(service);
    // Isolate the independent board startup from the existing app-theme toggle.
    vi.spyOn(app as any, 'applyTheme').mockImplementation(() => {});
    app.ngOnInit();
    expect(document.documentElement.getAttribute('data-board')).toBe('blue-grey');
  });
});
