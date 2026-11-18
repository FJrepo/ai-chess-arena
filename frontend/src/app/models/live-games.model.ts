export interface LiveGameSummary {
  id: string;
  status: 'IN_PROGRESS' | 'PAUSED' | 'COMPLETED' | 'FORFEIT';
  tournamentName?: string;
  whitePlayerName?: string;
  blackPlayerName?: string;
  practice: boolean;
  moveNumber?: number;
  evaluationCp?: number;
  evaluationMate?: number;
  /** Why a paused game needs attention, e.g. a model request that was in flight during a restart. */
  statusDetail?: string | null;
}

export interface LiveGamesSnapshot {
  active: LiveGameSummary[];
  lastFinished?: LiveGameSummary;
}
