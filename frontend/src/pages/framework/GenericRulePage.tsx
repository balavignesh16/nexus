import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { deviceApi, type Device } from '../../api/deviceApi';
import { deviceContractApi } from '../../api/deviceContractApi';
import type { ContractCapability, DeviceContract } from '../../api/frameworkTypes';
import { ruleApi, type RuleOperator } from '../../api/ruleApi';
import { Button } from '../../components/foundation/Button';
import { Input, Select } from '../../components/foundation/Input';
import { FrameworkLayout } from './FrameworkLayout';

const operatorsFor = (type: string): RuleOperator[] =>
  type === 'NUMBER' ? ['EQ','NE','GT','GTE','LT','LTE'] :
    type === 'OBJECT' ? [] : ['EQ','NE'];

function ValueInput({ capability, value, onChange }: { capability: ContractCapability | undefined;
  value: string; onChange: (value: string) => void }) {
  if (!capability) return null;
  if (capability.valueType === 'BOOLEAN') return <Select aria-label="Comparison value" value={value} onChange={e => onChange(e.target.value)}>
    <option value="true">true</option><option value="false">false</option></Select>;
  if (capability.valueType === 'ENUM') return <Select aria-label="Comparison value" value={value} onChange={e => onChange(e.target.value)}>
    {capability.enumValues.map(option => <option key={option}>{option}</option>)}</Select>;
  if (capability.valueType === 'OBJECT') return <p className="text-xs text-foundation-muted-fg">Object capabilities have no generic comparison operator.</p>;
  return <Input aria-label="Comparison value" type={capability.valueType === 'NUMBER' ? 'number' : 'text'}
    value={value} onChange={e => onChange(e.target.value)} />;
}

