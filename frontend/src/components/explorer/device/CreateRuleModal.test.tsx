import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { CreateRuleModal } from './CreateRuleModal';
import { ruleApi } from '../../../api/ruleApi';

vi.mock('../../../api/ruleApi', async () => {
  const actual = await vi.importActual<typeof import('../../../api/ruleApi')>('../../../api/ruleApi');
  return {
    ...actual,
    ruleApi: { ...actual.ruleApi, createRule: vi.fn() },
  };
});

describe('CreateRuleModal - real rule creation, no fabrication', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders nothing when closed', () => {
    render(<CreateRuleModal deviceId="dev-1" isOpen={false} onClose={vi.fn()} onCreated={vi.fn()} />);
    expect(screen.queryByText('Create Rule')).not.toBeInTheDocument();
  });

  it('pre-fills the condition to DEVICE_ID = this device', () => {
    render(<CreateRuleModal deviceId="dev-1" isOpen={true} onClose={vi.fn()} onCreated={vi.fn()} />);
    expect(screen.getByDisplayValue('dev-1')).toBeInTheDocument();
  });

  it('requires a name before submitting', async () => {
    render(<CreateRuleModal deviceId="dev-1" isOpen={true} onClose={vi.fn()} onCreated={vi.fn()} />);
    fireEvent.click(screen.getByText('Create Rule', { selector: 'button' }));

    await waitFor(() => expect(screen.getByTestId('create-rule-error')).toHaveTextContent('Name is required'));
    expect(ruleApi.createRule).not.toHaveBeenCalled();
  });

  it('submits the real POST payload built from the form, then calls onCreated and onClose', async () => {
    vi.mocked(ruleApi.createRule).mockResolvedValue({
      ruleId: 'rule-1', name: 'High Temp', enabled: true, priority: 1, conditions: [], actions: [], metadata: {},
    });
    const onCreated = vi.fn();
    const onClose = vi.fn();

    render(<CreateRuleModal deviceId="dev-1" isOpen={true} onClose={onClose} onCreated={onCreated} />);

    fireEvent.change(screen.getByPlaceholderText('e.g. High Temperature Alert'), { target: { value: 'High Temp' } });
    fireEvent.click(screen.getAllByText('Create Rule').find((el) => el.tagName === 'BUTTON')!);

    await waitFor(() => expect(ruleApi.createRule).toHaveBeenCalledWith({
      name: 'High Temp',
      enabled: true,
      priority: 1,
      conditions: [{ field: 'DEVICE_ID', operator: 'EQ', expectedValue: 'dev-1' }],
      actions: [{ actionType: 'CREATE_ALERT', parameters: { severity: 'INFO' } }],
      metadata: {},
    }));
    await waitFor(() => expect(onCreated).toHaveBeenCalled());
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it('shows a truthful error and does not close on invalid action-parameters JSON', async () => {
    render(<CreateRuleModal deviceId="dev-1" isOpen={true} onClose={vi.fn()} onCreated={vi.fn()} />);

    fireEvent.change(screen.getByPlaceholderText('e.g. High Temperature Alert'), { target: { value: 'X' } });
    fireEvent.change(screen.getByPlaceholderText('{"severity":"INFO"}'), { target: { value: '{not json' } });
    fireEvent.click(screen.getAllByText('Create Rule').find((el) => el.tagName === 'BUTTON')!);

    await waitFor(() => expect(screen.getByTestId('create-rule-error')).toHaveTextContent('valid JSON'));
    expect(ruleApi.createRule).not.toHaveBeenCalled();
  });

  it('shows the truthful backend error message when creation fails - never fabricates success', async () => {
    vi.mocked(ruleApi.createRule).mockRejectedValue(new Error('Server error'));
    const onCreated = vi.fn();

    render(<CreateRuleModal deviceId="dev-1" isOpen={true} onClose={vi.fn()} onCreated={onCreated} />);
    fireEvent.change(screen.getByPlaceholderText('e.g. High Temperature Alert'), { target: { value: 'X' } });
    fireEvent.click(screen.getAllByText('Create Rule').find((el) => el.tagName === 'BUTTON')!);

    await waitFor(() => expect(screen.getByTestId('create-rule-error')).toHaveTextContent('Server error'));
    expect(onCreated).not.toHaveBeenCalled();
  });
});
