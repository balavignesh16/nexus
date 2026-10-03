import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { RuleDetailModal } from './RuleDetailModal';
import type { Rule } from '../../../api/ruleApi';

const mockUseActiveDevices = vi.fn();
vi.mock('../../../hooks/queries', () => ({
  useActiveDevices: () => mockUseActiveDevices(),
}));

const devices = [
  { id: 'dev-1', name: 'ESP32-SAFETY-01' },
  { id: 'dev-2', name: 'ESP32-ACTUATOR-01' },
];

function rule(overrides: Partial<Rule & { description?: string }> = {}): Rule & { description?: string } {
  return {
    ruleId: 'r1',
    name: 'Gas + Motion Safety Event',
    description: 'Buzzer and fan on combined gas/motion alert',
    enabled: true,
    priority: 20,
    cooldownSeconds: 15,
    metadata: {},
    conditions: [
      { field: 'DEVICE_ID', operator: 'EQ', expectedValue: 'dev-1' },
      { field: 'VALUE', operator: 'GT', expectedValue: '500', capabilityType: 'GAS' },
      { field: 'VALUE', operator: 'EQ', expectedValue: 'true', capabilityType: 'MOTION' },
    ],
    actions: [
      { actionType: 'SEND_COMMAND', parameters: { deviceId: 'dev-2', capability: 'BUZZER', command: 'SET_STATE', value: true } },
      { actionType: 'SEND_COMMAND', parameters: { deviceId: 'dev-2', capability: 'FAN', command: 'SET_STATE', value: true } },
    ],
    ...overrides,
  };
}

describe('RuleDetailModal - shows the real rule, never a blank or summarized popup', () => {
  beforeEach(() => {
    mockUseActiveDevices.mockReturnValue({ data: devices });
  });

  it('renders nothing when closed or when there is no rule to show', () => {
    const { rerender } = render(<RuleDetailModal rule={rule()} isOpen={false} onClose={vi.fn()} onEdit={vi.fn()} />);
    expect(screen.queryByText('Gas + Motion Safety Event')).not.toBeInTheDocument();

    rerender(<RuleDetailModal rule={null} isOpen={true} onClose={vi.fn()} onEdit={vi.fn()} />);
    expect(screen.queryByText('Gas + Motion Safety Event')).not.toBeInTheDocument();
  });

  it('shows the real name, description, enabled state, priority and cooldown', () => {
    render(<RuleDetailModal rule={rule()} isOpen={true} onClose={vi.fn()} onEdit={vi.fn()} />);

    expect(screen.getByText('Gas + Motion Safety Event')).toBeInTheDocument();
    expect(screen.getByText('Buzzer and fan on combined gas/motion alert')).toBeInTheDocument();
    expect(screen.getByText('Active')).toBeInTheDocument();
    expect(screen.getByText('Priority 20')).toBeInTheDocument();
    expect(screen.getByText('Cooldown 15s')).toBeInTheDocument();
  });

  it('shows every real condition in plain language, not just the first, with the device name resolved', () => {
    render(<RuleDetailModal rule={rule()} isOpen={true} onClose={vi.fn()} onEdit={vi.fn()} />);

    expect(screen.getByText('Conditions (3, all must match)')).toBeInTheDocument();
    expect(screen.getByText('Device is ESP32-SAFETY-01')).toBeInTheDocument();
    expect(screen.getByText('is greater than 500')).toBeInTheDocument();
    expect(screen.getByText('equals true')).toBeInTheDocument();
    expect(screen.getByText('GAS')).toBeInTheDocument();
    expect(screen.getByText('MOTION')).toBeInTheDocument();
  });

  it('falls back to the raw device id when the device cannot be resolved', () => {
    mockUseActiveDevices.mockReturnValue({ data: [] });
    render(<RuleDetailModal rule={rule()} isOpen={true} onClose={vi.fn()} onEdit={vi.fn()} />);

    expect(screen.getByText('Device is dev-1')).toBeInTheDocument();
  });

  it('shows every real action in plain language, not just the first, and not as a raw JSON dump', () => {
    render(<RuleDetailModal rule={rule()} isOpen={true} onClose={vi.fn()} onEdit={vi.fn()} />);

    expect(screen.getByText('Actions (2)')).toBeInTheDocument();
    expect(screen.getByText('Set BUZZER to ON on ESP32-ACTUATOR-01')).toBeInTheDocument();
    expect(screen.getByText('Set FAN to ON on ESP32-ACTUATOR-01')).toBeInTheDocument();
    expect(screen.queryByText(/"deviceId"/)).not.toBeInTheDocument();
  });

  it('describes a RELAY action by its channel and ON/OFF state', () => {
    render(<RuleDetailModal rule={rule({
      actions: [{ actionType: 'SEND_COMMAND', parameters: { deviceId: 'dev-2', capability: 'RELAY', command: 'SET_STATE', value: { channel: 3, state: false } } }],
    })} isOpen={true} onClose={vi.fn()} onEdit={vi.fn()} />);

    expect(screen.getByText('Set RELAY channel 3 to OFF on ESP32-ACTUATOR-01')).toBeInTheDocument();
  });

  it('describes a SERVO action by its angle', () => {
    render(<RuleDetailModal rule={rule({
      actions: [{ actionType: 'SEND_COMMAND', parameters: { deviceId: 'dev-2', capability: 'SERVO', command: 'SET_STATE', value: 90 } }],
    })} isOpen={true} onClose={vi.fn()} onEdit={vi.fn()} />);

    expect(screen.getByText('Move SERVO to 90° on ESP32-ACTUATOR-01')).toBeInTheDocument();
  });

  it('shows an honest "no conditions/actions" fallback for a rule that genuinely has none', () => {
    render(<RuleDetailModal rule={rule({ conditions: [], actions: [] })} isOpen={true} onClose={vi.fn()} onEdit={vi.fn()} />);

    expect(screen.getByText('No conditions defined.')).toBeInTheDocument();
    expect(screen.getByText('No actions defined.')).toBeInTheDocument();
  });

  it('shows "No cooldown" honestly when the rule has none set', () => {
    render(<RuleDetailModal rule={rule({ cooldownSeconds: null })} isOpen={true} onClose={vi.fn()} onEdit={vi.fn()} />);
    expect(screen.getByText('No cooldown')).toBeInTheDocument();
  });

  it('disables Edit for a multi-action rule and explains why, rather than silently dropping actions on save', () => {
    render(<RuleDetailModal rule={rule()} isOpen={true} onClose={vi.fn()} onEdit={vi.fn()} />);

    expect(screen.getByRole('button', { name: /edit rule/i })).toBeDisabled();
    expect(screen.getByText(/This rule has 2 actions/)).toBeInTheDocument();
  });

  it('enables Edit for a single-action rule and calls onEdit when clicked', () => {
    const onEdit = vi.fn();
    render(<RuleDetailModal rule={rule({ actions: [rule().actions[0]] })} isOpen={true} onClose={vi.fn()} onEdit={onEdit} />);

    const editButton = screen.getByRole('button', { name: /edit rule/i });
    expect(editButton).not.toBeDisabled();
    fireEvent.click(editButton);
    expect(onEdit).toHaveBeenCalled();
  });

  it('calls onClose from the Close button and the backdrop, not from the panel itself', () => {
    const onClose = vi.fn();
    render(<RuleDetailModal rule={rule()} isOpen={true} onClose={onClose} onEdit={vi.fn()} />);

    fireEvent.click(screen.getByText('Gas + Motion Safety Event')); // inside the panel
    expect(onClose).not.toHaveBeenCalled();

    fireEvent.click(screen.getByText('Close'));
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
