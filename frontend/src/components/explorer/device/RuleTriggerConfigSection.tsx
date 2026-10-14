import { useState } from 'react';
import type { RepeatPolicy, TemporalOperator } from '../../../api/ruleApi';
import { TEMPORAL_OPERATORS, TRIGGER_TYPES, type TriggerConfig } from './ruleTriggerConfig';
import type { TriggerType } from '../../../api/ruleApi';

interface SectionProps {
  config: TriggerConfig;
  onChange: (next: TriggerConfig) => void;
  hasDevice: boolean;
  selectClass: string;
  inputClass: string;
}

export function RuleTriggerConfigSection({ config, onChange, hasDevice, selectClass, inputClass }: SectionProps) {
  const toggle = (type: TriggerType, on: boolean) => {
    const selected = { ...config.selected };
    if (on) selected[type] = ''; else delete selected[type];
    onChange({ ...config, selected });
  };
  const setTemporal = (patch: Partial<TriggerConfig['temporal']>) => onChange({ ...config, temporal: { ...config.temporal, ...patch } });
  const t = config.temporal;
  // The body renders only while expanded, so a rule that never uses these options keeps the original form untouched.
  const configured = Object.keys(config.selected).length > 0 || t.enabled || config.repeatPolicy !== 'EVERY_TRIGGER_WHILE_TRUE';
  const [userChoice, setUserChoice] = useState<boolean | null>(null);
  const open = userChoice ?? configured;   // an edited rule that already uses these options opens automatically
  const setOpen = (next: boolean) => setUserChoice(next);
  return (
    <div className="rounded border border-foundation-border p-3" data-testid="trigger-config">
      <button type="button" aria-expanded={open} onClick={() => setOpen(!open)} data-testid="trigger-config-toggle"
        className="text-xs font-medium text-foundation-fg cursor-pointer">Triggers and timing (advanced)</button>
      {open && <div className="flex flex-col gap-3 mt-3">
        <p className="text-xs text-foundation-muted-fg">
          Without a selection the rule keeps the original device-event behavior. Selected triggers are explicit and stored with the immutable version.
        </p>

        <label className="flex items-center gap-2 text-xs text-foundation-fg">
          <input type="checkbox" checked={t.enabled} disabled={!hasDevice} data-testid="temporal-enabled"
            onChange={(e) => setTemporal({ enabled: e.target.checked })} />
          Temporal condition (evaluated when its deadline or count is reached)
        </label>

        {t.enabled ? (
          <div className="flex flex-col gap-2" data-testid="temporal-fields">
            <select value={t.operator} onChange={(e) => setTemporal({ operator: e.target.value as TemporalOperator })} className={selectClass} data-testid="temporal-operator">
              {TEMPORAL_OPERATORS.map((o) => <option key={o.operator} value={o.operator}>{o.label}</option>)}
            </select>
            {t.operator !== 'COUNT_WITHIN' && (
              <input type="number" min={1} value={t.durationSeconds} placeholder={t.operator === 'TREND' ? 'Required span (seconds)' : 'Duration (seconds)'}
                onChange={(e) => setTemporal({ durationSeconds: e.target.value })} className={inputClass} data-testid="temporal-duration" />
            )}
            {(t.operator === 'COUNT_WITHIN' || t.operator === 'TREND') && (
              <input type="number" min={1} value={t.windowSeconds} placeholder="Window (seconds, max 86400)"
                onChange={(e) => setTemporal({ windowSeconds: e.target.value })} className={inputClass} data-testid="temporal-window" />
            )}
            {t.operator === 'COUNT_WITHIN' && (
              <>
                <input type="number" min={1} value={t.count} placeholder="Count" onChange={(e) => setTemporal({ count: e.target.value })} className={inputClass} data-testid="temporal-count" />
                <select value={t.countMode} onChange={(e) => setTemporal({ countMode: e.target.value as 'SAMPLES' | 'EDGES' })} className={selectClass} data-testid="temporal-count-mode">
                  <option value="SAMPLES">Count qualified samples</option>
                  <option value="EDGES">Count false-to-true edges</option>
                </select>
              </>
            )}
            {t.operator === 'TREND' && (
              <>
                <select value={t.direction} onChange={(e) => setTemporal({ direction: e.target.value as 'RISING' | 'FALLING' })} className={selectClass} data-testid="temporal-direction">
                  <option value="RISING">Rising</option>
                  <option value="FALLING">Falling</option>
                </select>
                <input type="number" min={0} step="any" value={t.minChange} placeholder="Minimum change (device unit)"
                  onChange={(e) => setTemporal({ minChange: e.target.value })} className={inputClass} data-testid="temporal-min-change" />
              </>
            )}
            <p className="text-xs text-foundation-muted-fg">Uses the first condition above as its capability. Stale, offline or unit-mismatched readings reset progress; nothing is converted or interpolated.</p>
          </div>
        ) : (
          <div className="flex flex-col gap-2" data-testid="trigger-list">
            {TRIGGER_TYPES.map((entry) => {
              const on = config.selected[entry.type] !== undefined;
              const disabled = (entry.type === 'MANUAL' || entry.type === 'SCHEDULE') && !hasDevice;
              return (
                <div key={entry.type} className="flex items-center gap-2 text-xs">
                  <label className="flex items-center gap-2 flex-1 text-foundation-fg">
                    <input type="checkbox" checked={on} disabled={disabled} data-testid={`trigger-${entry.type}`}
                      onChange={(e) => toggle(entry.type, e.target.checked)} />
                    {entry.label}
                  </label>
                  {on && entry.filterOptions && (
                    <select value={config.selected[entry.type] ?? ''} className={selectClass} data-testid={`filter-${entry.type}`}
                      onChange={(e) => onChange({ ...config, selected: { ...config.selected, [entry.type]: e.target.value } })}>
                      {entry.filterOptions.map((o) => <option key={o} value={o}>{o === '' ? 'Any' : o}</option>)}
                    </select>
                  )}
                  {on && entry.filterKey && !entry.filterOptions && (
                    <input type="text" value={config.selected[entry.type] ?? ''} placeholder={entry.filterLabel} className={inputClass} data-testid={`filter-${entry.type}`}
                      onChange={(e) => onChange({ ...config, selected: { ...config.selected, [entry.type]: e.target.value } })} />
                  )}
                </div>
              );
            })}
            {config.selected.SCHEDULE !== undefined && (
              <div className="flex flex-col gap-2" data-testid="schedule-fields">
                <select value={config.schedule.kind} className={selectClass} data-testid="schedule-kind"
                  onChange={(e) => onChange({ ...config, schedule: { ...config.schedule, kind: e.target.value as 'ONE_SHOT' | 'FIXED_INTERVAL' } })}>
                  <option value="ONE_SHOT">One-shot (UTC)</option>
                  <option value="FIXED_INTERVAL">Fixed interval</option>
                </select>
                {config.schedule.kind === 'ONE_SHOT' ? (
                  <input type="datetime-local" value={config.schedule.at} className={inputClass} data-testid="schedule-at"
                    onChange={(e) => onChange({ ...config, schedule: { ...config.schedule, at: e.target.value } })} />
                ) : (
                  <>
                    <input type="number" min={1} value={config.schedule.intervalSeconds} placeholder="Interval (seconds)" className={inputClass} data-testid="schedule-interval"
                      onChange={(e) => onChange({ ...config, schedule: { ...config.schedule, intervalSeconds: e.target.value } })} />
                    <input type="datetime-local" value={config.schedule.startsAt} className={inputClass} data-testid="schedule-starts-at"
                      onChange={(e) => onChange({ ...config, schedule: { ...config.schedule, startsAt: e.target.value } })} />
                  </>
                )}
                <p className="text-xs text-foundation-muted-fg">Times are interpreted in your browser's zone and stored as UTC instants. Missed interval slots are coalesced into one evaluation.</p>
              </div>
            )}
          </div>
        )}

        <div>
          <label className="text-xs text-foundation-muted-fg mb-1 block">Repeat</label>
          <select value={config.repeatPolicy} onChange={(e) => onChange({ ...config, repeatPolicy: e.target.value as RepeatPolicy })} className={`w-full ${selectClass}`} data-testid="repeat-policy">
            <option value="EVERY_TRIGGER_WHILE_TRUE">Every qualifying trigger while true</option>
            <option value="EDGE_TRUE">Only on the transition into true</option>
          </select>
        </div>
      </div>}
    </div>
  );
}
