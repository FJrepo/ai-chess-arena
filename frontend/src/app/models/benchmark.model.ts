import type { ChatMode, Game } from './tournament.model';
import type { ReasoningEffort } from '../utils/reasoning-effort';

export type BenchmarkColor = 'WHITE' | 'BLACK';

export interface BenchmarkRunSettings {
  budgetUsd: string;
  maxConcurrentGames: number;
}

export interface CreateBenchmarkRequest {
  clientRequestId: string;
  name: string;
  modelIds: string[];
  targetStrengths: [number, number];
  legalMoveHints: boolean;
  reasoningEffort: ReasoningEffort | 'default';
  chatMode: ChatMode;
  runSettings: BenchmarkRunSettings;
}

export interface BenchmarkSummary {
  id: string;
  name: string;
  createdAt: string;
  status: 'RUNNING' | 'PAUSED' | 'FINISHED' | 'CANCELLED';
  pauseReason: string | null;
  rerunOf: string | null;
}

export interface BenchmarkScore {
  wins: number;
  draws: number;
  losses: number;
  forfeits: number;
  points: number;
  scoredGames: number;
}

export interface BenchmarkGame {
  id: string;
  color: BenchmarkColor;
  status: Game['status'];
  result: string | null;
  reason: string | null;
  statusDetail: string | null;
  spendUsd: number;
}

export interface BenchmarkOpening {
  id: string;
  name: string;
  moves: string[];
}

export interface BenchmarkPair {
  id: string;
  openingId: string;
  targetStrength: number;
  complete: boolean;
  games: BenchmarkGame[];
}

export interface BenchmarkModelResult {
  modelId: string;
  resolvedModelIds: string[];
  overall: BenchmarkScore;
  splits: { targetStrength: number; color: BenchmarkColor; score: BenchmarkScore }[];
  incompletePairs: number;
  spendUsd: number;
  pairs: BenchmarkPair[];
}

export interface BenchmarkRun extends BenchmarkRunSettings {
  run: BenchmarkSummary;
  definition: {
    suite: string;
    openings: BenchmarkOpening[];
    openingSeed?: number;
    modelIds: string[];
    targetStrengths: number[];
    modelColors: BenchmarkColor[];
    engineSha256: string;
    engineNetworks: { name: string; sha256: string }[];
    searchNodes: number;
    threads: number;
    hashMiB: number;
    promptVersion: string;
  };
  legalMoveHints: boolean;
  reasoningEffort: ReasoningEffort | 'default';
  chatMode: ChatMode;
  spendUsd: number;
  models: BenchmarkModelResult[];
}

export interface BenchmarkRerunChanges {
  modelId: { from: string; to: string };
  legalMoveHints: boolean;
  reasoningEffort: BenchmarkRun['reasoningEffort'];
  chatMode: ChatMode;
  budgetUsd: string;
}

export interface RerunBenchmarkRequest {
  clientRequestId: string;
  change: Partial<BenchmarkRerunChanges>;
  expectedRunSettings: BenchmarkRunSettings;
}

export interface BenchmarkTelemetry {
  firstTryMoves: number;
  turnsSampled: number;
  firstTryLegalRate: number | null;
  reasoningMovesReported: number;
  reasoningTokensPerMove: number | null;
}

export interface BenchmarkComparisonModelSide {
  score: BenchmarkScore;
  telemetry: BenchmarkTelemetry;
  incompletePairs: number;
  spendUsd: number;
  resolvedModelIds: string[];
}

export interface BenchmarkComparisonGameSide {
  gameId: string | null;
  status: Game['status'] | null;
  reason: string | null;
  statusDetail: string | null;
  uncertainRequest: boolean;
  outcome: 'WIN' | 'DRAW' | 'LOSS' | null;
  points: number | null;
  forfeit: boolean;
  pairIncomplete: boolean;
  telemetry: BenchmarkTelemetry | null;
  spendUsd: number | null;
}

export interface BenchmarkSettingDifference {
  setting: string;
  base: string;
  candidate: string;
}

export interface BenchmarkComparison {
  base: BenchmarkSummary;
  candidate: BenchmarkSummary;
  comparable: boolean;
  comparisonType: 'CONTROLLED_RERUN' | 'COMPARABLE' | 'MULTIPLE_SETTINGS' | 'NOT_COMPARABLE';
  differences: BenchmarkSettingDifference[];
  settingChanges: BenchmarkSettingDifference[];
  operationalLimitsNote: string;
  baseSpendUsd: number;
  candidateSpendUsd: number;
  spendDeltaUsd: number | null;
  unmatchedBaseModels: string[];
  unmatchedCandidateModels: string[];
  models: {
    baseModelId: string;
    candidateModelId: string;
    base: BenchmarkComparisonModelSide;
    candidate: BenchmarkComparisonModelSide;
    matchedGames: number;
    scoreDelta: number | null;
    spendDeltaUsd: number;
  }[];
  matchups: {
    baseModelId: string;
    candidateModelId: string;
    openingId: string;
    openingName: string;
    targetStrength: number;
    color: BenchmarkColor;
    base: BenchmarkComparisonGameSide;
    candidate: BenchmarkComparisonGameSide;
    outcomeChanged: boolean;
    scoreDelta: number | null;
    spendDeltaUsd: number | null;
  }[];
}
