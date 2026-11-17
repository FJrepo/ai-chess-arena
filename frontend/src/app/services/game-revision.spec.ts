import { describe, expect, it } from 'vitest';
import { GameRevision } from './game-revision';

describe('GameRevision', () => {
  it('drops gaps and duplicates and tracks only the highest observed revision', () => {
    const revision = new GameRevision();
    revision.acceptSnapshot(3);
    expect(revision.acceptEvent(3)).toBe(false);
    expect(revision.acceptEvent(6)).toBe(false);
    expect(revision.acceptEvent(4)).toBe(false);
    expect(revision.needsSnapshot).toBe(true);
    expect(revision.acceptSnapshot(5)).toBe(true);
    expect(revision.needsSnapshot).toBe(true);
    expect(revision.acceptSnapshot(4)).toBe(false);
    expect(revision.acceptSnapshot(6)).toBe(true);
    expect(revision.acceptEvent(7)).toBe(true);
    expect(revision.acceptEvent(7)).toBe(false);
  });

  it('requires a snapshot before accepting events and detects a missing last event', () => {
    const revision = new GameRevision();
    expect(revision.acceptEvent(1)).toBe(false);
    expect(revision.acceptSnapshot(1)).toBe(true);
    revision.observe(2);
    expect(revision.needsSnapshot).toBe(true);
    expect(revision.acceptSnapshot(1)).toBe(true);
    expect(revision.needsSnapshot).toBe(true);
    expect(revision.acceptSnapshot(3)).toBe(true);
    revision.observe(2);
    expect(revision.needsSnapshot).toBe(false);
  });
});
