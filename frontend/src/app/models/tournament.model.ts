export interface Tournament {
  id: string;
  name: string;
  status: 'CREATED' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';
  format: string;
  drawPolicy:
    | 'WHITE_ADVANCES'
    | 'BLACK_ADVANCES'
    | 'HIGHER_SEED_ADVANCES'
    | 'RANDOM_ADVANCES'
    | 'REPLAY_GAME';
  sharedCustomInstructions: string | null;
  moveTimeoutSeconds: number;
  maxRetries: number;
  matchupBestOf: 1 | 3 | 5 | 7;
  finalsBestOf: 1 | 3 | 5 | 7 | null;
  trashTalkEnabled: boolean;
  createdAt: string;
  updatedAt: string;
  participants: Participant[];
  games: Game[];
}

export interface Participant {
  id: string;
  playerName: string;
  modelId: string;
  customInstructions: string | null;
  seed: number;
}

export interface Game {
  id: string;
  tournamentId: string | null;
  whitePlayerName: string | null;
  whiteModelId: string | null;
  blackPlayerName: string | null;
  blackModelId: string | null;
  status: 'WAITING' | 'IN_PROGRESS' | 'PAUSED' | 'COMPLETED' | 'FORFEIT';
  result: string | null;
  resultReason: string | null;
  pgn: string | null;
  currentFen: string;
  bracketRound: string | null;
  bracketPosition: number | null;
  seriesId: string | null;
  seriesGameNumber: number;
  seriesBestOf: number;
  totalCostUsd: number;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  moves: Move[];
  chatMessages: ChatMessage[];
}

export interface Move {
  id: string;
  moveNumber: number;
  color: 'WHITE' | 'BLACK';
  san: string;
  fen: string;
  modelId: string;
  promptVersion: string | null;
  promptHash: string | null;
  promptTokens: number | null;
  completionTokens: number | null;
  costUsd: number | null;
  responseTimeMs: number | null;
  retryCount: number;
  isOverride: boolean;
  evaluationCp?: number | null;
  evaluationMate?: number | null;
  createdAt: string;
}

export interface ChatMessage {
  id: string;
  moveNumber: number;
  senderModel: string;
  senderColor: 'WHITE' | 'BLACK';
  message: string;
  createdAt: string;
}

export interface WsMessage {
  type: 'move' | 'chat' | 'gameStatus' | 'retry' | 'forfeit' | 'evaluationUpdate';
  gameId: string;
  activeColor?: 'WHITE' | 'BLACK';
  turnStartedAt?: string;
  turnDeadlineAt?: string;
  evaluationCp?: number;
  evaluationMate?: number;
  moveNumber?: number;
  [key: string]: any;
}
