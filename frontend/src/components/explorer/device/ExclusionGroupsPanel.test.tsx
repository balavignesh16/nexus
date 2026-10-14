import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { ExclusionGroupsPanel } from './ExclusionGroupsPanel';
import { ruleApi, type Rule } from '../../../api/ruleApi';

vi.mock('../../../api/ruleApi', async () => {
  const actual = await vi.importActual<typeof import('../../../api/ruleApi')>('../../../api/ruleApi');
  return {
    ...actual,
    ruleApi: { ...actual.ruleApi, listExclusionGroups: vi.fn(), createExclusionGroup: vi.fn(), setExclusionMember: vi.fn(), removeExclusionMember: vi.fn(), deleteExclusionGroup: vi.fn() },
  };
});

const rules = [
  { ruleId: 'r1', name: 'Heater', lifecycle: 'ACTIVE' }, { ruleId: 'r2', name: 'Cooler', lifecycle: 'ACTIVE' }, { ruleId: 'r3', name: 'Old', lifecycle: 'ARCHIVED' },
] as unknown as Rule[];

describe('ExclusionGroupsPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(ruleApi.listExclusionGroups).mockResolvedValue([{ groupId: 'g1', name: 'Climate', members: [{ ruleId: 'r1', rank: 1 }] }]);
  });

  it('loads nothing until it is opened', () => {
    render(<ExclusionGroupsPanel rules={rules} />);
    expect(ruleApi.listExclusionGroups).not.toHaveBeenCalled();
    expect(screen.queryByTestId('exclusion-group')).not.toBeInTheDocument();
  });

  it('shows groups with rule names and ranks, and states what the policy is not', async () => {
    render(<ExclusionGroupsPanel rules={rules} />);
    fireEvent.click(screen.getByTestId('exclusion-groups-toggle'));
    await waitFor(() => expect(screen.getByTestId('exclusion-group')).toHaveTextContent('Climate'));
    expect(screen.getByTestId('exclusion-member')).toHaveTextContent('Heater · rank 1');
    expect(screen.getByTestId('exclusion-groups')).toHaveTextContent('not a safety override');
    expect(screen.getByTestId('exclusion-group-delete')).toBeDisabled();
  });

  it('adds a member with an explicit rank, offers no archived rule, and refreshes from the server', async () => {
    vi.mocked(ruleApi.setExclusionMember).mockResolvedValue({ groupId: 'g1', name: 'Climate', members: [] });
    render(<ExclusionGroupsPanel rules={rules} />);
    fireEvent.click(screen.getByTestId('exclusion-groups-toggle'));
    await waitFor(() => screen.getByTestId('exclusion-member-rule'));
    expect(screen.getByTestId('exclusion-member-rule')).not.toHaveTextContent('Old');
    expect(screen.getByTestId('exclusion-member-add')).toBeDisabled();
    fireEvent.change(screen.getByTestId('exclusion-member-rule'), { target: { value: 'r2' } });
    fireEvent.change(screen.getByTestId('exclusion-member-rank'), { target: { value: '2' } });
    fireEvent.click(screen.getByTestId('exclusion-member-add'));
    await waitFor(() => expect(ruleApi.setExclusionMember).toHaveBeenCalledWith('g1', 'r2', 2));
    await waitFor(() => expect(ruleApi.listExclusionGroups).toHaveBeenCalledTimes(2));
  });

  it('reports a rejected change instead of pretending it succeeded', async () => {
    vi.mocked(ruleApi.createExclusionGroup).mockRejectedValue(new Error('A group with this name already exists'));
    render(<ExclusionGroupsPanel rules={rules} />);
    fireEvent.click(screen.getByTestId('exclusion-groups-toggle'));
    await waitFor(() => screen.getByTestId('exclusion-group-name'));
    fireEvent.change(screen.getByTestId('exclusion-group-name'), { target: { value: 'Climate' } });
    fireEvent.click(screen.getByTestId('exclusion-group-create'));
    await waitFor(() => expect(screen.getByTestId('exclusion-error')).toHaveTextContent('already exists'));
    expect(ruleApi.listExclusionGroups).toHaveBeenCalledTimes(1);
  });

  it('removes a member through the server', async () => {
    vi.mocked(ruleApi.removeExclusionMember).mockResolvedValue(undefined);
    render(<ExclusionGroupsPanel rules={rules} />);
    fireEvent.click(screen.getByTestId('exclusion-groups-toggle'));
    await waitFor(() => screen.getByTestId('exclusion-member-remove'));
    fireEvent.click(screen.getByTestId('exclusion-member-remove'));
    await waitFor(() => expect(ruleApi.removeExclusionMember).toHaveBeenCalledWith('g1', 'r1'));
  });
});
