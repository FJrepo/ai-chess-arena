import type { ChatMode, Game } from './tournament.model';
import type { ReasoningEffort } from '../utils/reasoning-effort';

export type BenchmarkColor = 'WHITE' | 'BLACK';

export interface BenchmarkRunSettings {
  budgetUsd: string;
  maxConcurrentGames: number;
}

export interface CreateBenchmarkRequest {
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
  status: 'RUNNING' | 'PAUSED' | 'FINISHED';
  pauseReason: string | null;
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
    modelIds: string[];
    targetStrengths: number[];
    modelColors: BenchmarkColor[];
    engineSha256: string;
    searchNodes: number;
    threads: number;
    hashMiB: number;
  };
  legalMoveHints: boolean;
  reasoningEffort: ReasoningEffort | 'default';
  chatMode: ChatMode;
  spendUsd: number;
  models: BenchmarkModelResult[];
}
