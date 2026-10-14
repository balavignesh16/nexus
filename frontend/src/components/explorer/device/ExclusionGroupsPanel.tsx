import { useState } from 'react';
import { ruleApi, type ExclusionGroup, type Rule } from '../../../api/ruleApi';

/**
 * Mutual-exclusion groups (C16). Within one trigger batch at most one member rule's COMMANDS may act: the smaller explicit rank wins and
 * an equal best rank suppresses every competing command. Nothing is loaded until the panel is opened, and every change is a server
 * call that either succeeds as a whole or reports its error; the browser decides nothing.
 */
export function ExclusionGroupsPanel({ rules }: { rules: Rule[] }) {
  const [open, setOpen] = useState(false);
  const [groups, setGroups] = useState<ExclusionGroup[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [name, setName] = useState('');
  const [picks, setPicks] = useState<Record<string, { ruleId: string; rank: string }>>({});

  const run = async (action: () => Promise<unknown>) => {
    setError(null);
    try { await action(); setGroups(await ruleApi.listExclusionGroups()); }
    catch (e) { setError(e instanceof Error ? e.message : 'The change was rejected.'); }
  };
  const toggle = () => {
    const next = !open;
    setOpen(next);
    if (next && groups === null) void run(async () => undefined);
  };
  const ruleName = (id: string) => rules.find((r) => r.ruleId === id)?.name ?? id;

  return (
    <div className="rounded border border-foundation-border p-3" data-testid="exclusion-groups">
      <button type="button" aria-expanded={open} onClick={toggle} data-testid="exclusion-groups-toggle" className="text-xs font-medium text-foundation-fg cursor-pointer">
        Mutual exclusion groups
      </button>
      {open && (
        <div className="flex flex-col gap-3 mt-3 text-xs">
          <p className="text-foundation-muted-fg">
            Rules in the same group never both send commands for one trigger: the smaller rank wins; equal ranks suppress every competing command.
            Alerts are never silenced. This is not a safety override and does not span independent trigger batches.
          </p>
          {error && <p role="alert" data-testid="exclusion-error" style={{ color: 'var(--danger)' }}>{error}</p>}
          <div className="flex gap-2">
            <input value={name} onChange={(e) => setName(e.target.value)} placeholder="New group name" data-testid="exclusion-group-name" className="flex-1 rounded border border-foundation-border px-2 py-1" />
            <button type="button" data-testid="exclusion-group-create" disabled={!name.trim()} className="rounded border border-foundation-border px-2 py-1"
              onClick={() => void run(async () => { await ruleApi.createExclusionGroup(name.trim()); setName(''); })}>Create group</button>
          </div>
          {(groups ?? []).length === 0 && groups !== null && <p className="text-foundation-muted-fg">No groups configured.</p>}
          {(groups ?? []).map((group) => {
            const pick = picks[group.groupId] ?? { ruleId: '', rank: '' };
            return (
              <div key={group.groupId} data-testid="exclusion-group" className="rounded border border-foundation-border p-2 flex flex-col gap-2">
                <div className="flex items-center justify-between">
                  <span className="font-medium">{group.name}</span>
                  <button type="button" data-testid="exclusion-group-delete" disabled={group.members.length > 0} title="Remove every member first"
                    onClick={() => void run(() => ruleApi.deleteExclusionGroup(group.groupId))}>Delete group</button>
                </div>
                {group.members.map((m) => (
                  <div key={m.ruleId} data-testid="exclusion-member" className="flex items-center justify-between">
                    <span>{ruleName(m.ruleId)} · rank {m.rank}</span>
                    <button type="button" data-testid="exclusion-member-remove" onClick={() => void run(() => ruleApi.removeExclusionMember(group.groupId, m.ruleId))}>Remove</button>
                  </div>
                ))}
                <div className="flex gap-2">
                  <select value={pick.ruleId} data-testid="exclusion-member-rule" className="flex-1 rounded border border-foundation-border px-2 py-1"
                    onChange={(e) => setPicks({ ...picks, [group.groupId]: { ...pick, ruleId: e.target.value } })}>
                    <option value="">Choose a rule</option>
                    {rules.filter((r) => r.lifecycle !== 'ARCHIVED').map((r) => <option key={r.ruleId} value={r.ruleId}>{r.name}</option>)}
                  </select>
                  <input type="number" min={0} value={pick.rank} placeholder="Rank" data-testid="exclusion-member-rank" className="w-20 rounded border border-foundation-border px-2 py-1"
                    onChange={(e) => setPicks({ ...picks, [group.groupId]: { ...pick, rank: e.target.value } })} />
                  <button type="button" data-testid="exclusion-member-add" disabled={!pick.ruleId || pick.rank.trim() === '' || Number(pick.rank) < 0}
                    onClick={() => void run(() => ruleApi.setExclusionMember(group.groupId, pick.ruleId, Number(pick.rank)))}>Add / update</button>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
