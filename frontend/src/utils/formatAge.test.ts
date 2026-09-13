import { describe, it, expect } from 'vitest';
import { formatAge } from './formatAge';

describe('formatAge - pure duration formatting of a real timestamp', () => {
  it('formats sub-minute ages in seconds', () => {
    const now = 1_700_000_000_000;
    expect(formatAge(now - 5_000, now)).toBe('5s ago');
  });

  it('formats minute-scale ages with seconds remainder', () => {
    const now = 1_700_000_000_000;
    expect(formatAge(now - (4 * 60_000 + 12_000), now)).toBe('4m 12s ago');
  });

  it('formats hour-scale ages with minutes remainder', () => {
    const now = 1_700_000_000_000;
    expect(formatAge(now - (2 * 3_600_000 + 5 * 60_000), now)).toBe('2h 5m ago');
  });

  it('formats day-scale ages in whole days', () => {
    const now = 1_700_000_000_000;
    expect(formatAge(now - 3 * 86_400_000, now)).toBe('3d ago');
  });

  it('never returns a negative duration for a clock-skewed future timestamp', () => {
    const now = 1_700_000_000_000;
    expect(formatAge(now + 10_000, now)).toBe('0s ago');
  });
});
