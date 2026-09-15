import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Server, Activity, ArrowRight, CheckCircle2, Circle, Plus, AlertTriangle, Sparkles, Clock, Power, Trash2 } from 'lucide-react';
import { useRepositories } from '../../../../repositories/RepositoryProvider';
import { usePolling } from '../../../../hooks/usePolling';
import { DebugOverlay } from '../DebugOverlay';
import { CreateRuleModal } from '../CreateRuleModal';
import { aiApi } from '../../../../api/aiApi';
import { ruleApi } from '../../../../api/ruleApi';
import { Card } from '../../../foundation/Card';
import { GlassPanel } from '../../../foundation/GlassPanel';
import { Button } from '../../../foundation/Button';
import { EmptyState } from '../../../foundation/EmptyState';
import { LoadingBlock } from '../../../foundation/LoadingState';
import { ErrorState } from '../../../foundation/ErrorState';

export function RulesTab({ deviceId }: { deviceId: string }) {
  const { rules } = useRepositories();
  const { data: deviceRules = [], isLoading, isError, error, refetch } = usePolling(
    ['rules', deviceId],
    () => rules.getRules(deviceId),
    { interval: 30000 }
  );
  const [modalOpen, setModalOpen] = useState(false);
  const [busyRuleId, setBusyRuleId] = useState<string | null>(null);

  const toggleEnabled = async (rule: any) => {
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
    const id = rule.ruleId ?? rule.id;
    setBusyRuleId(id);
    try {
      await ruleApi.deleteRule(id);
      await refetch();
    } finally {
      setBusyRuleId(null);
    }
  };

  // Phase 3: this device's real recent rule-match history, via the
  // persisted eventId -> DomainEvent.deviceId relationship
  // (RuleMatchRepository#findByDeviceId) - the rule *definitions* above
  // show what could match; this shows what actually did.
  const { data: recentMatches = [], isLoading: matchesLoading, isError: matchesError } = usePolling(
    ['rule-matches', 'device', deviceId],
    () => aiApi.getRuleMatchHistory(20, undefined, undefined, deviceId),
    { interval: 30000 }
  );

  if (isLoading) return <LoadingBlock className="h-64 mt-6" />;

  if (isError) {
    return (
      <div className="py-10">
        <ErrorState
          icon={<AlertTriangle size={32} />}
          message={error instanceof Error ? error.message : 'Could not load rules for this device.'}
        />
        <p className="text-center text-xs text-foundation-muted-fg -mt-4" data-testid="rules-tab-error">Rules Unavailable</p>
      </div>
    );
  }

  return (
    <div className="py-6 flex flex-col gap-6">

      {/* Header & Controls */}
      <Card level="glass" padding="lg" className="shrink-0 relative">
        <DebugOverlay
          title="Active Automations"
          data={deviceRules}
          repository="DataSourceResolver"
        />
        <div className="flex items-center justify-between gap-4">
          <div className="flex items-center gap-3">
            <div className="p-2 rounded-[var(--radius-panel)]" style={{ backgroundColor: 'color-mix(in srgb, var(--accent) 14%, transparent)', color: 'var(--accent)' }}>
              <Server size={20} />
            </div>
            <div>
              <h3 className="font-semibold text-foundation-fg">Active Automations</h3>
              <p className="text-xs text-foundation-muted-fg">Rules currently monitoring this device</p>
            </div>
          </div>
          <Button variant="secondary" size="sm" onClick={() => setModalOpen(true)}>
            <Plus size={14} /> Create Rule
          </Button>
        </div>
      </Card>

      {/* Rules Grid */}
      {deviceRules.length === 0 ? (
        <Card level="glass" padding="lg">
          <EmptyState
            icon={<Server size={28} />}
            title="No automations bound."
            description="There are currently no rules monitoring this device's telemetry stream."
            action={<Button variant="secondary" size="sm" onClick={() => setModalOpen(true)}>Create Rule</Button>}
          />
        </Card>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-2 xl:grid-cols-3 gap-4">
          {deviceRules.map((rule: any) => (
            <GlassPanel key={rule.id} level="glass" className="overflow-hidden flex flex-col">
              {/* Rule Header */}
              <div className="p-4 border-b border-foundation-border flex items-start justify-between gap-2">
                <div className="min-w-0">
                  <h4 className="font-semibold text-foundation-fg truncate">{rule.name}</h4>
                  <p className="text-xs text-foundation-muted-fg mt-1 line-clamp-2">{rule.description || 'No description provided.'}</p>
                </div>
                <div
                  className="flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-bold uppercase tracking-wider shrink-0"
                  style={rule.enabled
                    ? { backgroundColor: 'color-mix(in srgb, var(--success) 14%, transparent)', color: 'var(--success)' }
                    : { backgroundColor: 'var(--foundation-bg)', color: 'var(--foundation-muted-fg)', border: '1px solid var(--foundation-border)' }}
                >
                  <CheckCircle2 size={10} /> {rule.enabled ? 'Active' : 'Disabled'}
                </div>
              </div>

              {/* Rule Content (Conditions -> Actions) */}
              <div className="p-4 flex-1 flex flex-col gap-3">
                <div>
                  <span className="text-[10px] font-bold text-foundation-muted-fg uppercase tracking-wider mb-1.5 block">Trigger Condition</span>
                  <div className="rounded p-2 text-xs font-mono text-foundation-fg/80 break-all" style={{ backgroundColor: 'var(--foundation-bg)', border: '1px solid var(--foundation-border)' }}>
                    {rule.condition}
                  </div>
                </div>

                <div className="flex items-center justify-center text-foundation-tertiary-fg">
                  <ArrowRight size={14} />
                </div>

                <div>
                  <span className="text-[10px] font-bold text-foundation-muted-fg uppercase tracking-wider mb-1.5 block">Actions</span>
                  <div className="flex flex-col gap-1.5">
                    {(rule.actions ?? []).map((action: any, idx: number) => (
                      <div key={idx} className="rounded p-2 text-xs flex items-center gap-2" style={{ backgroundColor: 'color-mix(in srgb, var(--accent) 8%, transparent)', border: '1px solid color-mix(in srgb, var(--accent) 20%, transparent)' }}>
                        <Activity size={12} style={{ color: 'var(--accent)' }} className="shrink-0" />
                        <span className="font-medium text-foundation-fg">{action.type}</span>
                        <span className="text-foundation-muted-fg truncate">{JSON.stringify(action.payload)}</span>
                      </div>
                    ))}
                  </div>
                </div>
              </div>

              {/* Rule Footer */}
              <div className="px-4 py-2 border-t border-foundation-border flex items-center justify-between text-[10px] text-foundation-muted-fg">
                <div className="flex items-center gap-1">
                  <Circle size={8} style={{ fill: rule.enabled ? 'var(--success)' : 'var(--foundation-tertiary-fg)', color: rule.enabled ? 'var(--success)' : 'var(--foundation-tertiary-fg)' }} />
                  {rule.enabled ? 'Monitored Live' : 'Not evaluated'}
                </div>
                <div className="flex items-center gap-3">
                  <span>Priority: {rule.priority}</span>
                  <button
                    type="button"
                    onClick={() => toggleEnabled(rule)}
                    disabled={busyRuleId === (rule.ruleId ?? rule.id)}
                    aria-label={rule.enabled ? 'Disable rule' : 'Enable rule'}
                    className="text-foundation-muted-fg hover:text-foundation-fg disabled:opacity-40"
                  >
                    <Power size={12} />
                  </button>
                  <button
                    type="button"
                    onClick={() => deleteRule(rule)}
                    disabled={busyRuleId === (rule.ruleId ?? rule.id)}
                    aria-label="Delete rule"
                    className="text-foundation-muted-fg hover:text-[var(--danger)] disabled:opacity-40"
                  >
                    <Trash2 size={12} />
                  </button>
                </div>
              </div>
            </GlassPanel>
          ))}
        </div>
      )}

      {/* Recent Matches - real, device-scoped rule-match history (Phase 3):
          what actually fired against this device, distinct from the rule
          definitions above. Links to the same real, evidence-grounded AI
          explanation experience (never a second investigation page). */}
      <GlassPanel level="glass" className="shrink-0 max-h-64 flex flex-col overflow-hidden">
        <div className="p-4 pb-2 flex items-center gap-3 shrink-0">
          <div className="p-2 rounded-[var(--radius-panel)]" style={{ backgroundColor: 'color-mix(in srgb, var(--accent) 14%, transparent)', color: 'var(--accent)' }}>
            <Sparkles size={18} />
          </div>
          <div>
            <h3 className="font-semibold text-foundation-fg text-sm">Recent Matches</h3>
            <p className="text-xs text-foundation-muted-fg">What actually fired against this device</p>
          </div>
        </div>
        <div className="flex-1 min-h-0 overflow-y-auto custom-scrollbar px-4 pb-4">
          {matchesLoading ? (
            <LoadingBlock className="h-16 w-full" />
          ) : matchesError ? (
            <p data-testid="recent-matches-error" className="text-xs" style={{ color: 'var(--danger)' }}>Could not load recent matches for this device.</p>
          ) : recentMatches.length === 0 ? (
            <p className="text-xs text-foundation-muted-fg py-2">No rule matches recorded for this device yet.</p>
          ) : (
            <div className="space-y-2">
              {recentMatches.map((m: any) => (
                <Link
                  key={m.matchId}
                  to={`/ai?matchId=${m.matchId}`}
                  className="flex items-center justify-between gap-3 rounded-[var(--radius-panel)] px-3 py-2 text-xs transition-colors"
                  style={{ backgroundColor: 'var(--foundation-surface)', border: '1px solid var(--foundation-border)' }}
                >
                  <span className="font-medium text-foundation-fg truncate">{m.ruleName ?? 'Unnamed rule'}</span>
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

      <CreateRuleModal
        deviceId={deviceId}
        isOpen={modalOpen}
        onClose={() => setModalOpen(false)}
        onCreated={() => refetch()}
      />
    </div>
  );
}
