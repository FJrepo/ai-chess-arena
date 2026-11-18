import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { MatSlideToggle } from '@angular/material/slide-toggle';
import { By } from '@angular/platform-browser';
import { Observable, of, Subject, throwError } from 'rxjs';
import { LeaderboardModel, LeaderboardResponse } from '../../models/leaderboard.model';
import { ApiService } from '../../services/api.service';
import { Leaderboard } from './leaderboard';

const model = (overrides: Partial<LeaderboardModel> = {}): LeaderboardModel => ({
  modelId: 'anthropic/example',
  displayName: 'example',
  rating: 1516,
  games: 1,
  wins: 1,
  draws: 0,
  losses: 0,
  forfeits: 0,
  lastPlayed: '2026-11-01T12:00:00Z',
  provisional: true,
  alias: false,
  ratingHistory: [{ gameId: 'game-1', completedAt: '2026-11-01T12:00:00Z', rating: 1516 }],
  ...overrides,
});

const response = (models: LeaderboardModel[] = [model()]): LeaderboardResponse => ({
  includeForfeits: true,
  gamesCount: 1,
  models,
});

describe('Leaderboard', () => {
  let fixture: ComponentFixture<Leaderboard>;
  let getLeaderboard: ReturnType<
    typeof vi.fn<(include: boolean) => Observable<LeaderboardResponse>>
  >;

  beforeEach(() => {
    getLeaderboard = vi.fn(() => of(response()));
    TestBed.configureTestingModule({
      imports: [Leaderboard],
      providers: [provideRouter([]), { provide: ApiService, useValue: { getLeaderboard } }],
    });
    fixture = TestBed.createComponent(Leaderboard);
  });

  async function render(): Promise<HTMLElement> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement;
  }

  it('summarizes the first ranked model before the table', async () => {
    getLeaderboard.mockReturnValue(
      of(
        response([
          model({
            displayName: 'Paper Kestrel',
            games: 12,
            wins: 8,
            draws: 3,
            losses: 1,
            provisional: false,
          }),
          model({ modelId: 'openai/other', displayName: 'Other', rating: 1516 }),
        ]),
      ),
    );
    const element = await render();
    const leader = element.querySelector('.leader-summary');
    const table = element.querySelector('table')!;
    expect(leader).not.toBeNull();
    expect(leader?.textContent).toContain('Current leader');
    expect(leader?.textContent).toContain('Paper Kestrel');
    expect(leader?.querySelector('app-provider-logo img')?.getAttribute('src')).toContain(
      'anthropic',
    );
    expect(leader?.textContent).toMatch(/Elo\s*1516/);
    expect(leader?.textContent).not.toContain('Provisional');
    expect(leader?.textContent).toMatch(/W–D–L\s*8\s*–\s*3\s*–\s*1/);
    expect(leader?.textContent).toMatch(/Games rated\s*12/);
    expect(leader?.compareDocumentPosition(table)).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
  });

  it('shows an accessible Provisional label beside the Elo of a provisional leader', async () => {
    const element = await render();
    const elo = element.querySelector('.leader-summary dd');
    const label = elo?.querySelector('.provisional');
    expect(elo?.textContent).toMatch(/1516\s*Provisional/);
    expect(label?.textContent?.trim()).toBe('Provisional');
    expect(label?.closest('[aria-hidden="true"], .cdk-visually-hidden')).toBeNull();
  });

  it('keeps the ratings explanation closed until its keyboard-focusable summary is activated', async () => {
    const element = await render();
    const details = element.querySelector('details');
    const summary = details?.querySelector('summary');
    expect(summary?.textContent?.trim()).toBe('How ratings work');
    expect(details?.open).toBe(false);
    summary?.focus();
    expect(document.activeElement).toBe(summary);
    // Native keyboard activation dispatches a click. jsdom implements the click default action.
    summary?.click();
    expect(details?.open).toBe(true);
    expect(details?.textContent).toContain('Elo starts at 1500');
  });

  it('states the provisional threshold only in the legend with the ratings explanation open', async () => {
    const element = await render();
    const details = element.querySelector('details')!;
    details.open = true;
    const threshold = /fewer than\s+10\s+rated\s+games/g;
    expect(element.querySelector('.provisional-legend')?.textContent).toMatch(threshold);
    expect(element.textContent?.match(threshold)).toHaveLength(1);
    expect(details.textContent).toContain('See the table legend for the provisional rating cutoff');
  });

  it('renders ranked API rows, records, last played, provider logos and accessible sparklines', async () => {
    getLeaderboard.mockReturnValue(
      of(
        response([model(), model({ modelId: 'openai/other', displayName: 'other', rating: 1484 })]),
      ),
    );
    const element = await render();
    const rows = element.querySelectorAll('tbody tr');
    expect(rows).toHaveLength(2);
    expect(rows[0].querySelector('.rank')?.textContent?.trim()).toBe('1');
    expect(rows[1].querySelector('.rank')?.textContent?.trim()).toBe('2');
    expect(rows[0].querySelector('.rating')?.textContent?.trim()).toBe('1516');
    expect(rows[0].querySelector('.record')?.textContent).toContain('1 / 0 / 0');
    expect(rows[0].textContent).toContain('anthropic/example');
    expect(rows[0].querySelector('.last-played')?.textContent).toContain('Nov 1, 2026');
    expect(rows[0].querySelector('app-provider-logo')).not.toBeNull();
    expect(rows[0].querySelector('svg[role="img"]')?.getAttribute('aria-label')).toContain('1516');
    expect(element.querySelector('caption')?.textContent).toContain('Including forfeits');
    expect(getLeaderboard).toHaveBeenCalledWith(true);
  });

  it('explains provisional status once and gives flagged rows an accessible marker', async () => {
    getLeaderboard.mockReturnValue(
      of(
        response([
          model({ modelId: '~anthropic/example', alias: true }),
          model({ modelId: 'anthropic/another' }),
          model({ modelId: 'anthropic/established', provisional: false, games: 10 }),
        ]),
      ),
    );
    const element = await render();
    const rows = element.querySelectorAll('tbody tr');
    expect(element.querySelectorAll('.provisional-legend')).toHaveLength(1);
    expect(element.querySelector('.provisional-legend')?.textContent).toContain(
      'Provisional: fewer than 10 rated games',
    );
    for (const row of [rows[0], rows[1]]) {
      const marker = row.querySelector('.provisional');
      expect(marker?.querySelector('[aria-hidden="true"]')?.textContent).toBe('P');
      expect(marker?.querySelector('.cdk-visually-hidden')?.textContent).toBe('Provisional');
      expect(marker?.hasAttribute('title')).toBe(false);
    }
    expect(rows[0].querySelector('.alias')?.textContent).toContain('Latest alias');
    expect(rows[0].querySelector('app-provider-logo img')?.getAttribute('src')).toContain(
      'anthropic',
    );
    expect(rows[2].querySelector('.provisional')).toBeNull();
    expect(rows[1].querySelector('.alias')).toBeNull();
  });

  it('renders a single point with a singular accessible label for a one-game rating history', async () => {
    const element = await render();
    const trend = element.querySelector('tbody .trend');
    expect(trend?.querySelectorAll('svg circle')).toHaveLength(1);
    expect(trend?.querySelector('polyline')).toBeNull();
    expect(trend?.querySelector('svg[role="img"]')?.getAttribute('aria-label')).toBe(
      'example: one rated game, Elo 1516',
    );
  });

  it('announces missing rating history and hides the decorative dash from assistive technology', async () => {
    getLeaderboard.mockReturnValue(of(response([model({ ratingHistory: [] })])));
    const element = await render();
    const trend = element.querySelector('tbody .trend');
    expect(trend?.querySelector('svg')).toBeNull();
    expect
      .soft(trend?.querySelector('.cdk-visually-hidden')?.textContent?.trim())
      .toBe('No rating history');
    expect.soft(trend?.querySelector('[aria-hidden="true"]')?.textContent?.trim()).toBe('—');
  });

  it('keeps the effort labels attached to the selected rated rows', async () => {
    getLeaderboard.mockReturnValue(
      of(response([model({ modelId: 'anthropic/mixed', reasoningEffort: 'mixed' })])),
    );
    const element = await render();
    expect(element.querySelector('tbody th')?.textContent).toContain('Mixed effort');

    getLeaderboard.mockReturnValue(
      of(response([model({ modelId: 'anthropic/mixed', reasoningEffort: 'xhigh' })])),
    );
    fixture.componentInstance.setLegalMoveHints(false);
    await render();
    expect(element.querySelector('tbody th')?.textContent).toContain('Extra high');
  });

  it('requests a full recalculation when the forfeits toggle changes', async () => {
    await render();
    getLeaderboard.mockReturnValue(
      of({ ...response([model({ rating: 1500 })]), includeForfeits: false }),
    );
    const toggle = fixture.debugElement.query(By.directive(MatSlideToggle));
    toggle.triggerEventHandler('change', { checked: false });
    const element = await render();
    expect(getLeaderboard).toHaveBeenLastCalledWith(false);
    expect(fixture.componentInstance.includeForfeits()).toBe(false);
    expect(element.querySelector('.leader-summary')?.textContent).toContain('1500');
    expect(element.querySelector('.rating')?.textContent?.trim()).toBe('1500');
    expect(element.querySelector('caption')?.textContent).toContain('Excluding forfeits');
    const button = Array.from(element.querySelectorAll('button')).find((item) =>
      item.textContent?.includes('Refresh'),
    );
    button?.click();
    expect(getLeaderboard).toHaveBeenLastCalledWith(false);
  });

  it('uses the selected hint mode for the displayed rating sample', async () => {
    await render();
    fixture.componentInstance.setLegalMoveHints(false);
    const element = await render();
    expect(getLeaderboard).toHaveBeenLastCalledWith(true, false);
    expect(element.querySelector('caption')?.textContent).toContain('Legal move hints off');
    fixture.componentInstance.setLegalMoveHints('all');
    expect(getLeaderboard).toHaveBeenLastCalledWith(true);
  });

  it('opens with a clear empty state when the selected filters yield no rated models', async () => {
    getLeaderboard.mockReturnValue(of({ ...response([]), gamesCount: 0 }));
    const element = await render();
    const empty = element.querySelector('.empty-state');
    expect(empty?.textContent).toContain('No rated models for these filters');
    expect(empty?.compareDocumentPosition(element.querySelector('.controls')!)).toBe(
      Node.DOCUMENT_POSITION_FOLLOWING,
    );
    expect(element.querySelector('.leader-summary')).toBeNull();
    expect(element.querySelector('table')).toBeNull();
  });

  it('shows a recoverable error and supports refresh', async () => {
    getLeaderboard.mockReturnValue(throwError(() => new Error('offline')));
    const element = await render();
    expect(element.querySelector('[role="alert"]')?.textContent).toContain('Could not load');
    expect(fixture.componentInstance.loading()).toBe(false);
    getLeaderboard.mockReturnValue(of(response()));
    fixture.componentInstance.refresh();
    await render();
    expect(element.querySelector('[role="alert"]')).toBeNull();
    expect(element.querySelector('table')).not.toBeNull();
  });

  it('clears previous rows while loading and cancels obsolete toggle responses', async () => {
    const oldRequest = new Subject<LeaderboardResponse>();
    const newRequest = new Subject<LeaderboardResponse>();
    await render();
    getLeaderboard.mockReturnValueOnce(oldRequest).mockReturnValueOnce(newRequest);
    fixture.componentInstance.setIncludeForfeits(false);
    const element = await render();
    expect(element.querySelector('table')).toBeNull();
    expect(element.querySelector('[role="status"]')?.textContent).toContain('Loading');
    fixture.componentInstance.setIncludeForfeits(true);
    oldRequest.next({ ...response([model({ rating: 999 })]), includeForfeits: false });
    expect(fixture.componentInstance.response()).toBeNull();
    newRequest.next(response());
    await render();
    expect(element.querySelector('.rating')?.textContent?.trim()).toBe('1516');
    oldRequest.error(new Error('obsolete failure'));
    expect(fixture.componentInstance.error()).toBe(false);
  });

  it('unsubscribes from pending requests on destruction', async () => {
    const unsubscribe = vi.fn();
    getLeaderboard.mockReturnValue(new Observable(() => unsubscribe));
    await render();
    fixture.destroy();
    expect(unsubscribe).toHaveBeenCalledOnce();
  });

  it('scales varying and flat histories without invalid SVG coordinates', () => {
    const point = model().ratingHistory[0];
    expect(fixture.componentInstance.sparklinePoints([point, point])).toBe('2.0,14.0 98.0,14.0');
    expect(
      fixture.componentInstance.sparklinePoints([
        { ...point, rating: 1500 },
        { ...point, rating: 1520 },
        { ...point, rating: 1510 },
      ]),
    ).toBe('2.0,25.0 50.0,3.0 98.0,14.0');
  });
});
