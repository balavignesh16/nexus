import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { Bot, Send } from 'lucide-react';
import ModelStatusPanel from './ModelStatusPanel';
import { GlassPanel } from '../components/foundation/GlassPanel';
import { ApiError } from '../api/apiClient';
import { intelligenceApi, type AssistantAnswer, type DraftView, type Json, type StoredDevice } from '../api/intelligenceApi';

export type IntelligenceScreen = 'assistant' | 'insights' | 'trends' | 'drafts' | 'energy';
const screens: { key: IntelligenceScreen; title: string; path: string }[] = [
  { key: 'assistant', title: 'NEXUS Assistant', path: '/assistant' },
  { key: 'insights', title: 'AI Insights', path: '/intelligence' },
  { key: 'trends', title: 'Trends', path: '/trends' },
  { key: 'drafts', title: 'AI Rule Drafts', path: '/ai/drafts' },
  { key: 'energy', title: 'Energy Intelligence', path: '/energy' },
];
const field = 'rounded-xl border border-foundation-border bg-foundation-surface px-3 py-2 text-sm text-foundation-fg min-w-0';
const button = 'rounded-xl bg-[var(--accent)] px-4 py-2 text-sm font-semibold text-[var(--accent-foreground)] disabled:opacity-50 disabled:cursor-not-allowed';
const label = (key: string) => key.replace(/([a-z])([A-Z])/g, '$1 $2').replaceAll('_', ' ');

/** React text only. Device/model-looking strings are never executable HTML or Markdown. */
export function EvidenceView({ value }: { value: Json }) {
  if (value === null) return <span className="text-foundation-muted-fg">Not recorded</span>;
  if (typeof value !== 'object') return <span className="whitespace-pre-wrap break-words">{String(value)}</span>;
  if (Array.isArray(value)) return value.length === 0 ? <p>No records in this response.</p> : <div className="space-y-3">
    {value.slice(0, 50).map((item, i) => <div key={i} className="rounded-lg border border-foundation-border p-3"><EvidenceView value={item} /></div>)}
    {value.length > 50 && <p>Showing first 50 of {value.length} returned records.</p>}
  </div>;
  return <dl className="grid gap-2 text-sm">{Object.entries(value).map(([key, item]) => <div key={key} className="min-w-0">
    <dt className="text-xs font-semibold text-foundation-muted-fg capitalize">{label(key)}</dt>
    <dd className="mt-1 pl-2 border-l border-foundation-border"><EvidenceView value={item} /></dd>
  </div>)}</dl>;
}
function errorMessage(error: unknown) {
  if (error instanceof ApiError && error.status === 404) return 'Endpoint or record unavailable. For the assistant, start the backend with the phase6-local profile (nexus.assistant.enabled=true).';
  if (error instanceof ApiError && error.status === 429) return 'The backend rate limit is busy. Wait a minute and retry; no answer was fabricated.';
  return error instanceof Error ? error.message : 'Backend request failed. Check localhost:8080.';
}
function Answer({ answer }: { answer: AssistantAnswer }) {
  return <div className="space-y-4" data-testid="assistant-answer">
    <div className="flex flex-wrap gap-3 text-xs text-foundation-muted-fg"><strong>{answer.status}</strong>
      <span>Routing: {answer.routedBy}</span><span>Ordering: {answer.orderedBy}</span><time>{answer.evaluatedAt}</time></div>
    <p className="text-xs text-foundation-muted-fg">{answer.orderedBy === 'MODEL_SELECTED'
      ? 'Validated model-assisted evidence ordering. Factual statements are platform-written.'
      : 'Deterministic answer ordering (model not used or fallback). This response alone does not prove a provider call.'}</p>
    {answer.clarification && <p>{answer.clarification}</p>}
    {[...answer.statements, ...answer.additional].map((statement) => <div key={statement.id}>
      <span className="text-[10px] font-bold text-[var(--accent)]">{statement.kind}</span><p className="whitespace-pre-wrap">{statement.text}</p>
      {statement.refs.length > 0 && <p className="text-xs text-foundation-muted-fg break-all">Evidence: {statement.refs.join(' · ')}</p>}
    </div>)}
    {answer.notices.map((notice, i) => <p key={i} className="text-sm text-foundation-muted-fg">{notice}</p>)}
    <details><summary className="cursor-pointer text-sm font-semibold">Supporting evidence ({answer.sources.length})</summary>
      <div className="mt-3"><EvidenceView value={answer.sources as unknown as Json} /></div></details>
  </div>;
}

