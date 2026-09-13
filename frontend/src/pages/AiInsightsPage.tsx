import { useState } from 'react';
import { Sparkles, Hash, Clock, CheckCircle2, HelpCircle, Lightbulb } from 'lucide-react';
import { aiApi, type RuleMatchExplanation, type Claim } from '../api/aiApi';
import { ApiError } from '../api/apiClient';
import { DataSourceBadge } from '../components/ui/DataSourceBadge';
import { Skeleton } from '../components/ui/Skeleton';
import { usePolling } from '../hooks/usePolling';

/**
 * NEXUS Intelligence: an evidence-grounded rule-match explanation
 * interface, not a chatbot. There is no conversational AI API in NEXUS -
 * every explanation shown here is either genuinely provider-generated
 * (generatedBy === "AI") or the backend's own deterministic, non-AI
 * fallback (generatedBy === "DETERMINISTIC_FALLBACK"). The two are never
 * visually interchangeable - see DataSourceBadge's AI vs CALCULATED
 * classifications.
 */
export default function AiInsightsPage() {
  const [selectedMatchId, setSelectedMatchId] = useState<string | null>(null);

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

  return (
    <div className="flex-1 flex h-full bg-canvas overflow-hidden text-foreground animate-in fade-in duration-300">

      {/* LEFT: REAL RULE MATCH HISTORY */}
      <div className="w-[380px] shrink-0 border-r border-surface-border bg-sunken flex flex-col h-full">
        <div className="shrink-0 h-16 border-b border-surface-border flex items-center gap-3 px-5">
          <div className="p-2 bg-primary/10 rounded-lg border border-primary/20">
            <Sparkles size={18} className="text-primary" />
          </div>
          <div>
            <h1 className="text-sm font-bold">NEXUS Intelligence</h1>
            <p className="text-[11px] text-muted-foreground">Evidence-grounded rule-match explanations</p>
          </div>
        </div>

        <div className="flex-1 overflow-y-auto custom-scrollbar p-3 space-y-1.5">
          {matchesLoading ? (
            <>
              <Skeleton className="h-16 w-full" />
              <Skeleton className="h-16 w-full" />
              <Skeleton className="h-16 w-full" />
            </>
          ) : matches.length === 0 ? (
            <div className="h-full flex flex-col items-center justify-center text-center gap-2 px-4 py-12 text-muted-foreground">
              <Hash size={28} className="opacity-30" />
              <p className="text-xs">No rule matches recorded yet. Explanations become available once a rule fires.</p>
            </div>
          ) : (
            matches.map((m) => (
              <button
                key={m.matchId}
                data-testid={`rule-match-${m.matchId}`}
                onClick={() => setSelectedMatchId(m.matchId)}
                className={`w-full text-left rounded-lg p-3 border transition-colors ${
                  selectedMatchId === m.matchId
                    ? 'bg-primary/10 border-primary/30'
                    : 'bg-surface border-surface-border hover:border-primary/20'
                }`}
              >
                <div className="text-sm font-medium text-foreground truncate">{m.ruleName ?? 'Unnamed rule'}</div>
                <div className="flex items-center gap-1.5 mt-1 text-[10px] text-muted-foreground">
                  <Clock size={10} />
                  {new Date(m.matchedAt).toLocaleString()}
                </div>
                {m.correlationId && (
                  <div className="text-[10px] font-mono text-muted-foreground/70 truncate mt-1">{m.correlationId}</div>
                )}
              </button>
            ))
          )}
        </div>
      </div>

      {/* RIGHT: EXPLANATION */}
      <div className="flex-1 flex flex-col h-full overflow-hidden">
        {selectedMatchId === null ? (
          <div className="flex-1 flex flex-col items-center justify-center text-center gap-3 text-muted-foreground">
            <Sparkles size={32} className="opacity-20" />
            <p className="text-sm">Select a rule match to view its evidence-grounded explanation.</p>
          </div>
        ) : explanationLoading ? (
          <div className="flex-1 p-8 space-y-4">
            <Skeleton className="h-8 w-1/3" />
            <Skeleton className="h-20 w-full" />
            <Skeleton className="h-16 w-full" />
            <Skeleton className="h-16 w-full" />
          </div>
        ) : explanationError ? (
          <div className="flex-1 flex flex-col items-center justify-center text-center gap-3 px-8">
            {explanationError.status === 404 ? (
              <p data-testid="explanation-error" className="text-sm text-muted-foreground">This rule match no longer exists.</p>
            ) : explanationError.status === 429 ? (
              <p data-testid="explanation-error" className="text-sm text-muted-foreground">Too many explanation requests — try again in a moment.</p>
            ) : (
              <p data-testid="explanation-error" className="text-sm text-destructive">{explanationError.message}</p>
            )}
          </div>
        ) : explanation ? (
          <ExplanationView explanation={explanation} />
        ) : null}
      </div>
    </div>
  );
}

function ExplanationView({ explanation }: { explanation: RuleMatchExplanation }) {
  return (
    <div className="flex-1 overflow-y-auto custom-scrollbar p-8 space-y-6 max-w-3xl">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold">Explanation</h2>
        <DataSourceBadge
          classification={explanation.generatedBy === 'AI' ? 'AI' : 'CALCULATED'}
        />
      </div>

      <p className="text-sm leading-relaxed text-foreground/90">{explanation.summary}</p>

      <div className="space-y-3">
        <h3 className="text-xs font-bold uppercase tracking-wider text-muted-foreground">Evidence</h3>
        {explanation.claims.map((claim, idx) => (
          <ClaimRow key={idx} claim={claim} />
        ))}
      </div>

      {explanation.actionNote && (
        <div className="bg-surface border border-surface-border rounded-lg p-4 text-xs text-muted-foreground">
          {explanation.actionNote}
        </div>
      )}
    </div>
  );
}

function ClaimRow({ claim }: { claim: Claim }) {
  const tierStyle =
    claim.tier === 'FACT'
      ? { icon: CheckCircle2, color: 'text-success' }
      : claim.tier === 'INFERENCE'
        ? { icon: Lightbulb, color: 'text-info' }
        : { icon: HelpCircle, color: 'text-[var(--data-unavailable)]' };

  return (
    <div className="bg-surface border border-surface-border rounded-lg p-3 flex gap-3">
      <tierStyle.icon size={16} className={`shrink-0 mt-0.5 ${tierStyle.color}`} />
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2">
          <span className={`text-[10px] font-bold uppercase tracking-wider ${tierStyle.color}`}>{claim.tier}</span>
        </div>
        <p className="text-sm text-foreground/90 mt-0.5">{claim.text}</p>
        {claim.evidenceRef && (
          <p className="text-[11px] font-mono text-muted-foreground/70 mt-1">
            {claim.evidenceRef}{claim.assertedValue !== null && claim.assertedValue !== undefined ? ` = ${JSON.stringify(claim.assertedValue)}` : ''}
          </p>
        )}
        {claim.supportingEvidenceRefs && claim.supportingEvidenceRefs.length > 0 && (
          <p className="text-[11px] font-mono text-muted-foreground/70 mt-1">
            supported by: {claim.supportingEvidenceRefs.join(', ')}
          </p>
        )}
      </div>
    </div>
  );
}
