import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, defer, tap } from 'rxjs';
import {
  TournamentRunSettings,
  BracketDrawMode,
  ChatMode,
  BanterLevel,
  Tournament,
  Participant,
  Game,
  CreateTournamentRequest,
  MoveAttempt,
  Opening,
} from '../models/tournament.model';
import { OpenRouterModelsResponse } from '../models/openrouter.model';
import { LeaderboardResponse } from '../models/leaderboard.model';
import { ReasoningEffort } from '../utils/reasoning-effort';
import { OpenRouterClient, OpenRouterKeyStatus } from './openrouter-client';
import {
  AnalyticsComparisonResponse,
  AnalyticsHealthResponse,
  ModelReliabilityDetail,
  ModelReliabilityResponse,
  TournamentCostSummary,
} from '../models/analytics.model';

type PromptVariant = { template: string; version: string; hash: string };

export interface PromptTemplateResponse {
  banterChatModes?: Partial<Record<BanterLevel, Partial<Record<ChatMode, PromptVariant>>>>;
  resignationBanterChatModes?: Partial<
    Record<BanterLevel, Partial<Record<ChatMode, PromptVariant>>>
  >;
  template: string;
  version: string;
  hash: string;
  /** The rules of every chat mode by name; absent from backends before 1.4. */
  chatModes?: Partial<Record<ChatMode, { template: string; version: string; hash: string }>>;
  /** The same rules for a tournament that allows resignation (rule 9 added); absent before 1.4. */
  resignationChatModes?: Partial<
    Record<ChatMode, { template: string; version: string; hash: string }>
  >;
}

export interface OpenRouterStatusResponse {
  /** Legacy validation-only flag. False also includes untested/unavailable; use key.source for connection. */
  valid: boolean;
  key: OpenRouterKeyStatus;
}

export interface SystemStatusResponse {
  backendVersion: string;
  /** Legacy validation-only flag; use key.source for connection and key.validation for the last check. */
  openRouterValid: boolean;
  key: OpenRouterKeyStatus;
  stockfishAvailable: boolean;
  stockfishReason: string | null;
  checkedAt: string;
}

@Injectable({ providedIn: 'root' })
export class ApiService {
  private baseUrl = '/api';

  constructor(
    private http: HttpClient,
    private connection: OpenRouterClient,
  ) {}

  getOpenings(): Observable<Opening[]> {
    return this.http.get<Opening[]>(`${this.baseUrl}/openings`);
  }

  // Tournaments
  getTournaments(): Observable<Tournament[]> {
    return this.http.get<Tournament[]>(`${this.baseUrl}/tournaments`);
  }

  getTournament(id: string): Observable<Tournament> {
    return this.http.get<Tournament>(`${this.baseUrl}/tournaments/${id}`);
  }

  getTournamentExport(id: string, format: 'pgn' | 'csv' | 'json'): Observable<Blob> {
    return this.http.get(`${this.baseUrl}/tournaments/${id}/export.${format}`, {
      responseType: 'blob',
    });
  }

  /**
   * Creates the tournament and its participants in one request (stored together or not at all).
   * Reusing the same clientRequestId on a retry returns the tournament created by the first attempt.
   */
  createTournament(data: CreateTournamentRequest): Observable<Tournament> {
    return this.http.post<Tournament>(`${this.baseUrl}/tournaments`, data);
  }

  updateTournament(id: string, data: Partial<Tournament>): Observable<Tournament> {
    return this.http.put<Tournament>(`${this.baseUrl}/tournaments/${id}`, data);
  }

