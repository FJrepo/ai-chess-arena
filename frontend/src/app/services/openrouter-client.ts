import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, finalize, shareReplay } from 'rxjs';

export interface OpenRouterKeyStatus {
  /** Key presence determines connection; validation is independent. */
  source: 'env' | 'file' | 'ui' | 'none';
  maskedSuffix: string | null;
  lastValidatedAt: string | null;
  /** Raw last-check outcome; unavailable/none carry unknown validity. */
  validation: 'none' | 'unknown' | 'valid' | 'invalid' | 'unavailable';
}

export interface OpenRouterStatus {
  key: OpenRouterKeyStatus;
  canManage: boolean;
}

@Injectable({ providedIn: 'root' })
export class OpenRouterClient {
  private readonly url = '/api/config/openrouter';
  private pendingStatus?: Observable<OpenRouterStatus>;
  constructor(private http: HttpClient) {}

  status() {
    // Initial app and route loads must establish one browser cookie, even when concurrent.
    this.pendingStatus ??= this.http.get<OpenRouterStatus>(this.url).pipe(
      finalize(() => (this.pendingStatus = undefined)),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
    return this.pendingStatus;
  }
  start() {
    return this.http.post<{ authUrl: string }>(`${this.url}/connect`, {});
  }
  complete(code: string, state: string) {
    return this.http.post<OpenRouterStatus>(`${this.url}/complete`, { code, state });
  }
  manual(key: string) {
    return this.http.post<OpenRouterStatus>(`${this.url}/key`, { key });
  }
  delete() {
    return this.http.delete<OpenRouterStatus>(`${this.url}/key`);
  }
  test() {
    return this.http.post<OpenRouterStatus>(`${this.url}/test`, {});
  }
  claim(token: string) {
    return this.http.post<OpenRouterStatus>(`${this.url}/claim`, { token });
  }
}
