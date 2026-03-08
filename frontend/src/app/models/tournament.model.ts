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
  controlType: 'AI' | 'HUMAN';
  modelId: string | null;
  customInstructions: string | null;
  seed: number;
}

export interface Game {
  id: string;
  tournamentId: string | null;
  whitePlayerName: string | null;
  whiteControlType: 'AI' | 'HUMAN';
  whiteModelId: string | null;
  blackPlayerName: string | null;
  blackControlType: 'AI' | 'HUMAN';
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
  modelId: string | null;
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
  senderModel: string | null;
  senderColor: 'WHITE' | 'BLACK';
  message: string;
  createdAt: string;
}

export interface MoveWsMessage {
  type: 'move';
  gameId: string;
  moveNumber: number;
  color: 'WHITE' | 'BLACK';
  san: string;
  fen: string;
  modelId: string | null;
  pgn?: string | null;
  responseTimeMs?: number;
  retryCount?: number;
  evaluationCp?: number | null;
  evaluationMate?: number | null;
}

export interface EvaluationUpdateWsMessage {
  type: 'evaluationUpdate';
  gameId: string;
  moveNumber: number;
  color: 'WHITE' | 'BLACK';
  evaluationCp?: number | null;
  evaluationMate?: number | null;
}

export interface ChatWsMessage {
  type: 'chat';
  gameId: string;
  moveNumber: number;
  senderModel: string | null;
  senderColor: 'WHITE' | 'BLACK';
  senderName?: string | null;
  message?: string;
}

export interface GameStatusWsMessage {
  type: 'gameStatus';
  gameId: string;
  status?: Game['status'];
  result?: string | null;
  resultReason?: string | null;
  totalCostUsd?: number | null;
  activeColor?: 'WHITE' | 'BLACK';
  turnStartedAt?: string;
  turnDeadlineAt?: string;
}

export interface RetryWsMessage {
  type: 'retry';
  gameId: string;
  color: 'WHITE' | 'BLACK';
  attemptNumber: number;
  reason: string;
}

export interface ForfeitWsMessage {
  type: 'forfeit';
  gameId: string;
}

export type WsMessage =
  | MoveWsMessage
  | EvaluationUpdateWsMessage
  | ChatWsMessage
  | GameStatusWsMessage
  | RetryWsMessage
  | ForfeitWsMessage;

export interface WsOutgoingMessage {
  type: 'subscribe' | 'unsubscribe';
  gameId: string;
}
