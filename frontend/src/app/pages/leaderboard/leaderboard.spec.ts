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
  lastPlayed: '2026-11-01T12:00:00',
  provisional: true,
  alias: false,
  ratingHistory: [{ gameId: 'game-1', completedAt: '2026-11-01T12:00:00', rating: 1516 }],
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
    expect(rows[0].querySelector('polyline')?.getAttribute('points')).toBe('2,14 98,14');
    expect(element.querySelector('caption')?.textContent).toContain('Including forfeits');
    expect(getLeaderboard).toHaveBeenCalledWith(true);
  });

  it('renders provisional and alias badges only on flagged rows', async () => {
    getLeaderboard.mockReturnValue(
      of(
        response([
          model({ modelId: '~anthropic/example', alias: true }),
          model({ modelId: 'anthropic/established', provisional: false, games: 10 }),
        ]),
      ),
    );
    const element = await render();
    const rows = element.querySelectorAll('tbody tr');
    expect(rows[0].querySelector('.provisional')?.textContent).toContain('Provisional');
    expect(rows[0].querySelector('.alias')?.textContent).toContain('Latest alias');
    expect(rows[0].querySelector('app-provider-logo img')?.getAttribute('src')).toContain(
      'anthropic',
    );
    expect(rows[1].querySelector('.provisional')).toBeNull();
    expect(rows[1].querySelector('.alias')).toBeNull();
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
    expect(element.querySelector('.rating')?.textContent?.trim()).toBe('1500');
    expect(element.querySelector('caption')?.textContent).toContain('Excluding forfeits');
    const button = Array.from(element.querySelectorAll('button')).find((item) =>
      item.textContent?.includes('Refresh'),
    );
    button?.click();
    expect(getLeaderboard).toHaveBeenLastCalledWith(false);
  });

  it('shows the empty state without a table', async () => {
    getLeaderboard.mockReturnValue(of(response([])));
    const element = await render();
    expect(element.querySelector('.empty-state')?.textContent).toContain('No rated games yet');
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
    expect(fixture.componentInstance.sparklinePoints([])).toBe('2,14 98,14');
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