  deleteTournament(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/tournaments/${id}`);
  }

  cancelTournament(id: string): Observable<Tournament> {
    return this.http.post<Tournament>(`${this.baseUrl}/tournaments/${id}/cancel`, {});
  }

  runTournament(id: string, settings: Partial<TournamentRunSettings>): Observable<Tournament> {
    return this.http.post<Tournament>(`${this.baseUrl}/tournaments/${id}/run`, settings);
  }

  updateTournamentRun(
    id: string,
    settings: Partial<TournamentRunSettings>,
  ): Observable<Tournament> {
    return this.http.put<Tournament>(`${this.baseUrl}/tournaments/${id}/run`, settings);
  }

  pauseTournamentRun(id: string): Observable<Tournament> {
    return this.http.post<Tournament>(`${this.baseUrl}/tournaments/${id}/run/pause`, {});
  }

  addParticipant(tournamentId: string, data: Partial<Participant>): Observable<Participant> {
    return this.http.post<Participant>(
      `${this.baseUrl}/tournaments/${tournamentId}/participants`,
      data,
    );
  }

  /**
   * Changes a participant's reasoning-effort override (null: use the tournament's). Allowed at any time; a running
   * or resumed game applies it from that participant's next turn.
   */
  updateParticipant(
    tournamentId: string,
    participantId: string,
    data: { reasoningEffort: ReasoningEffort | null },
  ): Observable<Participant> {
    return this.http.put<Participant>(
      `${this.baseUrl}/tournaments/${tournamentId}/participants/${participantId}`,
      data,
    );
  }

  removeParticipant(tournamentId: string, participantId: string): Observable<void> {
    return this.http.delete<void>(
      `${this.baseUrl}/tournaments/${tournamentId}/participants/${participantId}`,
    );
  }

  generateBracket(tournamentId: string, drawMode: BracketDrawMode = 'ORDER'): Observable<Game[]> {
    return this.http.post<Game[]>(
      `${this.baseUrl}/tournaments/${tournamentId}/generate-bracket`,
      {},
      { params: { drawMode } },
    );
  }

  // Games
  getGame(id: string): Observable<Game> {
    return this.http.get<Game>(`${this.baseUrl}/games/${id}`);
  }

  startGame(id: string, queueIfFull = false): Observable<Game> {
    return this.http.post<Game>(
      `${this.baseUrl}/games/${id}/start`,
      {},
      {
        params: queueIfFull ? { queueIfFull: true } : {},
      },
    );
  }

  cancelResumeRequest(id: string, statusRevision: number): Observable<Game> {
    return this.http.delete<Game>(`${this.baseUrl}/games/${id}/resume-request`, {
      params: { statusRevision },
    });
  }

  pauseGame(id: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/games/${id}/pause`, {});
  }

  overrideMove(gameId: string, move: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/games/${gameId}/override-move`, { move });
  }

  submitHumanMove(gameId: string, move: string, message: string | null): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/games/${gameId}/human-move`, {
      move,
      message,
    });
  }

  submitHumanMoveCoordinates(
    gameId: string,
    from: string,
    to: string,
    promotion: string | null,
    message: string | null,
  ): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/games/${gameId}/human-move`, {
      from,
      to,
      promotion,
      message,
    });
  }

  /** Every model attempt of the game, including rejected answers, ordered by move number. */
  getGameAttempts(gameId: string): Observable<MoveAttempt[]> {
    return this.http.get<MoveAttempt[]>(`${this.baseUrl}/games/${gameId}/attempts`);
  }

  getGamePgn(id: string): Observable<string> {
    return this.http.get(`${this.baseUrl}/games/${id}/pgn`, { responseType: 'text' });
  }

  // Models
  /** `limit: 0` returns every matching model (no cap). */
  getModels(params?: {
    featuredOnly?: boolean;
    q?: string;
    limit?: number;
  }): Observable<OpenRouterModelsResponse> {
    let httpParams = new HttpParams();

    if (params?.featuredOnly !== undefined) {
      httpParams = httpParams.set('featuredOnly', String(params.featuredOnly));
    }
    if (params?.q && params.q.trim().length > 0) {
      httpParams = httpParams.set('q', params.q.trim());
    }
    if (params?.limit !== undefined) {
      httpParams = httpParams.set('limit', String(params.limit));
    }

    return this.http.get<OpenRouterModelsResponse>(`${this.baseUrl}/models`, {
      params: httpParams,
    });
  }

  checkOpenRouterStatus(): Observable<OpenRouterStatusResponse> {
    return defer(() => {
      const generation = this.connection.mutationGeneration;
      return this.http
        .get<OpenRouterStatusResponse>(`${this.baseUrl}/config/openrouter-status`)
        .pipe(tap((status) => this.connection.reconcileKeyStatus(status.key, generation)));
    });
  }

  getPromptTemplate(): Observable<PromptTemplateResponse> {
    return this.http.get<PromptTemplateResponse>(`${this.baseUrl}/config/prompt-template`);
  }

  getSystemStatus(): Observable<SystemStatusResponse> {
    return defer(() => {
      const generation = this.connection.mutationGeneration;
      return this.http
        .get<SystemStatusResponse>(`${this.baseUrl}/config/system-status`)
        .pipe(tap((status) => this.connection.reconcileKeyStatus(status.key, generation)));
    });
  }

  getTournamentCostSummary(tournamentId: string): Observable<TournamentCostSummary> {
    return this.http.get<TournamentCostSummary>(
      `${this.baseUrl}/tournaments/${tournamentId}/cost-summary`,
    );
  }

  getLeaderboard(includeForfeits = true): Observable<LeaderboardResponse> {
    return this.http.get<LeaderboardResponse>(`${this.baseUrl}/analytics/leaderboard`, {
      params: { includeForfeits },
    });
  }

  getAnalyticsHealth(params?: {
    days?: number;
    tournamentId?: string | null;
  }): Observable<AnalyticsHealthResponse> {
    let httpParams = new HttpParams();
    if (params?.days !== undefined) {
      httpParams = httpParams.set('days', String(params.days));
    }
    if (params?.tournamentId) {
      httpParams = httpParams.set('tournamentId', params.tournamentId);
    }
    return this.http.get<AnalyticsHealthResponse>(`${this.baseUrl}/analytics/health`, {
      params: httpParams,
    });
  }

  getAnalyticsComparison(params?: {
    days?: number;
    tournamentId?: string | null;
    minGames?: number;
  }): Observable<AnalyticsComparisonResponse> {
    let httpParams = new HttpParams();
    if (params?.days !== undefined) {
      httpParams = httpParams.set('days', String(params.days));
    }
    if (params?.tournamentId) {
      httpParams = httpParams.set('tournamentId', params.tournamentId);
    }
    if (params?.minGames !== undefined) {
      httpParams = httpParams.set('minGames', String(params.minGames));
    }
    return this.http.get<AnalyticsComparisonResponse>(`${this.baseUrl}/analytics/comparison`, {
      params: httpParams,
    });
  }

  getAnalyticsReliability(params?: {
    days?: number;
    tournamentId?: string | null;
    minGames?: number;
  }): Observable<ModelReliabilityResponse> {
    let httpParams = new HttpParams();
    if (params?.days !== undefined) {
      httpParams = httpParams.set('days', String(params.days));
    }
    if (params?.tournamentId) {
      httpParams = httpParams.set('tournamentId', params.tournamentId);
    }
    if (params?.minGames !== undefined) {
      httpParams = httpParams.set('minGames', String(params.minGames));
    }
    return this.http.get<ModelReliabilityResponse>(`${this.baseUrl}/analytics/reliability`, {
      params: httpParams,
    });
  }

  getAnalyticsReliabilityModel(
    modelId: string,
    params?: {
      days?: number;
      tournamentId?: string | null;
    },
  ): Observable<ModelReliabilityDetail> {
    let httpParams = new HttpParams();
    if (params?.days !== undefined) {
      httpParams = httpParams.set('days', String(params.days));
    }
    if (params?.tournamentId) {
      httpParams = httpParams.set('tournamentId', params.tournamentId);
    }
    return this.http.get<ModelReliabilityDetail>(
      `${this.baseUrl}/analytics/reliability/${encodeURIComponent(modelId)}`,
      { params: httpParams },
    );
  }
}
