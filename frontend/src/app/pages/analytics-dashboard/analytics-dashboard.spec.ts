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
    totalCostUsd: 2.5,
    averageCostPerMoveUsd: 0.08,
    costPerWinUsd: 1.25,
    pricingAvailable: true,
    reliabilityScore: 81.3,
    reliabilityBand: 'B',
    insufficientData: false,
  };
}
