import { useEffect, useState } from 'react';
import { intelligenceApi, type ModelStatus } from '../api/intelligenceApi';
import { GlassPanel } from '../components/foundation/GlassPanel';

export default function ModelStatusPanel({ refreshKey }: { refreshKey: number }) {
  const [value, setValue] = useState<ModelStatus | null>(null);
  const [error, setError] = useState(false);
  const [loading, setLoading] = useState(true);
  const [refresh, setRefresh] = useState(0);
  useEffect(() => {
    let current = true;
    setLoading(true);
    intelligenceApi.modelStatus().then((status) => {
      if (current) { setValue(status); setError(false); }
    }).catch(() => { if (current) { setError(true); setValue(null); } })
      .finally(() => { if (current) setLoading(false); });
    return () => { current = false; };
  }, [refreshKey, refresh]);
  return <GlassPanel className="p-4 text-sm space-y-2">
    <div className="flex justify-between gap-3"><h2 className="font-semibold">Model connection</h2>
      <button type="button" className="underline" disabled={loading} onClick={() => setRefresh((n) => n + 1)}>Refresh model status</button></div>
    {loading && <p>Checking model configuration (no inference)...</p>}
    {error && <p role="status">Model status unavailable. This does not prove a model is connected.</p>}
    {value && <>
      <p>Mode: {value.mode === 'DETERMINISTIC_ONLY' ? 'Deterministic-only (no model)' : value.mode}</p>
      {value.gateway.configured && <p>Provider: {value.gateway.identity.providerId}; model: {value.gateway.identity.modelId}.
        Local evidence consent: {value.gateway.evidenceConsent ? 'granted' : 'not granted'}.</p>}
      {value.gateway.configured && <p className="text-xs text-foundation-muted-fg">Model loading needs free host RAM and GPU memory.
        Free memory before submitting questions under resource pressure. A guarded request may take up to 60 seconds.</p>}
      {value.ollama && <>
        <p>Ollama: {value.ollama.inspection.reachable ? 'reachable' : 'unreachable or unverified'};
          selected model: {value.ollama.inspection.installed ? 'installed' : 'missing or unverified'};
          loaded: {value.ollama.loaded === null ? 'unknown' : value.ollama.loaded ? 'yes' : 'no'}.</p>
        {value.ollama.inspection.remotelyHosted && <p>Remote model refused. Local models only.</p>}
        {value.ollama.inspection.problem && <p>{value.ollama.inspection.problem}</p>}
        {value.ollama.inspection.digest && <p className="break-all text-xs">Model digest: {value.ollama.inspection.digest}</p>}
        <p className="text-xs">Probe checked: {value.ollama.checkedAt}. Metadata cached for 15 seconds.</p>
      </>}
      <p>Provider attempts: {value.gateway.providerAttempts}; returned responses: {value.gateway.providerResponses} (since backend startup).</p>
      <p>Last gateway outcome: {value.gateway.lastOutcome
        ? `${value.gateway.lastOutcome.outcome}, ${value.gateway.lastOutcome.durationMillis} ms, ${value.gateway.lastOutcome.finishedAt}`
        : 'No model request recorded'}.</p>
      <p className="text-xs text-foundation-muted-fg">Configured does not mean used. SCHEMA_ACCEPTED is not proof of evidence-selection acceptance.
        Each answer below states whether validated model ordering was used. Status checks never load a model.</p>
    </>}
  </GlassPanel>;
}
