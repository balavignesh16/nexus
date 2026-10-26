import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Sparkles, Hash, Clock, CheckCircle2, HelpCircle, Lightbulb, Zap } from 'lucide-react';
import { aiApi, type RuleMatchExplanation, type Claim, type MatchAction } from '../api/aiApi';
import { ApiError } from '../api/apiClient';
import { DataSourceBadge } from '../components/ui/DataSourceBadge';
import { GlassPanel } from '../components/foundation/GlassPanel';
import { EmptyState } from '../components/foundation/EmptyState';
import { LoadingBlock } from '../components/foundation/LoadingState';
import { usePolling } from '../hooks/usePolling';

/**
 * NEXUS Intelligence: an evidence-grounded rule-match explanation
 * interface, not the Phase 6 chatbot (which lives at /assistant). Here,
 * every explanation shown here is either genuinely provider-generated
 * (generatedBy === "AI") or the backend's own deterministic, non-AI
 * fallback (generatedBy === "DETERMINISTIC_FALLBACK"). The two are never
 * visually interchangeable - see DataSourceBadge's AI vs CALCULATED
 * classifications.
 */
export default function AiInsightsPage() {
  const [searchParams] = useSearchParams();
  // Phase 3: deep-linked investigation entry point - e.g. from a device's
  // real "Recent Matches" list (RulesTab.tsx).
  const [selectedMatchId, setSelectedMatchId] = useState<string | null>(() => searchParams.get('matchId'));

  const { data: matches = [], isLoading: matchesLoading } = usePolling(
    ['rule-match-history'],
    () => aiApi.getRuleMatchHistory(50),
    { interval: 10000 }
  );

  const {
    data: explanation,
    isLoading: explanationLoading,
    error: explanationError,
  } = usePolling<RuleMatchExplanation, ApiError>(
    ['rule-match-explanation', selectedMatchId],
    () => aiApi.explainRuleMatch(selectedMatchId as string),
    { interval: 0, enabled: selectedMatchId !== null, retry: false }
  );

  // Phase 3: the real actions this rule match triggered, via the
  // already-persisted ActionHistoryRecord.matchId FK - independent of
  // whether the AI explanation itself loaded successfully, so it remains
  // usable even when AI is disabled/unavailable.
  const { data: matchActions, isLoading: actionsLoading, error: actionsError } = usePolling<MatchAction[]>(
    ['rule-match-actions', selectedMatchId],
    () => aiApi.getActionsForMatch(selectedMatchId as string),
    { interval: 0, enabled: selectedMatchId !== null, retry: false }
  );

  return (
    <div className="flex flex-col lg:flex-row h-full overflow-hidden" style={{ backgroundColor: 'var(--foundation-bg)' }}>

      {/* LEFT: REAL RULE MATCH HISTORY */}
      <div
        className="w-full lg:w-[380px] shrink-0 h-[42vh] lg:h-full flex flex-col border-b lg:border-b-0 lg:border-r"
        style={{ borderColor: 'var(--foundation-border)' }}
      >
        <div className="shrink-0 h-16 border-b flex items-center gap-3 px-5" style={{ borderColor: 'var(--foundation-border)' }}>
          <div
            className="w-9 h-9 rounded-[var(--radius-panel)] flex items-center justify-center shrink-0"
            style={{ backgroundColor: 'color-mix(in srgb, var(--accent) 14%, transparent)', color: 'var(--accent)' }}
          >
            <Sparkles size={18} />
          </div>
          <div className="min-w-0">
            <h1 className="text-sm font-bold text-foundation-fg truncate">NEXUS Intelligence</h1>
            <p className="text-[11px] text-foundation-muted-fg truncate">Evidence-grounded rule-match explanations</p>
          </div>
        </div>

        <div className="flex-1 overflow-y-auto custom-scrollbar p-3 space-y-1.5">
          {matchesLoading ? (
            <>
              <LoadingBlock className="h-16 w-full" />
              <LoadingBlock className="h-16 w-full" />
              <LoadingBlock className="h-16 w-full" />
            </>
          ) : matches.length === 0 ? (
            <EmptyState
              icon={<Hash size={28} />}
              title="No rule matches recorded yet. Explanations become available once a rule fires."
            />
          ) : (
            matches.map((m) => (
              <button
                key={m.matchId}
                data-testid={`rule-match-${m.matchId}`}
                onClick={() => setSelectedMatchId(m.matchId)}
                className="w-full text-left rounded-[var(--radius-panel)] p-3 border transition-colors"
                style={
                  selectedMatchId === m.matchId
                    ? { backgroundColor: 'color-mix(in srgb, var(--accent) 10%, transparent)', borderColor: 'var(--accent)' }
                    : { backgroundColor: 'var(--foundation-surface)', borderColor: 'var(--foundation-border)' }
                }
              >
                <div className="text-sm font-medium text-foundation-fg truncate">{m.ruleName ?? 'Unnamed rule'}</div>
                <div className="flex items-center gap-1.5 mt-1 text-[10px] text-foundation-muted-fg">
                  <Clock size={10} />
                  {new Date(m.matchedAt).toLocaleString()}
                </div>
                {m.correlationId && (
                  <div className="text-[10px] font-mono text-foundation-muted-fg/70 truncate mt-1">{m.correlationId}</div>
                )}
              </button>
            ))
          )}
        </div>
      </div>

      {/* RIGHT: EXPLANATION + RESULTING ACTIONS */}
      <div className="flex-1 flex flex-col overflow-hidden">
        {selectedMatchId === null ? (
          <EmptyState
            icon={<Sparkles size={32} />}
            title="Select a rule match to view its evidence-grounded explanation."
          />
        ) : (
          <div className="flex-1 overflow-y-auto custom-scrollbar p-6 sm:p-8 space-y-6 max-w-3xl">
            {/* Explanation - independent of the Resulting Actions section
                below: an AI-unavailable/explanation error never hides the
                real action evidence, and vice versa (Phase 3). */}
            {explanationLoading ? (
              <div className="space-y-4">
                <LoadingBlock className="h-8 w-1/3" />
                <LoadingBlock className="h-20 w-full" />
                <LoadingBlock className="h-16 w-full" />
              </div>
            ) : explanationError ? (
              <div className="py-4">
                {explanationError.status === 404 ? (
                  <p data-testid="explanation-error" className="text-sm text-foundation-muted-fg">This rule match no longer exists.</p>
                ) : explanationError.status === 429 ? (
                  <p data-testid="explanation-error" className="text-sm text-foundation-muted-fg">Too many explanation requests — try again in a moment.</p>
                ) : (
                  <p data-testid="explanation-error" className="text-sm" style={{ color: 'var(--danger)' }}>{explanationError.message}</p>
                )}
              </div>
            ) : explanation ? (
              <ExplanationView explanation={explanation} />
            ) : null}

            <ResultingActionsSection actions={matchActions} isLoading={actionsLoading} error={actionsError} />
          </div>
        )}
      </div>
    </div>
  );
}

