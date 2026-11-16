import { Game, Tournament } from '../models/tournament.model';

const TOURNAMENT_STATUS_LABELS: Record<Tournament['status'], string> = {
  CREATED: 'Not started',
  IN_PROGRESS: 'In progress',
  COMPLETED: 'Completed',
  CANCELLED: 'Cancelled',
};

const GAME_STATUS_LABELS: Record<Game['status'], string> = {
  WAITING: 'Waiting',
  IN_PROGRESS: 'Live',
  PAUSED: 'Paused',
  COMPLETED: 'Completed',
  FORFEIT: 'Forfeit',
};

/** Reader-facing label for a tournament status; unknown values are humanized. */
export function tournamentStatusLabel(status: string | null | undefined): string {
  return lookup(TOURNAMENT_STATUS_LABELS, status);
}

/** Reader-facing label for a game status; unknown values are humanized. */
export function gameStatusLabel(status: string | null | undefined): string {
  return lookup(GAME_STATUS_LABELS, status);
}

/** Turns a raw enum value such as `SOME_NEW_STATE` into `Some new state`. */
export function humanizeStatus(status: string | null | undefined): string {
  const words = (status ?? '').trim().replace(/[_-]+/g, ' ').replace(/\s+/g, ' ').toLowerCase();
  return words.length === 0 ? 'Unknown' : words.charAt(0).toUpperCase() + words.slice(1);
}

function lookup(labels: Record<string, string>, status: string | null | undefined): string {
  if (status && Object.hasOwn(labels, status)) {
    return labels[status];
  }
  return humanizeStatus(status);
}
