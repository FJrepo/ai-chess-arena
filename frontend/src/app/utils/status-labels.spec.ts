import {
  gameStatusLabel,
  humanizeStatus,
  resultReasonLabel,
  tournamentStatusLabel,
} from './status-labels';

describe('tournamentStatusLabel', () => {
  it('maps every known tournament status to a readable label', () => {
    expect(tournamentStatusLabel('CREATED')).toBe('Not started');
    expect(tournamentStatusLabel('IN_PROGRESS')).toBe('In progress');
    expect(tournamentStatusLabel('COMPLETED')).toBe('Completed');
    expect(tournamentStatusLabel('CANCELLED')).toBe('Cancelled');
  });

  it('humanizes unknown values instead of showing the raw enum', () => {
    expect(tournamentStatusLabel('ON_HOLD')).toBe('On hold');
  });
});

describe('gameStatusLabel', () => {
  it('maps every known game status to a readable label', () => {
    expect(gameStatusLabel('WAITING')).toBe('Waiting');
    expect(gameStatusLabel('IN_PROGRESS')).toBe('Live');
    expect(gameStatusLabel('PAUSED')).toBe('Paused');
    expect(gameStatusLabel('COMPLETED')).toBe('Completed');
    expect(gameStatusLabel('FORFEIT')).toBe('Forfeit');
  });

  it('humanizes unknown values instead of showing the raw enum', () => {
    expect(gameStatusLabel('ABORTED_BY_ADMIN')).toBe('Aborted by admin');
  });

  it('does not treat inherited object keys as known statuses', () => {
    expect(gameStatusLabel('toString')).toBe('Tostring');
  });
});

describe('humanizeStatus', () => {
  it('handles empty and missing values', () => {
    expect(humanizeStatus('')).toBe('Unknown');
    expect(humanizeStatus(null)).toBe('Unknown');
    expect(humanizeStatus(undefined)).toBe('Unknown');
  });

  it('collapses separators and whitespace', () => {
    expect(humanizeStatus('  waiting__for-PLAYERS ')).toBe('Waiting for players');
  });
});

describe('resultReasonLabel', () => {
  it('phrases known result reasons', () => {
    expect(resultReasonLabel('RESIGNATION')).toBe('by resignation');
    expect(resultReasonLabel('CHECKMATE')).toBe('by checkmate');
    expect(resultReasonLabel('TIMEOUT')).toBe('on time');
    expect(resultReasonLabel('FIFTY_MOVE')).toBe('by the fifty-move rule');
  });

  it('humanizes unknown reasons and drops a missing one', () => {
    expect(resultReasonLabel('MUTUAL_AGREEMENT')).toBe('by mutual agreement');
    expect(resultReasonLabel(null)).toBe('');
    expect(resultReasonLabel(undefined)).toBe('');
    expect(resultReasonLabel(' ')).toBe('');
  });
});
