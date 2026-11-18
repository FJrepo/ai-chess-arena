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
}

export interface LiveGamesSnapshot {
  active: LiveGameSummary[];
  lastFinished?: LiveGameSummary;
}
