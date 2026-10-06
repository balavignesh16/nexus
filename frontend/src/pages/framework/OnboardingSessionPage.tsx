import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { deviceBindingApi } from '../../api/deviceBindingApi';
import { deviceContractApi } from '../../api/deviceContractApi';
import { onboardingApi } from '../../api/onboardingApi';
import { twinApi, type DigitalTwinResponse } from '../../api/twinApi';
import type { DeviceBinding, DeviceContract, OnboardingSession } from '../../api/frameworkTypes';
import { Button } from '../../components/foundation/Button';
import { Input } from '../../components/foundation/Input';
import { FrameworkLayout } from './FrameworkLayout';
import { ContractViewer } from './ContractViewer';

const steps = ['WAITING_FOR_STATUS', 'WAITING_FOR_TELEMETRY', 'OPERATIONAL'];

function TypedValue({ value, valueType, unit }: { value: unknown; valueType?: string | null; unit?: string | null }) {
  if (value === null || value === undefined) return <span>Not reported</span>;
  if (valueType === 'BOOLEAN') return <span>{value === true ? 'On / true' : 'Off / false'}</span>;
  if (valueType === 'OBJECT') return <pre className="text-xs overflow-auto">{JSON.stringify(value, null, 2)}</pre>;
  return <span>{String(value)}{valueType === 'NUMBER' && unit ? ` ${unit}` : ''}</span>;
}

function BindingEditor({ binding, onSaved }: { binding: DeviceBinding; onSaved: () => Promise<void> }) {
  const [enabled, setEnabled] = useState(binding.enabled);
  const [unit, setUnit] = useState(binding.unit ?? '');
  const [configuration, setConfiguration] = useState(JSON.stringify(binding.configuration ?? {}, null, 2));
  const [external, setExternal] = useState(JSON.stringify(binding.externalIdentifiers ?? {}, null, 2));
  const [freshness, setFreshness] = useState(binding.freshnessThresholdSeconds?.toString() ?? '');
  const [error, setError] = useState('');
  const save = async () => {
    setError('');
    try {
      await deviceBindingApi.update(binding.deviceId, binding.id, { enabled, unit: unit || null,
        configuration: JSON.parse(configuration) as Record<string, unknown>,
        externalIdentifiers: JSON.parse(external) as Record<string, string>,
        freshnessThresholdSeconds: freshness ? Number(freshness) : null });
      await onSaved();
    } catch (e) { setError(e instanceof Error ? e.message : String(e)); }
  };
  return <details className="rounded-lg border border-foundation-border p-3 text-xs"><summary className="cursor-pointer font-medium">
    {binding.capabilityKey} · {binding.enabled ? 'enabled' : 'disabled'}</summary>
    <div className="mt-3 space-y-2">
      <label className="flex gap-2"><input type="checkbox" checked={enabled} onChange={e => setEnabled(e.target.checked)} /> Enabled</label>
      <label className="block">Unit <Input value={unit} onChange={e => setUnit(e.target.value)} /></label>
      <label className="block">Effective constraints (JSON) <textarea className="w-full rounded-lg border border-foundation-border bg-transparent p-2 font-mono" rows={3} value={configuration} onChange={e => setConfiguration(e.target.value)} /></label>
      <label className="block">External identifiers (JSON) <textarea className="w-full rounded-lg border border-foundation-border bg-transparent p-2 font-mono" rows={3} value={external} onChange={e => setExternal(e.target.value)} /></label>
      <label className="block">Freshness override seconds <Input type="number" min={1} value={freshness}
        placeholder="Inherit template policy" onChange={e => setFreshness(e.target.value)} /></label>
      <p className="text-foundation-muted-fg">The server rejects expansion, ambiguity, and changes that would break active Rules.</p>
      {error && <p role="alert" className="text-danger">{error}</p>}
      <Button size="sm" onClick={() => void save()}>Save binding</Button>
    </div>
  </details>;
}

