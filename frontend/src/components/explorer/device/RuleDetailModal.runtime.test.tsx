import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { RuleDetailModal } from './RuleDetailModal';
import { ruleApi, type Rule, type RuleRuntimeSnapshot } from '../../../api/ruleApi';

vi.mock('../../../api/ruleApi', async () => {
  const actual = await vi.importActual<typeof import('../../../api/ruleApi')>('../../../api/ruleApi');
  return { ...actual, ruleApi: { ...actual.ruleApi, getRuntime: vi.fn(), executeManually: vi.fn() } };
});

vi.mock('../../../hooks/queries', () => ({
  useActiveDevices: () => ({ data: [{ id: 'dev-1', name: 'ESP32-SAFETY-01' }] }),
}));

const emptyRuntime: RuleRuntimeSnapshot = { timers: [], temporalState: [], temporalEvidence: [], candidates: [], matches: [] };

function rule(overrides: Partial<Rule> = {}): Rule {
  return {
    ruleId: 'r1', name: 'Sustained high temperature', enabled: true, priority: 1, metadata: {}, lifecycle: 'ACTIVE', activeVersionId: 'v1',
    conditions: [
      { field: 'DEVICE_ID', operator: 'EQ', expectedValue: 'dev-1' },
      { field: 'VALUE', operator: 'GT', expectedValue: '30', capabilityType: 'TEMPERATURE', conditionId: 'c1', temporal: { operator: 'FOR_DURATION', durationSeconds: 120 } },
    ],
    actions: [{ actionType: 'CREATE_ALERT', parameters: {} }],
    triggers: [{ type: 'TEMPORAL_DUE' }],
    ...overrides,
  };
}

describe('RuleDetailModal - triggers, temporal configuration and persisted runtime evidence', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(ruleApi.getRuntime).mockResolvedValue(emptyRuntime);
  });

  it('explains explicit subscriptions, repeat policy and the temporal condition in plain language', async () => {
    render(<RuleDetailModal rule={rule({ repeatPolicy: 'EDGE_TRUE' })} isOpen={true} onClose={vi.fn()} onEdit={vi.fn()} />);
    expect(screen.getByTestId('rule-triggers')).toHaveTextContent('TEMPORAL_DUE');
    expect(screen.getByTestId('rule-triggers')).toHaveTextContent('only on the transition into true');
    expect(screen.getByText(/FOR_DURATION: holds continuously for 120s/)).toBeInTheDocument();
    await waitFor(() => expect(ruleApi.getRuntime).toHaveBeenCalledWith('r1'));
  });

  it('an imported rule with no subscriptions is described as the original device-event behavior', () => {
    render(<RuleDetailModal rule={rule({ triggers: undefined, conditions: [{ field: 'EVENT_TYPE', operator: 'EQ', expectedValue: 'TWIN_UPDATED' }] })} isOpen={true} onClose={vi.fn()} onEdit={vi.fn()} />);
    expect(screen.getByTestId('rule-triggers')).toHaveTextContent('original behavior');
  });

  it('shows persisted temporal state, resets, suppressed candidates and stale occurrences truthfully', async () => {
    vi.mocked(ruleApi.getRuntime).mockResolvedValue({
      timers: [{ timer_id: 't1', kind: 'TEMPORAL_DUE', due_at: '2026-10-14T10:00:00Z', status: 'INVALIDATED', invalidation_reason: 'OWNERSHIP_CHANGED' }],
      temporalState: [{ condition_id: 'c1', operator: 'FOR_DURATION', phase: 'ARMED', due_at: '2026-10-14T10:05:00Z', member_count: 3, sequence: 0, generation: 4, reset_reason: 'FRESHNESS_GAP' }],
      temporalEvidence: [{ seq: 2, event: 'RESET', conditionId: 'c1', occurredAt: '2026-10-14T10:01:00Z', details: { reason: 'QUALITY_OR_UNIT_OFFLINE' } }],
      candidates: [{ candidate_id: 'k1', action_type: 'SEND_COMMAND', priority: 5, disposition: 'SUPPRESSED_CONFLICT', reason: 'EQUAL_PRIORITY_CONFLICT' }],
      matches: [],
    });
    render(<RuleDetailModal rule={rule()} isOpen={true} onClose={vi.fn()} onEdit={vi.fn()} />);
    await waitFor(() => expect(screen.getByTestId('temporal-state')).toHaveTextContent('FOR_DURATION · ARMED'));
    expect(screen.getByTestId('temporal-state')).toHaveTextContent('last reset: FRESHNESS_GAP');
    expect(screen.getByTestId('stale-timer')).toHaveTextContent('no decision was made');
    expect(screen.getByTestId('stale-timer')).toHaveTextContent('OWNERSHIP_CHANGED');
    expect(screen.getByTestId('temporal-evidence')).toHaveTextContent('QUALITY_OR_UNIT_OFFLINE');
    expect(screen.getByTestId('candidate')).toHaveTextContent('SUPPRESSED_CONFLICT · EQUAL_PRIORITY_CONFLICT');
  });

  it('degrades honestly when the runtime evidence cannot be loaded', async () => {
    vi.mocked(ruleApi.getRuntime).mockRejectedValue(new Error('boom'));
    render(<RuleDetailModal rule={rule()} isOpen={true} onClose={vi.fn()} onEdit={vi.fn()} />);
    await waitFor(() => expect(screen.getByTestId('rule-runtime')).toHaveTextContent('Runtime evidence is unavailable.'));
  });

  it('offers a manual run only for an ACTIVE rule with an explicit MANUAL subscription and reports it as a persisted request, not an outcome', async () => {
    const { rerender } = render(<RuleDetailModal rule={rule()} isOpen={true} onClose={vi.fn()} onEdit={vi.fn()} />);
    expect(screen.queryByTestId('manual-execute')).not.toBeInTheDocument();
    vi.mocked(ruleApi.executeManually).mockResolvedValue({ requestId: 'req-1', factId: 'f1', ruleVersionId: 'v1', note: '' });
    rerender(<RuleDetailModal rule={rule({ triggers: [{ type: 'MANUAL' }] })} isOpen={true} onClose={vi.fn()} onEdit={vi.fn()} />);
    fireEvent.click(screen.getByTestId('manual-execute'));
    await waitFor(() => expect(ruleApi.executeManually).toHaveBeenCalledWith('r1', 'v1'));
    await waitFor(() => expect(screen.getByTestId('manual-note')).toHaveTextContent('Request req-1 was persisted'));
    expect(screen.getByTestId('manual-note')).toHaveTextContent('separate recorded facts');
    rerender(<RuleDetailModal rule={rule({ triggers: [{ type: 'MANUAL' }], lifecycle: 'PAUSED' })} isOpen={true} onClose={vi.fn()} onEdit={vi.fn()} />);
    expect(screen.queryByTestId('manual-execute')).not.toBeInTheDocument();
  });

  it('shows the server rejection when a manual request is refused', async () => {
    vi.mocked(ruleApi.executeManually).mockRejectedValue(new Error('Rule is not ACTIVE; manual execution is rejected'));
    render(<RuleDetailModal rule={rule({ triggers: [{ type: 'MANUAL' }] })} isOpen={true} onClose={vi.fn()} onEdit={vi.fn()} />);
    fireEvent.click(screen.getByTestId('manual-execute'));
    await waitFor(() => expect(screen.getByTestId('manual-note')).toHaveTextContent('manual execution is rejected'));
  });
});
