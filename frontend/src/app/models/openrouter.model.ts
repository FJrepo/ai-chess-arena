export interface OpenRouterModelOption {
  id: string;
  name: string;
  provider: string;
  contextLength: number | null;
  promptPricePerMillion: number | null;
  completionPricePerMillion: number | null;
  featured: boolean;
}

export interface OpenRouterModelsResponse {
  data?: OpenRouterModelOption[];
  totalMatched?: number;
  featuredCount?: number;
  error?: string;
}