export default function OnboardingSessionPage() {
  const { sessionId } = useParams();
  const [session, setSession] = useState<OnboardingSession | null>(null);
  const [contract, setContract] = useState<DeviceContract | null>(null);
  const [bindings, setBindings] = useState<DeviceBinding[]>([]);
  const [twin, setTwin] = useState<DigitalTwinResponse | null>(null);
  const [error, setError] = useState('');

  const refresh = async () => {
    if (!sessionId) return;
    const current = await onboardingApi.get(sessionId);
    setSession(current);
    const [nextContract, nextBindings] = await Promise.all([
      deviceContractApi.get(current.deviceId), deviceBindingApi.all(current.deviceId)]);
    setContract(nextContract); setBindings(nextBindings);
    try { setTwin(await twinApi.getDeviceTwin(current.deviceId)); } catch { setTwin(null); }
  };
  useEffect(() => {
    void refresh().catch(e => setError(String(e)));
    const timer = window.setInterval(() => void refresh().catch(e => setError(String(e))), 3000);
    return () => window.clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId]);
  const action = async (kind: 'retry' | 'disable') => {
    if (!sessionId) return;
    setError('');
    try { if (kind === 'retry') await onboardingApi.retry(sessionId); else await onboardingApi.disable(sessionId);
      await refresh(); } catch (e) { setError(e instanceof Error ? e.message : String(e)); }
  };

  return <FrameworkLayout title="Onboarding Session">
    {!session ? <p>Loading session from backend…</p> : <div className="space-y-6">
      <div><p className="font-mono text-xs">{session.deviceId}</p><h2 className="font-semibold">{session.state}</h2>
        <p className="text-xs text-foundation-muted-fg">Updated {session.updatedAt}</p></div>
      <div className="flex flex-wrap gap-2">{steps.map((state, index) => <div key={state}
        className="rounded-full border border-foundation-border px-3 py-1 text-xs"
        style={steps.indexOf(session.state) >= index ? { backgroundColor: 'var(--accent)', color: 'var(--accent-foreground)' } : undefined}>{state}</div>)}</div>
      {session.failureCode && <div role="alert" className="rounded-lg border border-danger p-3 text-sm">
        <strong>{session.failureCode}</strong><p>{session.failureMessage}</p></div>}
      <div className="flex gap-2">{(session.state === 'STATUS_TIMEOUT' || session.state === 'TELEMETRY_REJECTED') &&
        <Button size="sm" onClick={() => void action('retry')}>Retry</Button>}
        {session.state !== 'OPERATIONAL' && session.state !== 'DISABLED' &&
          <Button variant="destructive" size="sm" onClick={() => void action('disable')}>Disable</Button>}
        <Link to={`/devices/${session.deviceId}/contract`} className="text-sm underline">Device contract</Link>
        <Link to={`/framework/rules?deviceId=${session.deviceId}`} className="text-sm underline">Create generic Rule</Link></div>
      {contract && <ContractViewer contract={contract} />}
      <section className="space-y-2"><h2 className="font-semibold">Bindings</h2>
        {bindings.map(binding => <BindingEditor key={`${binding.id}:${JSON.stringify(binding.configuration)}`} binding={binding}
          onSaved={refresh} />)}</section>
      <section className="space-y-2"><h2 className="font-semibold">Typed Twin</h2>
        {!twin ? <p className="text-sm text-foundation-muted-fg">No reported Twin state yet.</p> :
          Object.entries(twin.reportedState).map(([key, state]) => <div key={key} className="rounded-lg border border-foundation-border p-3 text-sm">
            <strong>{state.capabilityKey ?? key}</strong><span className="ml-2 text-xs text-foundation-muted-fg">{state.valueType ?? 'legacy numeric'} · {state.resolutionSource ?? 'legacy'}</span>
            <div><TypedValue value={state.typedValue ?? state.value} valueType={state.valueType} unit={state.unit} /></div>
          </div>)}</section>
    </div>}
    {error && <p role="alert" className="text-danger text-sm">{error}</p>}
  </FrameworkLayout>;
}
