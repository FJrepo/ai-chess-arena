const ISO_INSTANT = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})$/;

/**
 * Chronological order of API timestamps (ISO-8601 with an offset). Text order is not chronological because the
 * fractional seconds vary in length (`…24Z` is earlier than `…24.1Z`), and Date keeps only milliseconds, so this
 * compares whole seconds and then the fraction down to the backend's microseconds. Missing or unreadable values sort
 * first and compare equal to each other; callers add their own tie-break.
 */
export function compareInstants(
  left: string | null | undefined,
  right: string | null | undefined,
): number {
  const a = instantKey(left);
  const b = instantKey(right);
  if (a === null || b === null) return a === b ? 0 : a === null ? -1 : 1;
  return a[0] - b[0] || a[1] - b[1];
}

/** [epoch milliseconds of the whole second, nanoseconds within it], or null. */
function instantKey(value: string | null | undefined): [number, number] | null {
  const match = value ? ISO_INSTANT.exec(value) : null;
  if (!match) return null;
  const seconds = Date.parse(match[1] + match[3]);
  if (Number.isNaN(seconds)) return null;
  return [seconds, Number((match[2] ?? '').padEnd(9, '0'))];
}
