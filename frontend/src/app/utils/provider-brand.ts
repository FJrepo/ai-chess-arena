export interface ProviderBrand {
  key: string;
  name: string;
  logoPath: string | null;
  accent: string;
  background: string;
  approvalStatus: 'custom' | 'approved' | 'pending' | 'denied';
  termsUrl: string | null;
}

const PROVIDER_ALIASES: Record<string, string> = {
  'anthropic-ai': 'anthropic',
  cohereforai: 'cohere',
  'google-ai': 'google',
  'google-deepmind': 'google',
  meta: 'meta-llama',
  'meta-ai': 'meta-llama',
  metaai: 'meta-llama',
  mistral: 'mistralai',
  'mini-max': 'minimax',
  minmax: 'minimax',
  'open-ai': 'openai',
  'x.ai': 'x-ai',
  xai: 'x-ai',
};

const KNOWN_PROVIDERS: Record<string, ProviderBrand> = {
  openai: {
    key: 'openai',
    name: 'OpenAI',
    logoPath: '/provider-logos/openai.svg',
    accent: '#9fe3ce',
    background: '#103a33',
    approvalStatus: 'custom',
    termsUrl: 'https://openai.com/brand/',
  },
  anthropic: {
    key: 'anthropic',
    name: 'Anthropic',
    logoPath: '/provider-logos/anthropic.svg',
    accent: '#ffd4a7',
    background: '#3f2712',
    approvalStatus: 'custom',
    termsUrl: 'https://brandfolder.com/anthropic/',
  },
  google: {
    key: 'google',
    name: 'Google',
    logoPath: '/provider-logos/google.svg',
    accent: '#9ec5ff',
    background: '#152542',
    approvalStatus: 'custom',
    termsUrl: 'https://about.google/brand-resource-center/guidance/',
  },
  'x-ai': {
    key: 'x-ai',
    name: 'xAI',
    logoPath: '/provider-logos/x-ai.svg',
    accent: '#e6e6ea',
    background: '#1b1c22',
    approvalStatus: 'custom',
    termsUrl: null,
  },
  'meta-llama': {
    key: 'meta-llama',
    name: 'Meta',
    logoPath: '/provider-logos/meta-llama.svg',
    accent: '#9fc0ff',
    background: '#18284f',
    approvalStatus: 'custom',
    termsUrl: 'https://opensource.fb.com/legal/trademark/',
  },
  deepseek: {
    key: 'deepseek',
    name: 'DeepSeek',
    logoPath: '/provider-logos/deepseek.svg',
    accent: '#9fbcff',
    background: '#11254d',
    approvalStatus: 'custom',
    termsUrl: null,
  },
  mistralai: {
    key: 'mistralai',
    name: 'Mistral',
    logoPath: '/provider-logos/mistralai.svg',
    accent: '#ffc8a4',
    background: '#412616',
    approvalStatus: 'custom',
    termsUrl: 'https://mistral.ai/brand',
  },
  qwen: {
    key: 'qwen',
    name: 'Qwen',
    logoPath: '/provider-logos/qwen.svg',
    accent: '#a9f2e2',
    background: '#173932',
    approvalStatus: 'custom',
    termsUrl: null,
  },
  cohere: {
    key: 'cohere',
    name: 'Cohere',
    logoPath: '/provider-logos/cohere.svg',
    accent: '#e9b9ff',
    background: '#352047',
    approvalStatus: 'custom',
    termsUrl: null,
  },
  microsoft: {
    key: 'microsoft',
    name: 'Microsoft',
    logoPath: '/provider-logos/microsoft.svg',
    accent: '#c2d0f4',
    background: '#1f2d44',
    approvalStatus: 'custom',
    termsUrl: null,
  },
  minimax: {
    key: 'minimax',
    name: 'MiniMax',
    logoPath: '/provider-logos/minimax.svg',
    accent: '#e1c4ff',
    background: '#2f2350',
    approvalStatus: 'custom',
    termsUrl: null,
  },
};

function buildDisplayNameFromKey(key: string): string {
  if (!key) {
    return 'Unknown';
  }

  return key
    .split('-')
    .filter((token) => token.length > 0)
    .map((token) => token[0].toUpperCase() + token.slice(1))
    .join(' ');
}

export function normalizeProviderKey(provider?: string | null, modelId?: string | null): string {
  const raw = (provider && provider.trim().length > 0 ? provider : (modelId ?? ''))
    .trim()
    .toLowerCase();
  if (!raw) {
    return 'other';
  }

  const fromModelId = raw.includes('/') ? raw.split('/')[0] : raw;
  const normalized = fromModelId.replace(/[_\s]+/g, '-');
  return PROVIDER_ALIASES[normalized] ?? normalized;
}

export function resolveProviderBrand(
  provider?: string | null,
  modelId?: string | null,
): ProviderBrand {
  const key = normalizeProviderKey(provider, modelId);
  const known = KNOWN_PROVIDERS[key];
  if (known) {
    return known;
  }

  return {
    key: 'other',
    name: buildDisplayNameFromKey(key),
    logoPath: null,
    accent: '#c8d1e3',
    background: '#2b3343',
    approvalStatus: 'custom',
    termsUrl: null,
  };
}

export function providerDisplayName(provider?: string | null, modelId?: string | null): string {
  return resolveProviderBrand(provider, modelId).name;
}
