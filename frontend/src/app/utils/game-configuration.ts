import type { GameConfiguration, GameConfigurationPlayer } from '../models/tournament.model';
import { reasoningEffortLabel } from './reasoning-effort';

type Field = { setting: string; value: string; fingerprint?: string };

export interface ConfigurationDifference {
  setting: string;
  before: string;
  after: string;
}

export function requestedEffortLabel(player: GameConfigurationPlayer | null | undefined): string {
  if (!player) return 'Unknown';
  if (player.controlType !== 'AI') return '—';
  return reasoningEffortLabel(player.requestedReasoningEffort) || 'Model default';
}

export function configurationFields(
  settings: GameConfiguration,
  includeInstructionFingerprints = true,
): Field[] {
  const toggle = (value: boolean) => (value ? 'On' : 'Off');
  const words = (value: string) =>
    value.charAt(0) + value.slice(1).toLowerCase().replaceAll('_', ' ');
  const fingerprint = (setting: string, hash: string | null | undefined): Field => ({
    setting,
    value: hash ? 'Custom' : 'None',
    fingerprint: hash ?? undefined,
  });
  const side = (color: string, player: GameConfigurationPlayer | null): Field[] => [
    { setting: color + ' player', value: player?.playerName ?? 'None' },
    {
      setting: color + ' control',
      value: player
        ? { HUMAN: 'Human', AI: 'Model', STOCKFISH: 'Stockfish' }[player.controlType]
        : 'None',
    },
    { setting: color + ' model', value: player?.modelId ?? 'None' },
    {
      setting: color + ' effort override',
      value:
        player?.controlType === 'AI'
          ? reasoningEffortLabel(player.reasoningEffortOverride) || 'Inherit'
          : '—',
    },
    { setting: color + ' requested effort', value: requestedEffortLabel(player) },
    ...(includeInstructionFingerprints
      ? [
          fingerprint(color + ' instructions', player?.instructionsHash),
          fingerprint(color + ' legacy prompt', player?.systemPromptHash),
        ]
      : []),
  ];
  return [
    { setting: 'Format', value: settings.format ? words(settings.format) : 'None' },
    { setting: 'Draw policy', value: settings.drawPolicy ? words(settings.drawPolicy) : 'None' },
    { setting: 'Matchup length', value: String(settings.matchupBestOf ?? 'None') },
    { setting: 'Final length', value: String(settings.finalsBestOf ?? 'Same as matchups') },
    {
      setting: 'Opening policy',
      value:
        settings.openingMode === 'FIXED'
          ? 'Fixed · ' + (settings.configuredOpeningId ?? 'Unknown')
          : words(settings.openingMode ?? 'STANDARD'),
    },
    { setting: 'Game opening', value: settings.gameOpeningId ?? 'Standard' },
    {
      setting: 'Move time limit',
      value: settings.moveTimeoutSeconds == null ? 'No limit' : settings.moveTimeoutSeconds + ' s',
    },
    { setting: 'Attempts per move', value: String(settings.maxAttempts) },
    { setting: 'Output-token cap', value: String(settings.maxOutputTokens) },
    { setting: 'Chat', value: words(settings.chatMode) },
    {
      setting: 'Banter tone',
      value: settings.banterLevel ? words(settings.banterLevel) : 'Original tone',
    },
    { setting: 'Resignation', value: toggle(settings.resignationAllowed) },
    { setting: 'Adjudication', value: toggle(settings.adjudicationEnabled) },
    { setting: 'Legal move hints', value: toggle(settings.legalMoveHints) },
    {
      setting: 'Reasoning effort',
      value: reasoningEffortLabel(settings.reasoningEffort) || 'Model default',
    },
    ...(includeInstructionFingerprints
      ? [
          fingerprint('Shared instructions', settings.sharedInstructionsHash),
          fingerprint('Shared legacy prompt', settings.defaultSystemPromptHash),
        ]
      : []),
    ...side('White', settings.white),
    ...side('Black', settings.black),
    ...(settings.stockfishElo == null
      ? []
      : [{ setting: 'Stockfish target Elo', value: String(settings.stockfishElo) }]),
  ];
}

export function configurationDifferences(
  before: GameConfiguration,
  after: GameConfiguration,
  includeInstructionFingerprints = true,
): ConfigurationDifference[] {
  const earlier = configurationFields(before, includeInstructionFingerprints);
  return configurationFields(after, includeInstructionFingerprints).flatMap((current, index) => {
    const previous = earlier[index];
    if (previous.value !== current.value) {
      return [{ setting: current.setting, before: previous.value, after: current.value }];
    }
    if (previous.fingerprint !== current.fingerprint) {
      return [{ setting: current.setting, before: previous.value, after: 'Edited' }];
    }
    return [];
  });
}
