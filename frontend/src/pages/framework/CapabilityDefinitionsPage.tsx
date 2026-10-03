import { useEffect, useMemo, useState } from 'react';
import { capabilityDefinitionApi } from '../../api/capabilityDefinitionApi';
import type { CapabilityDefinition, CapabilityDefinitionCreate, Direction, ValueType } from '../../api/frameworkTypes';
import { Button } from '../../components/foundation/Button';
import { Input, Select } from '../../components/foundation/Input';
import { FrameworkLayout } from './FrameworkLayout';

const empty: CapabilityDefinitionCreate = {
  key: '', displayName: '', description: null, direction: 'READ', valueType: 'NUMBER',
  unit: null, allowedUnits: [], minimum: null, maximum: null, enumValues: [],
  supportedCommands: [], semanticCategory: null, objectConstraints: {}, metadata: {},
};

const csv = (value: string) => value.split(',').map(s => s.trim()).filter(Boolean);

export default function CapabilityDefinitionsPage() {
  const [items, setItems] = useState<CapabilityDefinition[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [query, setQuery] = useState('');
  const [directionFilter, setDirectionFilter] = useState('');
  const [selected, setSelected] = useState<CapabilityDefinition | null>(null);
  const [draft, setDraft] = useState<CapabilityDefinitionCreate>(empty);
  const [objectText, setObjectText] = useState('{}');
  const [saving, setSaving] = useState(false);

  const reload = async () => { setItems(await capabilityDefinitionApi.all()); };
  useEffect(() => { void reload().catch(e => setError(String(e))).finally(() => setLoading(false)); }, []);
  const filtered = useMemo(() => items.filter(item =>
    (!directionFilter || item.direction === directionFilter) &&
    `${item.key} ${item.displayName}`.toLowerCase().includes(query.toLowerCase())), [items, query, directionFilter]);
  const edit = (item: CapabilityDefinition | null) => {
    setSelected(item); setDraft(item ? {
      key: item.key, displayName: item.displayName, description: item.description,
      direction: item.direction, valueType: item.valueType, unit: item.unit,
      allowedUnits: item.allowedUnits, minimum: item.minimum, maximum: item.maximum,
      enumValues: item.enumValues, supportedCommands: item.supportedCommands,
      semanticCategory: item.semanticCategory, objectConstraints: item.objectConstraints,
      metadata: item.metadata,
    } : { ...empty });
    setObjectText(JSON.stringify(item?.objectConstraints ?? {}, null, 2)); setError('');
  };
  const save = async () => {
    setSaving(true); setError('');
    try {
      const data = { ...draft, objectConstraints: JSON.parse(objectText) as Record<string, unknown>,
        allowedUnits: draft.unit && !draft.allowedUnits.includes(draft.unit)
          ? [...draft.allowedUnits, draft.unit] : draft.allowedUnits,
        minimum: draft.valueType === 'NUMBER' ? draft.minimum : null,
        maximum: draft.valueType === 'NUMBER' ? draft.maximum : null,
        enumValues: draft.valueType === 'ENUM' ? draft.enumValues : [],
        supportedCommands: draft.direction === 'WRITE' ? draft.supportedCommands : [],
      };
      if (selected) await capabilityDefinitionApi.update(selected.id, data);
      else await capabilityDefinitionApi.create(data);
      await reload(); edit(null);
    } catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setSaving(false); }
  };
  return <FrameworkLayout title="Capability Definitions">
    <div className="grid lg:grid-cols-[minmax(240px,1fr)_minmax(320px,1.3fr)] gap-6">
      <section className="space-y-3">
        <div className="flex gap-2"><Input aria-label="Search capabilities" placeholder="Search capabilities" value={query} onChange={e => setQuery(e.target.value)} />
          <Select aria-label="Filter direction" value={directionFilter} onChange={e => setDirectionFilter(e.target.value)}>
            <option value="">All</option><option value="READ">READ</option><option value="WRITE">WRITE</option></Select></div>
        {loading ? <p>Loading capabilities…</p> : filtered.length === 0 ? <p>No definitions found.</p> :
          <div className="space-y-2">{filtered.map(item => <button key={item.id} onClick={() => edit(item)}
            className="w-full text-left rounded-lg border border-foundation-border p-3 hover:border-[var(--accent)]">
            <span className="font-medium">{item.displayName}</span><span className="ml-2 text-xs text-foundation-muted-fg">{item.key}</span>
            <div className="text-xs text-foundation-muted-fg">{item.direction} · {item.valueType}</div></button>)}</div>}
        <Button variant="outline" onClick={() => edit(null)}>New definition</Button>
      </section>
      <section className="space-y-3">
        <h2 className="font-semibold">{selected ? `Edit ${selected.key}` : 'Create capability'}</h2>
        <label className="block text-xs">Key <Input value={draft.key} disabled={!!selected} onChange={e => setDraft({ ...draft, key: e.target.value })} /></label>
        <label className="block text-xs">Display name <Input value={draft.displayName} onChange={e => setDraft({ ...draft, displayName: e.target.value })} /></label>
        <label className="block text-xs">Description <Input value={draft.description ?? ''} onChange={e => setDraft({ ...draft, description: e.target.value })} /></label>
        <div className="grid grid-cols-2 gap-2">
          <label className="text-xs">Direction <Select value={draft.direction} disabled={!!selected} onChange={e => setDraft({ ...draft, direction: e.target.value as Direction })}><option>READ</option><option>WRITE</option></Select></label>
          <label className="text-xs">Value type <Select value={draft.valueType} disabled={!!selected} onChange={e => setDraft({ ...draft, valueType: e.target.value as ValueType })}>
            {(['NUMBER','BOOLEAN','STRING','ENUM','OBJECT'] as const).map(type => <option key={type}>{type}</option>)}</Select></label>
        </div>
        <label className="block text-xs">Unit <Input value={draft.unit ?? ''} onChange={e => setDraft({ ...draft, unit: e.target.value || null })} /></label>
        <label className="block text-xs">Allowed units (comma separated) <Input value={draft.allowedUnits.join(', ')} onChange={e => setDraft({ ...draft, allowedUnits: csv(e.target.value) })} /></label>
        {draft.valueType === 'NUMBER' && <div className="grid grid-cols-2 gap-2">
          <label className="text-xs">Minimum <Input type="number" value={draft.minimum ?? ''} onChange={e => setDraft({ ...draft, minimum: e.target.value === '' ? null : Number(e.target.value) })} /></label>
          <label className="text-xs">Maximum <Input type="number" value={draft.maximum ?? ''} onChange={e => setDraft({ ...draft, maximum: e.target.value === '' ? null : Number(e.target.value) })} /></label></div>}
        {draft.valueType === 'ENUM' && <label className="block text-xs">Enum values (comma separated) <Input value={draft.enumValues.join(', ')} onChange={e => setDraft({ ...draft, enumValues: csv(e.target.value) })} /></label>}
        {draft.valueType === 'OBJECT' && <label className="block text-xs">Object constraints (JSON) <textarea className="w-full rounded-lg border border-foundation-border bg-transparent p-2 font-mono text-xs" rows={5} value={objectText} onChange={e => setObjectText(e.target.value)} /></label>}
        {draft.direction === 'WRITE' && <label className="block text-xs">Supported commands <Input value={draft.supportedCommands.join(', ')} onChange={e => setDraft({ ...draft, supportedCommands: csv(e.target.value) })} /></label>}
        {selected && <p className="text-xs text-foundation-muted-fg">Key, direction, and value type are immutable.</p>}
        {error && <p role="alert" className="text-danger text-sm">{error}</p>}
        <Button disabled={saving || !draft.key || !draft.displayName} onClick={() => void save()}>{saving ? 'Saving…' : 'Save definition'}</Button>
      </section>
    </div>
  </FrameworkLayout>;
}
