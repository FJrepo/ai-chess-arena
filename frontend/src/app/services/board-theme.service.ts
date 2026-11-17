import { DOCUMENT } from '@angular/common';
import { Inject, Injectable, signal } from '@angular/core';

export type BoardTheme = 'green' | 'blue-grey' | 'wood';

export const BOARD_THEMES: readonly { value: BoardTheme; label: string }[] = [
  { value: 'green', label: 'Green' },
  { value: 'blue-grey', label: 'Blue-grey' },
  { value: 'wood', label: 'Wood' },
];

@Injectable({ providedIn: 'root' })
export class BoardThemeService {
  private readonly selected = signal<BoardTheme>('green');
  readonly theme = this.selected.asReadonly();
  readonly options = BOARD_THEMES;

  constructor(@Inject(DOCUMENT) private readonly document: Document) {
    let saved: string | null = null;
    try {
      saved = this.document.defaultView?.localStorage.getItem('boardTheme') ?? null;
    } catch {
      // Restricted storage leaves the default board usable.
    }
    this.selected.set(BOARD_THEMES.find((option) => option.value === saved)?.value ?? 'green');
  }

  apply(): void {
    this.document.documentElement.setAttribute('data-board', this.theme());
  }

  select(theme: BoardTheme): void {
    this.selected.set(theme);
    this.apply();
    try {
      this.document.defaultView?.localStorage.setItem('boardTheme', theme);
    } catch {
      // The in-memory choice still works when persistence is unavailable.
    }
  }
}
