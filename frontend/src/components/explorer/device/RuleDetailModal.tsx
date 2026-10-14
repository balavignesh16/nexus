import { useCallback, useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { X, Pencil, Play } from 'lucide-react';
import { ruleApi, type Rule, type RuleAction, type RuleCondition, type RuleRuntimeSnapshot } from '../../../api/ruleApi';
import { useActiveDevices } from '../../../hooks/queries';
import { describeTemporal } from './ruleTriggerConfig';

interface RuleDetailModalProps {
  rule: (Rule & { description?: string }) | null;
  isOpen: boolean;
  onClose: () => void;
  onEdit: () => void;
}

const OPERATOR_WORDS: Record<string, string> = {
  EQ: 'equals', NE: 'does not equal', GT: 'is greater than', LT: 'is less than',
  GTE: 'is at least', LTE: 'is at most',
};

/** The capabilityType (when present) is rendered as its own colored badge
 * next to this text, so this never repeats it - callers with no
 * capabilityType (DEVICE_ID, legacy EVENT_TYPE/SEVERITY conditions) get no
 * badge, so they spell out the field name themselves instead. */
function describeCondition(c: RuleCondition, deviceName: (id: string) => string): string {
  if (c.field === 'DEVICE_ID') {
    return `Device is ${deviceName(c.expectedValue)}`;
  }
  const temporal = describeTemporal(c);
  if (temporal) return `${c.temporal?.operator}: ${temporal}`;
  const operatorWord = OPERATOR_WORDS[c.operator] ?? c.operator;
  if (c.capabilityType) {
    return `${operatorWord} ${c.expectedValue}`;
  }
  return `${c.field} ${operatorWord} ${c.expectedValue}`;
}

function describeAction(a: RuleAction, deviceName: (id: string) => string): string {
  const p = a.parameters ?? {};
  if (a.actionType === 'SEND_COMMAND' && typeof p.capability === 'string') {
    const target = typeof p.deviceId === 'string' ? ` on ${deviceName(p.deviceId)}` : '';
    const value = p.value;
    if (p.capability === 'RELAY' && value && typeof value === 'object') {
      const v = value as { channel?: number; state?: boolean };
      return `Set RELAY channel ${v.channel} to ${v.state ? 'ON' : 'OFF'}${target}`;
    }
    if (p.capability === 'SERVO') {
      return `Move SERVO to ${value}°${target}`;
    }
    if (typeof value === 'boolean') {
      return `Set ${p.capability} to ${value ? 'ON' : 'OFF'}${target}`;
    }
    return `Set ${p.capability} to ${JSON.stringify(value)}${target}`;
  }
  const entries = Object.entries(p);
  if (entries.length === 0) return 'No parameters';
  return entries.map(([k, v]) => `${k}: ${typeof v === 'object' ? JSON.stringify(v) : v}`).join(', ');
}

const ACTION_TYPE_LABEL: Record<string, string> = {
  SEND_COMMAND: 'Send Command',
  CREATE_ALERT: 'Create Alert',
  WEBHOOK: 'Call Webhook',
};

/**
 * Read-only "what have we actually set on this rule" view. Reuses the same
 * real Rule shape (conditions[]/actions[]) the list cards already fetch -
 * no separate request, nothing summarized/truncated here unlike the cards.
 * Conditions and actions are rendered in plain language (device names
 * resolved via the same useActiveDevices list CreateRuleModal already
 * uses), not as a raw parameters dump - the underlying real field/operator/
 * value or actionType/parameters are still what drive the text, nothing is
 * invented.
 *
 * Edit is disabled for rules with more than one action: CreateRuleModal's
 * action builder only supports editing a single action, and silently
 * collapsing a real multi-action rule (e.g. a Grand Sim "Buzzer+Fan" rule)
 * down to one on save would destroy data. Conditions have no such limit -
 * the builder already supports an arbitrary number of them.
 */
export function RuleDetailModal({ rule, isOpen, onClose, onEdit }: RuleDetailModalProps) {
  const { data: devices = [] } = useActiveDevices();
  const [runtime, setRuntime] = useState<RuleRuntimeSnapshot | null>(null);
  const [runtimeError, setRuntimeError] = useState<string | null>(null);
  const [manualNote, setManualNote] = useState<string | null>(null);
  const ruleId = rule?.ruleId;

  const loadRuntime = useCallback(async () => {
    if (!ruleId) return;
    try {
      setRuntime(await ruleApi.getRuntime(ruleId));
      setRuntimeError(null);
    } catch {
      setRuntime(null);
      setRuntimeError('Runtime evidence is unavailable.');
    }
  }, [ruleId]);

  useEffect(() => {
    if (isOpen && ruleId) void loadRuntime();
  }, [isOpen, ruleId, loadRuntime]);

  if (!isOpen || !rule) return null;

  const manualEnabled = rule.lifecycle === 'ACTIVE' && (rule.triggers ?? []).some((t) => t.type === 'MANUAL');
  const executeManually = async () => {
    setManualNote(null);
    try {
      const result = await ruleApi.executeManually(rule.ruleId, rule.activeVersionId);
      setManualNote(`Request ${result.requestId} was persisted. Evaluation, match and any action authorization are separate recorded facts.`);
      void loadRuntime();
    } catch (err) {
      setManualNote(err instanceof Error ? err.message : 'Manual execution was rejected.');
    }
  };

  const deviceName = (id: string): string => devices.find((d: any) => d.id === id)?.name ?? id;

  const conditions = rule.conditions ?? [];
  const actions = rule.actions ?? [];
  const canEdit = actions.length <= 1;

  // Portaled to document.body: this modal is triggered from deep inside a
  // page (RulesPage/RulesTab), which AppShell renders inside a content pane
  // that establishes its own stacking context (position:relative + a
  // z-index of its own, next to the sidebar's own z-index as a sibling).
  // Rendered inline, this modal's z-50 would only ever out-rank other
  // elements inside that same pane - never the sidebar, which sits outside
  // it - so the sidebar would visibly paint over the backdrop. Escaping to
  // document.body puts it in the root stacking context instead, where its
  // z-index is finally compared against the whole page, not just its pane.
  return createPortal(
    <div className="fixed inset-0 bg-black/40 backdrop-blur-sm flex items-center justify-center z-50 p-4" onClick={onClose}>
      <div
        className="rounded-[var(--radius-panel-lg)] shadow-[var(--shadow-glass-lg)] w-full max-w-lg max-h-[90vh] overflow-y-auto"
        style={{ backgroundColor: 'var(--foundation-surface)', border: '1px solid var(--foundation-border)' }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between p-5 border-b border-foundation-border">
          <div className="min-w-0">
            <h3 className="text-sm font-semibold text-foundation-fg truncate">{rule.name}</h3>
            {rule.description && <p className="text-xs text-foundation-muted-fg mt-0.5">{rule.description}</p>}
          </div>
          <button onClick={onClose} className="text-foundation-muted-fg hover:text-foundation-fg shrink-0 ml-3" aria-label="Close">
            <X size={18} />
          </button>
        </div>

        <div className="p-5 flex flex-col gap-4">
          <div className="flex items-center gap-4 text-xs text-foundation-muted-fg">
            <span
              className="px-2 py-0.5 rounded-full text-[10px] font-bold uppercase tracking-wider"
              style={rule.enabled
                ? { backgroundColor: 'color-mix(in srgb, var(--success) 14%, transparent)', color: 'var(--success)' }
                : { backgroundColor: 'var(--foundation-bg)', color: 'var(--foundation-muted-fg)', border: '1px solid var(--foundation-border)' }}
            >
              {rule.enabled ? 'Active' : 'Disabled'}
            </span>
            <span>Priority {rule.priority}</span>
            <span>{rule.cooldownSeconds ? `Cooldown ${rule.cooldownSeconds}s` : 'No cooldown'}</span>
          </div>

          <div data-testid="rule-triggers">
            <span className="text-[10px] font-bold text-foundation-muted-fg uppercase tracking-wider mb-1.5 block">Triggers</span>
            {(rule.triggers ?? []).length === 0 ? (
              <p className="text-xs text-foundation-tertiary-fg">Device events (original behavior; no explicit subscription).</p>
            ) : (
              <div className="flex flex-wrap gap-1.5">
                {(rule.triggers ?? []).map((t, idx) => (
                  <span key={idx} className="rounded px-2 py-0.5 text-[11px]" style={{ backgroundColor: 'var(--foundation-bg)', border: '1px solid var(--foundation-border)' }}>
                    {t.type}{t.filters && Object.keys(t.filters).length > 0 ? ` (${Object.entries(t.filters).map(([k, v]) => `${k}=${v}`).join(', ')})` : ''}
                  </span>
                ))}
              </div>
            )}
            <p className="text-xs text-foundation-muted-fg mt-1">
              Repeat: {rule.repeatPolicy === 'EDGE_TRUE' ? 'only on the transition into true' : 'every qualifying trigger while true'}
              {rule.schedule ? ` · Schedule: ${rule.schedule.kind === 'ONE_SHOT' ? `once at ${rule.schedule.at}` : `every ${rule.schedule.intervalSeconds}s`}` : ''}
            </p>
            {manualEnabled && (
              <button onClick={executeManually} data-testid="manual-execute" className="mt-2 flex items-center gap-1.5 px-3 py-1.5 text-xs rounded-[var(--radius-pill)] border border-foundation-border">
                <Play size={12} /> Run once now
              </button>
            )}
            {manualNote && <p className="text-xs mt-1" data-testid="manual-note">{manualNote}</p>}
          </div>

          <div>
            <span className="text-[10px] font-bold text-foundation-muted-fg uppercase tracking-wider mb-1.5 block">
              Conditions ({conditions.length}, all must match)
            </span>
            {conditions.length === 0 ? (
              <p className="text-xs text-foundation-tertiary-fg">No conditions defined.</p>
            ) : (
              <div className="flex flex-col gap-1.5">
                {conditions.map((c, idx) => (
                  <div
                    key={idx}
                    className="rounded p-2 text-xs text-foundation-fg break-words"
                    style={{ backgroundColor: 'var(--foundation-bg)', border: '1px solid var(--foundation-border)' }}
                  >
                    {c.capabilityType && <span className="font-semibold" style={{ color: 'var(--accent)' }}>{c.capabilityType} </span>}
                    {describeCondition(c, deviceName)}
                  </div>
                ))}
              </div>
            )}
          </div>

          <div>
            <span className="text-[10px] font-bold text-foundation-muted-fg uppercase tracking-wider mb-1.5 block">
              Actions ({actions.length})
            </span>
            {actions.length === 0 ? (
              <p className="text-xs text-foundation-tertiary-fg">No actions defined.</p>
            ) : (
              <div className="flex flex-col gap-1.5">
                {actions.map((a, idx) => (
                  <div
                    key={idx}
                    className="rounded p-2 text-xs text-foundation-fg"
                    style={{ backgroundColor: 'color-mix(in srgb, var(--accent) 8%, transparent)', border: '1px solid color-mix(in srgb, var(--accent) 20%, transparent)' }}
                  >
                    <span className="font-semibold" style={{ color: 'var(--accent)' }}>{ACTION_TYPE_LABEL[a.actionType] ?? a.actionType}: </span>
                    {describeAction(a, deviceName)}
                  </div>
                ))}
              </div>
            )}
          </div>

          <div data-testid="rule-runtime">
            <span className="text-[10px] font-bold text-foundation-muted-fg uppercase tracking-wider mb-1.5 block">Runtime evidence</span>
            {runtimeError && <p className="text-xs text-foundation-tertiary-fg">{runtimeError}</p>}
            {runtime && (
              <div className="flex flex-col gap-2 text-xs">
                {runtime.temporalState.map((state, idx) => (
                  <div key={`state-${idx}`} data-testid="temporal-state">
                    {state.operator} · {state.phase}{state.due_at ? ` · due ${state.due_at}` : ''} · {state.member_count} member(s) · occurrence {state.sequence}
                    {state.reset_reason ? ` · last reset: ${state.reset_reason}` : ''}
                  </div>
                ))}
                {runtime.timers.filter((timer) => timer.status !== 'PENDING' && timer.status !== 'CONSUMED').map((timer) => (
                  <div key={timer.timer_id} data-testid="stale-timer" style={{ color: 'var(--warning, #a8630a)' }}>
                    Stale {timer.kind} occurrence due {timer.due_at}: {timer.status}{timer.invalidation_reason ? ` (${timer.invalidation_reason})` : ''} — no decision was made.
                  </div>
                ))}
                {runtime.temporalEvidence.slice(0, 8).map((e) => (
                  <div key={e.seq} data-testid="temporal-evidence" className="text-foundation-muted-fg">
                    {e.occurredAt} · {e.event}{typeof e.details.reason === 'string' ? ` · ${e.details.reason}` : ''}
                  </div>
                ))}
                {runtime.candidates.slice(0, 8).map((c) => (
                  <div key={c.candidate_id} data-testid="candidate" style={c.disposition.startsWith('SUPPRESSED') ? { color: 'var(--warning, #a8630a)' } : undefined}>
                    {c.action_type} · {c.disposition} · {c.reason}
                  </div>
                ))}
                {runtime.temporalState.length + runtime.temporalEvidence.length + runtime.candidates.length + runtime.timers.length === 0 && (
                  <p className="text-foundation-tertiary-fg">No timers, temporal state or typed-trigger decisions recorded yet.</p>
                )}
              </div>
            )}
          </div>

          {!canEdit && (
            <p className="text-xs" style={{ color: 'var(--warning, #a8630a)' }}>
              This rule has {actions.length} actions - editing isn't available yet for multi-action rules
              (the edit form only supports one action at a time). Create a replacement rule instead if you need to change it.
            </p>
          )}
        </div>

        <div className="flex items-center justify-end gap-3 p-5 border-t border-foundation-border">
          <button onClick={onClose} className="px-4 py-2 text-xs font-medium text-foundation-muted-fg hover:text-foundation-fg">
            Close
          </button>
          <button
            onClick={onEdit}
            disabled={!canEdit}
            className="px-4 py-2 rounded-[var(--radius-pill)] text-xs font-medium transition-colors disabled:opacity-40 flex items-center gap-1.5"
            style={{ backgroundColor: 'var(--accent)', color: 'var(--accent-foreground)' }}
          >
            <Pencil size={12} /> Edit Rule
          </button>
        </div>
      </div>
    </div>,
    document.body
  );
}
