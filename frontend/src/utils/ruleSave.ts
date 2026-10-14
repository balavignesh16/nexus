import { ApiError } from '../api/apiClient';
import { ruleApi, type Rule } from '../api/ruleApi';
import type { CompiledDefinition } from './workflowCompiler';

/** Turns a rejected save into text the user can act on. A stale revision is called out; backend validation details are listed. */
export function describeSaveFailure(error: unknown): string[] {
  if (error instanceof ApiError) {
    const data = error.data as { message?: string; details?: { path?: string; message?: string }[] } | undefined;
    if (error.status === 409) return ['This rule was changed elsewhere (stale revision). Nothing was saved; reload the rule and re-apply your edit.'];
    const details = (data?.details ?? []).map((d) => (d.path ? `${d.path}: ${d.message}` : String(d.message)));
    return [data?.message ?? error.message, ...details];
  }
  return [error instanceof Error ? error.message : 'The save failed; nothing was saved.'];
}

/**
 * Saves a compiled flat definition through the backend and returns the rule exactly as the backend answered. An existing rule is UPDATED
 * with the revision it was loaded at (so a concurrent edit is refused with 409, never overwritten); a new one is created. Nothing here
 * fabricates an id, a revision or a lifecycle state: the caller may claim success only from the returned rule.
 */
export async function persistDefinition(persisted: Rule | null, definition: CompiledDefinition): Promise<Rule> {
  if (persisted) return ruleApi.updateRule(persisted.ruleId, { ...persisted, ...definition, ruleId: persisted.ruleId });
  return ruleApi.createRule(definition);
}
