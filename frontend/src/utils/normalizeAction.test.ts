import { describe, it, expect } from 'vitest';
import { actionFromRealtime, normalizeAction } from './normalizeAction';

describe('action normalization (P4-102, decision D5)', () => {
  it('a history row keeps its fields and gets an ISO timestamp', () => {
    const row = normalizeAction({
      executionId: 'e1', requestId: 'r1', actionType: 'SEND_COMMAND', status: 'SUCCESS', timestamp: '2026-01-01T00:00:00Z',
      ackStatus: 'ACCEPTED', deliveryState: 'PUBLISHED', message: 'm',
    });
    expect(row.executionId).toBe('e1');
    expect(row.requestId).toBe('r1');
    expect(row.status).toBe('SUCCESS');
    expect(row.timestamp).toBe('2026-01-01T00:00:00.000Z');
    expect(row.ackStatus).toBe('ACCEPTED');
    expect(row.deliveryState).toBe('PUBLISHED');
  });

  it('a realtime ActionExecuted message becomes the same shape: status kept, millisecond time becomes ISO, no ack invented', () => {
    const ms = Date.parse('2026-01-01T00:00:00Z');
    const row = actionFromRealtime({ executionId: 'e2', actionType: 'SEND_COMMAND', status: 'FAILURE', executedAt: ms, message: 'boom' }, 'dev-1', 'corr-1');
    expect(row.executionId).toBe('e2');
    expect(row.status).toBe('FAILURE');
    expect(row.timestamp).toBe('2026-01-01T00:00:00.000Z');
    expect(row.deviceId).toBe('dev-1');
    expect(row.correlationId).toBe('corr-1');
    expect(row.requestId).toBeUndefined();
    expect(row.ackStatus).toBeUndefined();
    expect(row.deliveryState).toBeUndefined();
  });

  it('an unusable timestamp is null, never an invalid date', () => {
    expect(normalizeAction({ actionType: 'X', timestamp: 'garbage' }).timestamp).toBeNull();
    expect(normalizeAction({ actionType: 'X' }).timestamp).toBeNull();
    expect(actionFromRealtime({ actionType: 'X' }, undefined, undefined).timestamp).toBeNull();
  });

  it('never throws on anything', () => {
    for (const junk of [null, undefined, 5, 'x', [], {}, { actionType: 7 }, { deliveryState: 9 }]) {
      expect(() => normalizeAction(junk)).not.toThrow();
      expect(normalizeAction(junk).actionType).toBeTypeOf('string');
    }
  });
});