function ExplanationView({ explanation }: { explanation: RuleMatchExplanation }) {
  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between gap-3">
        <h2 className="text-lg font-semibold text-foundation-fg">Explanation</h2>
        <DataSourceBadge
          classification={explanation.generatedBy === 'AI' ? 'AI' : 'CALCULATED'}
          variant="foundation"
        />
      </div>

      <p className="text-sm leading-relaxed text-foundation-fg/90">{explanation.summary}</p>

      <div className="space-y-3">
        <h3 className="text-xs font-bold uppercase tracking-wider text-foundation-muted-fg">Evidence</h3>
        {explanation.claims.map((claim, idx) => (
          <ClaimRow key={idx} claim={claim} />
        ))}
      </div>

      {explanation.actionNote && (
        <GlassPanel level="elevated" radius="panel-lg" className="p-4 text-xs text-foundation-muted-fg">
          {explanation.actionNote}
        </GlassPanel>
      )}
    </div>
  );
}

/**
 * The real actions this rule match triggered (Phase 3) - via the
 * already-persisted ActionHistoryRecord.matchId FK, never a fabricated
 * relationship. Reuses the exact ACCEPTED/REJECTED/NO_ACK_YET semantics
 * and coloring already established on Analytics/ActionsTab: an absent
 * ackStatus is NO_ACK_YET, never rendered as a failure.
 */
