/**
 * One OpenRouter model from `GET /api/models`. Prices are USD per million tokens: `0` for a free model, `null`
 * when unknown or variable. The detail fields are optional because older backends do not send them.
 */
export interface OpenRouterModelOption {
  id: string;
  name: string;
  provider: string;
  contextLength: number | null;
  promptPricePerMillion: number | null;
  completionPricePerMillion: number | null;
  featured: boolean;
  /** Release date as YYYY-MM-DD (UTC). */
  releasedAt?: string | null;
  cacheReadPricePerMillion?: number | null;
  maxCompletionTokens?: number | null;
  reasoningSupported?: boolean;
  /** Null when the catalog does not say. */
  reasoningMandatory?: boolean | null;
  /** Normalized selectable levels: catalog null expands to all, missing stays empty; mandatory excludes none. */
  reasoningEfforts?: string[];
  /** Artificial Analysis Intelligence Index, as published in the OpenRouter catalog. */
  intelligenceIndex?: number | null;
  description?: string | null;
  /** The reasoning effort the model uses when none is sent (e.g. "max"); absent before 1.4. */
  defaultEffort?: string | null;
}

export interface OpenRouterModelsResponse {
  data?: OpenRouterModelOption[];
  totalMatched?: number;
  featuredCount?: number;
  error?: string | null;
  /** The configured featured providers in their configured order. */
  featuredProviders?: string[];
}
