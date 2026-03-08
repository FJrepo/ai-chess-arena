import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Tournament, Participant, Game } from '../models/tournament.model';
import { OpenRouterModelsResponse } from '../models/openrouter.model';
import {
  AnalyticsComparisonResponse,
  AnalyticsHealthResponse,
  ModelReliabilityDetail,
  ModelReliabilityResponse,
  TournamentCostSummary,
} from '../models/analytics.model';

export interface PromptTemplateResponse {
  template: string;
  version: string;
  hash: string;
}

export interface SystemStatusResponse {
  backendVersion: string;
  openRouterValid: boolean;
  stockfishAvailable: boolean;
  stockfishReason: string | null;
  checkedAt: string;
}

@Injectable({ providedIn: 'root' })
export class ApiService {
  private baseUrl = '/api';

  constructor(private http: HttpClient) {}

  // Tournaments
  getTournaments(): Observable<Tournament[]> {
    return this.http.get<Tournament[]>(`${this.baseUrl}/tournaments`);
  }

  getTournament(id: string): Observable<Tournament> {
    return this.http.get<Tournament>(`${this.baseUrl}/tournaments/${id}`);
  }

  createTournament(data: Partial<Tournament>): Observable<Tournament> {
    return this.http.post<Tournament>(`${this.baseUrl}/tournaments`, data);
  }

  updateTournament(id: string, data: Partial<Tournament>): Observable<Tournament> {
    return this.http.put<Tournament>(`${this.baseUrl}/tournaments/${id}`, data);
  }

  deleteTournament(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/tournaments/${id}`);
  }

  addParticipant(tournamentId: string, data: Partial<Participant>): Observable<Participant> {
    return this.http.post<Participant>(
      `${this.baseUrl}/tournaments/${tournamentId}/participants`,
      data,
    );
  }

  removeParticipant(tournamentId: string, participantId: string): Observable<void> {
    return this.http.delete<void>(
      `${this.baseUrl}/tournaments/${tournamentId}/participants/${participantId}`,
    );
  }

  generateBracket(tournamentId: string): Observable<Game[]> {
    return this.http.post<Game[]>(
      `${this.baseUrl}/tournaments/${tournamentId}/generate-bracket`,
      {},
    );
  }

  // Games
  getGame(id: string): Observable<Game> {
    return this.http.get<Game>(`${this.baseUrl}/games/${id}`);
  }

  startGame(id: string): Observable<Game> {
    return this.http.post<Game>(`${this.baseUrl}/games/${id}/start`, {});
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

  getGamePgn(id: string): Observable<string> {
    return this.http.get(`${this.baseUrl}/games/${id}/pgn`, { responseType: 'text' });
  }

  // Models
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

  checkOpenRouterStatus(): Observable<{ valid: boolean }> {
    return this.http.get<{ valid: boolean }>(`${this.baseUrl}/config/openrouter-status`);
  }

  getPromptTemplate(): Observable<PromptTemplateResponse> {
    return this.http.get<PromptTemplateResponse>(`${this.baseUrl}/config/prompt-template`);
  }

  getSystemStatus(): Observable<SystemStatusResponse> {
    return this.http.get<SystemStatusResponse>(`${this.baseUrl}/config/system-status`);
  }

  getTournamentCostSummary(tournamentId: string): Observable<TournamentCostSummary> {
    return this.http.get<TournamentCostSummary>(
      `${this.baseUrl}/tournaments/${tournamentId}/cost-summary`,
    );
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
