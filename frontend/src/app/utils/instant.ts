const ISO_INSTANT =
  /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(?:Z|([+-])(\d{2}):(\d{2}))$/;

/**
 * Chronological order of API timestamps (ISO-8601 with an offset). Text order is not chronological because the
 * fractional seconds vary in length (`…24Z` is earlier than `…24.1Z`), and Date keeps only milliseconds, so this
 * compares whole seconds and then the fraction down to the backend's microseconds. Missing values and impossible
 * dates or times (`2026-02-30`, `24:00`) are not read leniently: they sort first and compare equal to each other;
 * callers add their own tie-break.
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

/** Whether the value is a valid ISO-8601 instant with an offset. */
export function isInstant(value: string | null | undefined): value is string {
  return instantKey(value) !== null;
}

/** [epoch milliseconds of the whole second, nanoseconds within it], or null. */
function instantKey(value: string | null | undefined): [number, number] | null {
  const match = value ? ISO_INSTANT.exec(value) : null;
  if (!match) return null;
  const [year, month, day, hour, minute, second] = match.slice(1, 7).map(Number);
  const offsetHours = match[8] ? Number(match[9]) : 0;
  const offsetMinutes = match[8] ? Number(match[10]) : 0;
  if (hour > 23 || minute > 59 || second > 59 || offsetHours > 23 || offsetMinutes > 59) {
    return null;
  }
  const local = Date.UTC(year, month - 1, day, hour, minute, second);
  const date = new Date(local);
  // Date.UTC rolls 30 February over into March; a real calendar date survives the round trip.
  if (
    date.getUTCFullYear() !== year ||
    date.getUTCMonth() !== month - 1 ||
    date.getUTCDate() !== day
  ) {
    return null;
  }
  const offset = (match[8] === '-' ? -1 : 1) * (offsetHours * 60 + offsetMinutes) * 60_000;
  return [local - offset, Number((match[7] ?? '').padEnd(9, '0'))];
}
