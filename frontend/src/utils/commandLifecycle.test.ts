import { describe, it, expect } from 'vitest';
import { ackLabel, deliveryLabel, formatDurationMs, formatInstant, NO_PHYSICAL_CLAIM, queueFullMessage, toEpochMillis, verificationLabel } from './commandLifecycle';

describe('command lifecycle labels (P4-102, P4-104)', () => {
  it('labels every known delivery state separately from acknowledgement and verification', () => {
    expect(deliveryLabel('QUEUED')?.label).toBe('Queued for delivery');
    expect(deliveryLabel('IN_FLIGHT')?.label).toBe('Being sent');
    expect(deliveryLabel('RETRY_WAIT')?.label).toBe('Waiting to retry');
    expect(deliveryLabel('PUBLISHED')?.label).toBe('Published to MQTT');
    expect(deliveryLabel('DELIVERY_UNKNOWN')?.label).toBe('Delivery unknown');
    expect(deliveryLabel('DEAD_LETTER', 'RETRY_EXHAUSTED')?.label).toContain('Dead-lettered');
    expect(ackLabel('ACCEPTED').label).toBe('Device accepted');
    expect(ackLabel('REJECTED').label).toBe('Device rejected');
    expect(verificationLabel('CONVERGED')?.label).toBe('Verified by readback');
  });

  it('an uncertain dead letter is labelled uncertain, never as a definite failure', () => {
    const label = deliveryLabel('DEAD_LETTER', 'UNCERTAIN_EXPIRED');
    expect(label?.label).toContain('uncertain');
    expect(label?.tone).toBe('warning');
  });

  it('PUBLISHED never reads as acknowledged, verified or physical', () => {
    const published = deliveryLabel('PUBLISHED')!.label.toLowerCase();
    expect(published).not.toMatch(/acknowledg|accepted|verified|converged|actuat|delivered|received/);
    expect(ackLabel(null).label).toBe('No acknowledgement recorded');
    expect(ackLabel(null).tone).toBe('neutral');
  });

  it('an acknowledgement timeout is its own label and is not a dead letter', () => {
    expect(ackLabel(null, true).label).toBe('No acknowledgement before the deadline');
    expect(ackLabel(null, true).label).not.toContain('Dead');
  });

  it('an unrecognised state renders a neutral label, does not echo the value and never throws', () => {
    for (const unknown of ['SOMETHING_NEW', '<script>alert(1)</script>', 42, {}, [], true]) {
      expect(() => deliveryLabel(unknown)).not.toThrow();
      const label = deliveryLabel(unknown)!;
      expect(label.tone).toBe('neutral');
      expect(label.label).toBe('Delivery state not recognised');
      expect(() => verificationLabel(unknown)).not.toThrow();
      expect(() => ackLabel(unknown)).not.toThrow();
    }
    expect(deliveryLabel(undefined)).toBeNull();
    expect(deliveryLabel(null)).toBeNull();
    expect(deliveryLabel('DEAD_LETTER', '<b>bad</b>')?.label).toBe('Dead-lettered');
  });

  it('no label and no standing sentence claims physical actuation or exactly-once delivery', () => {
    const all = [
      ...['QUEUED', 'IN_FLIGHT', 'RETRY_WAIT', 'PUBLISHED', 'DELIVERY_UNKNOWN', 'DEAD_LETTER', 'X'].map((s) => deliveryLabel(s)?.label ?? ''),
      ...['ACCEPTED', 'REJECTED', null].map((s) => ackLabel(s).label),
      ...['CONVERGED', 'PENDING', 'DIVERGED', 'TIMED_OUT', 'UNVERIFIED'].map((s) => verificationLabel(s)?.label ?? ''),
      queueFullMessage('DEVICE'), queueFullMessage('GLOBAL'), queueFullMessage(undefined),
    ].join(' ').toLowerCase();
    expect(all).not.toMatch(/exactly[- ]once|physically (acted|actuated|on|off)|is (on|off)\b|guaranteed/);
    expect(NO_PHYSICAL_CLAIM).toContain('do not establish that the device physically acted');
  });
});

describe('timestamps and durations', () => {
  it('shows a neutral placeholder instead of "Invalid Date"', () => {
    for (const bad of [undefined, null, '', 'not a date', NaN, 0, -5, {}, []]) expect(formatInstant(bad)).toBe('—');
    expect(formatInstant(undefined, 'n/a')).toBe('n/a');
  });

  it('accepts ISO strings, epoch milliseconds and epoch seconds', () => {
    const ms = Date.parse('2026-01-01T00:00:00Z');
    expect(toEpochMillis('2026-01-01T00:00:00Z')).toBe(ms);
    expect(toEpochMillis(ms)).toBe(ms);
    expect(toEpochMillis(ms / 1000)).toBe(ms);
    expect(formatInstant(ms)).not.toContain('Invalid');
  });

  it('formats durations and refuses a negative or missing figure', () => {
    expect(formatDurationMs(250)).toBe('250 ms');
    expect(formatDurationMs(4200)).toBe('4.2 s');
    expect(formatDurationMs(-1)).toBeNull();
    expect(formatDurationMs(undefined)).toBeNull();
    expect(formatDurationMs(NaN)).toBeNull();
  });
});

describe('queue-full message (P4.6 429)', () => {
  it('says nothing was queued or sent, and does not call the refusal a delivery failure', () => {
    for (const scope of ['DEVICE', 'GLOBAL', undefined, 'OTHER']) {
      const message = queueFullMessage(scope);
      expect(message).toContain('not queued');
      expect(message).toContain('nothing was sent');
      expect(message.toLowerCase()).not.toContain('failed');
    }
    expect(queueFullMessage('DEVICE')).toContain('This device');
    expect(queueFullMessage('GLOBAL')).toContain('NEXUS is holding');
  });
});
