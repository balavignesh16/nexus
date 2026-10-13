import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useRepositories } from '../repositories/RepositoryProvider';
import { usePolling } from '../hooks/usePolling';
import { useActiveDevices } from '../hooks/queries';
import { aiApi } from '../api/aiApi';
import { ruleApi } from '../api/ruleApi';
import { FileJson, Search, Plus, Zap, CheckCircle2, XCircle, Circle, ArrowRight, Sparkles, Clock, Trash2, Power } from 'lucide-react';
import { GlassPanel } from '../components/foundation/GlassPanel';
import { Card } from '../components/foundation/Card';
import { MetricCard } from '../components/foundation/MetricCard';
import { SectionHeader } from '../components/foundation/SectionHeader';
import { Input, Select } from '../components/foundation/Input';
import { Button } from '../components/foundation/Button';
import { EmptyState } from '../components/foundation/EmptyState';
import { LoadingBlock } from '../components/foundation/LoadingState';
import { CreateRuleModal } from '../components/explorer/device/CreateRuleModal';
import { RuleDetailModal } from '../components/explorer/device/RuleDetailModal';

export function RulesPage() {
  const { rules } = useRepositories();
  const [deviceFilter, setDeviceFilter] = useState('');
  const { data: globalRules = [], isLoading, refetch } = usePolling(
    ['global-rules', deviceFilter],
    () => rules.getRules(deviceFilter || undefined),
    { interval: 5000 }
  );

  const { data: devices = [] } = useActiveDevices();

  const [search, setSearch] = useState('');
  const [modalOpen, setModalOpen] = useState(false);
  const [detailRule, setDetailRule] = useState<any | null>(null);
  const [editingRule, setEditingRule] = useState<any | null>(null);

  // Real, established API (already used by AiInsightsPage and the
  // device-scoped RulesTab) - the global recent-matches view, not scoped
  // to any one device.
  const { data: recentMatches = [], isLoading: matchesLoading, isError: matchesError } = usePolling(
    ['rule-matches', 'global'],
    () => aiApi.getRuleMatchHistory(50),
    { interval: 30000 }
  );

  const filteredRules = globalRules.filter(r =>
    r.name?.toLowerCase().includes(search.toLowerCase()) ||
    r.description?.toLowerCase().includes(search.toLowerCase())
  );

  const enabledCount = globalRules.filter(r => r.enabled).length;
  const disabledCount = globalRules.length - enabledCount;

  const [busyRuleId, setBusyRuleId] = useState<string | null>(null);
  const [lifecycleError, setLifecycleError] = useState<string | null>(null);
  const lifecycleAction = async (rule: any, operation: 'validate' | 'activate' | 'pause' | 'resume' | 'archive') => {
    setBusyRuleId(rule.ruleId); setLifecycleError(null);
    try {
      const result = await ruleApi.transition(rule.ruleId, operation, rule.rowRevision);
      if (result?.diagnostics?.length) setLifecycleError(result.diagnostics.map(d => `${d.path}: ${d.message}`).join('; '));
      await refetch();
    } catch (error) { setLifecycleError(error instanceof Error ? error.message : 'Lifecycle change failed; reload and review.'); }
    finally { setBusyRuleId(null); }
  };

  const toggleEnabled = async (rule: any) => {
    if (rule.rowRevision !== undefined) { await lifecycleAction(rule, rule.enabled ? 'pause' : rule.lifecycle === 'PAUSED' ? 'resume' : 'activate'); return; }
    const id = rule.ruleId ?? rule.id;
    setBusyRuleId(id);
    try {
      await (rule.enabled ? ruleApi.disableRule(id) : ruleApi.enableRule(id));
      await refetch();
    } finally {
      setBusyRuleId(null);
    }
  };

  const deleteRule = async (rule: any) => {
    if (rule.rowRevision !== undefined) { await lifecycleAction(rule, 'archive'); return; }
    const id = rule.ruleId ?? rule.id;
    setBusyRuleId(id);
    try {
      await ruleApi.deleteRule(id);
      await refetch();
    } finally {
      setBusyRuleId(null);
    }
  };

  if (isLoading && globalRules.length === 0) {
    return (
      <div className="flex flex-col h-full overflow-y-auto p-4 sm:p-6" style={{ backgroundColor: 'var(--foundation-bg)' }}>
        <LoadingBlock className="h-16 mb-4" />
        <LoadingBlock className="flex-1" />
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full overflow-y-auto" style={{ backgroundColor: 'var(--foundation-bg)' }}>
      <div className="flex flex-col gap-6 p-4 sm:p-6 lg:p-8 max-w-[1600px] w-full mx-auto">

        {/* HEADER */}
        <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-4">
          <SectionHeader
            icon={<FileJson size={18} />}
            title="Rules"
            subtitle="Automation rule engine configuration"
          />
          <div className="flex flex-wrap items-center gap-3">
            <div className="relative">
              <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-foundation-muted-fg" />
              <Input
                type="text"
                placeholder="Search rules..."
                value={search}
                onChange={e => setSearch(e.target.value)}
                className="pl-9 w-56"
              />
            </div>
            <Select value={deviceFilter} onChange={(e) => setDeviceFilter(e.target.value)} className="w-48">
              <option value="">All devices</option>
              {devices.map((d: any) => <option key={d.id} value={d.id}>{d.name}</option>)}
            </Select>
            <Button variant="secondary" size="sm" onClick={() => setModalOpen(true)}>
              <Plus size={14} /> Create Rule
            </Button>
          </div>
        </div>

        {lifecycleError && <p role="alert" className="text-destructive">{lifecycleError}</p>}
        {/* STATISTICS - real counts from the loaded (possibly device-filtered) rule set */}
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 sm:gap-4">
          <MetricCard label="Total Rules" value={globalRules.length} icon={<FileJson size={18} />} />
          <MetricCard label="Enabled" value={enabledCount} icon={<CheckCircle2 size={18} />} />
          <MetricCard label="Disabled" value={disabledCount} icon={<XCircle size={18} />} />
        </div>

        {/* RULE CARDS */}
        {filteredRules.length === 0 ? (
          <Card level="glass" padding="lg">
            <EmptyState
              icon={<FileJson size={28} />}
              title="No automation rules found."
              description={deviceFilter ? 'No rules are scoped to this device.' : 'Create a rule to start automating responses to real telemetry and events.'}
              action={<Button variant="secondary" size="sm" onClick={() => setModalOpen(true)}>Create Rule</Button>}
            />
          </Card>
        ) : (
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
            {filteredRules.map((rule: any) => (
              <GlassPanel
                key={rule.id ?? rule.ruleId}
                level="glass"
                className="p-5 flex flex-col justify-between cursor-pointer hover:border-[var(--accent)] transition-colors"
                onClick={() => setDetailRule(rule)}
              >
                <div>
                  <div className="flex justify-between items-start mb-2 gap-2">
                    <h3 className="font-semibold text-foundation-fg truncate">{rule.name || rule.id}</h3>
                    <div
                      className="flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-bold uppercase tracking-wider shrink-0"
                      style={rule.enabled
                        ? { backgroundColor: 'color-mix(in srgb, var(--success) 14%, transparent)', color: 'var(--success)' }
                        : { backgroundColor: 'var(--foundation-bg)', color: 'var(--foundation-muted-fg)', border: '1px solid var(--foundation-border)' }}
                    >
                      <CheckCircle2 size={10} /> {rule.enabled ? 'Active' : 'Disabled'}
                    </div>
                  </div>
                  <p className="text-xs text-foundation-muted-fg mb-4">
                    {rule.description || 'Monitors telemetry streams and triggers automated actions.'}
                  </p>
                  {rule.lifecycle && <div className="text-xs mb-3" onClick={e => e.stopPropagation()}>
                    <p>{rule.lifecycle} · Active: {rule.activeVersionId ?? 'none'} · Working: {rule.workingVersionId ?? 'none'} ({rule.workingVersionState}) · Revision: {rule.rowRevision}</p>
                    <p>Reconciliation: {rule.reconciliation}</p>
                    {rule.originalEnabled != null && <p>Imported originally enabled: {String(rule.originalEnabled)}</p>}
                    {rule.diagnostics?.map((d: any, i: number) => <p key={i}>{d.code}: {d.message}</p>)}
                    <button disabled={busyRuleId === rule.ruleId} onClick={() => lifecycleAction(rule, 'validate')}>Validate draft</button>
                    {' · '}<button disabled={busyRuleId === rule.ruleId} onClick={() => lifecycleAction(rule, 'activate')}>Activate working version</button>
                  </div>}

                  <div className="rounded-[var(--radius-panel)] p-3 text-xs font-mono space-y-1.5 mb-4" style={{ backgroundColor: 'var(--foundation-bg)', border: '1px solid var(--foundation-border)' }}>
                    {rule.conditions?.[0] ? (
                      <div className="flex gap-2">
                        <span style={{ color: 'var(--accent)' }}>IF</span>
                        <span className="text-foundation-muted-fg">
                          {rule.conditions[0].field} {rule.conditions[0].operator} {rule.conditions[0].expectedValue}
                          {rule.conditions.length > 1 && ` (+${rule.conditions.length - 1} more)`}
                        </span>
                      </div>
                    ) : (
                      <div className="text-foundation-tertiary-fg">No conditions defined</div>
                    )}
                    {rule.actions?.[0] ? (
                      <div className="flex gap-2">
                        <span style={{ color: 'var(--success)' }}>THEN</span>
                        <span className="text-foundation-muted-fg">
                          {rule.actions[0].actionType}
                          {rule.actions.length > 1 && ` (+${rule.actions.length - 1} more)`}
                        </span>
                      </div>
                    ) : (
                      <div className="text-foundation-tertiary-fg">No actions defined</div>
                    )}
                  </div>
                </div>

                <div className="flex items-center justify-between pt-3 border-t border-foundation-border text-xs text-foundation-muted-fg">
                  <div className="flex items-center gap-1.5">
                    <Zap size={13} /> Priority {rule.priority}
                  </div>
                  <div className="flex items-center gap-1">
                    <Circle size={7} style={{ fill: rule.enabled ? 'var(--success)' : 'var(--foundation-tertiary-fg)', color: rule.enabled ? 'var(--success)' : 'var(--foundation-tertiary-fg)' }} />
                    {rule.enabled ? 'Monitored Live' : 'Not evaluated'}
                  </div>
                  <div className="flex items-center gap-2">
                    <button
                      type="button"
                      onClick={(e) => { e.stopPropagation(); toggleEnabled(rule); }}
                      disabled={busyRuleId === (rule.ruleId ?? rule.id)}
                      aria-label={rule.enabled ? 'Disable rule' : 'Enable rule'}
                      className="text-foundation-muted-fg hover:text-foundation-fg disabled:opacity-40"
                    >
                      <Power size={13} />
                    </button>
                    <button
                      type="button"
                      onClick={(e) => { e.stopPropagation(); deleteRule(rule); }}
                      disabled={busyRuleId === (rule.ruleId ?? rule.id)}
                      aria-label="Delete rule"
                      className="text-foundation-muted-fg hover:text-[var(--danger)] disabled:opacity-40"
                    >
                      <Trash2 size={13} />
                    </button>
                  </div>
                </div>
              </GlassPanel>
            ))}
          </div>
        )}

        {/* RECENT MATCHES - real, global rule-match history via the same
            established aiApi endpoint AiInsightsPage/RulesTab already use. */}
        <GlassPanel level="glass" className="flex flex-col overflow-hidden">
          <div className="p-4 pb-2 flex items-center gap-3 shrink-0">
            <div className="p-2 rounded-[var(--radius-panel)]" style={{ backgroundColor: 'color-mix(in srgb, var(--accent) 14%, transparent)', color: 'var(--accent)' }}>
              <Sparkles size={18} />
            </div>
            <div>
              <h3 className="font-semibold text-foundation-fg text-sm">Recent Matches</h3>
              <p className="text-xs text-foundation-muted-fg">What actually fired across the platform</p>
            </div>
          </div>
          <div className="px-4 pb-4">
            {matchesLoading ? (
              <LoadingBlock className="h-16 w-full" />
            ) : matchesError ? (
              <p data-testid="recent-matches-error" className="text-xs" style={{ color: 'var(--danger)' }}>Could not load recent rule matches.</p>
            ) : recentMatches.length === 0 ? (
              <p className="text-xs text-foundation-muted-fg py-2">No rule matches recorded yet.</p>
            ) : (
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
                {recentMatches.map((m: any) => (
                  <Link
                    key={m.matchId}
                    to={`/ai?matchId=${m.matchId}`}
                    className="flex items-center justify-between gap-3 rounded-[var(--radius-panel)] px-3 py-2 text-xs transition-colors"
                    style={{ backgroundColor: 'var(--foundation-surface)', border: '1px solid var(--foundation-border)' }}
                  >
                    <span className="font-medium text-foundation-fg truncate flex items-center gap-1.5">
                      <ArrowRight size={11} style={{ color: 'var(--accent)' }} />
                      {m.ruleName ?? 'Unnamed rule'}
                    </span>
                    <span className="flex items-center gap-1 text-[10px] text-foundation-muted-fg shrink-0">
                      <Clock size={10} />
                      {new Date(m.matchedAt).toLocaleString()}
                    </span>
                  </Link>
                ))}
              </div>
            )}
          </div>
        </GlassPanel>
      </div>

      <RuleDetailModal
        rule={detailRule}
        isOpen={detailRule !== null}
        onClose={() => setDetailRule(null)}
        onEdit={() => {
          setEditingRule(detailRule);
          setDetailRule(null);
          setModalOpen(true);
        }}
      />

      <CreateRuleModal
        editingRule={editingRule}
        isOpen={modalOpen}
        onClose={() => { setModalOpen(false); setEditingRule(null); }}
        onCreated={() => refetch()}
      />
    </div>
  );
}

export default RulesPage;
