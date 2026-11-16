/**
 * The backend's time limit per move in seconds (Tournament.DEFAULT_MOVE_TIMEOUT_SECONDS): used when a tournament is
 * created without one and for games outside a tournament. Running out of time forfeits the game.
 */
export const DEFAULT_MOVE_TIMEOUT_SECONDS = 120;

/** OFF: models stay silent. OPTIONAL: models may send a message. MANDATORY: models are asked for one every move. */
export type ChatMode = 'OFF' | 'OPTIONAL' | 'MANDATORY';

export interface Tournament {
  /** Champion of a completed tournament. */
  winnerName?: string | null;
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
  /**
   * Time limit per move in seconds (10-3600); null means no time limit. In create and update requests, 0
   * removes the limit and an omitted or null value keeps the default (120 s) or the current value.
   */
  moveTimeoutSeconds: number | null;
  maxRetries: number;
  matchupBestOf: 1 | 3 | 5 | 7;
  finalsBestOf: 1 | 3 | 5 | 7 | null;
  /** True unless chatMode is OFF; kept for older clients. */
  trashTalkEnabled: boolean;
  /** Whether models talk: OFF, OPTIONAL (default) or MANDATORY. Absent from backends before 1.4. */
  chatMode?: ChatMode;
  /**
   * Whether models may resign instead of moving, from their 10th move on (default false). Absent from
   * backends before 1.4.
   */
  resignationAllowed?: boolean;
  /**
   * Cap on generated tokens per model answer (includes reasoning tokens); cut-off answers pause the game.
   * Always sent by the backend (default 4096); optional only so existing test fixtures stay valid.
   */
  maxOutputTokens?: number;
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
  /** Why the game is paused when the pause was caused by an operational problem (e.g. OpenRouter). */
  statusDetail?: string | null;
  /** Set on the game that decided its series: who advanced (also when a draw policy decided a level series). */
  advancedPlayerName?: string | null;
  /**
   * Bracket participant ids of each side and of who advanced. Null for a side without a participant
   * (a bye or a slot not reached yet); absent in data from older backends.
   */
  whiteParticipantId?: string | null;
  blackParticipantId?: string | null;
  advancedParticipantId?: string | null;
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
  /** Per-side move quality; sent with a single game (with its moves), otherwise null or absent. */
  qualitySummary?: GameQualitySummary | null;
  /** How the game ended by resignation; sent with a single game that ended so, otherwise null or absent. */
  resignation?: GameResignation | null;
  /**
   * Grows with every status change (start, pause, game over); status-change updates carry it too, so an older
   * update that arrives late can be ignored. Absent from older backends.
   */
  statusRevision?: number;
}

/** A resignation: who resigned instead of playing which move, and how the position looked. */
export interface GameResignation {
  color: 'WHITE' | 'BLACK';
  moveNumber: number;
  /** The resigning side's winning chance (0–1) from Stockfish's evaluation; null while unknown. */
  winChance: number | null;
  /** Winning chance of at least 35%: resigned while not losing. */
  notLosing: boolean;
  /** The message sent with the resignation, if any. */
  lastWords: string | null;
  /** The opponent's most recent message before the resignation, and its move number. */
  opponentMessage: string | null;
  opponentMessageMoveNumber: number | null;
}

export type MoveJudgement = 'INACCURACY' | 'MISTAKE' | 'BLUNDER';

/**
 * Quality of one move, derived by the backend from the Stockfish evaluations before and after it
 * (Lichess-style win-probability model). Winning chances are the mover's, from 0 to 1.
 */
export interface MoveQuality {
  /** Null when the move was not a notable error, or when it is excluded. */
  judgement: MoveJudgement | null;
  /** The mover's evaluation drop in centipawns (0–1000); null when excluded. */
  centipawnLoss: number | null;
  winChanceBefore: number;
  winChanceAfter: number;
  /** Operator overrides and forced moves (the only legal move) are not judged. */
  excluded: 'OVERRIDE' | 'FORCED' | null;
}

/** One side's judged moves in a game: average centipawn loss (null without any) and error counts. */
export interface SideQuality {
  analyzedMoves: number;
  averageCentipawnLoss: number | null;
  inaccuracies: number;
  mistakes: number;
  blunders: number;
}

export interface GameQualitySummary {
  white: SideQuality;
  black: SideQuality;
}

/** The quality of the move with this move number and color; null when it is not judged. */
export interface PlyQuality {
  moveNumber: number;
  color: 'WHITE' | 'BLACK';
  quality: MoveQuality | null;
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
  /** Null or absent until both evaluations around the move are known. */
  quality?: MoveQuality | null;
  /** The chat mode that applied to a model move; absent for human moves, overrides and older backends. */
  chatMode?: ChatMode | null;
}

export type MoveAttemptOutcome =
  | 'ACCEPTED'
  | 'ILLEGAL_MOVE'
  | 'PARSE_FAILURE'
  | 'EMPTY_RESPONSE'
  | 'TRUNCATED'
  | 'TIMEOUT'
  | 'PROVIDER_RETRYABLE'
  | 'PROVIDER_FATAL'
  | 'DISCARDED'
  /** Tried to resign without a move while resigning was not allowed: an invalid answer. */
  | 'RESIGN_NOT_ALLOWED'
  /** An allowed resignation that ended the game. */
  | 'RESIGNED';

/** One model answer for a ply, including rejected ones (GET /api/games/{id}/attempts). */
export interface MoveAttempt {
  id: string;
  moveNumber: number;
  color: 'WHITE' | 'BLACK';
  attemptNumber: number;
  modelId: string | null;
  outcome: MoveAttemptOutcome;
  proposedMove: string | null;
  promptTokens: number | null;
  completionTokens: number | null;
  reasoningTokens: number | null;
  costUsd: number | null;
  responseTimeMs: number | null;
  finishReason: string | null;
  errorMessage: string | null;
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
  isOverride?: boolean;
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
  /** Qualities this evaluation decides: the evaluated move's and the next move's, if played. */
  qualities?: PlyQuality[];
  /** The game's per-side quality summary after this evaluation. */
  qualitySummary?: GameQualitySummary | null;
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
  detail?: string | null;
  /** The tournament's configured chat mode; sent with turn updates of model turns only. */
  tournamentChatMode?: ChatMode;
  /** The game's status revision after a status change (start, pause, game over); absent on turn updates. */
  statusRevision?: number;
}

export interface RetryWsMessage {
  type: 'retry';
  gameId: string;
  color: 'WHITE' | 'BLACK';
  attemptNumber: number;
  reason: string;
  /** MoveAttempt outcome of the rejected answer, e.g. 'ILLEGAL_MOVE'. */
  outcome?: MoveAttemptOutcome;
  /** The rejected move, when the answer contained one. */
  proposedMove?: string | null;
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

export interface CreateTournamentRequest extends Partial<
  Omit<Tournament, 'participants' | 'games'>
> {
  participants?: Array<Partial<Participant>>;
  clientRequestId?: string;
}
