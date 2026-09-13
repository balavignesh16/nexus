import { useState } from 'react';
import { X } from 'lucide-react';
import { ruleApi, type RuleField, type RuleOperator } from '../../../api/ruleApi';

interface CreateRuleModalProps {
  deviceId: string;
  isOpen: boolean;
  onClose: () => void;
  onCreated: () => void;
}

const FIELDS: RuleField[] = ['EVENT_TYPE', 'DEVICE_ID', 'SENSOR_TYPE', 'VALUE', 'SEVERITY'];
const OPERATORS: RuleOperator[] = ['EQ', 'NE', 'GT', 'LT', 'GTE', 'LTE'];
const ACTION_TYPES = ['CREATE_ALERT', 'SEND_COMMAND', 'WEBHOOK'] as const;

/**
 * Minimal, real rule builder: exactly one condition and one action, POSTed
 * to the existing /api/v1/rules endpoint. Pre-fills the condition to
 * DEVICE_ID = this device since it's launched from a device's Rules tab,
 * but every field is editable - nothing is hidden or fabricated.
 */
export function CreateRuleModal({ deviceId, isOpen, onClose, onCreated }: CreateRuleModalProps) {
  const [name, setName] = useState('');
  const [priority, setPriority] = useState(1);
  const [enabled, setEnabled] = useState(true);
  const [conditionField, setConditionField] = useState<RuleField>('DEVICE_ID');
  const [conditionOperator, setConditionOperator] = useState<RuleOperator>('EQ');
  const [conditionValue, setConditionValue] = useState(deviceId);
  const [actionType, setActionType] = useState<typeof ACTION_TYPES[number]>('CREATE_ALERT');
  const [actionParams, setActionParams] = useState('{"severity":"INFO"}');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!isOpen) return null;

  const reset = () => {
    setName('');
    setPriority(1);
    setEnabled(true);
    setConditionField('DEVICE_ID');
    setConditionOperator('EQ');
    setConditionValue(deviceId);
    setActionType('CREATE_ALERT');
    setActionParams('{"severity":"INFO"}');
    setError(null);
  };

  const handleClose = () => {
    reset();
    onClose();
  };

  const handleSubmit = async () => {
    setError(null);
    if (!name.trim()) {
      setError('Name is required.');
      return;
    }
    let parameters: Record<string, any>;
    try {
      parameters = JSON.parse(actionParams);
    } catch {
      setError('Action parameters must be valid JSON.');
      return;
    }

    setSubmitting(true);
    try {
      await ruleApi.createRule({
        name: name.trim(),
        enabled,
        priority,
        conditions: [{ field: conditionField, operator: conditionOperator, expectedValue: conditionValue }],
        actions: [{ actionType, parameters }],
        metadata: {},
      });
      reset();
      onCreated();
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to create rule.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 bg-black/60 flex items-center justify-center z-50 p-4" onClick={handleClose}>
      <div
        className="bg-[#12121a] border border-[#1f1f28] rounded-xl shadow-xl w-full max-w-lg max-h-[90vh] overflow-y-auto"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between p-5 border-b border-[#1f1f28]">
          <h3 className="text-sm font-semibold text-foreground">Create Rule</h3>
          <button onClick={handleClose} className="text-muted-foreground hover:text-foreground">
            <X size={18} />
          </button>
        </div>

        <div className="p-5 flex flex-col gap-4">
          <div>
            <label className="text-xs text-muted-foreground mb-1 block">Name</label>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="e.g. High Temperature Alert"
              className="w-full bg-[#0a0a0f] border border-[#1f1f28] rounded-md px-3 py-2 text-sm text-foreground focus:outline-none focus:border-primary"
            />
          </div>

          <div className="flex gap-4">
            <div className="flex-1">
              <label className="text-xs text-muted-foreground mb-1 block">Priority</label>
              <input
                type="number"
                value={priority}
                onChange={(e) => setPriority(Number(e.target.value))}
                className="w-full bg-[#0a0a0f] border border-[#1f1f28] rounded-md px-3 py-2 text-sm text-foreground focus:outline-none focus:border-primary"
              />
            </div>
            <div className="flex items-end pb-2">
              <label className="flex items-center gap-2 text-xs text-muted-foreground">
                <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
                Enabled
              </label>
            </div>
          </div>

          <div>
            <span className="text-xs text-muted-foreground mb-1 block">Trigger Condition</span>
            <div className="flex gap-2">
              <select
                value={conditionField}
                onChange={(e) => setConditionField(e.target.value as RuleField)}
                className="bg-[#0a0a0f] border border-[#1f1f28] rounded-md px-2 py-2 text-xs text-foreground focus:outline-none focus:border-primary"
              >
                {FIELDS.map((f) => <option key={f} value={f}>{f}</option>)}
              </select>
              <select
                value={conditionOperator}
                onChange={(e) => setConditionOperator(e.target.value as RuleOperator)}
                className="bg-[#0a0a0f] border border-[#1f1f28] rounded-md px-2 py-2 text-xs text-foreground focus:outline-none focus:border-primary"
              >
                {OPERATORS.map((o) => <option key={o} value={o}>{o}</option>)}
              </select>
              <input
                type="text"
                value={conditionValue}
                onChange={(e) => setConditionValue(e.target.value)}
                className="flex-1 bg-[#0a0a0f] border border-[#1f1f28] rounded-md px-3 py-2 text-xs font-mono text-foreground focus:outline-none focus:border-primary"
              />
            </div>
          </div>

          <div>
            <span className="text-xs text-muted-foreground mb-1 block">Action</span>
            <select
              value={actionType}
              onChange={(e) => setActionType(e.target.value as typeof ACTION_TYPES[number])}
              className="w-full bg-[#0a0a0f] border border-[#1f1f28] rounded-md px-3 py-2 text-xs text-foreground focus:outline-none focus:border-primary mb-2"
            >
              {ACTION_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
            </select>
            <textarea
              value={actionParams}
              onChange={(e) => setActionParams(e.target.value)}
              rows={3}
              className="w-full bg-[#0a0a0f] border border-[#1f1f28] rounded-md px-3 py-2 text-xs font-mono text-foreground focus:outline-none focus:border-primary"
              placeholder='{"severity":"INFO"}'
            />
          </div>

          {error && <div data-testid="create-rule-error" className="text-xs text-destructive">{error}</div>}
        </div>

        <div className="flex items-center justify-end gap-3 p-5 border-t border-[#1f1f28]">
          <button onClick={handleClose} className="px-4 py-2 text-xs font-medium text-muted-foreground hover:text-foreground">
            Cancel
          </button>
          <button
            onClick={handleSubmit}
            disabled={submitting}
            className="px-4 py-2 bg-primary/10 hover:bg-primary/20 text-primary rounded-md text-xs font-medium transition-colors border border-primary/20 disabled:opacity-50"
          >
            {submitting ? 'Creating...' : 'Create Rule'}
          </button>
        </div>
      </div>
    </div>
  );
}
