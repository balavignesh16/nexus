import { useEffect, useState } from 'react';
import { AlertOctagon } from 'lucide-react';
import { actionApi, type DeadLetter } from '../../api/actionApi';
import { Card } from '../foundation/Card';
import { EmptyState } from '../foundation/EmptyState';
import { deliveryLabel, formatInstant } from '../../utils/commandLifecycle';

/**
 * The most recent dead-lettered commands (GET /actions/dead-letters), read-only: a dead letter is a DELIVERY outcome. There is no retry,
 * requeue or dismiss action here, and an acknowledgement timeout is a different fact that never appears in this list.
 */
export function DeadLettersCard() {
  const [items, setItems] = useState<DeadLetter[] | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const page = await actionApi.getDeadLetters(5);
        if (!cancelled) setItems(Array.isArray(page?.items) ? page.items : []);
      } catch {
        if (!cancelled) setFailed(true);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  return (
    <Card level="glass" padding="lg" icon={<AlertOctagon size={16} />} title="Dead letters">
      {failed ? (
        <p className="text-xs text-foundation-muted-fg" data-testid="dead-letters-unavailable">Dead letters could not be loaded.</p>
      ) : items === null ? (
        <p className="text-xs text-foundation-muted-fg">Loading…</p>
      ) : items.length === 0 ? (
        <EmptyState icon={<AlertOctagon size={24} />} title="No dead-lettered commands." description="Commands whose delivery ended without a confirmed publication appear here. This list is read-only." />
      ) : (
        <ul className="flex flex-col gap-2" data-testid="dead-letters-list">
          {items.map((d) => {
            const label = deliveryLabel('DEAD_LETTER', d.terminalReason);
            return (
              <li key={d.commandId} className="text-xs" data-testid="dead-letter-item">
                <span className="font-semibold">{d.actionType}</span>
                <span className="text-foundation-muted-fg"> · {label?.label}</span>
                <div className="font-mono text-[11px] text-foundation-muted-fg">
                  {d.attemptCount}/{d.maxAttempts} attempts · {formatInstant(d.terminalAt)}{d.acknowledged ? ' · device acknowledged later' : ''}
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </Card>
  );
}