function ReportSummary({ value }: { value: Json }) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  if (value.available === false) return <p role="status" className="rounded-xl border border-foundation-border p-3">
    Energy unavailable: {typeof value.unavailableReason === 'string' ? value.unavailableReason : 'Required readings are not available.'}</p>;
  const trend = value.trend;
  if (trend && typeof trend === 'object' && !Array.isArray(trend)) {
    const rate = trend.rate && typeof trend.rate === 'object' && !Array.isArray(trend.rate) ? trend.rate : null;
    return <div className="grid sm:grid-cols-3 gap-3 border-b border-foundation-border pb-4">
      <div><p className="text-xs text-foundation-muted-fg">Direction</p><p className="font-semibold">{String(trend.direction)}</p></div>
      <div><p className="text-xs text-foundation-muted-fg">Usable samples in window</p><p className="font-semibold">{String(trend.sampleCount)}</p></div>
      <div><p className="text-xs text-foundation-muted-fg">Rate per minute</p><p className="font-semibold">{rate ? `${rate.perMinute} ${rate.unit}` : 'Not available'}</p></div>
    </div>;
  }
  if (value.available === true && Array.isArray(value.totals)) return <div><h3 className="font-semibold text-sm">Consumption totals (Wh, separate provenance groups)</h3><EvidenceView value={value.totals} /></div>;
  return null;
}

