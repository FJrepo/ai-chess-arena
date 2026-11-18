import type { ObservedReasoningEffort } from '../utils/reasoning-effort';

export interface RatingHistoryPoint {
  gameId: string;
  completedAt: string;
  rating: number;
}

export interface LeaderboardModel {
  modelId: string;
  displayName: string;
  rating: number;
  games: number;
  wins: number;
  draws: number;
  losses: number;
  forfeits: number;
  lastPlayed: string;
  provisional: boolean;
  alias: boolean;
  ratingHistory: RatingHistoryPoint[];
  reasoningEffort?: ObservedReasoningEffort;
}

export interface LeaderboardResponse {
  includeForfeits: boolean;
  gamesCount: number;
  models: LeaderboardModel[];
}
