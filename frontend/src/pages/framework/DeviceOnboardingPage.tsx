import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { buildingApi } from '../../api/buildingApi';
import { capabilityDefinitionApi } from '../../api/capabilityDefinitionApi';
import { deviceTemplateApi } from '../../api/deviceTemplateApi';
import { onboardingApi } from '../../api/onboardingApi';
import { siteApi } from '../../api/siteApi';
import { spaceApi } from '../../api/spaceApi';
import type { BindingOverride, CapabilityDefinition, DeviceTemplate, OnboardingSession, TemplateCapability } from '../../api/frameworkTypes';
import type { Site, Building, Space } from '../../types/models';
import { Button } from '../../components/foundation/Button';
import { Input, Select } from '../../components/foundation/Input';
import { FrameworkLayout } from './FrameworkLayout';

export default function DeviceOnboardingPage() {
  const navigate = useNavigate();
  const [sessions, setSessions] = useState<OnboardingSession[]>([]);
  const [templates, setTemplates] = useState<DeviceTemplate[]>([]);
  const [definitions, setDefinitions] = useState<CapabilityDefinition[]>([]);
  const [sites, setSites] = useState<Site[]>([]);
  const [buildings, setBuildings] = useState<Building[]>([]);
  const [spaces, setSpaces] = useState<Space[]>([]);
  const [siteId, setSiteId] = useState('');
  const [buildingId, setBuildingId] = useState('');
  const [templateId, setTemplateId] = useState('');
  const [members, setMembers] = useState<TemplateCapability[]>([]);
  const [spaceId, setSpaceId] = useState('');
  const [name, setName] = useState('');
  const [serialNumber, setSerialNumber] = useState('');
  const [mode, setMode] = useState<'SIMULATED' | 'REAL'>('SIMULATED');
  const [overrides, setOverrides] = useState<Record<string, BindingOverride>>({});
  const [step, setStep] = useState(0);
  const [requestKey, setRequestKey] = useState(() => crypto.randomUUID());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const selectedTemplate = templates.find(t => t.id === templateId);
  const definition = (key: string) => definitions.find(d => d.key === key);
  const setOverride = (key: string, change: BindingOverride) =>
    setOverrides(current => ({ ...current, [key]: { ...current[key], ...change } }));
  const setConfig = (key: string, field: string, value: unknown) =>
    setOverrides(current => ({ ...current, [key]: { ...current[key],
      configuration: { ...current[key]?.configuration, [field]: value } } }));
  const setNumericConfig = (key: string, field: string, raw: string) =>
    setOverrides(current => {
      const configuration = { ...current[key]?.configuration };
      if (raw === '') delete configuration[field];
      else configuration[field] = Number(raw);
      return { ...current, [key]: { ...current[key], configuration } };
    });

  useEffect(() => {
    void Promise.all([onboardingApi.all(), deviceTemplateApi.all(), capabilityDefinitionApi.all(), siteApi.getSites()])
      .then(([s, t, d, locations]) => { setSessions(s); setTemplates(t); setDefinitions(d); setSites(locations); })
      .catch(e => setError(String(e)));
  }, []);
  useEffect(() => { if (siteId) void buildingApi.getBuildingsBySiteId(siteId).then(setBuildings).catch(e => setError(String(e))); }, [siteId]);
  useEffect(() => { if (buildingId) void spaceApi.getSpacesByBuildingId(buildingId).then(setSpaces).catch(e => setError(String(e))); }, [buildingId]);
  useEffect(() => { if (templateId) void deviceTemplateApi.capabilities(templateId).then(setMembers).catch(e => setError(String(e))); }, [templateId]);

  const provision = async () => {
    setBusy(true); setError('');
    try {
      const result = await onboardingApi.provision({ requestKey, templateId, spaceId, name, serialNumber, mode, bindings: overrides });
      setRequestKey(crypto.randomUUID());
      navigate(`/framework/onboarding/${result.id}`);
    } catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  };

  return <FrameworkLayout title="Device Onboarding">
    <div className="grid lg:grid-cols-[minmax(220px,1fr)_minmax(360px,1.7fr)] gap-6">
      <section className="space-y-2">
        <h2 className="font-semibold">Sessions from backend</h2>
        {sessions.length === 0 ? <p className="text-sm text-foundation-muted-fg">No onboarding sessions yet.</p> :
          sessions.map(session => <Link key={session.id} to={`/framework/onboarding/${session.id}`}
            className="block rounded-lg border border-foundation-border p-3 hover:border-[var(--accent)]">
            <span className="font-mono text-xs">{session.deviceId}</span>
            <span className="block text-xs text-foundation-muted-fg">{session.state}</span></Link>)}
      </section>
      <section className="space-y-4">
        <h2 className="font-semibold">Provision a device</h2>
        <div className="flex flex-wrap gap-2 text-xs">{['Template', 'Identity & location', 'Binding overrides', 'Review'].map((label, i) =>
          <span key={label} className="rounded-full border border-foundation-border px-3 py-1"
            style={step === i ? { backgroundColor: 'var(--accent)', color: 'var(--accent-foreground)' } : undefined}>{i + 1}. {label}</span>)}</div>
        {step === 0 && <div className="space-y-3">
          <label className="block text-xs">Enabled MQTT template <Select value={templateId} onChange={e => { setTemplateId(e.target.value); setOverrides({}); }}>
            <option value="">Select template</option>{templates.filter(t => t.enabled).map(t =>
              <option key={t.id} value={t.id}>{t.displayName}</option>)}</Select></label>
          {selectedTemplate && <p className="text-sm text-foundation-muted-fg">{selectedTemplate.description}</p>}
          <Button disabled={!templateId || members.length === 0} onClick={() => setStep(1)}>Next</Button>
        </div>}
        {step === 1 && <div className="space-y-3">
          <p className="text-sm">Assign this device to a space in your chosen building. Need a location? <Link className="underline" to="/infrastructure" target="_blank" rel="noreferrer">Create site, building or room</Link>, then refresh this page before starting provisioning.</p>
          <label className="block text-xs">Site <Select aria-label="Site" value={siteId} onChange={e => { setSiteId(e.target.value); setBuildingId(''); setSpaceId(''); setBuildings([]); setSpaces([]); }}>
            <option value="">Select site</option>{sites.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}</Select></label>
          <label className="block text-xs">Building <Select aria-label="Building" value={buildingId} onChange={e => { setBuildingId(e.target.value); setSpaceId(''); setSpaces([]); }}>
            <option value="">Select building</option>{buildings.map(b => <option key={b.id} value={b.id}>{b.name}</option>)}</Select></label>
          <label className="block text-xs">Space <Select aria-label="Space" value={spaceId} onChange={e => setSpaceId(e.target.value)}>
            <option value="">Select space</option>{spaces.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}</Select></label>
          <label className="block text-xs">Name <Input value={name} onChange={e => setName(e.target.value)} /></label>
          <label className="block text-xs">Serial number <Input value={serialNumber} onChange={e => setSerialNumber(e.target.value)} /></label>
          <label className="block text-xs">Mode <Select value={mode} onChange={e => setMode(e.target.value as 'SIMULATED' | 'REAL')}>
            <option value="SIMULATED">SIMULATED</option><option value="REAL">REAL</option></Select></label>
          {mode === 'REAL' && <p className="text-xs text-foundation-muted-fg">Backend currently rejects manual/rule actuation for REAL devices.</p>}
          <div className="flex gap-2"><Button variant="outline" onClick={() => setStep(0)}>Back</Button>
            <Button disabled={!spaceId || !name || !serialNumber} onClick={() => setStep(2)}>Next</Button></div>
        </div>}
        {step === 2 && <div className="space-y-3">
          {members.map(member => { const cap = definition(member.capabilityKey); const current = overrides[member.capabilityKey] ?? {};
            const config = current.configuration ?? {};
            return <div key={member.id} className="rounded-lg border border-foundation-border p-3 space-y-2 text-xs">
              <div className="font-medium">{cap?.displayName ?? member.capabilityKey} · {cap?.direction} · {cap?.valueType}</div>
              {!member.required && <label className="flex gap-2"><input type="checkbox" checked={current.enabled ?? true}
                onChange={e => setOverride(member.capabilityKey, { enabled: e.target.checked })} /> Enabled</label>}
              {cap && cap.allowedUnits.length > 1 && <label>Unit <Select value={current.unit ?? member.defaultUnit ?? cap.unit ?? ''}
                onChange={e => setOverride(member.capabilityKey, { unit: e.target.value })}>
                {cap.allowedUnits.map(u => <option key={u}>{u}</option>)}</Select></label>}
              {cap?.valueType === 'NUMBER' && <div className="grid grid-cols-2 gap-2">
                <label>Minimum <Input type="number" placeholder={String(member.configuration.minimum ?? cap.minimum ?? '')}
                  value={config.minimum === undefined ? '' : String(config.minimum)} onChange={e => setNumericConfig(member.capabilityKey, 'minimum', e.target.value)} /></label>
                <label>Maximum <Input type="number" placeholder={String(member.configuration.maximum ?? cap.maximum ?? '')}
                  value={config.maximum === undefined ? '' : String(config.maximum)} onChange={e => setNumericConfig(member.capabilityKey, 'maximum', e.target.value)} /></label></div>}
              {cap?.direction === 'READ' && <label>Freshness override seconds (inherit {member.freshnessThresholdSeconds ?? 120})
                <Input type="number" min={1} max={member.freshnessThresholdSeconds ?? 120}
                  value={current.freshnessThresholdSeconds ?? ''} placeholder="Inherit"
                  onChange={e => setOverride(member.capabilityKey, { freshnessThresholdSeconds: e.target.value ? Number(e.target.value) : null })} />
              </label>}
              {member.capabilityKey === 'relay' && <label>Allowed channels (subset) <Input
                placeholder={String(member.configuration.channels ?? '')}
                value={Array.isArray(config.channels) ? config.channels.join(',') : ''}
                onChange={e => setConfig(member.capabilityKey, 'channels', e.target.value.split(',').map(Number).filter(Number.isInteger))} /></label>}
              {cap?.valueType === 'ENUM' && <fieldset><legend>Allowed values (subset)</legend>
                {cap.enumValues.map(value => <label key={value} className="mr-3 inline-flex gap-1"><input type="checkbox"
                  checked={(Array.isArray(config.enumValues) ? config.enumValues : cap.enumValues).includes(value)}
                  onChange={e => { const previous = Array.isArray(config.enumValues) ? config.enumValues as string[] : cap.enumValues;
                    setConfig(member.capabilityKey, 'enumValues', e.target.checked ? [...previous, value] : previous.filter(v => v !== value)); }} />{value}</label>)}</fieldset>}
              <label>External {cap?.direction === 'READ' ? 'telemetry' : 'command'} identifier <Input
                value={String(current.externalIdentifiers?.[cap?.direction === 'READ' ? 'telemetryKey' : 'commandCapability'] ?? '')}
                placeholder={cap?.direction === 'READ' ? member.capabilityKey : member.capabilityKey.toUpperCase()}
                onChange={e => setOverride(member.capabilityKey, { externalIdentifiers: e.target.value ?
                  { [cap?.direction === 'READ' ? 'telemetryKey' : 'commandCapability']: e.target.value } : undefined })} /></label>
            </div>; })}
          <div className="flex gap-2"><Button variant="outline" onClick={() => setStep(1)}>Back</Button><Button onClick={() => setStep(3)}>Review</Button></div>
        </div>}
        {step === 3 && <div className="space-y-3 text-sm">
          <p>Template: {selectedTemplate?.displayName}</p><p>Device: {name} · {serialNumber} · {mode}</p>
          <p>Site: {sites.find(s => s.id === siteId)?.name} · Building: {buildings.find(b => b.id === buildingId)?.name} · Space: {spaces.find(s => s.id === spaceId)?.name}</p>
          <pre className="rounded-lg border border-foundation-border p-3 overflow-auto text-xs">{JSON.stringify(overrides, null, 2)}</pre>
          <p className="text-xs text-foundation-muted-fg">The backend validates and commits the Device, bindings, session, and contract version together. Status and telemetry progress comes from the server.</p>
          <div className="flex gap-2"><Button variant="outline" onClick={() => setStep(2)}>Back</Button>
            <Button disabled={busy} onClick={() => void provision()}>{busy ? 'Provisioning…' : 'Confirm provisioning'}</Button></div>
        </div>}
        {error && <p role="alert" className="text-danger text-sm">{error}</p>}
      </section>
    </div>
  </FrameworkLayout>;
}
