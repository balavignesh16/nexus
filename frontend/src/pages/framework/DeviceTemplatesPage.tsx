import { useEffect, useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import { capabilityDefinitionApi } from '../../api/capabilityDefinitionApi';
import { deviceTemplateApi } from '../../api/deviceTemplateApi';
import type { CapabilityDefinition, DeviceTemplate, TemplateCapability } from '../../api/frameworkTypes';
import { Button } from '../../components/foundation/Button';
import { Input, Select } from '../../components/foundation/Input';
import { FrameworkLayout } from './FrameworkLayout';

const emptyTemplate: Omit<DeviceTemplate, 'id' | 'version' | 'compositionLocked'> = { key: '', displayName: '', description: null, manufacturer: null,
  model: null, protocolType: 'MQTT' as const, enabled: true, metadata: {} };

export default function DeviceTemplatesPage() {
  const { templateId } = useParams();
  const [templates, setTemplates] = useState<DeviceTemplate[]>([]);
  const [definitions, setDefinitions] = useState<CapabilityDefinition[]>([]);
  const [selectedId, setSelectedId] = useState(templateId ?? '');
  const [members, setMembers] = useState<TemplateCapability[]>([]);
  const [draft, setDraft] = useState(emptyTemplate);
  const [capabilityId, setCapabilityId] = useState('');
  const [required, setRequired] = useState(true);
  const [unit, setUnit] = useState('');
  const [configuration, setConfiguration] = useState('{}');
  const [freshness, setFreshness] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const selected = useMemo(() => templates.find(t => t.id === selectedId), [templates, selectedId]);

  const reload = async () => {
    const [list, caps] = await Promise.all([deviceTemplateApi.all(), capabilityDefinitionApi.all()]);
    setTemplates(list); setDefinitions(caps);
  };
  useEffect(() => { void reload().catch(e => setError(String(e))); }, []);
  useEffect(() => { if (selectedId) void deviceTemplateApi.capabilities(selectedId).then(setMembers).catch(e => setError(String(e))); }, [selectedId]);
  useEffect(() => {
    if (selected) setDraft({ key: selected.key, displayName: selected.displayName,
      description: selected.description, manufacturer: selected.manufacturer, model: selected.model,
      protocolType: 'MQTT', enabled: selected.enabled, metadata: selected.metadata });
    else setDraft(emptyTemplate);
  }, [selected]);
  const saveTemplate = async () => {
    setBusy(true); setError('');
    try {
      const result = selected ? await deviceTemplateApi.update(selected.id, draft)
        : await deviceTemplateApi.create(draft);
      await reload(); setSelectedId(result.id);
    } catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  };
  const addCapability = async () => {
    if (!selectedId || !capabilityId) return;
    setBusy(true); setError('');
    try {
      await deviceTemplateApi.addCapability(selectedId, { capabilityDefinitionId: capabilityId,
        required, defaultUnit: unit || null, displayOrder: members.length + 1,
        freshnessThresholdSeconds: freshness ? Number(freshness) : null,
        configuration: JSON.parse(configuration) as Record<string, unknown> });
      setMembers(await deviceTemplateApi.capabilities(selectedId));
      setCapabilityId(''); setConfiguration('{}'); setFreshness('');
    } catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  };
  return <FrameworkLayout title="Device Templates">
    <div className="grid lg:grid-cols-[minmax(220px,1fr)_minmax(340px,1.7fr)] gap-6">
      <section className="space-y-2">
        <Button variant="outline" onClick={() => setSelectedId('')}>New template</Button>
        {templates.map(t => <button key={t.id} onClick={() => setSelectedId(t.id)}
          className="w-full rounded-lg border border-foundation-border p-3 text-left hover:border-[var(--accent)]">
          <span className="font-medium">{t.displayName}</span><span className="block text-xs text-foundation-muted-fg">{t.key} · v{t.version}{t.compositionLocked ? ' · composition locked' : ''}</span>
        </button>)}
      </section>
      <section className="space-y-4">
        <h2 className="font-semibold">{selected ? selected.displayName : 'Create template'}</h2>
        <div className="grid sm:grid-cols-2 gap-3">
          <label className="text-xs">Key <Input value={draft.key} disabled={!!selected} onChange={e => setDraft({ ...draft, key: e.target.value })} /></label>
          <label className="text-xs">Display name <Input value={draft.displayName} onChange={e => setDraft({ ...draft, displayName: e.target.value })} /></label>
          <label className="text-xs">Manufacturer <Input value={draft.manufacturer ?? ''} onChange={e => setDraft({ ...draft, manufacturer: e.target.value || null })} /></label>
          <label className="text-xs">Model <Input value={draft.model ?? ''} onChange={e => setDraft({ ...draft, model: e.target.value || null })} /></label>
        </div>
        <label className="block text-xs">Description <Input value={draft.description ?? ''} onChange={e => setDraft({ ...draft, description: e.target.value || null })} /></label>
        <label className="flex gap-2 text-xs"><input type="checkbox" checked={draft.enabled} onChange={e => setDraft({ ...draft, enabled: e.target.checked })} /> Enabled</label>
        <p className="text-xs text-foundation-muted-fg">Protocol: MQTT. Template identity and capability composition lock once a device references it.</p>
        <Button disabled={busy || !draft.key || !draft.displayName} onClick={() => void saveTemplate()}>{selected ? 'Save metadata' : 'Create template'}</Button>
        {selected && <div className="border-t border-foundation-border pt-4 space-y-3">
          <h3 className="font-medium">Capabilities {selected.compositionLocked && <span className="text-xs text-foundation-muted-fg">· locked</span>}</h3>
          {members.length === 0 ? <p className="text-sm text-foundation-muted-fg">No capabilities yet.</p> : members.map(member =>
            <div key={member.id} className="rounded-lg border border-foundation-border p-3 text-sm">
              {member.capabilityKey} <span className="text-xs text-foundation-muted-fg">· {member.required ? 'required' : 'optional'} · {member.defaultUnit ?? 'unitless'}</span>
              <div className="text-xs text-foundation-muted-fg">Freshness: {member.freshnessThresholdSeconds ?? 120}s {selected.compositionLocked ? '(read-only parent policy)' : ''}</div>
              <pre className="text-xs overflow-auto">{JSON.stringify(member.configuration)}</pre>
            </div>)}
          {!selected.compositionLocked && <div className="space-y-2">
            <Select aria-label="Add capability" value={capabilityId} onChange={e => {
              setCapabilityId(e.target.value); setUnit(definitions.find(d => d.id === e.target.value)?.unit ?? '');
            }}><option value="">Select capability</option>{definitions.filter(d => !members.some(m => m.capabilityDefinitionId === d.id)).map(d =>
              <option key={d.id} value={d.id}>{d.displayName} ({d.direction}, {d.valueType})</option>)}</Select>
            <label className="flex gap-2 text-xs"><input type="checkbox" checked={required} onChange={e => setRequired(e.target.checked)} /> Required</label>
            <div className="grid sm:grid-cols-2 gap-2"><label className="text-xs">Default unit <Input value={unit} onChange={e => setUnit(e.target.value)} /></label>
              <label className="text-xs">Freshness seconds (policy) <Input type="number" min={1} value={freshness} onChange={e => setFreshness(e.target.value)} /></label></div>
            <label className="block text-xs">Constraint narrowing (JSON) <textarea className="w-full rounded-lg border border-foundation-border bg-transparent p-2 font-mono text-xs" value={configuration} onChange={e => setConfiguration(e.target.value)} /></label>
            <Button disabled={busy || !capabilityId} onClick={() => void addCapability()}>Add capability</Button>
          </div>}
        </div>}
        {error && <p role="alert" className="text-danger text-sm">{error}</p>}
      </section>
    </div>
  </FrameworkLayout>;
}
