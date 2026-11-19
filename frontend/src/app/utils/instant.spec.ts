import { compareInstants } from './instant';

describe('compareInstants', () => {
  it('orders by instant even when fractional seconds differ in length', () => {
    const values = [
      '2026-11-18T23:15:24.1Z',
      '2026-11-18T23:15:25Z',
      '2026-11-18T23:15:24Z',
      '2026-11-18T23:15:24.000001Z',
      '2026-11-18T23:15:24.0009Z',
    ];

    expect([...values].sort(compareInstants)).toEqual([
      '2026-11-18T23:15:24Z',
      '2026-11-18T23:15:24.000001Z',
      '2026-11-18T23:15:24.0009Z',
      '2026-11-18T23:15:24.1Z',
      '2026-11-18T23:15:25Z',
    ]);
  });

  it('keeps microseconds that Date would drop and treats equal instants as equal', () => {
    expect(
      compareInstants('2026-11-18T23:15:24.540714Z', '2026-11-18T23:15:24.540715Z'),
    ).toBeLessThan(0);
    expect(compareInstants('2026-11-18T23:15:24.5Z', '2026-11-18T23:15:24.500000Z')).toBe(0);
  });

  it('compares instants across offsets', () => {
    expect(compareInstants('2026-11-19T00:15:24+01:00', '2026-11-18T23:15:24.1Z')).toBeLessThan(0);
  });

  it('treats impossible dates and times as missing instead of normalizing them', () => {
    for (const invalid of [
      '2026-02-30T12:00:00Z',
      '2026-13-01T12:00:00Z',
      '2026-11-18T24:00:00.1Z',
      '2026-11-18T23:60:00Z',
      '2026-11-18T23:15:60Z',
      '2026-11-18T23:15:24+24:00',
    ]) {
      expect(compareInstants(invalid, null)).toBe(0);
      expect(compareInstants(invalid, '2000-01-01T00:00:00Z')).toBeLessThan(0);
    }
    expect(compareInstants('2028-02-29T12:00:00Z', null)).toBeGreaterThan(0);
  });

  it('sorts missing or unreadable values first', () => {
    expect(compareInstants(null, '2026-11-18T23:15:24Z')).toBeLessThan(0);
    expect(compareInstants('2026-11-18T23:15:24Z', 'not a time')).toBeGreaterThan(0);
    expect(compareInstants(undefined, '')).toBe(0);
  });
});