function ResultingActionsSection({ actions, isLoading, error }: { actions: MatchAction[] | undefined; isLoading: boolean; error: unknown }) {
  const ackLabel = (ackStatus: MatchAction['ackStatus']) => {
    if (ackStatus === 'ACCEPTED') return { label: 'ACCEPTED', color: 'var(--success)' };
    if (ackStatus === 'REJECTED') return { label: 'REJECTED', color: 'var(--danger)' };
    return { label: 'NO ACK YET', color: 'var(--foundation-muted-fg)' };
  };

  return (
    <div className="space-y-3 pt-2 border-t" style={{ borderColor: 'var(--foundation-border)' }}>
      <h3 className="text-xs font-bold uppercase tracking-wider text-foundation-muted-fg flex items-center gap-1.5">
        <Zap size={12} /> Resulting Actions
      </h3>
      {isLoading ? (
        <LoadingBlock className="h-16 w-full" />
      ) : error ? (
        <p data-testid="match-actions-error" className="text-xs" style={{ color: 'var(--danger)' }}>Could not load actions for this match.</p>
      ) : !actions || actions.length === 0 ? (
        <p className="text-xs text-foundation-muted-fg">No actions were recorded for this rule match.</p>
      ) : (
        <div className="space-y-2">
          {actions.map((action) => {
            const statusColor = action.status === 'FAILURE' ? 'var(--danger)' : 'var(--success)';
            const ack = ackLabel(action.ackStatus);
            return (
              <GlassPanel key={action.executionId} level="glass" className="p-3 flex items-center justify-between gap-3">
                <div className="min-w-0">
                  <div className="text-sm font-medium text-foundation-fg truncate">{action.actionType}</div>
                  <div className="text-[11px] text-foundation-muted-fg mt-0.5">{new Date(action.timestamp).toLocaleString()}</div>
                </div>
                <div className="flex items-center gap-2 shrink-0">
                  <span
                    className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-full"
                    style={{ backgroundColor: `color-mix(in srgb, ${statusColor} 14%, transparent)`, color: statusColor }}
                  >
                    {action.status}
                  </span>
                  {action.actionType === 'SEND_COMMAND' && (
                    <span
                      className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-full"
                      style={{ backgroundColor: `color-mix(in srgb, ${ack.color} 14%, transparent)`, color: ack.color }}
                    >
                      {ack.label}
                    </span>
                  )}
                </div>
              </GlassPanel>
            );
          })}
        </div>
      )}
    </div>
  );
}

function ClaimRow({ claim }: { claim: Claim }) {
  const tierStyle =
    claim.tier === 'FACT'
      ? { icon: CheckCircle2, color: 'var(--success)' }
      : claim.tier === 'INFERENCE'
        ? { icon: Lightbulb, color: 'var(--info)' }
        : { icon: HelpCircle, color: 'var(--data-unavailable)' };

  return (
    <GlassPanel level="elevated" radius="panel-lg" className="p-3 flex gap-3">
      <tierStyle.icon size={16} className="shrink-0 mt-0.5" style={{ color: tierStyle.color }} />
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2">
          <span className="text-[10px] font-bold uppercase tracking-wider" style={{ color: tierStyle.color }}>{claim.tier}</span>
        </div>
        <p className="text-sm text-foundation-fg/90 mt-0.5 break-words">{claim.text}</p>
        {claim.evidenceRef && (
          <p className="text-[11px] font-mono text-foundation-muted-fg/70 mt-1 break-words">
            {claim.evidenceRef}{claim.assertedValue !== null && claim.assertedValue !== undefined ? ` = ${JSON.stringify(claim.assertedValue)}` : ''}
          </p>
        )}
        {claim.supportingEvidenceRefs && claim.supportingEvidenceRefs.length > 0 && (
          <p className="text-[11px] font-mono text-foundation-muted-fg/70 mt-1 break-words">
            supported by: {claim.supportingEvidenceRefs.join(', ')}
          </p>
        )}
      </div>
    </GlassPanel>
  );
}
