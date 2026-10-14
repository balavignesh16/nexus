import { AlertTriangle, FlaskConical } from 'lucide-react';
import type { DryRunResponse } from '../../api/ruleApi';
import { formatActual } from '../../utils/dryRunForm';

const OUTCOME_TEXT: Record<string, string> = {
  MATCHED: 'Would match',
  NOT_MATCHED: 'Would not match',
  NOT_ELIGIBLE: 'Not evaluated: the trigger does not reach this version',
  SKIPPED_IN_COOLDOWN: 'Not evaluated: inside its cooldown window',
  EDGE_SUPPRESSED: 'True, but the edge latch is still held (no repeat)',
  COOLDOWN_SUPPRESSED: 'True, but suppressed by the cooldown window',
  SCOPE_MISSING: 'Not evaluated: no device scope could be resolved',
  CONTRACT_DIAGNOSTICS: 'Not evaluated: the installed device contract does not satisfy this version',
};

const DISPOSITION_TEXT: Record<string, string> = {
  SELECTED: 'would be selected by arbitration (acceptance and authorization are not evaluated)',
  COALESCED: 'would be merged into an identical selected command',
  SUPPRESSED_PRIORITY: 'would lose to a higher-priority command',
  SUPPRESSED_CONFLICT: 'would be suppressed: equal-priority conflict (fails closed)',
  SUPPRESSED_EXCLUSION: 'would be suppressed by a mutual-exclusion group',
  SUPPRESSED_EXCLUSION_CONFLICT: 'would be suppressed: equal-rank exclusion conflict (fails closed)',
};

/** Renders a backend dry-run answer. Every statement is hypothetical: nothing here was matched, dispatched, published or acknowledged. */
export function DryRunResultView({ result }: { result: DryRunResponse }) {
  const winnerLabel = (id: string | null) => (id ? result.candidates.find((c) => c.simulatedId === id)?.simulatedId ?? id : null);
  return (
    <div className="space-y-3 text-xs" data-testid="dry-run-result">
      <div className="flex items-start gap-2 rounded border border-blue-500/40 bg-blue-500/10 p-2 text-blue-400" role="note">
        <FlaskConical size={14} className="mt-0.5 shrink-0" />
        <div>
          <div className="font-semibold">Hypothetical result - nothing was executed</div>
          <div>{result.notice}</div>
          <div className="mt-1 text-muted-foreground">
            {result.mode === 'SUPPLIED_STATE' ? 'Supplied (hypothetical) state' : 'Backend state'} at {result.simulatedAt}
            {' '}({result.timeSource === 'SUPPLIED' ? 'simulation time you supplied' : 'backend clock'}).
          </div>
        </div>
      </div>

      {result.diagnostics.length > 0 && (
        <ul className="space-y-1">
          {result.diagnostics.map((d, i) => (
            <li key={i} className="flex gap-1.5 text-amber-500"><AlertTriangle size={12} className="mt-0.5 shrink-0" /><span><b>{d.code}</b> {d.message}{d.path ? ` (${d.path})` : ''}</span></li>
          ))}
        </ul>
      )}

      {result.versions.map((v) => (
        <div key={v.simulatedVersionId} className="rounded border border-border p-2">
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-semibold text-foreground">{v.name}</span>
            <span className="rounded bg-muted px-1.5 py-0.5 font-mono text-[10px]">{v.source === 'INLINE' ? 'unsaved definition' : v.source === 'STORED_ACTIVE' ? 'active version' : 'stored version'}</span>
            <span className={v.matched ? 'font-semibold text-success' : 'font-semibold text-muted-foreground'} data-testid="version-outcome">{OUTCOME_TEXT[v.outcome] ?? v.outcome}</span>
          </div>
          <div className="mt-1 text-muted-foreground">{v.message}</div>
          {v.ownership && !v.ownership.executableInProduction && (
            <div role="alert" className="mt-1 rounded border border-amber-500/50 bg-amber-500/10 p-1.5 text-amber-500" data-testid="not-executable">
              Production would not execute this version: the rule is {v.ownership.lifecycle}{v.ownership.activeVersion ? '' : ', and this is not its active version'} (reconciliation {v.ownership.reconciliation ?? 'unknown'}).
              Arbitration below shows only what it would pick if the version were executable.
            </div>
          )}
          {v.contractDiagnostics.length > 0 && (
            <ul className="mt-1 list-disc pl-4 text-destructive">{v.contractDiagnostics.map((d, i) => <li key={i}><b>{d.code}</b> {d.message}</li>)}</ul>
          )}
          {v.conditions.length > 0 && (
            <table className="mt-2 w-full border-collapse text-left">
              <thead><tr className="text-muted-foreground"><th>Condition</th><th>Value used</th><th>Quality</th><th>Result</th></tr></thead>
              <tbody>
                {v.conditions.map((c, i) => (
                  <tr key={i} className="border-t border-border/50 align-top">
                    <td className="py-1 pr-2 font-mono">{c.field} {c.operator} {c.expectedValue === '' ? '"" (empty string)' : c.expectedValue}</td>
                    <td className="py-1 pr-2 font-mono" data-testid="actual-value">{formatActual(c.actualValue, c)}{c.unit ? ` ${c.unit}` : ''}</td>
                    <td className="py-1 pr-2">{c.stateQuality ? `${c.stateQuality}${c.qualityReason ? ` (${c.qualityReason})` : ''}` : '-'}{c.eligible === false ? ' - not eligible' : ''}</td>
                    <td className={`py-1 font-semibold ${c.conditionMet ? 'text-success' : 'text-destructive'}`}>{c.conditionMet ? 'met' : 'not met'}{c.hysteresis?.decision ? ` (${c.hysteresis.decision})` : ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      ))}

      <div>
        <div className="mb-1 font-semibold text-foreground">Proposed actions (arbitration)</div>
        {result.candidates.length === 0 ? (
          <div className="text-muted-foreground">No action would be proposed.</div>
        ) : (
          <ul className="space-y-1">
            {result.candidates.map((c) => (
              <li key={c.simulatedId} className="rounded border border-border p-1.5" data-testid="candidate">
                <span className="font-mono">{c.simulatedId}</span> <b>{c.actionType}</b>{c.targetKey ? <span className="text-muted-foreground"> on {c.targetKey}</span> : null}:{' '}
                <span className={c.wouldBeSelected ? 'font-semibold text-success' : 'text-destructive'}>{DISPOSITION_TEXT[c.disposition] ?? c.disposition}</span>
                <span className="text-muted-foreground"> - {c.reason}{c.winnerId ? `; winner ${winnerLabel(c.winnerId)}` : ''}</span>
                {c.wouldBeSelected && <div className="text-muted-foreground">A proposal only: guarded acceptance and authorization were not run, and nothing was accepted, published, acknowledged or actuated.</div>}
              </li>
            ))}
          </ul>
        )}
      </div>

      {result.hypotheticalEffects.length > 0 && (
        <div>
          <div className="mb-1 font-semibold text-foreground">State production WOULD change (not applied)</div>
          <ul className="list-disc pl-4 text-muted-foreground">
            {result.hypotheticalEffects.map((e, i) => <li key={i}><b>{e.kind}</b> {e.detail}: {formatActual(e.from)} {'->'} {formatActual(e.to)}</li>)}
          </ul>
        </div>
      )}
    </div>
  );
}
