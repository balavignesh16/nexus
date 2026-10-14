import type { DryRunJsonValue, DryRunTwinValue, DryRunValueType, Rule, RuleCondition } from '../api/ruleApi';

/**
 * Typed dry-run input. The backend decides; this module only builds a faithful request and displays the typed answer.
 * Nothing here evaluates a rule, and nothing relies on JavaScript truthiness or loose equality: false, 0 and the empty string are values,
 * an explicit null is a different thing from a value that was never supplied, and an unparseable number is an error, not 0 or NaN.
 */

/** MISSING = the capability is not reported at all (no row is sent); NULL = reported as an explicit null (not a value). */
export type ValueKind = 'MISSING' | 'NULL' | 'NUMBER' | 'BOOLEAN' | 'STRING';
export type ReceiptKind = 'AT_SIMULATION_TIME' | 'UNAVAILABLE' | 'CUSTOM';

export interface TwinRow {
  id: string;
  capabilityKey: string;
  kind: ValueKind;
  /** NUMBER and STRING text exactly as typed (a STRING keeps its whitespace and may be empty). */
  text: string;
  boolValue: boolean;
  unit: string;
  receipt: ReceiptKind;
  receivedAt: string;
  thresholdSeconds: string;
}

export const emptyRow = (id: string, capabilityKey = ''): TwinRow => ({
  id, capabilityKey, kind: 'NUMBER', text: '', boolValue: false, unit: '', receipt: 'AT_SIMULATION_TIME', receivedAt: '', thresholdSeconds: '',
});

export type Parsed<T> = { ok: true; value: T } | { ok: false; error: string };

/** Strict number syntax: no empty string, no whitespace-only, no NaN/Infinity, no hex or trailing garbage. */
const NUMBER = /^-?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?$/;

export function parseTypedValue(row: Pick<TwinRow, 'kind' | 'text' | 'boolValue'>): Parsed<DryRunJsonValue | undefined> {
  switch (row.kind) {
    case 'MISSING': return { ok: true, value: undefined };
    case 'NULL': return { ok: true, value: null };
    case 'BOOLEAN': return { ok: true, value: row.boolValue };
    case 'STRING': return { ok: true, value: row.text };
    case 'NUMBER': {
      const text = row.text.trim();
      if (!NUMBER.test(text)) return { ok: false, error: text === '' ? 'Enter a number (0 is a valid number; empty is not).' : `"${text}" is not a number.` };
      const value = Number(text);
      return Number.isFinite(value) ? { ok: true, value } : { ok: false, error: `"${text}" is out of range.` };
    }
  }
}

const valueTypeOf = (kind: ValueKind): DryRunValueType => (kind === 'BOOLEAN' ? 'BOOLEAN' : kind === 'STRING' ? 'STRING' : 'NUMBER');

export interface BuiltTwins { twins: DryRunTwinValue[]; errors: { rowId: string; message: string }[] }

/** MISSING rows are not sent at all; every other row is sent with its explicit type, so the backend never has to guess. */
export function buildTwins(rows: TwinRow[], deviceId: string, simulationTimeIso: string): BuiltTwins {
  const twins: DryRunTwinValue[] = [];
  const errors: { rowId: string; message: string }[] = [];
  for (const row of rows) {
    if (row.kind === 'MISSING') continue;
    if (row.capabilityKey.trim() === '') { errors.push({ rowId: row.id, message: 'A capability key is required.' }); continue; }
    const parsed = parseTypedValue(row);
    if (!parsed.ok) { errors.push({ rowId: row.id, message: parsed.error }); continue; }
    let receivedAt: string | null | undefined;
    if (row.receipt === 'AT_SIMULATION_TIME') receivedAt = simulationTimeIso;
    else if (row.receipt === 'UNAVAILABLE') receivedAt = null;
    else {
      const custom = new Date(row.receivedAt);
      if (row.receivedAt === '' || Number.isNaN(custom.getTime())) { errors.push({ rowId: row.id, message: 'Enter a valid receipt time.' }); continue; }
      receivedAt = custom.toISOString();
    }
    let threshold: number | undefined;
    if (row.thresholdSeconds.trim() !== '') {
      const t = Number(row.thresholdSeconds);
      if (!Number.isInteger(t) || t < 0) { errors.push({ rowId: row.id, message: 'The freshness threshold must be a whole number of seconds.' }); continue; }
      threshold = t;
    }
    twins.push({
      deviceId: deviceId || undefined, capabilityKey: row.capabilityKey.trim(), valueType: valueTypeOf(row.kind),
      value: parsed.value as DryRunJsonValue, unit: row.unit.trim() === '' ? null : row.unit.trim(),
      observedAt: simulationTimeIso, receivedAt, freshnessThresholdSeconds: threshold,
    });
  }
  return { twins, errors };
}

/** The device a rule is scoped to (its explicit DEVICE_ID condition), if any. */
export function scopeDeviceOf(rule: Pick<Rule, 'conditions'> | null | undefined): string {
  const scoped = rule?.conditions?.find((c) => c.field === 'DEVICE_ID' && c.operator === 'EQ');
  return scoped?.expectedValue ?? '';
}

/** One starter row per capability the rule's conditions read, so the user supplies exactly what the rule looks at. */
export function suggestedRows(conditions: RuleCondition[] | undefined, newId: () => string): TwinRow[] {
  const seen = new Set<string>();
  const rows: TwinRow[] = [];
  for (const c of conditions ?? []) {
    if (c.field !== 'VALUE') continue;
    const key = (c.capabilityKey ?? c.capabilityType?.toLowerCase() ?? '').trim();
    if (key === '' || seen.has(key)) continue;
    seen.add(key);
    const boolean = c.expectedValue === 'true' || c.expectedValue === 'false';
    rows.push({ ...emptyRow(newId(), key), kind: boolean ? 'BOOLEAN' : 'NUMBER', unit: c.unit ?? '' });
  }
  return rows;
}

/**
 * Typed display of an evidence value. The point is that false, 0 and "" never collapse into each other or into "nothing":
 * false -> `false`, 0 -> `0`, "" -> `"" (empty string)`, null -> `null`, undefined -> `not reported`.
 */
export function formatActual(value: unknown, observation?: { stateQuality?: string | null; capabilityKey?: string | null }): string {
  if (value === undefined) return 'not reported';
  if (value === null) return 'null';
  if (value === 'MISSING' && observation && !observation.stateQuality && !observation.capabilityKey) return 'no observation';
  if (typeof value === 'string') return value === '' ? '"" (empty string)' : JSON.stringify(value);
  if (typeof value === 'boolean') return value ? 'true' : 'false';
  if (typeof value === 'number') return String(value);
  return JSON.stringify(value);
}
