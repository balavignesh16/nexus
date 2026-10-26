import { useEffect, useState } from 'react';
import { actionApi, type CommandLifecycle } from '../../api/actionApi';
import {
  NO_PHYSICAL_CLAIM, ackLabel, deliveryLabel, formatDurationMs, formatInstant, verificationLabel, type Label,
} from '../../utils/commandLifecycle';

const TONE_COLOR: Record<Label['tone'], string> = {
  neutral: 'var(--foundation-muted-fg)',
  pending: 'var(--warning)',
  success: 'var(--success)',
  warning: 'var(--warning)',
  danger: 'var(--danger)',
};

function Row({ name, label, detail, testId }: { name: string; label: Label | null; detail?: string | null; testId: string }) {
  return (
    <div className="flex flex-wrap items-baseline gap-2" data-testid={testId}>
      <span className="w-28 shrink-0 text-[11px] uppercase tracking-wider text-foundation-muted-fg">{name}</span>
      <span className="text-xs font-semibold" style={{ color: label ? TONE_COLOR[label.tone] : 'var(--foundation-muted-fg)' }}>
        {label ? label.label : 'Not applicable'}
      </span>
      {detail && <span className="text-[11px] text-foundation-muted-fg font-mono">{detail}</span>}
    </div>
  );
}

/**
 * Read-only lifecycle of one command, loaded when the panel opens (GET /actions/{id}/lifecycle). Queued, Published, Acknowledged and
 * Verified are four SEPARATE lines, each from its own recorded field; none implies another and nothing here claims physical actuation.
 */
export function CommandLifecyclePanel({ commandId }: { commandId: string }) {
  const [lifecycle, setLifecycle] = useState<CommandLifecycle | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setLifecycle(null);
    setFailed(false);
    (async () => {
      try {
        const loaded = await actionApi.getLifecycle(commandId);
        if (!cancelled) setLifecycle(loaded);
      } catch {
        if (!cancelled) setFailed(true);
      }
    })();
    return () => { cancelled = true; };
  }, [commandId]);

  if (failed) return <p className="text-xs text-foundation-muted-fg" data-testid="lifecycle-unavailable">The command lifecycle could not be loaded.</p>;
  if (!lifecycle) return <p className="text-xs text-foundation-muted-fg" data-testid="lifecycle-loading">Loading lifecycle…</p>;

  const delivery = lifecycle.delivery;
  const published = delivery?.state === 'PUBLISHED' ? delivery.firstPublishConfirmedAt : undefined;
  const ack = lifecycle.ack;
  const facts = Array.isArray(lifecycle.facts) ? lifecycle.facts : [];
  const hasUnknownDeliveryFact = facts.some((f) => f.fact === 'DELIVERY_UNKNOWN');
  const queuedLabel: Label | null = delivery
    ? { label: 'Accepted for delivery', tone: 'success' }
    : hasUnknownDeliveryFact ? { label: 'Delivery unknown', tone: 'warning' } : null;
  const publishedLabel = delivery ? deliveryLabel(delivery.state, delivery.terminalReason) : null;
  const latency = lifecycle.latency;
  const latencyParts = [
    ['accepted → published', formatDurationMs(latency?.acceptedToPublishedMs)],
    ['published → acknowledged', formatDurationMs(latency?.publishedToAckMs)],
    ['published → verified', formatDurationMs(latency?.publishedToConvergenceMs)],
  ].filter(([, v]) => v !== null) as [string, string][];

  return (
    <div className="flex flex-col gap-2 mt-2 p-3 rounded-md border border-foundation-border" data-testid="command-lifecycle-panel">
      <Row testId="lifecycle-queued" name="Queued" label={queuedLabel} detail={delivery ? formatInstant(delivery.acceptedAt) : null} />
      <Row testId="lifecycle-published" name="Published" label={publishedLabel} detail={published ? formatInstant(published) : null} />
      <Row testId="lifecycle-acknowledged" name="Acknowledged" label={ack ? ackLabel(ack.status, ack.timedOut) : null}
           detail={ack?.backendReceivedAt ? formatInstant(ack.backendReceivedAt) : null} />
      <Row testId="lifecycle-verified" name="Verified" label={verificationLabel(lifecycle.convergence?.status)}
           detail={lifecycle.convergence?.updatedAt ? formatInstant(lifecycle.convergence.updatedAt) : null} />
      {latencyParts.length > 0 && (
        <p className="text-[11px] text-foundation-muted-fg" data-testid="lifecycle-latency">
          Measured on the NEXUS server clock: {latencyParts.map(([name, value]) => `${name} ${value}`).join(' · ')}
        </p>
      )}
      {Array.isArray(lifecycle.explanation) && lifecycle.explanation.length > 0 && (
        <ul className="list-disc pl-4 text-[11px] text-foundation-muted-fg" data-testid="lifecycle-explanation">
          {lifecycle.explanation.map((sentence, i) => <li key={i}>{sentence}</li>)}
        </ul>
      )}
      {facts.length > 0 && (
        <ol className="text-[11px] font-mono text-foundation-muted-fg" data-testid="lifecycle-facts">
          {facts.map((f) => (
            <li key={f.sequence}>
              {f.sequence}. {f.fact}{f.toState ? ` → ${f.toState}` : ''}{f.attempt ? ` (attempt ${f.attempt})` : ''}{f.reason ? ` [${f.reason}]` : ''} · {formatInstant(f.occurredAt)}
            </li>
          ))}
        </ol>
      )}
      {lifecycle.factsTruncated && <p className="text-[11px] text-foundation-muted-fg">Only the first facts are shown.</p>}
      <p className="text-[11px] text-foundation-muted-fg" data-testid="lifecycle-no-physical-claim">{NO_PHYSICAL_CLAIM}</p>
    </div>
  );
}
