/** The installed snapshot/event revision and the highest revision the server has announced. */
export class GameRevision {
  private applied: number | null = null;
  private highest = 0;

  get needsSnapshot(): boolean {
    return this.applied === null || this.highest > this.applied;
  }

  observe(revision: number): void {
    this.highest = Math.max(this.highest, revision);
  }

  acceptSnapshot(revision: number): boolean {
    if (revision < (this.applied ?? 0)) return false;
    this.applied = revision;
    this.observe(revision);
    return true;
  }

  acceptEvent(revision: number): boolean {
    const canApply = this.applied !== null && revision === this.applied + 1 && !this.needsSnapshot;
    this.observe(revision);
    if (canApply) this.applied = revision;
    return canApply;
  }
}
