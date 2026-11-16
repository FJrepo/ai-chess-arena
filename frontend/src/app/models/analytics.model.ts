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

/** Unknown counters may be omitted by the API; reported zeros remain meaningful. */
export interface AnalyticsPromptCaching {
  promptTokens?: number | null;
  cachedPromptTokens?: number | null;
  cacheHitRate?: number | null;
  cacheWriteTokens?: number | null;
  estimatedSavingsUsd?: number | null;
}

export interface AnalyticsComparisonModel {
  promptCaching?: AnalyticsPromptCaching;

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
  /** Share of turns answered with a legal move on the first attempt (0–1). */
  firstTryLegalRate: number;
  /** Includes the cost of rejected attempts. */
  totalCostUsd: number | null;
  averageCostPerMoveUsd: number | null;
  costPerWinUsd: number | null;
  pricingAvailable: boolean;
  reliabilityScore: number;
  reliabilityBand: string;
  insufficientData: boolean;
  /** Moves judged by Stockfish (evaluated, not forced, not overrides). Absent from older backends. */
  analyzedMoves?: number;
  /** Finished games with at least one judged move of the model. */
  analyzedGames?: number;
  /** Average centipawn loss per judged move; null without judged moves. */
  averageCentipawnLoss?: number | null;
  inaccuracies?: number;
  mistakes?: number;
  blunders?: number;
  /** Blunders per analyzed game; null without analyzed games. */
  blundersPerGame?: number | null;
  /** Talkativeness of the model's moves made with chat OPTIONAL. Absent from older backends. */
  optionalChat?: AnalyticsTalkativeness;
  /** Talkativeness of the model's moves made with chat MANDATORY (a compliance rate). */
  mandatoryChat?: AnalyticsTalkativeness;
  /** Games the model resigned. Absent from backends before 1.4. */
  resignations?: number;
  /** Of those, resignations whose position Stockfish had evaluated. */
  resignationsEvaluated?: number;
  /** Of those, resignations with at least 35% winning chances ("resigned while not losing"). */
  resignationsWhileNotLosing?: number;
  /** Games the model won because its opponent resigned. */
  wonByResignation?: number;
}

/** Of a model's moves in one chat mode, how many came with a non-empty message. */
export interface AnalyticsTalkativeness {
  moves: number;
  messages: number;
  /** messages / moves (0-1); absent or null without moves. */
  talkRate?: number | null;
  /** Average message length in characters; absent or null without messages. */
  averageMessageLength?: number | null;
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
  /** Share of turns answered with a legal move on the first attempt (0–1). */
  firstTryLegalRate: number;
  averageResponseTimeMs: number | null;
  /** Includes the cost of rejected attempts. */
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
  firstTryLegalScore: number;
  forfeitScore: number;
  retryScore: number;
  rawScore: number;
  sampleWeight: number;
}
