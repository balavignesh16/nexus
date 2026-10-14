import { useMemo, useState } from 'react';
import { v4 as uuidv4 } from 'uuid';
import { Play, RotateCcw, Plus, Trash2, Loader2, FlaskConical } from 'lucide-react';
import { ruleApi } from '../../api/ruleApi';
import type { DryRunInlineDefinition, DryRunMode, DryRunRequest, DryRunResponse, Rule, TriggerType } from '../../api/ruleApi';
import { ApiError } from '../../api/apiClient';
import { DryRunResultView } from './DryRunResultView';
import { buildTwins, emptyRow, scopeDeviceOf, suggestedRows, type TwinRow, type ValueKind } from '../../utils/dryRunForm';

interface RuleSimulatorProps {
  /** A stored rule: its active (else working) version is simulated by the backend. */
  rule?: Rule | null;
  /** An unsaved definition (for example from the visual studio): evaluated by the backend, never stored. */
  inlineDefinition?: DryRunInlineDefinition | null;
  onClose?: () => void;
}

const TRIGGERS: TriggerType[] = ['LEGACY_DOMAIN_EVENT', 'ACCEPTED_TELEMETRY', 'TWIN_STATE_CHANGED', 'CONNECTIVITY_CHANGED', 'MANUAL', 'TEMPORAL_DUE'];
const EVENT_TYPES = ['TWIN_UPDATED', 'TWIN_CREATED', 'SENSOR_VALUE_CHANGED'];
const KINDS: { value: ValueKind; label: string }[] = [
  { value: 'NUMBER', label: 'number' }, { value: 'BOOLEAN', label: 'boolean' }, { value: 'STRING', label: 'text (may be empty)' },
  { value: 'NULL', label: 'explicit null' }, { value: 'MISSING', label: 'not reported' },
];

const localInput = (date: Date) => new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 19);

