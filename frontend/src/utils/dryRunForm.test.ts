import { describe, it, expect } from 'vitest';
import { buildTwins, emptyRow, formatActual, parseTypedValue, scopeDeviceOf, suggestedRows, type TwinRow } from './dryRunForm';

const row = (patch: Partial<TwinRow>): TwinRow => ({ ...emptyRow('r', 'temperature'), ...patch });
const TIME = '2026-10-14T10:00:00.000Z';

describe('typed dry-run values (scenario 61)', () => {
  it('keeps 0 a number, false a boolean and the empty string a string - never missing', () => {
    expect(parseTypedValue({ kind: 'NUMBER', text: '0', boolValue: false })).toEqual({ ok: true, value: 0 });
    expect(parseTypedValue({ kind: 'NUMBER', text: ' -0.5 ', boolValue: false })).toEqual({ ok: true, value: -0.5 });
    expect(parseTypedValue({ kind: 'BOOLEAN', text: '', boolValue: false })).toEqual({ ok: true, value: false });
    expect(parseTypedValue({ kind: 'STRING', text: '', boolValue: true })).toEqual({ ok: true, value: '' });
    expect(parseTypedValue({ kind: 'STRING', text: '  padded ', boolValue: true })).toEqual({ ok: true, value: '  padded ' });
    expect(parseTypedValue({ kind: 'NULL', text: '5', boolValue: true })).toEqual({ ok: true, value: null });
    expect(parseTypedValue({ kind: 'MISSING', text: '5', boolValue: true })).toEqual({ ok: true, value: undefined });
  });

  it('refuses to coerce: an empty or malformed number is an error, not 0 or NaN', () => {
    for (const text of ['', '   ', 'abc', 'NaN', 'Infinity', '0x10', '1,5', '1 2', '--1', '1e999']) {
      const parsed = parseTypedValue({ kind: 'NUMBER', text, boolValue: false });
      expect(parsed.ok, `"${text}"`).toBe(false);
    }
    expect(parseTypedValue({ kind: 'NUMBER', text: '1e3', boolValue: false })).toEqual({ ok: true, value: 1000 });
  });

  it('sends every non-missing value with its explicit type and omits a not-reported capability entirely', () => {
    const built = buildTwins([
      row({ id: 'a', capabilityKey: 'temperature', kind: 'NUMBER', text: '0', unit: 'CELSIUS' }),
      row({ id: 'b', capabilityKey: 'motion', kind: 'BOOLEAN', boolValue: false }),
      row({ id: 'c', capabilityKey: 'label', kind: 'STRING', text: '' }),
      row({ id: 'd', capabilityKey: 'gone', kind: 'NULL' }),
      row({ id: 'e', capabilityKey: 'absent', kind: 'MISSING' }),
    ], 'device-1', TIME);
    expect(built.errors).toEqual([]);
    expect(built.twins.map((t) => [t.capabilityKey, t.valueType, t.value, t.unit])).toEqual([
      ['temperature', 'NUMBER', 0, 'CELSIUS'], ['motion', 'BOOLEAN', false, null], ['label', 'STRING', '', null], ['gone', 'NUMBER', null, null],
    ]);
    expect(built.twins.some((t) => t.capabilityKey === 'absent')).toBe(false);
    for (const t of built.twins) expect('value' in t, 'the value key is always present').toBe(true);
  });

  it('models receipt time honestly: at simulation time, unavailable (null, never "now"), or an explicit instant', () => {
    const at = buildTwins([row({ text: '1', receipt: 'AT_SIMULATION_TIME' })], 'd', TIME).twins[0];
    const unavailable = buildTwins([row({ text: '1', receipt: 'UNAVAILABLE' })], 'd', TIME).twins[0];
    const custom = buildTwins([row({ text: '1', receipt: 'CUSTOM', receivedAt: '2026-10-14T09:00:00Z' })], 'd', TIME).twins[0];
    expect(at.receivedAt).toBe(TIME);
    expect(unavailable.receivedAt).toBeNull();
    expect(custom.receivedAt).toBe('2026-10-14T09:00:00.000Z');
    const bad = buildTwins([row({ text: '1', receipt: 'CUSTOM', receivedAt: '' })], 'd', TIME);
    expect(bad.twins).toEqual([]);
    expect(bad.errors).toHaveLength(1);
  });

  it('reports each invalid row and still never sends it', () => {
    const built = buildTwins([row({ id: 'x', kind: 'NUMBER', text: '' }), row({ id: 'y', capabilityKey: ' ', kind: 'BOOLEAN' }), row({ id: 'z', text: '1', thresholdSeconds: '-5' })], 'd', TIME);
    expect(built.twins).toEqual([]);
    expect(built.errors.map((e) => e.rowId)).toEqual(['x', 'y', 'z']);
  });

  it('displays false, 0, the empty string, null and a missing observation as distinct things', () => {
    expect(formatActual(false)).toBe('false');
    expect(formatActual(0)).toBe('0');
    expect(formatActual('')).toBe('"" (empty string)');
    expect(formatActual(null)).toBe('null');
    expect(formatActual(undefined)).toBe('not reported');
    expect(formatActual('MISSING', { stateQuality: null, capabilityKey: null })).toBe('no observation');
    expect(formatActual('MISSING', { stateQuality: 'GOOD', capabilityKey: 'x' })).toBe('"MISSING"');
    expect(new Set([formatActual(false), formatActual(0), formatActual(''), formatActual(null), formatActual(undefined)]).size).toBe(5);
  });

  it('suggests one starter row per capability a rule reads and finds its scope device', () => {
    let n = 0;
    const rows = suggestedRows([
      { field: 'DEVICE_ID', operator: 'EQ', expectedValue: 'dev-1' },
      { field: 'VALUE', operator: 'GT', expectedValue: '10', capabilityType: 'TEMPERATURE', unit: 'CELSIUS' },
      { field: 'VALUE', operator: 'EQ', expectedValue: 'false', capabilityType: 'MOTION' },
      { field: 'VALUE', operator: 'GT', expectedValue: '20', capabilityType: 'TEMPERATURE' },
      { field: 'VALUE', operator: 'EQ', expectedValue: 'x', capabilityKey: 'mode' },
    ], () => `id${n++}`);
    expect(rows.map((r) => [r.capabilityKey, r.kind, r.unit])).toEqual([['temperature', 'NUMBER', 'CELSIUS'], ['motion', 'BOOLEAN', ''], ['mode', 'NUMBER', '']]);
    expect(scopeDeviceOf({ conditions: [{ field: 'DEVICE_ID', operator: 'EQ', expectedValue: 'dev-1' }] })).toBe('dev-1');
    expect(scopeDeviceOf({ conditions: [] })).toBe('');
    expect(scopeDeviceOf(null)).toBe('');
  });
});