export default function Phase6Page({ screen }: { screen: IntelligenceScreen }) {
  const [devices, setDevices] = useState<StoredDevice[]>([]);
  const [deviceError, setDeviceError] = useState<string | null>(null);
  const [available, setAvailable] = useState<boolean | null>(null);
  const [deviceId, setDeviceId] = useState('');
  const [capability, setCapability] = useState('temperature');
  const [hours, setHours] = useState(24);
  const [limit, setLimit] = useState('');
  const [unit, setUnit] = useState('');
  const [ruleId, setRuleId] = useState('');
  const [question, setQuestion] = useState('');
  const [goal, setGoal] = useState('');
  const [turns, setTurns] = useState<{ question: string; answer: AssistantAnswer }[]>([]);
  const conversation = useRef<string | undefined>(undefined);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [reports, setReports] = useState<{ title: string; value?: Json; error?: string }[]>([]);
  const [drafts, setDrafts] = useState<DraftView[] | null>(null);
  const [draftResult, setDraftResult] = useState<Json | undefined>(undefined);
  // In-memory only: device evidence is not persisted to browser storage.
  useEffect(() => {
    let current = true;
    intelligenceApi.devices().then((v) => { if (current) setDevices(v); }).catch((e) => { if (current) setDeviceError(errorMessage(e)); });
    intelligenceApi.availability().then(() => { if (current) setAvailable(true); }).catch(() => { if (current) setAvailable(false); });
    return () => { current = false; };
  }, []);
  useEffect(() => { setReports([]); setError(null); }, [screen]);

  async function ask(event: FormEvent) {
    event.preventDefault();
    if (!question.trim() || busy) return;
    setBusy(true); setError(null);
    const submitted = question.trim();
    try {
      const answer = await intelligenceApi.ask(submitted, conversation.current);
      conversation.current = answer.conversationId;
      setTurns((previous) => [...previous, { question: submitted, answer }]);
      setQuestion(''); setAvailable(true);
    } catch (e) { setError(errorMessage(e)); }
    finally { setBusy(false); }
  }
  async function runReports() {
    setBusy(true); setError(null); setReports([]);
    const work: [string, () => Promise<Json>][] = screen === 'energy'
      ? [['Consumption and provenance', () => intelligenceApi.energy(deviceId, hours)]]
      : screen === 'trends'
        ? [['Trend analysis', () => intelligenceApi.trend(deviceId, capability, hours, limit === '' ? undefined : Number(limit), limit === '' ? undefined : unit)],
          ['Stored readings (most recent 50, with recorded timestamps and values)', () => intelligenceApi.readings(deviceId)]]
        : [['Recommendations (advice only)', () => intelligenceApi.recommendations(deviceId || undefined)],
          ...(deviceId ? [['Anomalies', () => intelligenceApi.anomalies(deviceId, capability, hours)] as [string, () => Promise<Json>]] : []),
          ...(ruleId ? [['Rule non-match explanation', () => intelligenceApi.nonMatch(ruleId, deviceId || undefined)] as [string, () => Promise<Json>]] : [])];
    // Sequential requests preserve bounded backend concurrency.
    const completed: typeof reports = [];
    for (const [title, request] of work) {
      try { completed.push({ title, value: await request() }); }
      catch (e) { completed.push({ title, error: errorMessage(e) }); }
      setReports([...completed]);
    }
    setBusy(false);
  }
  async function loadDrafts() {
    setBusy(true); setError(null);
    try { setDrafts(await intelligenceApi.drafts()); }
    catch (e) { setError(errorMessage(e)); }
    finally { setBusy(false); }
  }
  async function propose(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError(null); setDraftResult(undefined);
    try { setDraftResult(await intelligenceApi.propose(goal)); setDrafts(await intelligenceApi.drafts()); }
    catch (e) {
      if (e instanceof ApiError && e.status === 422 && e.data) setDraftResult(e.data as Json);
      else setError(errorMessage(e));
    } finally { setBusy(false); }
  }

  return <main className="h-full overflow-y-auto p-4 sm:p-7 text-foundation-fg space-y-5" style={{ backgroundColor: 'var(--foundation-bg)' }}>
    <header className="flex flex-wrap items-center justify-between gap-3"><div>
      <p className="text-xs uppercase tracking-widest text-[var(--accent)]">Phase 6 · Physical intelligence</p>
      <h1 className="text-2xl font-bold mt-1">{screens.find((item) => item.key === screen)?.title}</h1>
    </div><span className="text-xs text-foundation-muted-fg">Live backend API · No demo answers</span></header>
    <nav aria-label="Phase 6" className="flex flex-wrap gap-2">{screens.map((item) => <Link key={item.key} to={item.path}
      aria-current={screen === item.key ? 'page' : undefined} className={`${field} ${screen === item.key ? 'border-[var(--accent)]' : ''}`}>{item.title}</Link>)}</nav>
    <GlassPanel className="p-4 text-sm text-foundation-muted-fg">Evidence, not autonomous control. Offline deterministic answers need no model.
      Missing readings are not evidence of safety or a fault. Drafts never approve or activate themselves.
      Local/private use only: NEXUS has no user authentication.</GlassPanel>
    {available === false && <p role="status" className="text-sm text-[var(--warning)]">Assistant endpoint unavailable or disabled. Use start.bat from D:\nexus-p6 (phase6-local profile), then retry. Other analysis endpoints may still work.</p>}
    {deviceError && <p role="status">Device list unavailable: {deviceError}</p>}
    {screen === 'assistant' && <ModelStatusPanel refreshKey={turns.length} />}
    {error && <div role="alert" className="rounded-xl border border-[var(--danger)] p-4">{error}</div>}
    {screen === 'assistant' ? <div className="grid lg:grid-cols-[1fr_260px] gap-5"><div className="space-y-4">
      {turns.length === 0 && <GlassPanel className="p-6"><Bot className="text-[var(--accent)] mb-3" /><h2 className="font-semibold">Ask about your stored device evidence</h2>
        <p className="text-sm mt-2 text-foundation-muted-fg">Choose an example or ask below. Answers come from the running Phase 6 backend, even without a model.</p></GlassPanel>}
      <div aria-live="polite" aria-label="Conversation history" className="space-y-4">{turns.map((turn, i) => <GlassPanel key={i} className="p-5 space-y-4">
        <p className="font-semibold border-b border-foundation-border pb-3">You: {turn.question}</p><Answer answer={turn.answer} /></GlassPanel>)}</div>
      {busy && <p role="status">Reading stored evidence…</p>}
      <form onSubmit={ask} className="space-y-3"><label htmlFor="assistant-question" className="text-sm font-semibold">Ask NEXUS</label>
        <textarea id="assistant-question" className={`${field} w-full min-h-24`} maxLength={2000} required value={question} onChange={(e) => setQuestion(e.target.value)}
          placeholder="What is the status of my device?" disabled={busy} />
        <div className="flex gap-3"><button className={`${button} inline-flex items-center gap-2`} disabled={busy || !question.trim()}><Send size={15} />Ask assistant</button>
          <button type="button" className={field} disabled={busy} onClick={() => { setTurns([]); conversation.current = undefined; setError(null); }}>New conversation</button></div>
      </form></div>
      <GlassPanel className="p-4 h-fit space-y-3"><h2 className="font-semibold">Try a real question</h2>
        {['What recommendations do you have?', 'Why is energy consumption unavailable?', ...(devices[0]
          ? [`What is the status of ${devices[0].name}?`, `What is the latest temperature of ${devices[0].name}?`] : ['What is the device status?'])]
          .map((example) => <button key={example} type="button" className={`${field} w-full text-left`} onClick={() => setQuestion(example)}>{example}</button>)}
        <p className="text-xs text-foundation-muted-fg">History lasts for this page session. This is not a general-purpose chatbot. Follow-ups reuse the named device context.</p>
      </GlassPanel></div>
    : screen === 'drafts' ? <>
      <GlassPanel className="p-5 space-y-3"><h2 className="font-semibold">Human-reviewed proposals</h2>
        <p className="text-sm">Draft → validation/conflict checks → local authorized approval → normal rule lifecycle. No approve or activate endpoint is exposed here.</p>
        <button className={button} onClick={loadDrafts} disabled={busy}>Load stored drafts</button>
        <form onSubmit={propose} className="space-y-2"><label htmlFor="draft-goal" className="block text-sm">Proposed rule goal (requires a configured, consented model)</label>
          <textarea id="draft-goal" className={`${field} w-full`} required maxLength={2000} value={goal} onChange={(e) => setGoal(e.target.value)} />
          <button className={button} disabled={busy || !goal.trim()}>Generate draft only</button></form>
        <p className="text-xs text-foundation-muted-fg">Without a model, ask the assistant: “Draft a hard limit rule for DEVICE temperature at VALUE UNIT”. It stores an alert-only proposal, never an active rule.</p>
      </GlassPanel>
      {busy && <p role="status">Reading draft evidence…</p>}
      {draftResult !== undefined && <GlassPanel className="p-5"><h2 className="font-semibold mb-3">Draft request outcome</h2><EvidenceView value={draftResult} /></GlassPanel>}
      {drafts?.length === 0 && <p>No drafts stored. No rule has been created by this screen.</p>}
      {drafts?.map((draft) => <GlassPanel key={draft.draftId} className="p-5 space-y-3"><h2 className="font-semibold break-all">{draft.draftId} · {draft.state}</h2>
        <p>Validation: {draft.valid ? 'Valid at proposal time' : 'Invalid — not approvable'}</p><p className="text-xs">Created {draft.createdAt} · Expires {draft.expiresAt} · Rule: {draft.ruleId ?? 'None'}</p>
        <h3 className="font-semibold text-sm">Validation, conflicts and warnings</h3><EvidenceView value={draft.validation} />
        <details><summary>Proposed definition and evidence</summary><EvidenceView value={draft.definition} /><EvidenceView value={draft.evidenceRefs} /></details></GlassPanel>)}
    </> : <>
      <GlassPanel className="p-5 space-y-4"><div className="grid sm:grid-cols-2 lg:grid-cols-4 gap-3">
        <label className="text-sm">Stored device<select aria-label="Stored device" className={`${field} block w-full mt-1`} value={deviceId} onChange={(e) => setDeviceId(e.target.value)}>
          <option value="">{screen === 'trends' ? 'Select a device' : 'All devices'}</option>{devices.map((d) => <option key={d.id} value={d.id}>{d.name} {d.mode ? `(${d.mode})` : ''}</option>)}</select></label>
        {screen !== 'energy' && <label className="text-sm">Capability key<input aria-label="Capability key" className={`${field} block w-full mt-1`} value={capability} onChange={(e) => setCapability(e.target.value)} /></label>}
        <label className="text-sm">Window (hours)<input aria-label="Window hours" type="number" min={1} max={720} className={`${field} block w-full mt-1`} value={hours} onChange={(e) => setHours(Number(e.target.value))} /></label>
        {screen === 'insights' && <label className="text-sm">Rule ID (optional non-match)<input className={`${field} block w-full mt-1`} value={ruleId} onChange={(e) => setRuleId(e.target.value)} /></label>}
        {screen === 'trends' && <><label className="text-sm">Configured upper limit (optional)<input aria-label="Configured upper limit" type="number" step="any" className={`${field} block w-full mt-1`} value={limit} onChange={(e) => setLimit(e.target.value)} /></label>
          <label className="text-sm">Limit unit (required with limit)<input aria-label="Limit unit" className={`${field} block w-full mt-1`} value={unit} onChange={(e) => setUnit(e.target.value)} /></label></>}
      </div><button className={button} onClick={runReports} disabled={busy || hours < 1 || hours > 720 || (screen === 'trends' && (!deviceId || !capability || (limit !== '' && !unit.trim())))}>
        Load {screen === 'energy' ? 'energy analysis' : screen === 'trends' ? 'trend and readings' : 'insights'}</button>
        {screen === 'insights' && <p className="text-sm"><Link className="underline text-[var(--accent)]" to="/ai">Open recorded rule-match explanations and resulting actions →</Link> Select a device for anomalies; enter a rule ID for non-match evidence.</p>}
        {screen === 'trends' && <p className="text-xs text-foundation-muted-fg">Limits are explicit analysis assumptions, not saved configurations or certified cutoffs. No unit conversion is assumed. The separate raw-reading list shows the most recent 50 records and may predate the analysis window.</p>}
        {screen === 'energy' && <p className="text-xs text-foundation-muted-fg">Power/energy telemetry is required. Missing telemetry is unavailable, never zero consumption. Measured, simulated and estimated data stay distinct.</p>}
      </GlassPanel>
      {busy && <p role="status">Reading stored evidence…</p>}
      {!busy && reports.length === 0 && <p className="text-sm text-foundation-muted-fg">Choose a scope and load a real backend report. No values are inferred before a response arrives.</p>}
      {reports.map((report) => <GlassPanel key={report.title} className="p-5 space-y-3"><h2 className="font-semibold">{report.title}</h2>
        {report.error ? <p role="alert">{report.error}</p> : report.value !== undefined && <><ReportSummary value={report.value} />
          <details open><summary className="cursor-pointer text-sm">Recorded evidence, quality and limitations</summary><div className="mt-3"><EvidenceView value={report.value} /></div></details></>}</GlassPanel>)}
    </>}
    <footer className="text-xs text-foundation-muted-fg">localhost:8080/api/v1 · Phase 6 APIs · No browser-side model calls or approval credentials</footer>
  </main>;
}