export default function GenericRulePage() {
  const [params] = useSearchParams();
  const [devices, setDevices] = useState<Device[]>([]);
  const [triggerId, setTriggerId] = useState(params.get('deviceId') ?? '');
  const [targetId, setTargetId] = useState(params.get('deviceId') ?? '');
  const [triggerContract, setTriggerContract] = useState<DeviceContract | null>(null);
  const [targetContract, setTargetContract] = useState<DeviceContract | null>(null);
  const [name, setName] = useState('');
  const [readKey, setReadKey] = useState('');
  const [operator, setOperator] = useState<RuleOperator>('EQ');
  const [expected, setExpected] = useState('');
  const [writeKey, setWriteKey] = useState('');
  const [command, setCommand] = useState('');
  const [actionValue, setActionValue] = useState('true');
  const [error, setError] = useState('');
  const [result, setResult] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => { void deviceApi.getActiveDevices().then(setDevices).catch(e => setError(String(e))); }, []);
  useEffect(() => { if (triggerId) void deviceContractApi.get(triggerId).then(setTriggerContract).catch(e => setError(String(e)));
    else setTriggerContract(null); }, [triggerId]);
  useEffect(() => { if (targetId) void deviceContractApi.get(targetId).then(setTargetContract).catch(e => setError(String(e)));
    else setTargetContract(null); }, [targetId]);
  const readable = useMemo(() => triggerContract?.capabilities.filter(c => c.enabled && c.direction === 'READ'
    && c.valueType !== 'OBJECT') ?? [], [triggerContract]);
  const writable = useMemo(() => targetContract?.capabilities.filter(c => c.enabled && c.direction === 'WRITE') ?? [], [targetContract]);
  const read = readable.find(c => c.key === readKey);
  const write = writable.find(c => c.key === writeKey);
  useEffect(() => { setReadKey(readable[0]?.key ?? ''); }, [readable]);
  useEffect(() => { setWriteKey(writable[0]?.key ?? ''); }, [writable]);
  useEffect(() => { if (read) { setOperator(current => operatorsFor(read.valueType).includes(current)
    ? current : operatorsFor(read.valueType)[0]);
    setExpected(read.valueType === 'BOOLEAN' ? 'true' : read.valueType === 'ENUM' ? read.enumValues[0] ?? '' : ''); }
  }, [readKey, read]);
  useEffect(() => { if (write) { setCommand(write.supportedCommands[0] ?? '');
    setActionValue(write.valueType === 'BOOLEAN' ? 'true' : write.valueType === 'ENUM' ? write.enumValues[0] ?? '' : ''); }
  }, [writeKey, write]);

  const submit = async () => {
    if (!read || !write) return;
    setBusy(true); setError(''); setResult('');
    try {
      const value: unknown = write.valueType === 'BOOLEAN' ? actionValue === 'true' :
        write.valueType === 'NUMBER' ? Number(actionValue) :
          write.valueType === 'OBJECT' ? JSON.parse(actionValue) : actionValue;
      const created = await ruleApi.createRule({ name, enabled: true, priority: 1, metadata: {},
        conditions: [
          { field: 'EVENT_TYPE', operator: 'EQ', expectedValue: 'TWIN_UPDATED' },
          { field: 'DEVICE_ID', operator: 'EQ', expectedValue: triggerId },
          { field: 'VALUE', operator, expectedValue: expected, capabilityKey: read.key, unit: read.unit ?? undefined },
        ],
        actions: [{ actionType: 'SEND_COMMAND', parameters: { deviceId: targetId,
          capabilityKey: write.key, command, value } }],
      });
      setResult(`Rule ${created.ruleId} created by backend.`);
    } catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  };
  return <FrameworkLayout title="Generic Rule Builder">
    <div className="max-w-2xl space-y-4">
      <p className="text-sm text-foundation-muted-fg">Capability choices, types, operators, and commands come from the selected devices' backend contracts.</p>
      <label className="block text-xs">Rule name <Input value={name} onChange={e => setName(e.target.value)} /></label>
      <label className="block text-xs">Trigger device <Select value={triggerId} onChange={e => { setTriggerId(e.target.value); if (!targetId) setTargetId(e.target.value); }}>
        <option value="">Select active device</option>{devices.map(d => <option key={d.id} value={d.id}>{d.name} ({d.mode ?? 'device'})</option>)}</Select></label>
      <div className="grid sm:grid-cols-3 gap-2">
        <label className="text-xs">Readable capability <Select value={readKey} onChange={e => setReadKey(e.target.value)}>
          {readable.map(c => <option key={c.bindingId} value={c.key}>{c.displayName} · {c.valueType}</option>)}</Select></label>
        <label className="text-xs">Operator <Select value={operator} onChange={e => setOperator(e.target.value as RuleOperator)}>
          {read && operatorsFor(read.valueType).map(op => <option key={op}>{op}</option>)}</Select></label>
        <label className="text-xs">Comparison value <ValueInput capability={read} value={expected} onChange={setExpected} /></label>
      </div>
      <label className="block text-xs">Action target device <Select value={targetId} onChange={e => setTargetId(e.target.value)}>
        <option value="">Select active device</option>{devices.map(d => <option key={d.id} value={d.id}>{d.name} ({d.mode ?? 'device'})</option>)}</Select></label>
      {devices.find(d => d.id === targetId)?.mode === 'REAL' && <p className="text-xs text-danger">Backend currently rejects actuation of REAL devices.</p>}
      <div className="grid sm:grid-cols-3 gap-2">
        <label className="text-xs">Writable capability <Select value={writeKey} onChange={e => setWriteKey(e.target.value)}>
          {writable.map(c => <option key={c.bindingId} value={c.key}>{c.displayName} · {c.valueType}</option>)}</Select></label>
        <label className="text-xs">Command <Select value={command} onChange={e => setCommand(e.target.value)}>
          {write?.supportedCommands.map(c => <option key={c}>{c}</option>)}</Select></label>
        <label className="text-xs">Action value {write?.valueType === 'BOOLEAN' ?
          <Select value={actionValue} onChange={e => setActionValue(e.target.value)}><option>true</option><option>false</option></Select> :
          write?.valueType === 'ENUM' ? <Select value={actionValue} onChange={e => setActionValue(e.target.value)}>
            {write.enumValues.map(v => <option key={v}>{v}</option>)}</Select> :
            <Input type={write?.valueType === 'NUMBER' ? 'number' : 'text'} value={actionValue}
              placeholder={write?.valueType === 'OBJECT' ? '{"channel":1,"state":true}' : ''}
              onChange={e => setActionValue(e.target.value)} />}</label>
      </div>
      <Button disabled={busy || !name || !triggerId || !targetId || !read || !write || !command || !expected ||
        devices.find(d => d.id === targetId)?.mode === 'REAL'} onClick={() => void submit()}>
        {busy ? 'Creating…' : 'Create generic Rule'}</Button>
      {error && <p role="alert" className="text-danger text-sm">{error}</p>}
      {result && <p role="status" className="text-sm">{result}</p>}
    </div>
  </FrameworkLayout>;
}
