export interface ModelCostBreakdown {
  modelId: string;
  totalCostUsd: number;
  moveCount: number;
  averageCostPerMoveUsd: number;
}

export interface HighestCostGame {
  gameId: string;
  costUsd: number;
  whitePlayerName: string | null;
  blackPlayerName: string | null;
}

export interface TournamentCostSummary {
  tournamentId: string;
  totalCostUsd: number;
  averageCostPerGameUsd: number;
  averageCostPerMoveUsd: number;
  highestCostGame: HighestCostGame | null;
  topModels: ModelCostBreakdown[];
}

export interface AnalyticsHealthModelRow {
  modelId: string;
  movesCount: number;
  retriesTotal: number;
  averageRetriesPerMove: number;
  averageResponseTimeMs: number | null;
  promptTokensTotal: number;
  completionTokensTotal: number;
  totalCostUsd: number;
  averageCostPerMoveUsd: number;
}

export interface AnalyticsComparisonModel {
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
}

export interface AnalyticsComparisonResponse {
  windowDays: number;
  tournamentId: string | null;
  minGames: number;
  gamesCount: number;
  models: AnalyticsComparisonModel[];
}

export interface AnalyticsHealthResponse {
  windowDays: number;
  tournamentId: string | null;
  gamesCount: number;
  activeGamesCount: number;
  completedGamesCount: number;
  forfeitGamesCount: number;
  movesCount: number;
  retriesTotal: number;
  averageRetriesPerMove: number;
  averageResponseTimeMs: number | null;
  p95ResponseTimeMs: number | null;
  promptTokensTotal: number;
  completionTokensTotal: number;
  costTotalUsd: number;
  models: AnalyticsHealthModelRow[];
}

export interface ModelReliability {
  modelId: string;
  gamesPlayed: number;
  gamesCompleted: number;
  forfeitCount: number;
  forfeitRate: number;
  timeoutForfeitRate: number;
  averageRetriesPerMove: number;
  averageResponseTimeMs: number | null;
  averageCostPerMoveUsd: number;
  movesSampled: number;
  finalScore: number;
  band: string;
  insufficientData: boolean;
}

export interface ModelReliabilityResponse {
  windowDays: number;
  tournamentId: string | null;
  minGames: number;
  models: ModelReliability[];
}

export interface ModelReliabilityDetail {
  windowDays: number;
  tournamentId: string | null;
  model: ModelReliability;
  completionScore: number;
  forfeitScore: number;
  retryScore: number;
  latencyScore: number;
  rawScore: number;
  sampleWeight: number;
}
