import { AnalyticsDashboard } from './analytics-dashboard';

describe('AnalyticsDashboard', () => {
  it('sorts comparison rows by win rate by default', () => {
    const component = new AnalyticsDashboard({} as any);
    component.comparison.set({
      windowDays: 30,
      tournamentId: null,
      minGames: 0,
      gamesCount: 6,
      models: [
        comparisonRow('model.alpha', { winRate: 0.75, wins: 3, gamesPlayed: 4 }),
        comparisonRow('model.beta', { winRate: 0.5, wins: 2, gamesPlayed: 4 }),
      ],
    });

    expect(component.comparisonRows().map((row) => row.modelId)).toEqual([
      'model.alpha',
      'model.beta',
    ]);
  });

  it('sorts comparison rows by first-try legal rate', () => {
    const component = new AnalyticsDashboard({} as any);
    component.comparison.set({
      windowDays: 30,
      tournamentId: null,
      minGames: 0,
      gamesCount: 6,
      models: [
        comparisonRow('model.alpha', { winRate: 0.75, firstTryLegalRate: 0.6 }),
        comparisonRow('model.beta', { winRate: 0.5, firstTryLegalRate: 0.95 }),
      ],
    });

    component.comparisonSortBy.set('firstTryLegalRate');

    expect(component.comparisonRows().map((row) => row.modelId)).toEqual([
      'model.beta',
      'model.alpha',
    ]);
    expect(component.comparisonSortOptions.map((option) => option.label)).toContain(
      'First-try legal',
    );
  });

  it('explains the reliability score without latency', () => {
    const component = new AnalyticsDashboard({} as any);

    expect(component.scoreExplanation).toBe(
      'Score, based on finished games: 50% first-try legal moves, 30% no forfeits, 20% few retries; low samples pulled toward 60. Latency is shown but not scored.',
    );
  });

  it('picks best value only from models with pricing and wins', () => {
    const component = new AnalyticsDashboard({} as any);
    component.comparison.set({
      windowDays: 30,
      tournamentId: null,
      minGames: 0,
      gamesCount: 8,
      models: [
        comparisonRow('model.alpha', {
          wins: 4,
          pricingAvailable: true,
          costPerWinUsd: 0.8,
          totalCostUsd: 3.2,
        }),
        comparisonRow('model.beta', {
          wins: 3,
          pricingAvailable: false,
          costPerWinUsd: null,
          totalCostUsd: null,
        }),
      ],
    });

    const bestValue = component.leaderCards().find((card) => card.label === 'Best Value');

    expect(bestValue?.row?.modelId).toBe('model.alpha');
    expect(bestValue?.value(bestValue.row!)).toBe('$0.80');
  });

  it('shows the score as the most reliable value and only that card carries the grade', () => {
    const component = new AnalyticsDashboard({} as any);
    component.comparison.set({
      windowDays: 30,
      tournamentId: null,
      minGames: 0,
      gamesCount: 7,
      models: [comparisonRow('model.alpha', { reliabilityScore: 99.18, gamesPlayed: 7 })],
    });

    const cards = component.leaderCards();
    const mostReliable = cards.find((card) => card.label === 'Most Reliable');

    expect(mostReliable?.value(mostReliable.row!)).toBe('99.2');
    expect(mostReliable?.detail(mostReliable.row!)).toBe('Out of 100, over 7 games');
    expect(cards.filter((card) => card.showsGrade).map((card) => card.label)).toEqual([
      'Most Reliable',
    ]);
  });

  it('rounds the most reliable card and the table score the same way', () => {
    const component = new AnalyticsDashboard({} as any);
    component.comparison.set({
      windowDays: 30,
      tournamentId: null,
      minGames: 0,
      gamesCount: 5,
      models: [comparisonRow('model.alpha', { reliabilityScore: 86.55 })],
    });

    const mostReliable = component.leaderCards().find((card) => card.label === 'Most Reliable');

    expect(mostReliable?.value(mostReliable.row!)).toBe('86.6');
    expect(component.formatScore(86.55)).toBe('86.6');
  });

  it('formats latency with thousands separators', () => {
    const component = new AnalyticsDashboard({} as any);

    expect(component.formatMs(10196.4)).toBe('10,196 ms');
    expect(component.formatMs(812)).toBe('812 ms');
    expect(component.formatMs(null)).toBe('n/a');
  });
});

type AnalyticsComparisonRow = {
  modelId: string;
  gamesPlayed: number;
  wins: number;
  draws: number;
  losses: number;
  forfeits: number;
  timeoutForfeits: number;
  winRate: number;
  drawRate: number;
  lossRate: number;
  whiteGames: number;
  whiteWins: number;
  whiteWinRate: number;
  blackGames: number;
  blackWins: number;
  blackWinRate: number;
  movesSampled: number;
  averageResponseTimeMs: number | null;
  averageRetriesPerMove: number;
  firstTryLegalRate: number;
  totalCostUsd: number | null;
  averageCostPerMoveUsd: number | null;
  costPerWinUsd: number | null;
  pricingAvailable: boolean;
  reliabilityScore: number;
  reliabilityBand: string;
  insufficientData: boolean;
};

function comparisonRow(modelId: string, overrides: Partial<AnalyticsComparisonRow> = {}) {
  return { ...baseRow(modelId), ...overrides };
}

function baseRow(modelId: string): AnalyticsComparisonRow {
  return {
    modelId,
    gamesPlayed: 5,
    wins: 2,
    draws: 1,
    losses: 2,
    forfeits: 0,
    timeoutForfeits: 0,
    winRate: 0.4,
    drawRate: 0.2,
    lossRate: 0.4,
    whiteGames: 3,
    whiteWins: 1,
    whiteWinRate: 0.3333,
    blackGames: 2,
    blackWins: 1,
    blackWinRate: 0.5,
    movesSampled: 30,
    averageResponseTimeMs: 1200,
    averageRetriesPerMove: 0.1,
    firstTryLegalRate: 0.9,
    totalCostUsd: 2.5,
    averageCostPerMoveUsd: 0.08,
    costPerWinUsd: 1.25,
    pricingAvailable: true,
    reliabilityScore: 81.3,
    reliabilityBand: 'B',
    insufficientData: false,
  };
}