/** Backend dry-run console. The browser evaluates nothing: it builds a typed request and shows the production engine's hypothetical answer. */
export function RuleSimulator({ rule = null, inlineDefinition = null, onClose }: RuleSimulatorProps) {
  const conditions = rule?.conditions ?? inlineDefinition?.conditions;
  const [mode, setMode] = useState<DryRunMode>('SUPPLIED_STATE');
  const [deviceId, setDeviceId] = useState(scopeDeviceOf({ conditions: conditions ?? [] }));
  const [triggerType, setTriggerType] = useState<TriggerType>(rule?.triggers?.[0]?.type ?? inlineDefinition?.triggers?.[0]?.type ?? 'LEGACY_DOMAIN_EVENT');
  const [eventType, setEventType] = useState('TWIN_UPDATED');
  const [payloadText, setPayloadText] = useState('{}');
  const [time, setTime] = useState(localInput(new Date()));
  const [connectivity, setConnectivity] = useState<'ONLINE' | 'OFFLINE' | 'UNKNOWN'>('ONLINE');
  const [rows, setRows] = useState<TwinRow[]>(() => suggestedRows(conditions, uuidv4));
  const [edgeLatched, setEdgeLatched] = useState(false);
  const [lastMatchedAt, setLastMatchedAt] = useState('');
  const [latches, setLatches] = useState<Record<string, boolean>>({});
  const [busy, setBusy] = useState(false);
  const [errors, setErrors] = useState<string[]>([]);
  const [rejection, setRejection] = useState<{ code: string; message: string } | null>(null);
  const [result, setResult] = useState<DryRunResponse | null>(null);

  const hysteresisIds = useMemo(() => (conditions ?? []).filter((c) => c.hysteresis && c.conditionId).map((c) => c.conditionId as string), [conditions]);
  const supplied = mode === 'SUPPLIED_STATE';
  const legacy = triggerType === 'LEGACY_DOMAIN_EVENT';

  const updateRow = (id: string, patch: Partial<TwinRow>) => setRows((current) => current.map((r) => (r.id === id ? { ...r, ...patch } : r)));

  const run = async () => {
    setRejection(null); setResult(null);
    const problems: string[] = [];
    const simulationTime = new Date(time);
    if (supplied && (time === '' || Number.isNaN(simulationTime.getTime()))) problems.push('Enter a valid simulation time.');
    let payload: Record<string, unknown> = {};
    if (legacy) {
      try {
        const parsed = JSON.parse(payloadText);
        if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) problems.push('The event payload must be a JSON object.');
        else payload = parsed as Record<string, unknown>;
      } catch { problems.push('The event payload is not valid JSON.'); }
    }
    if (!rule && !inlineDefinition) problems.push('There is no rule to simulate.');
    const iso = supplied && !Number.isNaN(simulationTime.getTime()) ? simulationTime.toISOString() : '';
    const built = supplied ? buildTwins(rows, deviceId, iso) : { twins: [], errors: [] };
    built.errors.forEach((e) => problems.push(e.message));
    setErrors(problems);
    if (problems.length > 0) return;

    const request: DryRunRequest = {
      mode,
      scope: deviceId ? { deviceId } : undefined,
      versions: [rule ? { ruleId: rule.ruleId } : { definition: inlineDefinition as DryRunInlineDefinition }],
      trigger: legacy
        ? { type: triggerType, deviceId: deviceId || undefined, event: { eventType, payload } }
        : { type: triggerType, deviceId: deviceId || undefined },
    };
    if (supplied) {
      request.simulationTime = iso;
      request.supplied = {
        twins: built.twins,
        connectivity: deviceId ? [{ deviceId, status: connectivity }] : [],
        cooldowns: rule && lastMatchedAt ? [{ ruleId: rule.ruleId, lastMatchedAt: new Date(lastMatchedAt).toISOString() }] : [],
        edgeLatches: rule ? [{ ruleId: rule.ruleId, latched: edgeLatched }] : [],
        hysteresisLatches: rule ? hysteresisIds.map((conditionId) => ({ ruleId: rule.ruleId, conditionId, latched: latches[conditionId] === true })) : [],
      };
    }
    setBusy(true);
    try {
      setResult(await ruleApi.dryRun(request));
    } catch (error) {
      const data = error instanceof ApiError ? (error.data as { code?: string; message?: string } | undefined) : undefined;
      setRejection({ code: data?.code ?? (error instanceof ApiError ? String(error.status) : 'REQUEST_FAILED'), message: data?.message ?? (error instanceof Error ? error.message : 'The dry-run failed.') });
    } finally { setBusy(false); }
  };

  const reset = () => { setResult(null); setRejection(null); setErrors([]); setRows(suggestedRows(conditions, uuidv4)); };
  const field = 'w-full bg-background border border-border rounded px-2 py-1 text-xs text-foreground focus:outline-none focus:border-primary';

  return (
    <div className="flex h-full min-h-0 animate-in slide-in-from-bottom-2" data-testid="rule-simulator">
      <div className="flex w-[26rem] max-w-[50%] shrink-0 flex-col gap-3 overflow-y-auto border-r border-border p-4 text-xs">
        <div className="flex items-center gap-2"><FlaskConical size={16} className="text-muted-foreground" /><h3 className="text-sm font-semibold text-foreground">Dry-run on the backend</h3></div>
        <p className="text-muted-foreground">The production rule engine evaluates this. Nothing is saved, dispatched or published.</p>

        <label className="space-y-1"><span className="text-muted-foreground">Mode</span>
          <select aria-label="Mode" value={mode} onChange={(e) => setMode(e.target.value as DryRunMode)} className={field}>
            <option value="SUPPLIED_STATE">Supplied state (hypothetical values you enter)</option>
            <option value="CURRENT_STATE">Current state (backend snapshot and clock)</option>
            <option value="HISTORICAL_REPLAY">Historical replay (not supported)</option>
          </select>
        </label>
        {mode === 'CURRENT_STATE' && <p className="rounded border border-border p-2 text-muted-foreground">Uses the backend's persisted Twin state, connectivity and clock. Your browser's time and values are not used.</p>}

        <label className="space-y-1"><span className="text-muted-foreground">Scope device id</span>
          <input aria-label="Scope device id" value={deviceId} onChange={(e) => setDeviceId(e.target.value.trim())} className={`${field} font-mono`} placeholder="device UUID" />
        </label>
        <label className="space-y-1"><span className="text-muted-foreground">Trigger</span>
          <select aria-label="Trigger" value={triggerType} onChange={(e) => setTriggerType(e.target.value as TriggerType)} className={field}>
            {TRIGGERS.map((t) => <option key={t} value={t}>{t}</option>)}
          </select>
        </label>
        {legacy && (
          <>
            <label className="space-y-1"><span className="text-muted-foreground">Event type</span>
              <select aria-label="Event type" value={eventType} onChange={(e) => setEventType(e.target.value)} className={field}>{EVENT_TYPES.map((t) => <option key={t}>{t}</option>)}</select>
            </label>
            <label className="space-y-1"><span className="text-muted-foreground">Event payload (typed JSON: false, 0 and "" are values)</span>
              <textarea aria-label="Event payload" value={payloadText} onChange={(e) => setPayloadText(e.target.value)} rows={3} className={`${field} font-mono`} />
            </label>
          </>
        )}

        {supplied && (
          <>
            <label className="space-y-1"><span className="text-muted-foreground">Simulation time (explicit; never the browser clock)</span>
              <input aria-label="Simulation time" type="datetime-local" step={1} value={time} onChange={(e) => setTime(e.target.value)} className={field} />
            </label>
            <label className="space-y-1"><span className="text-muted-foreground">Device connectivity</span>
              <select aria-label="Connectivity" value={connectivity} onChange={(e) => setConnectivity(e.target.value as 'ONLINE' | 'OFFLINE' | 'UNKNOWN')} className={field}>
                <option>ONLINE</option><option>OFFLINE</option><option>UNKNOWN</option>
              </select>
            </label>

            <div className="space-y-2">
              <div className="flex items-center justify-between"><span className="font-semibold text-foreground">Reported capability values</span>
                <button type="button" onClick={() => setRows((r) => [...r, emptyRow(uuidv4())])} className="flex items-center gap-1 rounded bg-muted px-2 py-0.5"><Plus size={11} /> Add</button></div>
              {rows.length === 0 && <p className="text-muted-foreground">No values supplied: every capability reads as missing.</p>}
              {rows.map((row, index) => (
                <div key={row.id} className="space-y-1.5 rounded border border-border p-2" data-testid="twin-row">
                  <div className="flex gap-1.5">
                    <input aria-label={`Capability key ${index + 1}`} value={row.capabilityKey} onChange={(e) => updateRow(row.id, { capabilityKey: e.target.value })} className={`${field} font-mono`} placeholder="capability key" />
                    <button type="button" aria-label={`Remove value ${index + 1}`} onClick={() => setRows((r) => r.filter((x) => x.id !== row.id))} className="px-1 text-destructive"><Trash2 size={12} /></button>
                  </div>
                  <div className="flex gap-1.5">
                    <select aria-label={`Value kind ${index + 1}`} value={row.kind} onChange={(e) => updateRow(row.id, { kind: e.target.value as ValueKind })} className={field}>
                      {KINDS.map((k) => <option key={k.value} value={k.value}>{k.label}</option>)}
                    </select>
                    {(row.kind === 'NUMBER' || row.kind === 'STRING') && (
                      <input aria-label={`Value ${index + 1}`} value={row.text} onChange={(e) => updateRow(row.id, { text: e.target.value })} className={`${field} font-mono`} placeholder={row.kind === 'NUMBER' ? 'e.g. 0 or 21.5' : 'text (empty is a value)'} />
                    )}
                    {row.kind === 'BOOLEAN' && (
                      <select aria-label={`Value ${index + 1}`} value={String(row.boolValue)} onChange={(e) => updateRow(row.id, { boolValue: e.target.value === 'true' })} className={field}>
                        <option value="true">true</option><option value="false">false</option>
                      </select>
                    )}
                  </div>
                  {row.kind !== 'MISSING' && (
                    <div className="flex gap-1.5">
                      <input aria-label={`Unit ${index + 1}`} value={row.unit} onChange={(e) => updateRow(row.id, { unit: e.target.value })} className={field} placeholder="unit (blank = unitless)" />
                      <select aria-label={`Receipt ${index + 1}`} value={row.receipt} onChange={(e) => updateRow(row.id, { receipt: e.target.value as TwinRow['receipt'] })} className={field}>
                        <option value="AT_SIMULATION_TIME">received at simulation time</option><option value="UNAVAILABLE">receipt time unavailable</option><option value="CUSTOM">received at...</option>
                      </select>
                    </div>
                  )}
                  {row.kind !== 'MISSING' && row.receipt === 'CUSTOM' && <input aria-label={`Received at ${index + 1}`} type="datetime-local" step={1} value={row.receivedAt} onChange={(e) => updateRow(row.id, { receivedAt: e.target.value })} className={field} />}
                  {row.kind !== 'MISSING' && <input aria-label={`Freshness threshold ${index + 1}`} value={row.thresholdSeconds} onChange={(e) => updateRow(row.id, { thresholdSeconds: e.target.value })} className={field} placeholder="freshness threshold seconds (blank = contract default)" />}
                </div>
              ))}
            </div>

            {rule && (
              <details className="rounded border border-border p-2">
                <summary className="cursor-pointer font-semibold text-foreground">Runtime state seeds (optional)</summary>
                <div className="mt-2 space-y-2">
                  <label className="flex items-center gap-2"><input type="checkbox" aria-label="Edge latch held" checked={edgeLatched} onChange={(e) => setEdgeLatched(e.target.checked)} /> EDGE_TRUE latch already held</label>
                  <label className="space-y-1"><span className="text-muted-foreground">Last matched at (cooldown)</span>
                    <input aria-label="Last matched at" type="datetime-local" step={1} value={lastMatchedAt} onChange={(e) => setLastMatchedAt(e.target.value)} className={field} /></label>
                  {hysteresisIds.map((id) => (
                    <label key={id} className="flex items-center gap-2"><input type="checkbox" aria-label={`Hysteresis latched ${id}`} checked={latches[id] === true} onChange={(e) => setLatches((l) => ({ ...l, [id]: e.target.checked }))} /> Hysteresis band {id} already latched</label>
                  ))}
                </div>
              </details>
            )}
          </>
        )}

        {errors.length > 0 && <ul role="alert" className="list-disc space-y-0.5 pl-4 text-destructive">{errors.map((e, i) => <li key={i}>{e}</li>)}</ul>}

        <div className="mt-1 flex items-center justify-between">
          <button type="button" onClick={reset} className="flex items-center gap-1.5 rounded bg-muted px-3 py-1.5 font-medium text-foreground"><RotateCcw size={12} /> Reset</button>
          <div className="flex gap-2">
            {onClose && <button type="button" onClick={onClose} className="rounded bg-muted px-3 py-1.5 font-medium text-foreground">Close</button>}
            <button type="button" onClick={run} disabled={busy} className="flex items-center gap-1.5 rounded bg-blue-500 px-4 py-1.5 font-medium text-white disabled:opacity-60">
              {busy ? <Loader2 size={12} className="animate-spin" /> : <Play size={12} />} Run dry-run
            </button>
          </div>
        </div>
      </div>

      <div className="min-w-0 flex-1 overflow-y-auto bg-muted/10 p-4">
        {rejection && (
          <div role="alert" className="rounded border border-destructive/50 bg-destructive/10 p-3 text-xs text-destructive" data-testid="dry-run-rejection">
            <div className="font-semibold">{rejection.code}</div>
            <div>{rejection.message}</div>
          </div>
        )}
        {result && <DryRunResultView result={result} />}
        {!result && !rejection && <div className="flex h-full items-center justify-center text-xs text-muted-foreground opacity-60">Run a dry-run to see the production engine's hypothetical decision.</div>}
      </div>
    </div>
  );
}
