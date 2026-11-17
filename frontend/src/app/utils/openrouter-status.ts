import { OpenRouterKeyStatus } from '../services/openrouter-client';

export type OpenRouterValidation = 'valid' | 'invalid' | 'unknown';

/** Connection means a key exists, independently of whether it has been tested. */
export function openRouterConnected(key: OpenRouterKeyStatus | null | undefined): boolean {
  return key?.source === 'env' || key?.source === 'file' || key?.source === 'ui';
}

/** Missing keys and unavailable checks provide no evidence that a key is valid or invalid. */
export function openRouterValidation(
  key: OpenRouterKeyStatus | null | undefined,
): OpenRouterValidation {
  if (!openRouterConnected(key)) return 'unknown';
  return key?.validation === 'valid' || key?.validation === 'invalid' ? key.validation : 'unknown';
}
