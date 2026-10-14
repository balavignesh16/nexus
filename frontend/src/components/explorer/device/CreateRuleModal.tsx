import { useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { X, Plus, Trash2 } from 'lucide-react';
import { ruleApi, type Rule, type RuleCondition, type RuleOperator } from '../../../api/ruleApi';
import {
  type CapabilityType,
  type Device,
  type DeviceCapability,
  READ_CAPABILITIES,
  WRITE_CAPABILITIES,
  isBooleanCapability,
} from '../../../api/deviceApi';
import { useActiveDevices } from '../../../hooks/queries';
import {
  applyTemporal, buildTriggerFields, emptyTriggerConfig, triggerConfigFromRule, validateTriggerConfig,
  type TriggerConfig,
} from './ruleTriggerConfig';
import { RuleTriggerConfigSection } from './RuleTriggerConfigSection';

interface CreateRuleModalProps {
  /** Optional: when launched from a device's Rules tab, pre-selects and
   * locks the trigger device to this one. When launched from the global
   * Rules page there is no device context, so the user picks one. */
  deviceId?: string;
  /** Optional: when provided, the modal opens pre-filled with this rule's
   * real conditions/actions and submits via PUT (update) instead of POST
   * (create). Editing preserves the rule's ruleId, so its match history,
   * AI explanations and cooldown state all stay attached to it - unlike
   * deleting and recreating, which orphans that history under a new id.
   * Undefined/null (the default) leaves create-mode entirely unchanged. */
  editingRule?: Rule | null;
  isOpen: boolean;
  onClose: () => void;
  onCreated: () => void;
}

const NUMERIC_OPERATORS: RuleOperator[] = ['EQ', 'NE', 'GT', 'LT', 'GTE', 'LTE'];
const BOOLEAN_OPERATORS: RuleOperator[] = ['EQ', 'NE'];
const ACTION_TYPES = ['CREATE_ALERT', 'SEND_COMMAND', 'WEBHOOK'] as const;

interface ConditionRow {
  id: string;
  capabilityType: CapabilityType | '';
  operator: RuleOperator;
  value: string; // 'true'/'false' for boolean capabilities, numeric text otherwise
  /** Carried from an existing rule so editing never drops it: generic capability reference, explicit unit, original position. */
  capabilityKey?: string;
  unit?: string;
  origin?: number;
  /** Stable condition identity (required when a hysteresis exit is set) and the optional EXIT threshold of a numeric hysteresis band. */
  conditionId?: string;
  hysteresisExit?: string;
}

/** A condition of an existing rule the form cannot edit (payload, severity, ...). It is preserved verbatim and in place. */
interface PreservedCondition { condition: RuleCondition; origin: number }

function readCapabilitiesOf(device: Device | undefined): DeviceCapability[] {
  if (!device) return [];
  return device.capabilities.filter((c) => c.direction === 'READ' && (READ_CAPABILITIES as string[]).includes(c.capabilityType));
}

function writeCapabilitiesOf(device: Device | undefined): DeviceCapability[] {
  if (!device) return [];
  return device.capabilities.filter((c) => c.direction === 'WRITE' && (WRITE_CAPABILITIES as string[]).includes(c.capabilityType));
}

function newConditionRow(defaultCapability: CapabilityType | ''): ConditionRow {
  return {
    id: Math.random().toString(36).slice(2),
    capabilityType: defaultCapability,
    operator: 'EQ',
    value: defaultCapability && isBooleanCapability(defaultCapability) ? 'true' : '',
  };
}

/**
 * Phase 13 capability-aware rule builder: Device -> Capability -> Operator
 * -> Typed Value for conditions (supports multiple, AND-combined - e.g.
 * "GAS > 800 AND MOTION == true"), and Target Device -> WRITE Capability ->
 * Typed Value for SEND_COMMAND actions (LED/FAN/BUZZER boolean, SERVO
 * angle, RELAY channel+state). No raw JSON required for these cases.
 * CREATE_ALERT/WEBHOOK actions keep the existing raw JSON params editor -
 * no capability semantics apply to them. Falls back to the legacy
 * EVENT_TYPE/SEVERITY-based single condition when no trigger device is
 * selected, preserving platform-wide rule creation exactly as before.
 */
export function CreateRuleModal({ deviceId, editingRule, isOpen, onClose, onCreated }: CreateRuleModalProps) {
  const { data: devices = [] } = useActiveDevices();

  const [name, setName] = useState('');
  const [priority, setPriority] = useState(1);
  const [enabled, setEnabled] = useState(true);
  const [cooldownSeconds, setCooldownSeconds] = useState('');
  const [triggerConfig, setTriggerConfig] = useState<TriggerConfig>(emptyTriggerConfig());

  const [triggerDeviceId, setTriggerDeviceId] = useState(deviceId ?? '');
  const [conditions, setConditions] = useState<ConditionRow[]>([]);
  const [preserved, setPreserved] = useState<PreservedCondition[]>([]);
  const [deviceOrigin, setDeviceOrigin] = useState<number | undefined>();
  // Platform-wide rules whose conditions the single legacy field cannot represent are kept exactly as stored.
  const [legacyLocked, setLegacyLocked] = useState(false);

  // Legacy fallback (no trigger device selected): the original generic
  // single-condition builder, unchanged.
  const [legacyField, setLegacyField] = useState<'EVENT_TYPE' | 'SEVERITY'>('EVENT_TYPE');
  const [legacyOperator, setLegacyOperator] = useState<RuleOperator>('EQ');
  const [legacyValue, setLegacyValue] = useState('');

  const [actionType, setActionType] = useState<typeof ACTION_TYPES[number]>('CREATE_ALERT');
  const [actionParams, setActionParams] = useState('{"severity":"INFO"}');
  const [targetDeviceId, setTargetDeviceId] = useState('');
  const [targetCapabilityType, setTargetCapabilityType] = useState<CapabilityType | ''>('');
  const [commandBoolValue, setCommandBoolValue] = useState(true);
  const [commandServoAngle, setCommandServoAngle] = useState(90);
  const [commandRelayChannel, setCommandRelayChannel] = useState<number | ''>('');
  const [commandRelayState, setCommandRelayState] = useState(true);

  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [baseRevision, setBaseRevision] = useState<number | undefined>();
  const openedRule = useRef<string | null>(null);

  const triggerDevice = useMemo(() => devices.find((d) => d.id === triggerDeviceId), [devices, triggerDeviceId]);
  const readCapabilities = useMemo(() => readCapabilitiesOf(triggerDevice), [triggerDevice]);

  const targetDevice = useMemo(() => devices.find((d) => d.id === targetDeviceId), [devices, targetDeviceId]);
  const writeCapabilities = useMemo(() => writeCapabilitiesOf(targetDevice), [targetDevice]);
  const targetCapability = useMemo(
    () => writeCapabilities.find((c) => c.capabilityType === targetCapabilityType),
    [writeCapabilities, targetCapabilityType]
  );
  const relayChannels: number[] = useMemo(() => {
    const raw = targetCapability?.metadata?.channels;
    return Array.isArray(raw) ? (raw as number[]) : [];
  }, [targetCapability]);

  // Seed one condition row once the trigger device's capabilities are known.
  useEffect(() => {
    if (triggerDeviceId && readCapabilities.length > 0 && conditions.length === 0 && !editingRule) {
      setConditions([newConditionRow(readCapabilities[0].capabilityType)]);
    }
    if (!triggerDeviceId) {
      setConditions([]);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [triggerDeviceId, readCapabilities.length]);

  // Default the action's target device to the trigger device (still overridable).
  useEffect(() => {
    if (triggerDeviceId && !targetDeviceId) {
      setTargetDeviceId(triggerDeviceId);
    }
  }, [triggerDeviceId, targetDeviceId]);

  useEffect(() => {
    if (writeCapabilities.length > 0 && !writeCapabilities.some((c) => c.capabilityType === targetCapabilityType)) {
      setTargetCapabilityType(writeCapabilities[0].capabilityType);
    }
    if (writeCapabilities.length === 0) {
      setTargetCapabilityType('');
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [targetDeviceId, writeCapabilities.length]);

  useEffect(() => {
    if (targetCapabilityType === 'RELAY' && relayChannels.length > 0 && commandRelayChannel === '') {
      setCommandRelayChannel(relayChannels[0]);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [targetCapabilityType, relayChannels.length]);

  // Edit mode: pre-fill every field from the real rule being edited. Runs
  // once per modal-open in edit mode (isOpen/editingRule as deps, not the
  // fields it writes) - the seed effects above only backfill a field when
  // it is still empty, so they never clobber what this sets.
  useEffect(() => {
    if (!isOpen) { openedRule.current = null; return; }
    if (!editingRule || openedRule.current === editingRule.ruleId) return;
    openedRule.current = editingRule.ruleId;
    setBaseRevision(editingRule.rowRevision);
    setName(editingRule.name);
    setPriority(editingRule.priority);
    setEnabled(editingRule.enabled);
    setCooldownSeconds(editingRule.cooldownSeconds != null ? String(editingRule.cooldownSeconds) : '');
    setTriggerConfig(triggerConfigFromRule(editingRule));

    const ruleConditions = editingRule.conditions ?? [];
    const deviceIndex = ruleConditions.findIndex((c) => c.field === 'DEVICE_ID');
    const deviceCondition = deviceIndex >= 0 ? ruleConditions[deviceIndex] : undefined;
    setPreserved([]);
    setLegacyLocked(false);
    setDeviceOrigin(deviceIndex >= 0 ? deviceIndex : undefined);

    if (deviceCondition) {
      setTriggerDeviceId(deviceCondition.expectedValue);
      const rows: ConditionRow[] = [];
      const kept: PreservedCondition[] = [];
      ruleConditions.forEach((c, origin) => {
        if (c === deviceCondition) return;
        // A temporal condition is owned by the trigger section (it is re-attached from there); everything else the form cannot show is kept as is.
        if (c.temporal && !c.capabilityType && !c.capabilityKey) return;
        if (c.field === 'VALUE' && (c.capabilityType || c.capabilityKey)) {
          rows.push({
            id: Math.random().toString(36).slice(2), capabilityType: (c.capabilityType ?? '') as CapabilityType | '', operator: c.operator, value: c.expectedValue,
            ...(c.capabilityKey ? { capabilityKey: c.capabilityKey } : {}), ...(c.unit ? { unit: c.unit } : {}), origin,
            ...(c.conditionId ? { conditionId: c.conditionId } : {}), ...(c.hysteresis?.exit != null ? { hysteresisExit: c.hysteresis.exit } : {}),
          });
        } else kept.push({ condition: c, origin });
      });
      setConditions(rows);
      setPreserved(kept);
    } else {
      setTriggerDeviceId('');
      setConditions([]);
      const legacy = ruleConditions[0];
      const representable = ruleConditions.length === 1 && legacy && (legacy.field === 'EVENT_TYPE' || legacy.field === 'SEVERITY') && !legacy.capabilityType && !legacy.capabilityKey;
      if (representable) {
        setLegacyField(legacy.field === 'SEVERITY' ? 'SEVERITY' : 'EVENT_TYPE');
        setLegacyOperator(legacy.operator);
        setLegacyValue(legacy.expectedValue);
      } else if (ruleConditions.length > 0) {
        setLegacyLocked(true);
        setPreserved(ruleConditions.map((condition, origin) => ({ condition, origin })));
      }
    }

    const firstAction = (editingRule.actions ?? [])[0];
    if (firstAction) {
      if (firstAction.actionType === 'SEND_COMMAND') {
        setActionType('SEND_COMMAND');
        const p = firstAction.parameters ?? {};
        setTargetDeviceId(p.deviceId ?? '');
        setTargetCapabilityType((p.capability as CapabilityType) ?? '');
        if (p.capability === 'RELAY' && p.value && typeof p.value === 'object') {
          setCommandRelayChannel(p.value.channel ?? '');
          setCommandRelayState(Boolean(p.value.state));
        } else if (p.capability === 'SERVO') {
          setCommandServoAngle(Number(p.value) || 0);
        } else {
          setCommandBoolValue(Boolean(p.value));
        }
      } else {
        setActionType(firstAction.actionType as typeof ACTION_TYPES[number]);
        setActionParams(JSON.stringify(firstAction.parameters ?? {}));
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen, editingRule]);

  if (!isOpen) return null;

  const reset = () => {
    setName('');
    setPriority(1);
    setEnabled(true);
    setCooldownSeconds('');
    setTriggerConfig(emptyTriggerConfig());
    setTriggerDeviceId(deviceId ?? '');
    setConditions([]);
    setPreserved([]);
    setDeviceOrigin(undefined);
    setLegacyLocked(false);
    setLegacyField('EVENT_TYPE');
    setLegacyOperator('EQ');
    setLegacyValue('');
    setActionType('CREATE_ALERT');
    setActionParams('{"severity":"INFO"}');
    setTargetDeviceId('');
    setTargetCapabilityType('');
    setCommandBoolValue(true);
    setCommandServoAngle(90);
    setCommandRelayChannel('');
    setCommandRelayState(true);
    setError(null);
  };

  const handleClose = () => {
    reset();
    onClose();
  };

  const addCondition = () => {
    const used = new Set(conditions.map((c) => c.capabilityType));
    const nextCapability = readCapabilities.find((c) => !used.has(c.capabilityType))?.capabilityType ?? readCapabilities[0]?.capabilityType ?? '';
    setConditions((rows) => [...rows, newConditionRow(nextCapability)]);
  };

  const removeCondition = (id: string) => {
    setConditions((rows) => rows.filter((r) => r.id !== id));
  };

  const updateCondition = (id: string, patch: Partial<ConditionRow>) => {
    setConditions((rows) => rows.map((r) => {
      if (r.id !== id) return r;
      const next = { ...r, ...patch };
      // Switching capability may change numeric<->boolean kind - reset the
      // operator/value to something valid for the new kind rather than
      // silently keeping an invalid combination.
      if (patch.capabilityType && patch.capabilityType !== r.capabilityType) {
        next.operator = 'EQ';
        next.value = isBooleanCapability(patch.capabilityType) ? 'true' : '';
        // A different capability invalidates the old generic reference and explicit unit.
        delete next.capabilityKey;
        delete next.unit;
      }
      return next;
    }));
  };

  /** Merges form-built and preserved conditions back into their original positions (new ones last). */
  const inOriginalOrder = (entries: { condition: RuleCondition; origin?: number }[]): RuleCondition[] =>
    entries
      .map((entry, index) => ({ ...entry, index }))
      .sort((a, b) => (a.origin ?? Number.MAX_SAFE_INTEGER) - (b.origin ?? Number.MAX_SAFE_INTEGER) || a.index - b.index)
      .map((entry) => entry.condition);

  const buildConditions = (): RuleCondition[] => {
    if (!triggerDeviceId) {
      if (legacyLocked) return inOriginalOrder(preserved);
      return [{ field: legacyField, operator: legacyOperator, expectedValue: legacyValue }];
    }
    const deviceCondition: RuleCondition = { field: 'DEVICE_ID', operator: 'EQ', expectedValue: triggerDeviceId };
    const capabilityConditions = conditions
      .filter((c) => c.capabilityType || c.capabilityKey)
      .map((c) => ({
        origin: c.origin,
        condition: {
          field: 'VALUE',
          operator: c.operator,
          expectedValue: c.value,
          ...(c.capabilityType ? { capabilityType: c.capabilityType as CapabilityType } : {}),
          ...(c.capabilityKey ? { capabilityKey: c.capabilityKey } : {}),
          ...(c.unit ? { unit: c.unit } : {}),
          ...(c.hysteresisExit && c.hysteresisExit.trim() ? { conditionId: c.conditionId ?? `hy-${c.id.slice(0, 8)}`, hysteresis: { exit: c.hysteresisExit.trim() } }
            : c.conditionId ? { conditionId: c.conditionId } : {}),
        } as RuleCondition,
      }));
    return inOriginalOrder([{ condition: deviceCondition, origin: deviceOrigin }, ...capabilityConditions, ...preserved]);
  };

  const buildActions = () => {
    if (actionType !== 'SEND_COMMAND') {
      try {
        return [{ actionType, parameters: JSON.parse(actionParams) }];
      } catch {
        throw new Error('Action parameters must be valid JSON.');
      }
    }
    if (!targetDeviceId || !targetCapabilityType) {
      throw new Error('Select a target device and capability for the command.');
    }
    let value: unknown;
    if (targetCapabilityType === 'SERVO') {
      value = commandServoAngle;
    } else if (targetCapabilityType === 'RELAY') {
      if (commandRelayChannel === '') throw new Error('Select a relay channel.');
      value = { channel: commandRelayChannel, state: commandRelayState };
    } else {
      value = commandBoolValue;
    }
    return [{
      actionType: 'SEND_COMMAND',
      parameters: { deviceId: targetDeviceId, capability: targetCapabilityType, command: 'SET_STATE', value },
    }];
  };

  const handleSubmit = async (draftOnly = false) => {
    setError(null);
    if (!name.trim()) {
      setError('Name is required.');
      return;
    }
    const connectivityOnly = triggerConfig.temporal.enabled && triggerConfig.temporal.operator === 'OFFLINE_FOR';
    if (triggerDeviceId && conditions.length === 0 && preserved.length === 0 && !connectivityOnly) {
      setError('Add at least one condition.');
      return;
    }
    if (!triggerDeviceId && !legacyLocked && !legacyValue.trim()) {
      setError('Condition value is required.');
      return;
    }

    const triggerError = validateTriggerConfig(triggerConfig, Boolean(triggerDeviceId));
    if (triggerError) {
      setError(triggerError);
      return;
    }

    let actions;
    try {
      actions = buildActions();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Invalid action configuration.');
      return;
    }

    const body = {
      name: name.trim(),
      enabled,
      priority,
      conditions: applyTemporal(buildConditions(), triggerConfig),
      actions,
      metadata: {},
      cooldownSeconds: cooldownSeconds.trim() ? Number(cooldownSeconds) : null,
      ...buildTriggerFields(triggerConfig),
    };

    setSubmitting(true);
    try {
      if (editingRule) {
        if (baseRevision !== undefined) {
          await ruleApi.saveDraft(editingRule.ruleId, body, baseRevision);
        } else {
          await ruleApi.updateRule(editingRule.ruleId, { ...body, ruleId: editingRule.ruleId });
        }
      } else if (draftOnly) {
        await ruleApi.createDraft({ ...body, enabled: false });
      } else {
        await ruleApi.createRule(body);
      }
      reset();
      onCreated();
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : `Failed to ${editingRule ? 'update' : 'create'} rule.`);
    } finally {
      setSubmitting(false);
    }
  };

  const selectClass = "h-9 px-2 text-xs rounded-[var(--radius-panel)] border border-foundation-border bg-black/[0.02] text-foundation-fg outline-none focus:border-[var(--accent)]";
  const inputClass = "h-9 px-3 text-xs rounded-[var(--radius-panel)] border border-foundation-border bg-black/[0.02] text-foundation-fg outline-none focus:border-[var(--accent)]";

  // Portaled to document.body - see RuleDetailModal.tsx's comment on its
  // own createPortal call for why: rendered inline, this modal is nested
  // inside AppShell's content pane, which establishes its own stacking
  // context, so this modal's z-50 would never actually out-rank the
  // sidebar (a sibling of that pane, not a descendant of it).
  return createPortal(
    <div className="fixed inset-0 bg-black/40 backdrop-blur-sm flex items-center justify-center z-50 p-4" onClick={handleClose}>
      <div
        className="rounded-[var(--radius-panel-lg)] shadow-[var(--shadow-glass-lg)] w-full max-w-xl max-h-[90vh] overflow-y-auto"
        style={{ backgroundColor: 'var(--foundation-surface)', border: '1px solid var(--foundation-border)' }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between p-5 border-b border-foundation-border">
          <h3 className="text-sm font-semibold text-foundation-fg">{editingRule ? 'Edit Rule' : 'Create Rule'}</h3>
          <button onClick={handleClose} className="text-foundation-muted-fg hover:text-foundation-fg">
            <X size={18} />
          </button>
        </div>

        <div className="p-5 flex flex-col gap-4">
          <div>
            <label className="text-xs text-foundation-muted-fg mb-1 block">Name</label>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="e.g. High Temperature Alert"
              className={`w-full ${inputClass}`}
            />
          </div>

          <div className="flex gap-4">
            <div className="flex-1">
              <label className="text-xs text-foundation-muted-fg mb-1 block">Priority</label>
              <input
                type="number"
                value={priority}
                onChange={(e) => setPriority(Number(e.target.value))}
                className={`w-full ${inputClass}`}
              />
            </div>
            <div className="flex-1">
              <label className="text-xs text-foundation-muted-fg mb-1 block">Cooldown (seconds, optional)</label>
              <input
                type="number"
                min={1}
                value={cooldownSeconds}
                onChange={(e) => setCooldownSeconds(e.target.value)}
                placeholder="No cooldown"
                data-testid="cooldown-input"
                className={`w-full ${inputClass}`}
              />
            </div>
            <div className="flex items-end pb-2">
              <label className="flex items-center gap-2 text-xs text-foundation-muted-fg">
                <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
                Enabled
              </label>
            </div>
          </div>

          <div>
            <label className="text-xs text-foundation-muted-fg mb-1 block">Device</label>
            <select
              value={triggerDeviceId}
              disabled={Boolean(deviceId)}
              onChange={(e) => setTriggerDeviceId(e.target.value)}
              data-testid="trigger-device-select"
              className={`w-full ${selectClass}`}
            >
              <option value="">Platform-wide (no specific device)</option>
              {devices.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
            </select>
          </div>

          <RuleTriggerConfigSection config={triggerConfig} onChange={setTriggerConfig} hasDevice={Boolean(triggerDeviceId)} selectClass={selectClass} inputClass={inputClass} />

          {triggerDeviceId ? (
            <div>
              <span className="text-xs text-foundation-muted-fg mb-1 block">Conditions (all must match)</span>
              {preserved.length > 0 && (
                <p className="text-xs mb-2 text-foundation-muted-fg" data-testid="preserved-conditions">
                  {preserved.length} existing condition{preserved.length === 1 ? '' : 's'} this form cannot edit will be kept unchanged.
                </p>
              )}
              {readCapabilities.length === 0 && !editingRule && (
                <p className="text-xs mb-2" style={{ color: 'var(--danger)' }}>
                  This device has no READ capabilities provisioned.
                </p>
              )}
              <div className="flex flex-col gap-2">
                {conditions.map((row) => {
                  const boolKind = row.capabilityType ? isBooleanCapability(row.capabilityType) : false;
                  const operators = boolKind ? BOOLEAN_OPERATORS : NUMERIC_OPERATORS;
                  return (
                    <div key={row.id} className="flex gap-2 items-center">
                      {row.capabilityKey && !row.capabilityType ? (
                        <span className={`${inputClass} flex items-center`} data-testid="condition-capability-key">{row.capabilityKey}{row.unit ? ` (${row.unit})` : ''}</span>
                      ) : (
                      <select
                        value={row.capabilityType}
                        onChange={(e) => updateCondition(row.id, { capabilityType: e.target.value as CapabilityType })}
                        className={selectClass}
                        data-testid="condition-capability-select"
                      >
                        {readCapabilities.map((c) => (
                          <option key={c.id} value={c.capabilityType}>{c.capabilityType}</option>
                        ))}
                      </select>
                      )}
                      <select
                        value={row.operator}
                        onChange={(e) => updateCondition(row.id, { operator: e.target.value as RuleOperator })}
                        className={selectClass}
                        data-testid="condition-operator-select"
                      >
                        {operators.map((o) => <option key={o} value={o}>{o}</option>)}
                      </select>
                      {row.capabilityKey && !row.capabilityType ? (
                        <input
                          type="text"
                          value={row.value}
                          onChange={(e) => updateCondition(row.id, { value: e.target.value })}
                          className={`flex-1 ${inputClass}`}
                          data-testid="condition-text-value-input"
                        />
                      ) : boolKind ? (
                        <select
                          value={row.value}
                          onChange={(e) => updateCondition(row.id, { value: e.target.value })}
                          className={selectClass}
                          data-testid="condition-boolean-value-select"
                        >
                          <option value="true">true</option>
                          <option value="false">false</option>
                        </select>
                      ) : (
                        <input
                          type="number"
                          value={row.value}
                          onChange={(e) => updateCondition(row.id, { value: e.target.value })}
                          className={`flex-1 ${inputClass}`}
                          data-testid="condition-numeric-value-input"
                        />
                      )}
                      {!boolKind && ['GT', 'GTE', 'LT', 'LTE'].includes(row.operator) && (
                        <input
                          type="number"
                          value={row.hysteresisExit ?? ''}
                          onChange={(e) => updateCondition(row.id, { hysteresisExit: e.target.value })}
                          placeholder="Exit (optional)"
                          title="Hysteresis: the condition stays true until the value passes this exit threshold (same unit, no conversion)"
                          className={`w-28 ${inputClass}`}
                          data-testid="condition-hysteresis-exit"
                        />
                      )}
                      <button
                        type="button"
                        onClick={() => removeCondition(row.id)}
                        disabled={conditions.length <= 1}
                        className="text-foundation-muted-fg hover:text-[var(--danger)] disabled:opacity-30"
                        aria-label="Remove condition"
                      >
                        <Trash2 size={14} />
                      </button>
                    </div>
                  );
                })}
              </div>
              <button
                type="button"
                onClick={addCondition}
                disabled={readCapabilities.length === 0}
                className="mt-2 flex items-center gap-1 text-xs font-medium disabled:opacity-40"
                style={{ color: 'var(--accent)' }}
              >
                <Plus size={14} /> Add condition
              </button>
            </div>
          ) : (
            legacyLocked ? (
              <p className="text-xs text-foundation-muted-fg" data-testid="preserved-conditions">
                This rule's {preserved.length} condition{preserved.length === 1 ? '' : 's'} cannot be shown in this form and will be kept unchanged.
              </p>
            ) : (
            <div>
              <span className="text-xs text-foundation-muted-fg mb-1 block">Trigger Condition</span>
              <div className="flex gap-2">
                <select
                  value={legacyField}
                  onChange={(e) => setLegacyField(e.target.value as 'EVENT_TYPE' | 'SEVERITY')}
                  className={selectClass}
                >
                  <option value="EVENT_TYPE">EVENT_TYPE</option>
                  <option value="SEVERITY">SEVERITY</option>
                </select>
                <select
                  value={legacyOperator}
                  onChange={(e) => setLegacyOperator(e.target.value as RuleOperator)}
                  className={selectClass}
                >
                  {NUMERIC_OPERATORS.map((o) => <option key={o} value={o}>{o}</option>)}
                </select>
                <input
                  type="text"
                  value={legacyValue}
                  onChange={(e) => setLegacyValue(e.target.value)}
                  className={`flex-1 font-mono ${inputClass}`}
                />
              </div>
            </div>
            )
          )}

          <div>
            <span className="text-xs text-foundation-muted-fg mb-1 block">Action</span>
            <select
              value={actionType}
              onChange={(e) => setActionType(e.target.value as typeof ACTION_TYPES[number])}
              data-testid="action-type-select"
              className={`w-full ${selectClass} mb-2`}
            >
              {ACTION_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
            </select>

            {actionType === 'SEND_COMMAND' ? (
              <div className="flex flex-col gap-2">
                <div className="flex gap-2">
                  <select
                    value={targetDeviceId}
                    onChange={(e) => setTargetDeviceId(e.target.value)}
                    className={`flex-1 ${selectClass}`}
                    data-testid="action-target-device-select"
                  >
                    <option value="">Select target device</option>
                    {devices.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
                  </select>
                  <select
                    value={targetCapabilityType}
                    onChange={(e) => setTargetCapabilityType(e.target.value as CapabilityType)}
                    className={`flex-1 ${selectClass}`}
                    data-testid="action-capability-select"
                  >
                    <option value="">Select capability</option>
                    {writeCapabilities.map((c) => <option key={c.id} value={c.capabilityType}>{c.capabilityType}</option>)}
                  </select>
                </div>
                {writeCapabilities.length === 0 && targetDeviceId && (
                  <p className="text-xs" style={{ color: 'var(--danger)' }}>This device has no WRITE capabilities provisioned.</p>
                )}

                {targetCapabilityType === 'SERVO' && (
                  <div>
                    <label className="text-xs text-foundation-muted-fg mb-1 block">Angle (0-180)</label>
                    <input
                      type="number"
                      min={0}
                      max={180}
                      value={commandServoAngle}
                      onChange={(e) => setCommandServoAngle(Number(e.target.value))}
                      className={`w-full ${inputClass}`}
                      data-testid="command-servo-angle-input"
                    />
                  </div>
                )}

                {targetCapabilityType === 'RELAY' && (
                  <div className="flex gap-2">
                    <select
                      value={commandRelayChannel}
                      onChange={(e) => setCommandRelayChannel(Number(e.target.value))}
                      className={selectClass}
                      data-testid="command-relay-channel-select"
                    >
                      {relayChannels.map((ch) => <option key={ch} value={ch}>Channel {ch}</option>)}
                    </select>
                    <select
                      value={String(commandRelayState)}
                      onChange={(e) => setCommandRelayState(e.target.value === 'true')}
                      className={selectClass}
                      data-testid="command-relay-state-select"
                    >
                      <option value="true">ON</option>
                      <option value="false">OFF</option>
                    </select>
                  </div>
                )}

                {(targetCapabilityType === 'LED' || targetCapabilityType === 'FAN' || targetCapabilityType === 'BUZZER') && (
                  <select
                    value={String(commandBoolValue)}
                    onChange={(e) => setCommandBoolValue(e.target.value === 'true')}
                    className={selectClass}
                    data-testid="command-boolean-value-select"
                  >
                    <option value="true">ON</option>
                    <option value="false">OFF</option>
                  </select>
                )}
              </div>
            ) : (
              <textarea
                value={actionParams}
                onChange={(e) => setActionParams(e.target.value)}
                rows={3}
                className={`w-full px-3 py-2 text-xs font-mono rounded-[var(--radius-panel)] border border-foundation-border bg-black/[0.02] text-foundation-fg outline-none focus:border-[var(--accent)]`}
                placeholder='{"severity":"INFO"}'
              />
            )}
          </div>

          {error && <div data-testid="create-rule-error" className="text-xs" style={{ color: 'var(--danger)' }}>{error}</div>}
          {baseRevision !== undefined && <p className="text-xs">Saving a draft does not pause or replace the active version. Use the lifecycle controls to validate, activate or pause. On conflict, your entries remain here; close and reopen only when ready to reload.</p>}
        </div>

        <div className="flex items-center justify-end gap-3 p-5 border-t border-foundation-border">
          {!editingRule && <button disabled={submitting} onClick={() => handleSubmit(true)} className="px-4 py-2 text-xs">Create Draft</button>}
          <button onClick={handleClose} className="px-4 py-2 text-xs font-medium text-foundation-muted-fg hover:text-foundation-fg">
            Cancel
          </button>
          <button
            onClick={() => handleSubmit()}
            disabled={submitting}
            className="px-4 py-2 rounded-[var(--radius-pill)] text-xs font-medium transition-colors disabled:opacity-50"
            style={{ backgroundColor: 'var(--accent)', color: 'var(--accent-foreground)' }}
          >
            {submitting ? (editingRule ? 'Saving...' : 'Creating...') : (editingRule ? (baseRevision !== undefined ? 'Save Draft' : 'Save Changes') : 'Create Rule')}
          </button>
        </div>
      </div>
    </div>,
    document.body
  );
}
