import { useEffect, useState } from 'react';
import { deviceContractApi } from '../../../../api/deviceContractApi';
import { readbackMappingApi, type ReadbackMapping, type ReadbackPolicy } from '../../../../api/readbackMappingApi';
import type { ContractCapability } from '../../../../api/frameworkTypes';

export function ReadbackMappingPanel({ deviceId }: { deviceId: string }) {
  const [capabilities, setCapabilities] = useState<ContractCapability[]>([]);
  const [mappings, setMappings] = useState<ReadbackMapping[]>([]);
  const [error, setError] = useState('');
  const [writeId, setWriteId] = useState('');
  const [readId, setReadId] = useState('');
  const [subtarget, setSubtarget] = useState('');
  const [selector, setSelector] = useState('IDENTITY');
  const [path, setPath] = useState('');
  const [comparator, setComparator] = useState('EXACT');
  const [tolerance, setTolerance] = useState('0');
  const [timeout, setTimeoutValue] = useState('10');
  const [qualification, setQualification] = useState<'NONE' | 'COMMAND_ECHO'>('COMMAND_ECHO');

  useEffect(() => {
    let active = true;
    void Promise.all([deviceContractApi.get(deviceId), readbackMappingApi.all(deviceId)])
      .then(([contract, rows]) => { if (active) { setCapabilities(contract.capabilities); setMappings(rows); setError(''); } })
      .catch(e => { if (active) setError(e instanceof Error ? e.message : String(e)); });
    return () => { active = false; };
  }, [deviceId]);

  const create = async () => {
    try {
      const policy: ReadbackPolicy = { writeId, readId, subtarget, selector,
        selectorPath: selector === 'OBJECT_FIELD' ? path : null, comparator,
        tolerance: comparator === 'NUMERIC_TOLERANCE' ? Number(tolerance) : null,
        qualification, timeoutSeconds: Number(timeout) };
      await readbackMappingApi.create(deviceId, policy);
      setMappings(await readbackMappingApi.all(deviceId));setError('');
    } catch (e) { setError(e instanceof Error ? e.message : String(e)); }
  };
  const disable = async (id: string) => {
    try { await readbackMappingApi.disable(deviceId,id);setMappings(await readbackMappingApi.all(deviceId));setError(''); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
  };
  const writes = capabilities.filter(c => c.direction === 'WRITE' && c.enabled);
  const reads = capabilities.filter(c => c.direction === 'READ' && c.enabled);
  const label = (id: string) => capabilities.find(c => c.bindingId === id)?.key ?? id;

  return <section className="rounded-[var(--radius-panel)] border border-foundation-border p-4 text-xs text-foundation-fg">
    <h4 className="font-semibold mb-2">Explicit readback mappings</h4>
    <p className="text-foundation-muted-fg mb-3">Only mapped command echo from a GOOD readback can verify current intent. Current physical ESP telemetry has no command echo.</p>
    {mappings.map(m => <div key={m.id} className="flex flex-wrap items-center gap-2 mb-2">
      <span>{label(m.writeBindingId)}{m.writeSubtargetKey ? ` / ${m.writeSubtargetKey}` : ''} → {label(m.readBindingId)} · {m.qualificationMode} · {m.enabled ? 'Enabled' : 'Disabled'}</span>
      {m.enabled && <button type="button" className="underline" onClick={() => void disable(m.id)}>Disable</button>}
    </div>)}
    {writes.length > 0 && reads.length > 0 && <div className="flex flex-wrap items-center gap-2 mt-3">
      <select aria-label="Write binding" value={writeId} onChange={e => setWriteId(e.target.value)}><option value="">WRITE binding</option>{writes.map(c => <option key={c.bindingId} value={c.bindingId}>{c.key}</option>)}</select>
      <input aria-label="Write subtarget" placeholder="channel:2 if applicable" value={subtarget} onChange={e => setSubtarget(e.target.value)} />
      <select aria-label="Read binding" value={readId} onChange={e => setReadId(e.target.value)}><option value="">READ binding</option>{reads.map(c => <option key={c.bindingId} value={c.bindingId}>{c.key}</option>)}</select>
      <select aria-label="Readback selector" value={selector} onChange={e => setSelector(e.target.value)}><option value="IDENTITY">Identity</option><option value="OBJECT_FIELD">Object field</option></select>
      {selector === 'OBJECT_FIELD' && <input aria-label="Object field" placeholder="field name" value={path} onChange={e => setPath(e.target.value)} />}
      <select aria-label="Comparator" value={comparator} onChange={e => setComparator(e.target.value)}><option value="EXACT">Exact</option><option value="NUMERIC_TOLERANCE">Numeric tolerance</option></select>
      {comparator === 'NUMERIC_TOLERANCE' && <input aria-label="Tolerance" type="number" min="0" step="any" value={tolerance} onChange={e => setTolerance(e.target.value)} />}
      <select aria-label="Qualification" value={qualification} onChange={e => setQualification(e.target.value as 'NONE' | 'COMMAND_ECHO')}><option value="COMMAND_ECHO">Command echo</option><option value="NONE">None</option></select>
      <input aria-label="Verification timeout seconds" type="number" min="1" value={timeout} onChange={e => setTimeoutValue(e.target.value)} />
      <button type="button" className="underline" disabled={!writeId || !readId} onClick={() => void create()}>Add mapping</button>
    </div>}
    {error && <p role="alert" className="text-danger mt-2">{error}</p>}
  </section>;
}
